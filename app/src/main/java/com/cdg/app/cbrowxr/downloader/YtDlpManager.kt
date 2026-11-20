package com.cdg.app.cbrowxr.downloader

import android.os.Environment
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object YtDlpManager {

    suspend fun downloadMedia(
        url: String,
        headers: Map<String, String> = emptyMap(),
        audioOnly: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val outDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "C-Browxr"
            )
            if (!outDir.exists()) outDir.mkdirs()

            val request = YoutubeDLRequest(url).apply {
                addOption("-o", "${outDir.absolutePath}/%(title).80s.%(ext)s")
                addOption("--no-mtime")
                if (audioOnly) {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                } else {
                    addOption("-f", "bestvideo+bestaudio/best")
                    addOption("--merge-output-format", "mp4")
                }
                headers.forEach { (k, v) ->
                    addOption("--add-header", "$k:$v")
                }
            }

            YoutubeDL.getInstance().execute(request) { progress, _, line ->
                onProgress(progress, line)
            }

            Result.success("Descarga completada en Downloads/C-Browxr")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
