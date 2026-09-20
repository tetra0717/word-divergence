package com.tetra.worddivergence.engine

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.security.MessageDigest

data class LlmDownloadProgress(
    val percent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val stage: String
) {
    val label: String
        get() {
            val downloadedMb = downloadedBytes / (1024L * 1024L)
            val totalMb = if (totalBytes > 0) totalBytes / (1024L * 1024L) else 0L
            return if (totalMb > 0) {
                stage + "  " + percent + "%  (" + downloadedMb + "MB / " + totalMb + "MB)"
            } else {
                stage + "  " + downloadedMb + "MB"
            }
        }
}

class LlmModelManager(private val context: Context) {
    private val legacyModelsDir = File(context.filesDir, "models")
    private val legacyModelFile = File(legacyModelsDir, MODEL_FILE)
    private val legacyMarker = File(legacyModelsDir, MODEL_FILE + ".sha256-ok")

    private val externalModelsDir = File(
        requireNotNull(context.getExternalFilesDir(null)) {
            "External app storage is unavailable"
        },
        "models"
    )
    private val externalModelFile = File(externalModelsDir, MODEL_FILE)
    private val externalMarker = File(externalModelsDir, MODEL_FILE + ".sha256-ok")

    private val downloadDir = requireNotNull(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    ) { "External download storage is unavailable" }
    private val downloadFile = File(downloadDir, MODEL_FILE + ".download")

    private val prefs = context.getSharedPreferences("llm_model_download", Context.MODE_PRIVATE)
    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    val modelFile: File
        get() = when {
            isValidModel(externalModelFile, externalMarker) -> externalModelFile
            isValidModel(legacyModelFile, legacyMarker) -> legacyModelFile
            else -> externalModelFile
        }

    fun isInstalled(): Boolean =
        isValidModel(externalModelFile, externalMarker) ||
            isValidModel(legacyModelFile, legacyMarker)

    fun downloadAndInstall(onProgress: (LlmDownloadProgress) -> Unit) {
        externalModelsDir.mkdirs()
        downloadDir.mkdirs()

        val id = existingActiveDownloadId() ?: enqueueDownload()

        while (true) {
            val query = DownloadManager.Query().setFilterById(id)
            downloadManager.query(query).use { cursor ->
                if (!cursor.moveToFirst()) {
                    prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
                    throw IllegalStateException("Android DownloadManager lost the model download")
                }

                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                val downloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                ).coerceAtLeast(0L)
                val total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                )
                val percent = if (total > 0L) {
                    ((downloaded * 96L) / total).toInt().coerceIn(0, 96)
                } else {
                    0
                }

                when (status) {
                    DownloadManager.STATUS_PENDING -> {
                        onProgress(LlmDownloadProgress(percent, downloaded, total, "待機中"))
                    }

                    DownloadManager.STATUS_RUNNING -> {
                        onProgress(LlmDownloadProgress(percent, downloaded, total, "ダウンロード中"))
                    }

                    DownloadManager.STATUS_PAUSED -> {
                        onProgress(LlmDownloadProgress(percent, downloaded, total, "一時停止・再開待ち"))
                    }

                    DownloadManager.STATUS_SUCCESSFUL -> {
                        onProgress(LlmDownloadProgress(97, downloaded, total, "検証中"))
                        break
                    }

                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                        )
                        prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
                        downloadManager.remove(id)
                        downloadFile.delete()
                        throw IllegalStateException(
                            "Android DownloadManager failed (reason=" + reason + ")"
                        )
                    }
                }
            }
            Thread.sleep(500L)
        }

        check(downloadFile.isFile && downloadFile.length() > 1_000_000_000L) {
            "Downloaded Qwen file is missing or incomplete"
        }

        val digest = sha256(downloadFile)
        check(digest.equals(MODEL_SHA256, ignoreCase = true)) {
            downloadFile.delete()
            prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
            "Qwen model checksum mismatch"
        }

        onProgress(
            LlmDownloadProgress(
                98,
                downloadFile.length(),
                downloadFile.length(),
                "インストール中"
            )
        )

        if (externalModelFile.exists()) externalModelFile.delete()
        if (!downloadFile.renameTo(externalModelFile)) {
            downloadFile.copyTo(externalModelFile, overwrite = true)
            downloadFile.delete()
        }
        externalMarker.writeText(MODEL_SHA256)

        if (legacyModelFile.exists()) legacyModelFile.delete()
        if (legacyMarker.exists()) legacyMarker.delete()
        File(context.filesDir, "model-pack").deleteRecursively()
        File(context.filesDir, "model-pack-old").deleteRecursively()
        File(context.filesDir, "model-pack-staging").deleteRecursively()

        prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
        runCatching { downloadManager.remove(id) }

        onProgress(
            LlmDownloadProgress(
                100,
                externalModelFile.length(),
                externalModelFile.length(),
                "完了"
            )
        )
    }

    fun deleteModel() {
        val id = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        if (id >= 0L) runCatching { downloadManager.remove(id) }
        prefs.edit().clear().apply()

        externalModelFile.delete()
        externalMarker.delete()
        legacyModelFile.delete()
        legacyMarker.delete()
        downloadFile.delete()
    }

    private fun existingActiveDownloadId(): Long? {
        val id = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        if (id < 0L) return null

        val query = DownloadManager.Query().setFilterById(id)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) {
                prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
                return null
            }

            return when (
                cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
            ) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED,
                DownloadManager.STATUS_SUCCESSFUL -> id

                else -> {
                    runCatching { downloadManager.remove(id) }
                    downloadFile.delete()
                    prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
                    null
                }
            }
        }
    }

    private fun enqueueDownload(): Long {
        runCatching {
            val oldId = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
            if (oldId >= 0L) downloadManager.remove(oldId)
        }
        downloadFile.delete()

        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("word divergence - Qwen3-1.7B")
            .setDescription("ローカルBrainstormモデルをダウンロード中")
            .setMimeType("application/octet-stream")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                MODEL_FILE + ".download"
            )

        val id = downloadManager.enqueue(request)
        prefs.edit().putLong(PREF_DOWNLOAD_ID, id).apply()
        return id
    }

    private fun isValidModel(file: File, marker: File): Boolean =
        file.isFile &&
            file.length() > 1_000_000_000L &&
            marker.readTextOrNull() == MODEL_SHA256

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
        private const val PREF_DOWNLOAD_ID = "download_id"

        const val MODEL_NAME = "Qwen3-1.7B Q4_K_M"
        const val MODEL_FILE = "Qwen3-1.7B-Q4_K_M.gguf"
        const val MODEL_SHA256 =
            "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"
        const val MODEL_URL =
            "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf?download=true"
    }
}
