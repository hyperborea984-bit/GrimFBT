package com.vrcosc.facetrack

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * MediaPipe .task model files are ~30MB binary blobs, too large to hand-write
 * as source. We fetch it once to app-private storage and reuse it after that.
 *
 * If you'd rather not depend on runtime download (e.g. offline / restricted
 * network), download the file yourself from the same URL and drop it into
 * app/src/main/assets/pose_landmarker_full.task — the app checks assets first.
 */
object ModelDownloader {

    private const val MODEL_FILENAME = "pose_landmarker_full.task"
    private const val MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task"

    /** Returns an absolute filesystem path to a usable model file. */
    suspend fun ensureModel(
        context: Context,
        onProgress: (percent: Int) -> Unit = {}
    ): String {
        // 1. Prefer a bundled asset if present (offline-friendly path).
        runCatching {
            context.assets.open(MODEL_FILENAME).use { return copyAssetToCache(context) }
        }

        // 2. Otherwise use/download to cache dir.
        val cacheFile = File(context.cacheDir, MODEL_FILENAME)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile.absolutePath
        }

        downloadTo(cacheFile, onProgress)
        return cacheFile.absolutePath
    }

    private fun copyAssetToCache(context: Context): String {
        val outFile = File(context.cacheDir, MODEL_FILENAME)
        if (!outFile.exists()) {
            context.assets.open(MODEL_FILENAME).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return outFile.absolutePath
    }

    private fun downloadTo(destination: File, onProgress: (Int) -> Unit) {
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connect()
        val totalBytes = connection.contentLength.coerceAtLeast(1)

        val tmpFile = File(destination.parentFile, destination.name + ".part")
        connection.inputStream.use { input ->
            tmpFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress((downloaded * 100L / totalBytes).toInt())
                }
            }
        }
        tmpFile.renameTo(destination)
    }
}
