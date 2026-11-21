package com.cdg.app.cbrowxr.updater

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.io.IOUtils
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object EngineUpdater {

    private val client = OkHttpClient()

    suspend fun initializeEngines(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            initializeYoutubeDL(context)
            FFmpeg.getInstance().init(context)
            ensureFolders(context)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun initializeYoutubeDL(context: Context) {
        try {
            preparePythonRuntime(context)
            YoutubeDL.getInstance().init(context)
        } catch (e: Exception) {
            val baseDir = File(context.noBackupFilesDir, "youtubedl-android")
            if (!baseDir.deleteRecursively() || baseDir.exists()) {
                throw IOException("Unable to clear the incomplete yt-dlp Python runtime", e)
            }
            context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
                .edit()
                .remove("pythonLibVersion")
                .commit()
            preparePythonRuntime(context)
            YoutubeDL.getInstance().init(context)
        }
    }

    private fun preparePythonRuntime(context: Context) {
        val baseDir = File(context.noBackupFilesDir, "youtubedl-android")
        val pythonDir = File(baseDir, "packages/python")
        val pythonArchive = File(context.applicationInfo.nativeLibraryDir, "libpython.zip.so")
        val marker = File(pythonDir, ".cbrowxr-ready")
        val version = pythonArchive.length().toString()

        if (marker.exists() && marker.readText().trim() == version && pythonDir.isDirectory) {
            return
        }

        if (!pythonDir.deleteRecursively() || pythonDir.exists()) {
            throw IOException("Unable to clear the stale yt-dlp Python runtime")
        }
        if (!pythonDir.mkdirs()) {
            throw IOException("Unable to create yt-dlp Python runtime directory")
        }

        extractPythonArchive(pythonArchive, pythonDir)
        marker.writeText(version)

        context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
            .edit()
            .putString("pythonLibVersion", version)
            .commit()
    }

    private fun extractPythonArchive(source: File, target: File) {
        val zipFile = ZipFile(source)
        val symlinks = mutableListOf<Pair<ZipArchiveEntry, String>>()

        try {
            val entries = zipFile.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val destination = File(target, entry.name)
                if (!destination.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    throw IOException("Python archive entry is outside its target directory")
                }

                when {
                    entry.isDirectory -> destination.mkdirs()
                    entry.isUnixSymlink -> {
                        zipFile.getInputStream(entry).use { input ->
                            symlinks += entry to IOUtils.toString(input, StandardCharsets.UTF_8).trim()
                        }
                    }
                    else -> {
                        destination.parentFile?.mkdirs()
                        zipFile.getInputStream(entry).use { input ->
                            destination.outputStream().use { output ->
                                IOUtils.copy(input, output)
                            }
                        }
                    }
                }
            }

            symlinks.forEach { (entry, linkTarget) ->
                val destination = File(target, entry.name)
                destination.parentFile?.mkdirs()
                if (destination.exists()) {
                    if (!destination.delete()) {
                        throw IOException("Unable to replace stale Python symlink: ${entry.name}")
                    }
                }
                android.system.Os.symlink(linkTarget, destination.absolutePath)
            }
        } finally {
            zipFile.close()
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
