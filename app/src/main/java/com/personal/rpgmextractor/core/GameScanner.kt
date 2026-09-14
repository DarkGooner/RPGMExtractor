package com.personal.rpgmextractor.core

import android.content.Context
import androidx.documentfile.provider.DocumentFile

enum class AssetCategory { IMAGE, AUDIO, VIDEO, OTHER }

data class AssetEntry(
    val doc: DocumentFile,
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
 */
object GameScanner {

    fun scan(context: Context, root: DocumentFile): ScanResult {
        val entries = mutableListOf<AssetEntry>()
        var keyHex: String? = null

        fun walk(dir: DocumentFile, relPath: String) {
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                val childRel = if (relPath.isEmpty()) name else "$relPath/$name"

                if (child.isDirectory) {
                    walk(child, childRel)
                    continue
                }

                if (keyHex == null && name.equals("System.json", ignoreCase = true)) {
                    keyHex = extractKey(context, child)
                }

                val ext = name.substringAfterLast('.', "").lowercase()
                val mappedExt = AssetTypes.ENCRYPTED_MAP[ext]
                when {
                    mappedExt != null -> {
                        val cat = when (mappedExt) {
                            "png" -> AssetCategory.IMAGE
                            "ogg", "m4a" -> AssetCategory.AUDIO
                            "webm" -> AssetCategory.VIDEO
                            else -> AssetCategory.OTHER
                        }
                        entries += AssetEntry(child, childRel, cat, encrypted = true, outputExtension = mappedExt)
                    }
                    ext in AssetTypes.IMAGE_EXT ->
                        entries += AssetEntry(child, childRel, AssetCategory.IMAGE, false, ext)
                    ext in AssetTypes.AUDIO_EXT ->
                        entries += AssetEntry(child, childRel, AssetCategory.AUDIO, false, ext)
                    ext in AssetTypes.VIDEO_EXT ->
                        entries += AssetEntry(child, childRel, AssetCategory.VIDEO, false, ext)
                }
            }
        }

        walk(root, "")
        return ScanResult(entries, keyHex)
    }

    private fun extractKey(context: Context, systemJson: DocumentFile): String? {
        return try {
            context.contentResolver.openInputStream(systemJson.uri)?.use { stream ->
                val text = stream.bufferedReader().readText()
                Regex("\"encryptionKey\"\\s*:\\s*\"([0-9a-fA-F]+)\"")
                    .find(text)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            null
        }
    }
}
