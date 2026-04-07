package com.securecam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.securecam.core.ai.HybridAIPipeline
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WatchTowerService : LifecycleService() {

    @Inject lateinit var aiPipeline: HybridAIPipeline

    companion object {
        const val CHANNEL_ID = "watchtower_service_channel"
        const val NOTIFICATION_ID = 101
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        // TODO: Initialize Headless CameraX here
        // We will bind an ImageAnalysis use-case to this service's lifecycle
        // and route frames to aiPipeline.processFrame(bitmap)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // START_STICKY ensures OS restarts the service if it's killed for memory
        return START_STICKY 
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "WatchTower Security AI",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SecureCam WatchTower Active")
            .setContentText("Local AI is monitoring in the background.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}