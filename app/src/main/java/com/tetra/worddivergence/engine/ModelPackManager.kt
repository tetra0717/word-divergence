package com.tetra.worddivergence.engine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class ModelPackManager(private val context: Context) {
    val modelDir: File = File(context.filesDir, "model-pack")
    private val indexFile get() = File(modelDir, "vectors.usearch")
    private val wordsFile get() = File(modelDir, "words.sqlite")

    data class Part(
        val name: String,
        val size: Long,
        val sha256: String
    )

    data class Manifest(
        val archive: String,
        val archiveSize: Long,
        val archiveSha256: String,
        val parts: List<Part>
    )

    fun isInstalled(): Boolean = indexFile.isFile && wordsFile.isFile

    fun downloadAndInstall(onProgress: (Int) -> Unit) {
        val manifest = fetchManifest()
        require(manifest.parts.isNotEmpty()) { "model manifest has no parts" }

        val tmpArchive = File(context.cacheDir, manifest.archive)
        val total = manifest.archiveSize.coerceAtLeast(1L)

        // Resume only at a known complete-part boundary. A process kill in the
        // middle of a part leaves a partial tail, which is discarded here.
        var completeBytes = 0L
        var startPart = 0
        val existing = tmpArchive.length()
        for ((index, part) in manifest.parts.withIndex()) {
            val next = completeBytes + part.size
            if (existing >= next) {
                completeBytes = next
                startPart = index + 1
            } else {
                break
            }
        }
        if (existing != completeBytes) {
            RandomAccessFile(tmpArchive, "rw").use { it.setLength(completeBytes) }
        }

        onProgress(((completeBytes * 94L) / total).toInt().coerceIn(0, 94))

        RandomAccessFile(tmpArchive, "rw").use { output ->
            output.seek(completeBytes)
            for (i in startPart until manifest.parts.size) {
                val part = manifest.parts[i]
                val startOffset = output.length()
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L

                try {
                    openReleaseAsset(part.name).use { connection ->
                        connection.inputStream.buffered(1024 * 256).use { input ->
                            val buffer = ByteArray(1024 * 256)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                written += read
                                completeBytes += read
                                onProgress(
                                    ((completeBytes * 94L) / total)
                                        .toInt()
                                        .coerceIn(0, 94)
                                )
                            }
                        }
                    }

                    check(written == part.size) {
                        "model part size mismatch: " + part.name +
                            " expected=" + part.size + " actual=" + written
                    }
                    check(hex(digest.digest()).equals(part.sha256, ignoreCase = true)) {
                        "model part checksum mismatch: " + part.name
                    }
                } catch (t: Throwable) {
                    output.setLength(startOffset)
                    throw t
                }
            }
        }

        check(tmpArchive.length() == manifest.archiveSize) {
            "model archive size mismatch"
        }
        onProgress(95)

        val archiveHash = sha256File(tmpArchive)
        if (!archiveHash.equals(manifest.archiveSha256, ignoreCase = true)) {
            tmpArchive.delete()
            throw IllegalStateException("model archive checksum mismatch; retry download")
        }
        onProgress(96)

        val staging = File(context.filesDir, "model-pack-staging")
        staging.deleteRecursively()
        staging.mkdirs()

        try {
            ZipInputStream(tmpArchive.inputStream().buffered(1024 * 256)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val out = File(staging, entry.name).canonicalFile
                    require(out.path.startsWith(staging.canonicalPath + File.separator)) {
                        "invalid zip path"
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().buffered(1024 * 256).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            check(File(staging, "vectors.usearch").isFile) {
                "model pack is missing vectors.usearch"
            }
            check(File(staging, "words.sqlite").isFile) {
                "model pack is missing words.sqlite"
            }

            val old = File(context.filesDir, "model-pack-old")
            old.deleteRecursively()
            if (modelDir.exists() && !modelDir.renameTo(old)) {
                throw IllegalStateException("could not move previous model aside")
            }
            if (!staging.renameTo(modelDir)) {
                if (old.exists()) old.renameTo(modelDir)
                throw IllegalStateException("could not activate downloaded model")
            }
            old.deleteRecursively()
            tmpArchive.delete()
            onProgress(100)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun fetchManifest(): Manifest {
        val text = openUrl(MANIFEST_URL).use { connection ->
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val json = JSONObject(text)
        check(json.optInt("format", -1) == 1) { "unsupported model manifest format" }

        val partsJson = json.getJSONArray("parts")
        val parts = ArrayList<Part>(partsJson.length())
        for (i in 0 until partsJson.length()) {
            val p = partsJson.getJSONObject(i)
            parts += Part(
                name = p.getString("name"),
                size = p.getLong("size"),
                sha256 = p.getString("sha256")
            )
        }

        return Manifest(
            archive = json.getString("archive"),
            archiveSize = json.getLong("archive_size"),
            archiveSha256 = json.getString("archive_sha256"),
            parts = parts
        )
    }

    private fun openReleaseAsset(name: String): HttpURLConnection =
        openUrl(RELEASE_BASE + name)

    private fun openUrl(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("User-Agent", "word-divergence-android")
        connection.connect()
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            throw IllegalStateException("model download HTTP " + code + ": " + url)
        }
        return connection
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02x".format(it) }

    companion object {
        private const val RELEASE_BASE =
            "https://github.com/tetra0717/word-divergence/releases/download/model-v1/"
        private const val MANIFEST_URL = RELEASE_BASE + "model-manifest.json"
    }
}
