package com.tetra.worddivergence.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class ModelPackManager(private val context: Context) {
    val modelDir: File = File(context.filesDir, "model-pack")
    private val indexFile get() = File(modelDir, "vectors.usearch")
    private val wordsFile get() = File(modelDir, "words.sqlite")

    fun isInstalled(): Boolean = indexFile.isFile && wordsFile.isFile

    fun downloadAndInstall(onProgress: (Int) -> Unit) {
        val url = URL(MODEL_URL)
        val tmp = File(context.cacheDir, "model-pack.zip")
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("model download HTTP " + connection.responseCode)
        }
        val total = connection.contentLengthLong.coerceAtLeast(1L)
        connection.inputStream.use { input ->
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(1024 * 256)
                var read: Int
                var done = 0L
                while (input.read(buffer).also { read = it } >= 0) {
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    done += read
                    onProgress(((done * 100L) / total).toInt().coerceIn(0, 99))
                }
            }
        }
        modelDir.deleteRecursively()
        modelDir.mkdirs()
        ZipInputStream(tmp.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(modelDir, entry.name).canonicalFile
                require(out.path.startsWith(modelDir.canonicalPath + File.separator)) { "invalid zip path" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        tmp.delete()
        check(isInstalled()) { "model pack is missing vectors.usearch or words.sqlite" }
        onProgress(100)
    }

    companion object {
        const val MODEL_URL =
            "https://github.com/tetra0717/word-divergence/releases/download/model-v1/ja-fasttext-usearch-pack.zip"
    }
}
