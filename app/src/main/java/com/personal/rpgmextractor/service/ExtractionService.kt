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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.documentfile.provider.DocumentFile
import com.personal.rpgmextractor.MainActivity
import com.personal.rpgmextractor.R
import com.personal.rpgmextractor.core.ExtractionEngine
import com.personal.rpgmextractor.core.ExtractionProgress
import com.personal.rpgmextractor.core.ExtractionProgressBus
import com.personal.rpgmextractor.core.GameScanner
import com.personal.rpgmextractor.core.RpgMakerDecryptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs the whole scan + decrypt/copy pipeline as a foreground service so it survives
 * the user leaving the app, and mirrors progress into a notification with a progress bar.
 */
class ExtractionService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val EXTRA_GAME_URI = "extra_game_uri"
        const val EXTRA_OUTPUT_URI = "extra_output_uri"
        const val CHANNEL_ID = "extraction_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gameUri = intent?.getUriExtraCompat(EXTRA_GAME_URI)
        val outputUri = intent?.getUriExtraCompat(EXTRA_OUTPUT_URI)

        if (gameUri == null || outputUri == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotification = buildProgressNotification(0, 1, "Scanning game folder…")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            initialNotification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )

        scope.launch {
            try {
                val root = DocumentFile.fromTreeUri(this@ExtractionService, gameUri)
                val outRoot = DocumentFile.fromTreeUri(this@ExtractionService, outputUri)
                if (root == null || outRoot == null) {
                    fail("Could not open the selected folders")
                    return@launch
                }

                val result = GameScanner.scan(this@ExtractionService, root)
                val keyBytes = result.encryptionKeyHex?.let { RpgMakerDecryptor.hexKeyToBytes(it) }
                val total = result.entries.size

                if (total == 0) {
                    ExtractionProgressBus.update(ExtractionProgress.Finished(0, 0))
                    notificationManager.notify(NOTIFICATION_ID, buildDoneNotification(0, 0))
                    return@launch
                }

                var lastNotifyMs = 0L
                val success = ExtractionEngine.extract(
                    context = this@ExtractionService,
                    entries = result.entries,
                    keyBytes = keyBytes,
                    outputRoot = outRoot
                ) { done, totalCount, current ->
                    ExtractionProgressBus.update(ExtractionProgress.Running(done, totalCount, current))
                    val now = System.currentTimeMillis()
                    if (now - lastNotifyMs > 150 || done == totalCount) {
                        lastNotifyMs = now
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            buildProgressNotification(done, totalCount, current)
                        )
                    }
                }

                ExtractionProgressBus.update(ExtractionProgress.Finished(success, total))
                notificationManager.notify(NOTIFICATION_ID, buildDoneNotification(success, total))
            } catch (e: Exception) {
                fail(e.message ?: "Extraction failed")
            } finally {
                ServiceCompat.stopForeground(this@ExtractionService, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }

        return START_NOT_STICKY
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

    private fun buildProgressNotification(done: Int, total: Int, current: String): Notification {
        val shortName = current.substringAfterLast('/')
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Extracting assets…")
            .setContentText(if (shortName.isNotEmpty()) "$done / $total  •  $shortName" else "$done / $total")
            .setProgress(total.coerceAtLeast(1), done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
