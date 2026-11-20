package com.cdg.app.cbrowxr.updater

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

object EngineUpdater {

    private val client = OkHttpClient()

    suspend fun initializeEngines(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            ensureFolders(context)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun ensureFolders(context: Context) {
        File(context.filesDir, "extensions").mkdirs()
        File(context.filesDir, "configs").mkdirs()
    }

    suspend fun hotUpdateYtDlp(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(
                context,
                YoutubeDL.UpdateChannel._STABLE
            )
            Result.success("yt-dlp actualizado: ${status?.name ?: "OK"}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadVerifiedModule(
        context: Context,
        remoteUrl: String,
        subDir: String,
        fileName: String,
        expectedSha256: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(remoteUrl).build()
            val bytes = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                resp.body?.bytes() ?: throw IllegalStateException("Empty body")
            }

            if (!expectedSha256.isNullOrBlank()) {
                val actual = sha256(bytes)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    throw SecurityException("SHA-256 inválido para $fileName")
                }
            }

            val targetDir = File(context.filesDir, subDir).apply { mkdirs() }
            val targetFile = File(targetDir, fileName)
            targetFile.writeBytes(bytes)
            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
