package com.securecam.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.securecam.data.repository.EventRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

@AndroidEntryPoint
class AlertService : Service() {
    @Inject lateinit var eventRepository: EventRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var viewerSocket: Socket? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification("SecureCam Background Service Active"))
        
        val prefs = getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        val appRole = prefs.getString("app_role", "Camera") ?: "Camera"

        if (appRole == "Camera") {
            serviceScope.launch {
                eventRepository.securityEvents.collect { event ->
                    val isSafe = event.description.contains("Safe", ignoreCase = true) || event.description.contains("CLEAR", ignoreCase = true)
                    if (!isSafe && !event.description.contains("[SYSTEM]")) {
                        showPopupNotification(event.description)
                    }
                }
            }
        } else if (appRole == "Viewer") {
            // CRITICAL FIX: Persistent Background TCP Listener for Viewer Notifications
            serviceScope.launch {
                while (isActive) {
                    try {
                        val ip = prefs.getString("target_ip", "") ?: ""
                        if (ip.isNotBlank()) {
                            viewerSocket = Socket(ip, 8081)
                            val reader = BufferedReader(InputStreamReader(viewerSocket!!.getInputStream()))
                            while (isActive) {
                                val line = reader.readLine() ?: break
                                val map = Gson().fromJson(line, Map::class.java)
                                if (map["type"] == "ALERT") {
                                    val text = map["text"] as? String ?: ""
                                    val isSafe = text.contains("Safe", ignoreCase = true) || text.contains("CLEAR", ignoreCase = true)
                                    if (!isSafe && !text.contains("[SYSTEM]")) {
                                        showPopupNotification(text)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {}
                    delay(5000) // If disconnected, retry every 5 seconds
                }
            }
        }
    }

    private fun showPopupNotification(text: String) {
        val prefs = getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enable_notifications", true)) return
        
        val notif = NotificationCompat.Builder(this, "securecam_alerts")
            .setContentTitle("🚨 Security Alert")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel("securecam_service", "SecureCam Background", NotificationManager.IMPORTANCE_LOW)
            val alertChannel = NotificationChannel("securecam_alerts", "SecureCam Alerts", NotificationManager.IMPORTANCE_HIGH)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "securecam_service")
            .setContentTitle("SecureCam")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try { viewerSocket?.close() } catch (e: Exception) {}
    }
}