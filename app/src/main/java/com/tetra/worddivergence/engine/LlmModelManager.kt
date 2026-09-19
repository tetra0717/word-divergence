package com.tetra.worddivergence.engine

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class LlmModelManager(private val context: Context) {
    private val modelsDir = File(context.filesDir, "models")
    val modelFile = File(modelsDir, MODEL_FILE)
    private val okMarker = File(modelsDir, MODEL_FILE + ".sha256-ok")
    private val tempFile = File(context.cacheDir, MODEL_FILE + ".part")

    fun isInstalled(): Boolean =
        modelFile.isFile &&
            modelFile.length() > 1_000_000_000L &&
            okMarker.readTextOrNull() == MODEL_SHA256

    fun downloadAndInstall(onProgress: (Int) -> Unit) {
        modelsDir.mkdirs()

        var existing = tempFile.length()
        var connection = openConnection(existing)
        var response = connection.responseCode

        if (existing > 0L && response != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            tempFile.delete()
            existing = 0L
            connection = openConnection(0L)
            response = connection.responseCode
        }

        if (response !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("LLM download HTTP " + response)
        }

        val total = totalBytes(connection, existing).coerceAtLeast(1L)
        RandomAccessFile(tempFile, "rw").use { output ->
            if (existing > 0L && response == HttpURLConnection.HTTP_PARTIAL) {
                output.seek(existing)
            } else {
                output.setLength(0L)
                existing = 0L
            }

            try {
                connection.inputStream.buffered(1024 * 256).use { input ->
                    val buffer = ByteArray(1024 * 256)
                    var done = existing
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(((done * 96L) / total).toInt().coerceIn(0, 96))
                    }
                }
            } finally {
                connection.disconnect()
            }
        }

        onProgress(97)
        val digest = sha256(tempFile)
        check(digest.equals(MODEL_SHA256, ignoreCase = true)) {
            tempFile.delete()
            "Qwen model checksum mismatch"
        }

        if (modelFile.exists()) modelFile.delete()
        check(tempFile.renameTo(modelFile)) { "Could not activate Qwen model" }
        okMarker.writeText(MODEL_SHA256)

        File(context.filesDir, "model-pack").deleteRecursively()
        File(context.filesDir, "model-pack-old").deleteRecursively()
        File(context.filesDir, "model-pack-staging").deleteRecursively()

        onProgress(100)
    }

    fun deleteModel() {
        modelFile.delete()
        okMarker.delete()
        tempFile.delete()
    }

    private fun openConnection(existing: Long): HttpURLConnection =
        (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "word-divergence-android")
            if (existing > 0L) setRequestProperty("Range", "bytes=" + existing + "-")
            connect()
        }

    private fun totalBytes(connection: HttpURLConnection, existing: Long): Long {
        val contentRange = connection.getHeaderField("Content-Range")
        val fromRange = contentRange
            ?.substringAfterLast('/', "")
            ?.toLongOrNull()
        if (fromRange != null) return fromRange
        val length = connection.contentLengthLong
        return if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
            existing + length.coerceAtLeast(0L)
        } else {
            length.coerceAtLeast(0L)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun File.readTextOrNull(): String? =
        runCatching { if (isFile) readText().trim() else null }.getOrNull()

    companion object {
        const val MODEL_NAME = "Qwen3-1.7B Q4_K_M"
        const val MODEL_FILE = "Qwen3-1.7B-Q4_K_M.gguf"
        const val MODEL_SHA256 =
            "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"
        const val MODEL_URL =
            "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf?download=true"
    }
}
