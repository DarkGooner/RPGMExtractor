package com.personal.rpgmextractor.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

enum class AssetCategory { IMAGE, AUDIO, VIDEO, OTHER }

data class AssetEntry(
    val uri: Uri,
    val relativePath: String,
    val category: AssetCategory,
    val encrypted: Boolean,
    val outputExtension: String
)

data class ScanResult(
    val entries: List<AssetEntry>,
    val encryptionKeyHex: String?
) {
    val imageCount get() = entries.count { it.category == AssetCategory.IMAGE }
    val audioCount get() = entries.count { it.category == AssetCategory.AUDIO }
    val videoCount get() = entries.count { it.category == AssetCategory.VIDEO }
}

/**
 * Walks a picked game folder (via Storage Access Framework) looking for
 * RPG Maker MV/MZ assets - both encrypted (.rpgmvp/.rpgmvo/.rpgmvm/.png_ etc.)
 * and plain (.png/.ogg/.m4a/.webm/.mp4) - and tries to locate the
 * "encryptionKey" inside data/System.json.
 *
 * Performance note: [DocumentFile.listFiles] only fetches each child's
 * document ID in its query - every subsequent call to `.name` or
 * `.isDirectory` on a child issues its *own* separate query (a classic
 * SAF N+1 problem). For a game folder with thousands of files this turns
 * a scan into thousands of extra binder round-trips. Instead we query
 * [DocumentsContract] directly with a single batched projection
 * (document id + display name + mime type for ALL children of a folder
 * in one call), which is dramatically faster.
 */
object GameScanner {

    private val CHILD_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )

    fun scan(context: Context, root: DocumentFile): ScanResult {
        val entries = mutableListOf<AssetEntry>()
        var keyHex: String? = null
        val resolver = context.contentResolver
        val treeUri = root.uri

        fun walk(parentUri: Uri, relPath: String) {
            val parentDocId = try {
                DocumentsContract.getDocumentId(parentUri)
            } catch (e: Exception) {
                return
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

            val cursor = try {
                resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
            } catch (e: Exception) {
                null
            } ?: return

            // Collect children first so the cursor is closed before we recurse
            // (recursive queries while a parent cursor is open can be unreliable
            // on some document providers).
            data class Child(val docId: String, val name: String, val isDir: Boolean)
            val children = ArrayList<Child>()
            cursor.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idIdx < 0 || nameIdx < 0 || mimeIdx < 0) return@use
                while (c.moveToNext()) {
                    val docId = c.getString(idIdx) ?: continue
                    val name = c.getString(nameIdx) ?: continue
                    val mime = c.getString(mimeIdx)
                    children += Child(docId, name, mime == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            }

            for (child in children) {
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)
                val childRel = if (relPath.isEmpty()) child.name else "$relPath/${child.name}"

                if (child.isDir) {
                    walk(childUri, childRel)
                    continue
                }

                if (keyHex == null && child.name.equals("System.json", ignoreCase = true)) {
                    keyHex = extractKey(resolver, childUri)
                }

                val ext = child.name.substringAfterLast('.', "").lowercase()
                val mappedExt = AssetTypes.ENCRYPTED_MAP[ext]
                when {
                    mappedExt != null -> {
                        val cat = when (mappedExt) {
                            "png" -> AssetCategory.IMAGE
                            "ogg", "m4a" -> AssetCategory.AUDIO
                            "webm" -> AssetCategory.VIDEO
                            else -> AssetCategory.OTHER
                        }
                        entries += AssetEntry(childUri, childRel, cat, encrypted = true, outputExtension = mappedExt)
                    }
                    ext in AssetTypes.IMAGE_EXT ->
                        entries += AssetEntry(childUri, childRel, AssetCategory.IMAGE, false, ext)
                    ext in AssetTypes.AUDIO_EXT ->
                        entries += AssetEntry(childUri, childRel, AssetCategory.AUDIO, false, ext)
                    ext in AssetTypes.VIDEO_EXT ->
                        entries += AssetEntry(childUri, childRel, AssetCategory.VIDEO, false, ext)
                }
            }
        }

        walk(treeUri, "")
        return ScanResult(entries, keyHex)
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
