package com.personal.rpgmextractor.core

import java.io.InputStream
import java.io.OutputStream

/**
 * Handles RPG Maker MV / MZ asset "encryption" (a simple XOR obfuscation).
 *
 * Format of an encrypted file:
 *  - 16 byte fixed header/signature (ignored on decrypt, just skipped)
 *  - 16 bytes that are the first 16 bytes of the *original* file, XORed
 *    with a 16 byte key taken from System.json's "encryptionKey" field
 *  - the remainder of the original file, stored as-is (unencrypted)
 *
 * This is the same scheme implemented by the many open-source RPG Maker
 * asset viewers/decrypters. It is not a real access-control / DRM system,
 * just an obfuscation the engine uses so raw assets aren't sitting in the
 * folder as plain files.
 */
object RpgMakerDecryptor {

    private const val HEADER_LEN = 16

    /** Converts a hex string (e.g. from System.json) into raw key bytes. */
    fun hexKeyToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i + 1 < clean.length) {
            val hi = Character.digit(clean[i], 16)
            val lo = Character.digit(clean[i + 1], 16)
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    /**
     * Reads an encrypted asset from [input] and writes the decrypted,
     * playable/viewable file to [output]. Returns true on success.
     */
    fun decryptStream(input: InputStream, output: OutputStream, key: ByteArray): Boolean {
        if (key.isEmpty()) return false

        // Skip the 16 byte fixed signature/header.
        val header = ByteArray(HEADER_LEN)
        if (readFully(input, header) < HEADER_LEN) return false

        // Next 16 bytes are XORed with the key.
        val block = ByteArray(HEADER_LEN)
        if (readFully(input, block) < HEADER_LEN) return false
        for (i in 0 until HEADER_LEN) {
            block[i] = (block[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        output.write(block)

        // Everything else is copied verbatim.
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
        return true
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r == -1) break
            total += r
        }
        return total
    }
}

/** Known extensions used by RPG Maker MV/MZ and their decrypted equivalent. */
object AssetTypes {
    val ENCRYPTED_MAP = mapOf(
        "rpgmvp" to "png",
        "rpgmvo" to "ogg",
        "rpgmvm" to "m4a",
        "png_" to "png",
        "ogg_" to "ogg",
        "m4a_" to "m4a",
        "rpgmvw" to "webm"
    )
    val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp")
    val AUDIO_EXT = setOf("ogg", "m4a", "wav", "mp3")
    val VIDEO_EXT = setOf("webm", "mp4", "avi")
}
