package com.cdg.app.cbrowxr.caster

import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class LocalStreamProxy(port: Int = 8899) : NanoHTTPD(port) {

    private val client = OkHttpClient()
    private val activeHeaders = mutableMapOf<String, Map<String, String>>()

    fun registerStream(targetUrl: String, headers: Map<String, String>): String {
        activeHeaders[targetUrl] = headers
        val encoded = URLEncoder.encode(targetUrl, "UTF-8")
        return "http://127.0.0.1:$listeningPort/proxy?url=$encoded"
    }

    override fun serve(session: IHTTPSession): Response {
        val targetUrl = session.parms["url"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")

        return try {
            val reqBuilder = Request.Builder().url(targetUrl)
            activeHeaders[targetUrl]?.forEach { (k, v) ->
                reqBuilder.addHeader(k, v)
            }
            session.headers["range"]?.let { rangeHeader ->
                reqBuilder.addHeader("Range", rangeHeader)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            val body = response.body
                ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Empty upstream")

            val mime = response.header("Content-Type") ?: "application/octet-stream"
            val status = if (response.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK

            newChunkedResponse(status, mime, body.byteStream()).apply {
                response.header("Accept-Ranges")?.let { addHeader("Accept-Ranges", it) }
                response.header("Content-Range")?.let { addHeader("Content-Range", it) }
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Proxy error: ${e.message}")
        }
    }
}
