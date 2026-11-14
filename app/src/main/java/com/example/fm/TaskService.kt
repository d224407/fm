package com.example.fm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Foreground service that only keeps the process alive (and un-throttled) while a long task runs. */
class TaskService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("task", "Tác vụ", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "task")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Đang xử lý tệp…")
            .setOngoing(true)
            .build()
        // startForeground must always run first, even when the intent only asks to stop
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(1, n)
        }.onFailure { stopSelf() }
        if (intent?.action == "stop") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
