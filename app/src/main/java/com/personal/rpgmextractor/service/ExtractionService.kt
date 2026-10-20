package com.personal.rpgmextractor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.personal.rpgmextractor.MainActivity
import com.personal.rpgmextractor.R
import com.personal.rpgmextractor.core.ExtractionControl
import com.personal.rpgmextractor.core.ExtractionEngine
import com.personal.rpgmextractor.core.ExtractionProgress
import com.personal.rpgmextractor.core.ExtractionProgressBus
import com.personal.rpgmextractor.core.GameScanner
import com.personal.rpgmextractor.core.RpgMakerDecryptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the whole scan + decrypt/copy pipeline as a foreground service so it survives
 * the user leaving the app, and mirrors progress into a notification with a progress bar
 * plus Pause/Resume and Stop actions.
 */
class ExtractionService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var notificationManager: NotificationManager
    private val gate = ExtractionControl.gate

    private var extractionJob: Job? = null
    private val lastPublishMs = AtomicLong(0)

    // Last reported progress, so the notification can be redrawn on pause/resume.
    @Volatile private var lastSuccess = 0
    @Volatile private var lastDone = 0
    @Volatile private var lastTotal = 0
    @Volatile private var lastCurrent = ""

    companion object {
        const val EXTRA_GAME_URI = "extra_game_uri"
        const val EXTRA_OUTPUT_URI = "extra_output_uri"
        const val EXTRA_RESUME = "extra_resume"
        const val ACTION_PAUSE = "com.personal.rpgmextractor.action.PAUSE"
        const val ACTION_RESUME = "com.personal.rpgmextractor.action.RESUME"
        const val ACTION_STOP = "com.personal.rpgmextractor.action.STOP"
        const val CHANNEL_ID = "extraction_channel"
        const val NOTIFICATION_ID = 1001
        private const val PUBLISH_INTERVAL_MS = 150L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        // Keep the notification's title/buttons in sync however pause was toggled (app or notification).
        scope.launch {
            gate.paused.collect {
                if (extractionJob?.isActive == true) {
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildProgressNotification(lastDone, lastTotal, lastCurrent)
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { gate.pause(); stopIfIdle(); return START_NOT_STICKY }
            ACTION_RESUME -> { gate.resume(); stopIfIdle(); return START_NOT_STICKY }
            ACTION_STOP -> {
                extractionJob?.cancel()
                gate.resume() // don't leave the next run paused
                stopIfIdle()
                return START_NOT_STICKY
            }
        }

        // Already extracting: ignore a duplicate start request.
        if (extractionJob?.isActive == true) return START_NOT_STICKY

        val gameUri = intent?.getUriExtraCompat(EXTRA_GAME_URI)
        val outputUri = intent?.getUriExtraCompat(EXTRA_OUTPUT_URI)
        val resume = intent?.getBooleanExtra(EXTRA_RESUME, false) ?: false

        if (gameUri == null || outputUri == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        gate.resume()
        lastSuccess = 0
        lastDone = 0
        lastTotal = 0
        lastCurrent = ""
        val initialNotification = buildProgressNotification(0, 0, "Preparing…")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            initialNotification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )

        extractionJob = scope.launch {
            try {
                // Reuse the scan the UI already did; only rescan if it's gone (process death) or stale.
                val result = ExtractionControl.lastScan?.takeIf { it.first == gameUri }?.second
                    ?: GameScanner.scan(this@ExtractionService, gameUri, gate) { found ->
                        publishProgress(0, 0, 0, "Scanning… $found assets found")
                    }
                val keyBytes = result.encryptionKeyHex?.let { RpgMakerDecryptor.hexKeyToBytes(it) }
                if (result.entries.isEmpty()) {
                    ExtractionProgressBus.update(ExtractionProgress.Finished(0, 0))
                    notificationManager.notify(NOTIFICATION_ID, buildDoneNotification(0, 0))
                    return@launch
                }

                val outcome = ExtractionEngine.extract(
                    context = this@ExtractionService,
                    entries = result.entries,
                    keyBytes = keyBytes,
                    outputTreeUri = outputUri,
                    gate = gate,
                    skipCompleted = resume
                ) { ok, done, totalCount, current ->
                    publishProgress(ok, done, totalCount, current)
                }

                ExtractionProgressBus.update(
                    ExtractionProgress.Finished(outcome.success, outcome.total, outcome.firstError)
                )
                notificationManager.notify(NOTIFICATION_ID, buildDoneNotification(outcome.success, outcome.total))
            } catch (e: CancellationException) {
                ExtractionProgressBus.update(ExtractionProgress.Stopped(lastSuccess, lastDone, lastTotal))
                notificationManager.notify(NOTIFICATION_ID, buildStoppedNotification(lastDone, lastTotal))
            } catch (e: Exception) {
                fail(e.message ?: "Extraction failed")
            } finally {
                gate.resume()
                ServiceCompat.stopForeground(this@ExtractionService, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Called from many worker threads at once. Pushing every single file to the UI and
     * notification would flood both (and Android drops notification updates beyond a few
     * per second anyway), so updates are rate limited.
     */
    private fun publishProgress(success: Int, done: Int, total: Int, current: String) {
        lastSuccess = success
        lastDone = done
        lastTotal = total
        lastCurrent = current
        val now = SystemClock.elapsedRealtime()
        val last = lastPublishMs.get()
        val isFinal = total > 0 && done == total
        if (!isFinal && now - last < PUBLISH_INTERVAL_MS) return
        if (!lastPublishMs.compareAndSet(last, now)) return
        ExtractionProgressBus.update(ExtractionProgress.Running(done, total, current))
        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(done, total, current))
    }

    private fun stopIfIdle() {
        if (extractionJob?.isActive != true) stopSelf()
    }

    private fun fail(message: String) {
        ExtractionProgressBus.update(ExtractionProgress.Failed(message))
        notificationManager.notify(NOTIFICATION_ID, buildErrorNotification(message))
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Asset extraction",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while extracting RPG Maker game assets"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, ExtractionService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildProgressNotification(done: Int, total: Int, current: String): Notification {
        val shortName = current.substringAfterLast('/')
        val paused = gate.paused.value
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (paused) "Extraction paused" else "Extracting assets…")
            .setContentText(
                when {
                    total == 0 -> shortName
                    shortName.isNotEmpty() -> "$done / $total  •  $shortName"
                    else -> "$done / $total"
                }
            )
            .setProgress(total.coerceAtLeast(1), done, total == 0 && !paused)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (paused) {
            builder.addAction(0, "Resume", actionIntent(ACTION_RESUME, 1))
        } else {
            builder.addAction(0, "Pause", actionIntent(ACTION_PAUSE, 2))
        }
        builder.addAction(0, "Stop", actionIntent(ACTION_STOP, 3))
        return builder.build()
    }

    private fun buildStoppedNotification(done: Int, total: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Extraction stopped")
            .setContentText("$done / $total assets processed")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun buildDoneNotification(success: Int, total: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Extraction complete")
            .setContentText("$success / $total assets extracted")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun buildErrorNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Extraction failed")
            .setContentText(message)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }
}

private fun Intent.getUriExtraCompat(key: String): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
