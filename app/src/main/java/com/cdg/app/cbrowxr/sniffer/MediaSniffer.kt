package com.cdg.app.cbrowxr.sniffer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SniffedMedia(
    val url: String,
    val type: MediaType,
    val pageUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val detectedAt: Long = System.currentTimeMillis()
) {
    fun toCurlCommand(): String {
        val sb = StringBuilder("curl -L \"\$url\"")
        headers.forEach { (k, v) ->
            sb.append(" -H \"\$k: \$v\"")
        }
        return sb.toString()
    }
}

enum class MediaType(val label: String) {
    HLS_STREAM("HLS (.m3u8)"),
    DASH_STREAM("DASH (.mpd)"),
    DIRECT_VIDEO("Video Directo"),
    AUDIO("Audio"),
    SUBTITLE("Subtítulos")
}

object MediaSniffer {
    private val _detectedMedia = MutableStateFlow<List<SniffedMedia>>(emptyList())
    val detectedMedia: StateFlow<List<SniffedMedia>> = _detectedMedia.asStateFlow()

    private val trackingParams = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "fbclid", "gclid", "igshid", "mc_eid", "ref_src"
    )

    fun cleanUrl(rawUrl: String): String {
        return try {
            val uri = Uri.parse(rawUrl)
            if (uri.queryParameterNames.isEmpty()) return rawUrl
            val builder = uri.buildUpon().clearQuery()
            for (name in uri.queryParameterNames) {
                if (name.lowercase() !in trackingParams) {
                    uri.getQueryParameters(name).forEach { value ->
                        builder.appendQueryParameter(name, value)
                    }
                }
            }
            builder.build().toString()
        } catch (_: Exception) {
            rawUrl
        }
    }

    fun inspectUrl(
        rawUrl: String,
        pageUrl: String,
        headers: Map<String, String> = emptyMap()
    ) {
        val lower = rawUrl.lowercase()
        if (lower.endsWith(".ts") || lower.contains(".ts?")) return

        val type = when {
            lower.contains(".m3u8") -> MediaType.HLS_STREAM
            lower.contains(".mpd") -> MediaType.DASH_STREAM
            lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") -> MediaType.DIRECT_VIDEO
            lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".aac") || lower.contains(".opus") -> MediaType.AUDIO
            lower.contains(".vtt") || lower.contains(".srt") -> MediaType.SUBTITLE
            else -> return
        }

        val cleaned = cleanUrl(rawUrl)
        val current = _detectedMedia.value
        if (current.none { it.url == cleaned }) {
            _detectedMedia.value = current + SniffedMedia(
                url = cleaned,
                type = type,
                pageUrl = pageUrl,
                headers = headers
            )
        }
    }

    fun clearCurrentList() {
        _detectedMedia.value = emptyList()
    }

    fun exportToTxt(context: Context, asM3u: Boolean = false): String {
        val items = _detectedMedia.value
        if (items.isEmpty()) return "No hay enlaces capturados."

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = if (asM3u) "m3u" else "txt"
        val fileName = "cbrowxr_links_$stamp.$ext"

        val content = buildString {
            if (asM3u) appendLine("#EXTM3U")
            items.forEachIndexed { idx, item ->
                if (asM3u) {
                    appendLine("#EXTINF:-1,[${item.type.label}] Stream ${idx + 1}")
                }
                appendLine(item.url)
            }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/C-Browxr")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    }
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "C-Browxr"
                )
                if (!dir.exists()) dir.mkdirs()
                File(dir, fileName).writeText(content, Charsets.UTF_8)
            }
            "Guardado en Downloads/C-Browxr/$fileName (${items.size} enlaces)"
        } catch (e: Exception) {
            "Error exportando: ${e.message}"
        }
    }
}
