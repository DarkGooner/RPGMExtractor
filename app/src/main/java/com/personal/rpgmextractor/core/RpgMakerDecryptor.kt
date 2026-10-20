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

    /** Encrypted files start with "RPGMV" followed by version bytes. */
    private val SIGNATURE = "RPGMV".toByteArray(Charsets.US_ASCII)

    /** The first 16 bytes of every PNG file: 8-byte magic + the IHDR chunk header. */
    private val PNG_HEADER = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    )

    /**
     * Recovers the real key from an encrypted PNG: its XORed block must decrypt to
     * [PNG_HEADER], so key = block XOR PNG_HEADER. This doesn't depend on System.json
     * at all, so it still works when the key there is missing, stale or picked from
     * the wrong file (some games ship several System.json copies).
     */
    fun keyFromEncryptedPng(input: InputStream): ByteArray? {
        val header = ByteArray(HEADER_LEN)
        if (readFully(input, header) < HEADER_LEN || !hasSignature(header)) return null
        val block = ByteArray(HEADER_LEN)
        if (readFully(input, block) < HEADER_LEN) return null
        return ByteArray(HEADER_LEN) { i -> (block[i].toInt() xor PNG_HEADER[i].toInt()).toByte() }
    }

    private fun hasSignature(header: ByteArray): Boolean =
        SIGNATURE.indices.all { header[it] == SIGNATURE[it] }

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
     * playable/viewable file to [output]. Returns the number of bytes written,
     * or -1 if the input is too short to be an encrypted asset.
     * [buffer] is supplied by the caller so it can be reused across files.
     */
    fun decryptStream(input: InputStream, output: OutputStream, key: ByteArray, buffer: ByteArray): Long {
        if (key.isEmpty()) return -1

        // Skip the 16 byte fixed signature/header.
        val header = ByteArray(HEADER_LEN)
        val headerRead = readFully(input, header)
        if (headerRead < HEADER_LEN || !hasSignature(header)) {
            // Not actually encrypted (some games ship plain files with encrypted
            // extensions) - pass it through untouched instead of corrupting it.
            output.write(header, 0, headerRead)
            return headerRead + copy(input, output, buffer)
        }

        // Next 16 bytes are XORed with the key.
        val block = ByteArray(HEADER_LEN)
        if (readFully(input, block) < HEADER_LEN) return -1
        for (i in 0 until HEADER_LEN) {
            block[i] = (block[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        output.write(block)

        // Everything else is copied verbatim.
        return HEADER_LEN + copy(input, output, buffer)
    }

    /**
     * Plain stream copy using a caller-owned buffer (avoids a fresh allocation per file).
     * Returns the number of bytes copied.
     */
    fun copy(input: InputStream, output: OutputStream, buffer: ByteArray): Long {
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        return total
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
