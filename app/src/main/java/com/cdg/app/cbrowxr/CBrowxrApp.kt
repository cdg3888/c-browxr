package com.cdg.app.cbrowxr

import android.app.Application
import android.util.Log
import com.cdg.app.cbrowxr.browser.BrowserEngine
import com.cdg.app.cbrowxr.caster.LocalStreamProxy
import com.cdg.app.cbrowxr.updater.EngineUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CBrowxrApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var streamProxy: LocalStreamProxy? = null

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logError("FATAL_CRASH", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        try { BrowserEngine.initRuntime(this) } catch (e: Throwable) { logError("GECKO_INIT_ERROR", e) }
        appScope.launch {
            try { streamProxy = LocalStreamProxy(8899).apply { start() } } catch (e: Throwable) { logError("PROXY_ERROR", e) }
            try {
                val result = EngineUpdater.initializeEngines(this@CBrowxrApp)
                result.exceptionOrNull()?.let { logError("ENGINE_ERROR", it) }
            } catch (e: Throwable) { logError("ENGINE_ERROR", e) }
        }
    }

    fun logError(tag: String, throwable: Throwable) {
        try {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            File(filesDir, "cbrowxr_diagnostics.log").appendText("\n[$time] $tag\n${Log.getStackTraceString(throwable)}\n")
        } catch (_: Exception) {}
    }
}
