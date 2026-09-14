package com.personal.rpgmextractor.core

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extracts (and decrypts, where needed) assets found by [GameScanner] into
 * a destination folder, preserving the original folder structure.
 *
 * Runs multiple files concurrently on the IO dispatcher for performance,
 * while streaming each file with a buffered copy instead of loading it
 * fully into memory (important for large video files).
 */
object ExtractionEngine {

    suspend fun extract(
        context: Context,
        entries: List<AssetEntry>,
        keyBytes: ByteArray?,
        outputRoot: DocumentFile,
        concurrency: Int = 4,
        onProgress: (done: Int, total: Int, currentName: String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val total = entries.size
        val done = AtomicInteger(0)
        val dirCache = HashMap<String, DocumentFile>()
        val dirLock = Any()
        val semaphore = Semaphore(concurrency)

        val jobs = entries.map { entry ->
            async {
                semaphore.withPermit {
                    val ok = try {
                        val targetDir = synchronized(dirLock) {
                            resolveDir(outputRoot, entry.relativePath.substringBeforeLast('/', ""), dirCache)
                        }
                        val baseName = entry.relativePath.substringAfterLast('/')
                            .substringBeforeLast('.', entry.relativePath.substringAfterLast('/'))
                        val outName = "$baseName.${entry.outputExtension}"

                        val outDoc = targetDir.findFile(outName)
                            ?: targetDir.createFile(mimeFor(entry.outputExtension), outName)

                        if (outDoc != null) {
                            context.contentResolver.openInputStream(entry.doc.uri)?.use { input ->
                                context.contentResolver.openOutputStream(outDoc.uri, "wt")?.use { output ->
                                    if (entry.encrypted && keyBytes != null) {
                                        RpgMakerDecryptor.decryptStream(input, output, keyBytes)
                                    } else {
                                        input.copyTo(output, bufferSize = 64 * 1024)
                                        true
                                    }
                                }
                            } ?: false
                        } else false
                    } catch (e: Exception) {
                        false
                    } finally {
                        val d = done.incrementAndGet()
                        onProgress(d, total, entry.relativePath)
                    }
                    ok
                }
            }
        }
        jobs.awaitAll().count { it == true }
    }

    /** Finds (or creates) the destination directory matching [relDir], caching lookups. */
    private fun resolveDir(root: DocumentFile, relDir: String, cache: HashMap<String, DocumentFile>): DocumentFile {
        if (relDir.isEmpty()) return root
        cache[relDir]?.let { return it }

        val parts = relDir.split('/')
        var current = root
        var pathSoFar = ""
        for (part in parts) {
            pathSoFar = if (pathSoFar.isEmpty()) part else "$pathSoFar/$part"
            val already = cache[pathSoFar]
            current = if (already != null) {
                already
            } else {
                val existing = current.findFile(part)
                val dir = if (existing != null && existing.isDirectory) existing else current.createDirectory(part)
                val resolved = dir ?: current
                cache[pathSoFar] = resolved
                resolved
            }
        }
        return current
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "webm" -> "video/webm"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }
}
