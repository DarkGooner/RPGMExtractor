package com.personal.rpgmextractor.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class AssetCategory { IMAGE, AUDIO, VIDEO, OTHER }

data class AssetEntry(
    val uri: Uri,
    val relativePath: String,
    val category: AssetCategory,
    val encrypted: Boolean,
    val outputExtension: String,
    /** Source size in bytes, or -1 if the provider didn't report it. */
    val size: Long
)

data class ScanResult(
    val entries: List<AssetEntry>,
    val encryptionKeyHex: String?
) {
    // Computed once - recounting 60k+ entries on every recomposition adds up.
    val imageCount = entries.count { it.category == AssetCategory.IMAGE }
    val audioCount = entries.count { it.category == AssetCategory.AUDIO }
    val videoCount = entries.count { it.category == AssetCategory.VIDEO }
}

/** One row of a batched [DocumentsContract] child listing. */
internal class ChildDoc(val docId: String, val name: String, val isDir: Boolean, val size: Long)

private val CHILD_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE
)

/**
 * Lists all direct children of [parentDocId] with ONE query (id + name + mime + size),
 * instead of [androidx.documentfile.provider.DocumentFile.listFiles] followed by a
 * separate binder round-trip for every child's `.name` / `.isDirectory` / `.length()`.
 */
internal fun listChildren(resolver: ContentResolver, treeUri: Uri, parentDocId: String): List<ChildDoc> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val cursor = try {
        resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
    } catch (e: Exception) {
        null
    } ?: return emptyList()

    val children = ArrayList<ChildDoc>(cursor.count.coerceAtLeast(0))
    cursor.use { c ->
        val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        if (idIdx < 0 || nameIdx < 0 || mimeIdx < 0) return@use
        while (c.moveToNext()) {
            val docId = c.getString(idIdx) ?: continue
            val name = c.getString(nameIdx) ?: continue
            val mime = c.getString(mimeIdx)
            val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L
            children += ChildDoc(docId, name, mime == DocumentsContract.Document.MIME_TYPE_DIR, size)
        }
    }
    return children
}

/**
 * Walks a picked game folder (via Storage Access Framework) looking for
 * RPG Maker MV/MZ assets - both encrypted (.rpgmvp/.rpgmvo/.rpgmvm/.png_ etc.)
 * and plain (.png/.ogg/.m4a/.webm/.mp4) - and tries to locate the
 * "encryptionKey" inside data/System.json.
 *
 * Performance notes:
 * - Each folder is listed with a single batched query (see [listChildren]).
 * - Sibling folders are listed concurrently (bounded by [MAX_PARALLEL_QUERIES]):
 *   nearly all scan time is binder/provider latency, so overlapping queries
 *   gives a large speed-up on games with many asset folders.
 */
object GameScanner {

    private const val MAX_PARALLEL_QUERIES = 8

    /**
     * @param treeUri the tree URI returned by the folder picker.
     * @param gate checked before each folder is listed, so the scan can be paused.
     * @param onProgress called after each folder with the number of assets found so far.
     */
    suspend fun scan(
        context: Context,
        treeUri: Uri,
        gate: PauseGate? = null,
        onProgress: (found: Int) -> Unit = {}
    ): ScanResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val entries = ConcurrentLinkedQueue<AssetEntry>()
        val found = AtomicInteger(0)
        val keyHex = AtomicReference<String?>(null)
        val querySlots = Semaphore(MAX_PARALLEL_QUERIES)

        // The permit is only held for the query itself (not while recursing), so
        // deep trees can't deadlock the semaphore.
        suspend fun walk(parentDocId: String, relPath: String): Unit = coroutineScope {
            gate?.awaitResumed()
            val children = querySlots.withPermit { listChildren(resolver, treeUri, parentDocId) }

            for (child in children) {
                val childRel = if (relPath.isEmpty()) child.name else "$relPath/${child.name}"

                if (child.isDir) {
                    launch { walk(child.docId, childRel) }
                    continue
                }

                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)

                if (keyHex.get() == null && child.name.equals("System.json", ignoreCase = true)) {
                    extractKey(resolver, childUri)?.let { keyHex.compareAndSet(null, it) }
                }

                val ext = child.name.substringAfterLast('.', "").lowercase()
                val mappedExt = AssetTypes.ENCRYPTED_MAP[ext]
                val entry = when {
                    mappedExt != null -> {
                        val cat = when (mappedExt) {
                            "png" -> AssetCategory.IMAGE
                            "ogg", "m4a" -> AssetCategory.AUDIO
                            "webm" -> AssetCategory.VIDEO
                            else -> AssetCategory.OTHER
                        }
                        AssetEntry(childUri, childRel, cat, encrypted = true, outputExtension = mappedExt, size = child.size)
                    }
                    ext in AssetTypes.IMAGE_EXT ->
                        AssetEntry(childUri, childRel, AssetCategory.IMAGE, false, ext, child.size)
                    ext in AssetTypes.AUDIO_EXT ->
                        AssetEntry(childUri, childRel, AssetCategory.AUDIO, false, ext, child.size)
                    ext in AssetTypes.VIDEO_EXT ->
                        AssetEntry(childUri, childRel, AssetCategory.VIDEO, false, ext, child.size)
                    else -> null
                }
                if (entry != null) {
                    entries += entry
                    found.incrementAndGet()
                }
            }
            ensureActive() // don't report progress for a scan that was just stopped
            onProgress(found.get())
        }

        walk(DocumentsContract.getTreeDocumentId(treeUri), "")
        // Parallel walking yields entries in arbitrary order; sorting keeps output
        // deterministic and groups files by folder for the extractor.
        ScanResult(entries.sortedBy { it.relativePath }, keyHex.get())
    }

    private fun extractKey(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.openInputStream(uri)?.use { stream ->
                val text = stream.bufferedReader().readText()
                Regex("\"encryptionKey\"\\s*:\\s*\"([0-9a-fA-F]+)\"")
                    .find(text)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            null
        }
    }
}
