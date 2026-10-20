package com.personal.rpgmextractor.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extracts (and decrypts, where needed) assets found by [GameScanner] into
 * a destination folder, preserving the original folder structure.
 *
 * Performance notes:
 * - All destination folders are created up front (there are only a handful),
 *   so the per-file phase never needs a global lock: workers create their
 *   output documents fully in parallel. Previously every createFile() - the
 *   single most expensive SAF call - was serialised behind one mutex.
 * - Existing destination contents are read with ONE batched query per folder
 *   (see [listChildren]) rather than DocumentFile.listFiles() + a binder call
 *   per child for its name.
 * - A fixed pool of [concurrency] workers pulls from a shared index instead of
 *   spawning one coroutine per asset, and each worker reuses a single copy
 *   buffer, so 60k+ assets don't mean 60k+ coroutines / buffer allocations.
 * - Files already fully extracted by an earlier (e.g. stopped) run are detected
 *   by size and skipped, so "continue" after Stop resumes where it left off.
 */
object ExtractionEngine {

    private const val COPY_BUFFER_BYTES = 256 * 1024 // 256 KB per worker

    /** Size of the fixed signature that encrypted files carry and decrypted ones don't. */
    private const val ENCRYPTED_HEADER_BYTES = 16

    suspend fun extract(
        context: Context,
        entries: List<AssetEntry>,
        keyBytes: ByteArray?,
        outputTreeUri: Uri,
        gate: PauseGate,
        concurrency: Int = 12,
        onProgress: (success: Int, done: Int, total: Int, currentName: String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        // e.g. Actor1.png_ and Actor1.rpgmvp would both become Actor1.png - only write it once.
        val unique = entries.distinctBy { outputPath(it) }
        val total = unique.size

        // dir doc uri -> (child name -> child), filled lazily, one query per folder.
        val listings = ConcurrentHashMap<String, Map<String, ChildDoc>>()
        fun childrenOf(dirUri: Uri): Map<String, ChildDoc> =
            listings.computeIfAbsent(dirUri.toString()) {
                listChildren(resolver, outputTreeUri, DocumentsContract.getDocumentId(dirUri))
                    .associateBy { it.name }
            }

        // Phase 1: create the folder tree sequentially.
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(
            outputTreeUri, DocumentsContract.getTreeDocumentId(outputTreeUri)
        )
        val dirUris = HashMap<String, Uri>().apply { put("", rootDocUri) }
        fun ensureDir(relDir: String): Uri {
            dirUris[relDir]?.let { return it }
            val parent = ensureDir(relDir.substringBeforeLast('/', ""))
            val name = relDir.substringAfterLast('/')
            val existing = childrenOf(parent)[name]
            val uri = if (existing != null && existing.isDir) {
                DocumentsContract.buildDocumentUriUsingTree(outputTreeUri, existing.docId)
            } else {
                val created = DocumentsContract.createDocument(
                    resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name
                ) ?: throw IOException("Could not create folder $relDir")
                listings[created.toString()] = emptyMap() // brand new: nothing to list
                created
            }
            dirUris[relDir] = uri
            return uri
        }
        for (relDir in unique.mapTo(sortedSetOf()) { it.relativePath.substringBeforeLast('/', "") }) {
            ensureActive()
            ensureDir(relDir)
        }

        // Phase 2: copy/decrypt files with a fixed worker pool.
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val success = AtomicInteger(0)
        repeat(concurrency.coerceAtMost(total.coerceAtLeast(1))) {
            launch {
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    gate.awaitResumed()
                    ensureActive()
                    val i = next.getAndIncrement()
                    if (i >= total) break
                    val entry = unique[i]
                    val ok = try {
                        val dirUri = dirUris.getValue(entry.relativePath.substringBeforeLast('/', ""))
                        extractOne(resolver, entry, keyBytes, dirUri, childrenOf(dirUri), buffer)
                    } catch (e: Exception) {
                        false
                    }
                    val s = if (ok) success.incrementAndGet() else success.get()
                    onProgress(s, done.incrementAndGet(), total, entry.relativePath)
                }
            }
        }
        // withContext only returns once all workers have finished.
        success
    }.get()

    private fun extractOne(
        resolver: ContentResolver,
        entry: AssetEntry,
        keyBytes: ByteArray?,
        dirUri: Uri,
        existingChildren: Map<String, ChildDoc>,
        buffer: ByteArray
    ): Boolean {
        val outName = outputName(entry)
        val decrypt = entry.encrypted && keyBytes != null
        val existing = existingChildren[outName]?.takeIf { !it.isDir }

        if (existing != null && entry.size > 0) {
            val expectedSize = if (decrypt) entry.size - ENCRYPTED_HEADER_BYTES else entry.size
            if (existing.size == expectedSize) return true // already extracted by an earlier run
        }

        val target = existing?.let { DocumentsContract.buildDocumentUriUsingTree(dirUri, it.docId) }
            ?: DocumentsContract.createDocument(resolver, dirUri, mimeFor(entry.outputExtension), outName)
            ?: return false

        return resolver.openInputStream(entry.uri)?.use { input ->
            resolver.openOutputStream(target, "wt")?.use { output ->
                if (decrypt) {
                    RpgMakerDecryptor.decryptStream(input, output, keyBytes!!, buffer)
                } else {
                    RpgMakerDecryptor.copy(input, output, buffer)
                    true
                }
            }
        } ?: false
    }

    private fun outputName(entry: AssetEntry): String {
        val fileName = entry.relativePath.substringAfterLast('/')
        return "${fileName.substringBeforeLast('.', fileName)}.${entry.outputExtension}"
    }

    private fun outputPath(entry: AssetEntry): String {
        val relDir = entry.relativePath.substringBeforeLast('/', "")
        return if (relDir.isEmpty()) outputName(entry) else "$relDir/${outputName(entry)}"
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
