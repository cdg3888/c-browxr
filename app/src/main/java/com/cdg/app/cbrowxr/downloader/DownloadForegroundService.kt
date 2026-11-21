package com.cdg.app.cbrowxr.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cdg.app.cbrowxr.CBrowxrApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DownloadForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = "cbrowxr_downloads"

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Descargas C-Browxr",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url")
        if (url.isNullOrBlank()) {
            (application as CBrowxrApp).logError(
                "DOWNLOAD_START_ERROR",
                IllegalArgumentException("La descarga se inició sin una URL")
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val mode = intent?.getStringExtra("mode") ?: "ytdlp"
        val app = application as CBrowxrApp

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("C-Browxr Descargando")
            .setContentText(url)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        startForeground(101, notification)

        scope.launch {
            try {
                val result = if (mode == "idm") {
                    val outDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "C-Browxr"
                    )
                    val fileName = "cbrowxr_${System.currentTimeMillis()}.mp4"
                    val threads = intent?.getIntExtra("threads", 8)?.coerceIn(2, 16) ?: 8
                    MultiThreadDownloader(threads).download(url, File(outDir, fileName))
                } else {
                    YtDlpManager.downloadMedia(url, audioOnly = (mode == "audio"))
                }
                result.exceptionOrNull()?.let {
                    app.logError("DOWNLOAD_${mode.uppercase()}", it)
                }
            } catch (e: Exception) {
                app.logError("DOWNLOAD_${mode.uppercase()}", e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
