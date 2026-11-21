package com.cdg.app.cbrowxr

import android.app.Application
import android.util.Log
import com.cdg.app.cbrowxr.browser.BrowserEngine
import com.cdg.app.cbrowxr.browser.BrowserPreferences
import com.cdg.app.cbrowxr.caster.LocalStreamProxy
import com.cdg.app.cbrowxr.updater.EngineUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CBrowxrApp : Application() {
    private val diagnosticsLock = Any()
    private val diagnosticLogName = "cbrowxr_diagnostics.log"
    private val maxDiagnosticLogBytes = 512 * 1024L

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var streamProxy: LocalStreamProxy? = null

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logError("FATAL_CRASH", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        try {
            val browserSettings = BrowserPreferences.load(this)
            BrowserEngine.initRuntime(
                this,
                browserSettings.adBlockEnabled,
                browserSettings.javaScriptEnabled,
                browserSettings.cookiePolicy
            )
        } catch (e: Throwable) { logError("GECKO_INIT_ERROR", e) }
        appScope.launch {
            try { streamProxy = LocalStreamProxy(0).apply { start() } } catch (e: Throwable) { logError("PROXY_ERROR", e) }
            try {
                val result = EngineUpdater.initializeEngines(this@CBrowxrApp)
                result.exceptionOrNull()?.let { logError("ENGINE_ERROR", it) }
            } catch (e: Throwable) { logError("ENGINE_ERROR", e) }
        }
    }

    fun logError(tag: String, throwable: Throwable) {
        try {
            val stackTrace = redactSensitiveData(Log.getStackTraceString(throwable))
            Log.e("C-Browxr", "[$tag]\n$stackTrace")
            synchronized(diagnosticsLock) {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val entry = "\n[$time] $tag\n$stackTrace\n"
                val file = File(filesDir, diagnosticLogName)
                if (file.length() + entry.toByteArray(Charsets.UTF_8).size > maxDiagnosticLogBytes) {
                    val retainedEntries = file.readLines().takeLast(300).joinToString("\n")
                    file.writeText(retainedEntries, Charsets.UTF_8)
                }
                file.appendText(entry, Charsets.UTF_8)
            }
        } catch (e: IOException) {
            Log.e("C-Browxr", "No se pudo guardar el diagnóstico", e)
        }
    }

    fun readDiagnostics(): Result<String> = try {
        synchronized(diagnosticsLock) {
            val file = File(filesDir, diagnosticLogName)
            Result.success(if (file.exists()) file.readText(Charsets.UTF_8) else "")
        }
    } catch (e: IOException) {
        Log.e("C-Browxr", "No se pudo leer el diagnóstico", e)
        Result.failure(e)
    }

    fun clearDiagnostics(): Result<Unit> = try {
        synchronized(diagnosticsLock) {
            val file = File(filesDir, diagnosticLogName)
            if (file.exists() && !file.delete()) {
                throw IOException("No se pudo borrar el archivo de diagnóstico")
            }
            Result.success(Unit)
        }
    } catch (e: IOException) {
        Log.e("C-Browxr", "No se pudo borrar el diagnóstico", e)
        Result.failure(e)
    }

    private fun redactSensitiveData(text: String): String {
        val withoutUrls = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
            .replace(text, "[URL ocultada]")
        return Regex(
            """(?im)^\s*(authorization|cookie|set-cookie)\s*:.*$|(?i)\b(access_token|refresh_token|token|password|secret|signature|sig)=([^&\s]+)"""
        ).replace(withoutUrls) { match ->
            val key = match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()
            "$key=[oculto]"
        }
    }
}
