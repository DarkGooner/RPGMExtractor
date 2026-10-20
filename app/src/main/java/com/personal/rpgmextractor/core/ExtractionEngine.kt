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
import java.util.concurrent.atomic.AtomicReference

/** [firstError] describes the first file that failed (path + reason), for showing to the user. */
data class ExtractionResult(val success: Int, val total: Int, val firstError: String?)

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
 * - When continuing after Stop, files already fully extracted are detected by
 *   size and skipped, so the job resumes where it left off. A fresh start always
 *   overwrites, so bad output from an earlier run is replaced.
 * - The XOR key is recovered from an encrypted PNG itself (see
 *   [RpgMakerDecryptor.keyFromEncryptedPng]) rather than trusting System.json.
 */
object ExtractionEngine {

    private const val COPY_BUFFER_BYTES = 256 * 1024 // 256 KB per worker

    /** Size of the fixed signature that encrypted files carry and decrypted ones don't. */
    private const val ENCRYPTED_HEADER_BYTES = 16

    /**
     * After writing, the first few outputs are re-queried from the provider to check it
     * really stored the bytes. Doing it for every file would cost an extra IPC each.
     */
    private const val VERIFY_FIRST_N = 5

    suspend fun extract(
        context: Context,
        entries: List<AssetEntry>,
        keyBytes: ByteArray?,
        outputTreeUri: Uri,
        gate: PauseGate,
        skipCompleted: Boolean = false,
        concurrency: Int = 12,
        onProgress: (success: Int, done: Int, total: Int, currentName: String) -> Unit
    ): ExtractionResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        // e.g. Actor1.png_ and Actor1.rpgmvp would both become Actor1.png - only write it once.
        val unique = entries.distinctBy { outputPath(it) }
        val total = unique.size
        val key = keyFromAssets(resolver, unique) ?: keyBytes

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
        val verified = AtomicInteger(0)
        val firstError = AtomicReference<String?>(null)
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
                        val target = extractOne(resolver, entry, key, dirUri, childrenOf(dirUri), skipCompleted, buffer)
                        if (target != null && verified.getAndIncrement() < VERIFY_FIRST_N) {
                            verifyWritten(resolver, target, entry, key)
                        }
                        true
                    } catch (e: Exception) {
                        firstError.compareAndSet(null, "${entry.relativePath}: ${e.message ?: e.javaClass.simpleName}")
                        false
                    }
                    val s = if (ok) success.incrementAndGet() else success.get()
                    onProgress(s, done.incrementAndGet(), total, entry.relativePath)
                }
            }
        }
        // withContext only returns once all workers have finished.
        ExtractionResult(success.get(), total, firstError.get())
    }

    /**
     * Derives the key from the first readable encrypted PNG (see
     * [RpgMakerDecryptor.keyFromEncryptedPng]); null if the game has none.
     */
    private fun keyFromAssets(resolver: ContentResolver, entries: List<AssetEntry>): ByteArray? =
        entries.asSequence()
            .filter { it.encrypted && it.outputExtension == "png" }
            .take(5)
            .firstNotNullOfOrNull { entry ->
                runCatching {
                    resolver.openInputStream(entry.uri)?.use { RpgMakerDecryptor.keyFromEncryptedPng(it) }
                }.getOrNull()
            }

    /**
     * Writes one asset. Returns the written document, or null if it was skipped because an
     * earlier run already extracted it. Throws with a human readable reason on any failure;
     * a file created by this call is deleted again rather than left behind empty.
     */
    private fun extractOne(
        resolver: ContentResolver,
        entry: AssetEntry,
        keyBytes: ByteArray?,
        dirUri: Uri,
        existingChildren: Map<String, ChildDoc>,
        skipCompleted: Boolean,
        buffer: ByteArray
    ): Uri? {
        val outName = outputName(entry)
        val decrypt = entry.encrypted && keyBytes != null
        val existing = existingChildren[outName]?.takeIf { !it.isDir }
        val expectedSize = if (entry.size <= 0) -1L
            else if (decrypt) entry.size - ENCRYPTED_HEADER_BYTES else entry.size

        if (skipCompleted && existing != null && expectedSize > 0 && existing.size == expectedSize) {
            return null // already extracted by an earlier run
        }

        val target = existing?.let { DocumentsContract.buildDocumentUriUsingTree(dirUri, it.docId) }
            ?: DocumentsContract.createDocument(resolver, dirUri, mimeFor(entry.outputExtension), outName)
            ?: throw IOException("could not create output file")

        try {
            val input = resolver.openInputStream(entry.uri)
                ?: throw IOException("could not open source file")
            val written = input.use {
                val output = resolver.openOutputStream(target, "wt")
                    ?: throw IOException("could not open output file for writing")
                output.use { out ->
                    if (decrypt) {
                        RpgMakerDecryptor.decryptStream(input, out, keyBytes!!, buffer)
                    } else {
                        RpgMakerDecryptor.copy(input, out, buffer)
                    }
                }
            }
            when {
                written < 0 -> throw IOException("not a valid encrypted file")
                written == 0L -> throw IOException("source file returned no data (expected ${entry.size} bytes)")
                // A "encrypted" file without the RPGMV signature is passed through at full size.
                expectedSize > 0 && written != expectedSize && written != entry.size ->
                    throw IOException("wrote $written of $expectedSize bytes")
            }
        } catch (e: Exception) {
            if (existing == null) runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw e
        }
        return target
    }

    /** Re-reads [target]'s size from the provider to catch writes that silently didn't stick. */
    private fun verifyWritten(resolver: ContentResolver, target: Uri, entry: AssetEntry, keyBytes: ByteArray?) {
        val stored = resolver.query(target, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
        if (stored == 0L) {
            val expected = if (entry.encrypted && keyBytes != null) entry.size - ENCRYPTED_HEADER_BYTES else entry.size
            throw IOException("output folder reports 0 bytes after writing $expected bytes")
        }
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
