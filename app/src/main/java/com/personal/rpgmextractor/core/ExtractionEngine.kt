package com.personal.rpgmextractor.core

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extracts (and decrypts, where needed) assets found by [GameScanner] into
 * a destination folder, preserving the original folder structure.
 *
 * Performance notes:
 * - [DocumentFile.findFile] does a *full directory listing* on every call.
 *   Calling it once per output file (the naive approach) makes the whole
 *   extraction O(files^2) inside any folder with many files - this is what
 *   made large extractions take tens of minutes. Instead we list each
 *   directory's children exactly once and cache the name -> DocumentFile
 *   map, so existence checks become O(1) hash lookups.
 * - Files are copied with a larger buffer to cut down on IPC round-trips
 *   through the SAF content provider, which matters more than CPU here.
 * - Multiple files are processed concurrently (bounded by [concurrency])
 *   since most of the time is spent waiting on I/O, not computing.
 */
object ExtractionEngine {

    private const val COPY_BUFFER_BYTES = 512 * 1024 // 512 KB

    suspend fun extract(
        context: Context,
        entries: List<AssetEntry>,
        keyBytes: ByteArray?,
        outputRoot: DocumentFile,
        concurrency: Int = 8,
        onProgress: (done: Int, total: Int, currentName: String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val total = entries.size
        val done = AtomicInteger(0)

        // dirCache: relative directory path -> its DocumentFile
        val dirCache = HashMap<String, DocumentFile>()
        // childCache: directory DocumentFile's uri -> (filename -> DocumentFile), populated
        // with ONE listFiles() call per directory instead of one findFile() call per file.
        val childCache = HashMap<String, MutableMap<String, DocumentFile>>()
        val cacheMutex = Mutex()

        val semaphore = Semaphore(concurrency)

        val jobs = entries.map { entry ->
            async {
                semaphore.withPermit {
                    val ok = try {
                        val relDir = entry.relativePath.substringBeforeLast('/', "")
                        val fileNameNoExt = entry.relativePath.substringAfterLast('/')
                            .substringBeforeLast('.', entry.relativePath.substringAfterLast('/'))
                        val outName = "$fileNameNoExt.${entry.outputExtension}"

                        val outDoc = cacheMutex.withLock {
                            val targetDir = resolveDirLocked(outputRoot, relDir, dirCache, childCache)
                            getOrCreateFileLocked(targetDir, outName, entry.outputExtension, childCache)
                        }

                        if (outDoc != null) {
                            context.contentResolver.openInputStream(entry.doc.uri)?.use { input ->
                                context.contentResolver.openOutputStream(outDoc.uri, "wt")?.use { output ->
                                    if (entry.encrypted && keyBytes != null) {
                                        RpgMakerDecryptor.decryptStream(input, output, keyBytes)
                                    } else {
                                        input.copyTo(output, bufferSize = COPY_BUFFER_BYTES)
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

    /** Finds (or creates) the destination directory matching [relDir], caching lookups. Must hold cacheMutex. */
    private fun resolveDirLocked(
        root: DocumentFile,
        relDir: String,
        dirCache: HashMap<String, DocumentFile>,
        childCache: HashMap<String, MutableMap<String, DocumentFile>>
    ): DocumentFile {
        if (relDir.isEmpty()) return root
        dirCache[relDir]?.let { return it }

        val parts = relDir.split('/')
        var current = root
        var pathSoFar = ""
        for (part in parts) {
            pathSoFar = if (pathSoFar.isEmpty()) part else "$pathSoFar/$part"
            val cached = dirCache[pathSoFar]
            current = if (cached != null) {
                cached
            } else {
                val children = childrenOfLocked(current, childCache)
                val existing = children[part]
                val resolved = if (existing != null && existing.isDirectory) {
                    existing
                } else {
                    val created = current.createDirectory(part) ?: current
                    children[part] = created
                    created
                }
                dirCache[pathSoFar] = resolved
                resolved
            }
        }
        return current
    }

    /** Returns (building + caching if needed) the name->file map for [dir]'s direct children. Must hold cacheMutex. */
    private fun childrenOfLocked(
        dir: DocumentFile,
        childCache: HashMap<String, MutableMap<String, DocumentFile>>
    ): MutableMap<String, DocumentFile> {
        val key = dir.uri.toString()
        childCache[key]?.let { return it }
        val map = HashMap<String, DocumentFile>()
        for (child in dir.listFiles()) {
            child.name?.let { map[it] = child }
        }
        childCache[key] = map
        return map
    }

    /** Reuses an existing output file if present, else creates it - using the cached listing, not findFile(). */
    private fun getOrCreateFileLocked(
        targetDir: DocumentFile,
        outName: String,
        outputExtension: String,
        childCache: HashMap<String, MutableMap<String, DocumentFile>>
    ): DocumentFile? {
        val children = childrenOfLocked(targetDir, childCache)
        children[outName]?.let { return it }
        val created = targetDir.createFile(mimeFor(outputExtension), outName)
        if (created != null) {
            children[created.name ?: outName] = created
        }
        return created
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
