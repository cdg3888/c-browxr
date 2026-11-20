package com.cdg.app.cbrowxr.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class MultiThreadDownloader(
    private val threads: Int = 8
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun download(
        url: String,
        outputFile: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val headReq = Request.Builder().url(url).head().apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }.build()

            val (contentLength, acceptRanges) = client.newCall(headReq).execute().use { resp ->
                val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                val ranges = resp.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
                len to ranges
            }

            outputFile.parentFile?.mkdirs()

            if (contentLength <= 0L || !acceptRanges || threads <= 1) {
                downloadSingleThread(url, outputFile, headers, contentLength, onProgress)
                return@withContext Result.success(outputFile)
            }

            RandomAccessFile(outputFile, "rw").use { raf ->
                raf.setLength(contentLength)
            }

            val chunkSize = contentLength / threads
            var totalDownloaded = 0L
            val lock = Any()

            val jobs = (0 until threads).map { index ->
                val start = index * chunkSize
                val end = if (index == threads - 1) contentLength - 1 else (start + chunkSize - 1)

                async {
                    val req = Request.Builder()
                        .url(url)
                        .addHeader("Range", "bytes=$start-$end")
                        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                        .build()

                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IllegalStateException("Empty body")
                        val buffer = ByteArray(8192)
                        var offset = start

                        RandomAccessFile(outputFile, "rw").use { raf ->
                            raf.seek(offset)
                            body.byteStream().use { input ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    raf.write(buffer, 0, read)
                                    offset += read
                                    synchronized(lock) {
                                        totalDownloaded += read
                                        val pct = ((totalDownloaded * 100) / contentLength).toInt()
                                        onProgress(pct.coerceIn(0, 100))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            jobs.awaitAll()
            onProgress(100)
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadSingleThread(
        url: String,
        outputFile: File,
        headers: Map<String, String>,
        contentLength: Long,
        onProgress: (Int) -> Unit
    ) {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body ?: throw IllegalStateException("Empty body")
            var downloaded = 0L
            val buffer = ByteArray(8192)
            outputFile.outputStream().use { out ->
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        if (contentLength > 0) {
                            onProgress(((downloaded * 100) / contentLength).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        }
    }
}
