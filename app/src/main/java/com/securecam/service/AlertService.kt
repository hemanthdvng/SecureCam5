package com.securecam.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.gson.Gson
import com.securecam.data.repository.EventRepository
import com.securecam.data.repository.SecurityEvent
import com.securecam.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import javax.inject.Inject

@AndroidEntryPoint
class AlertService : LifecycleService() {
    @Inject lateinit var eventRepository: EventRepository
    private var localSocket: Socket? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // CRITICAL FIX: Graceful fallback for API levels
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(202, createForegroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(202, createForegroundNotification())
            }
        } catch (e: Exception) {
            startForeground(202, createForegroundNotification())
        }
        
        initListeners()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun initListeners() {
        val prefs = getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("viewer_mode", "Firebase")
        
        CoroutineScope(Dispatchers.IO).launch {
            eventRepository.securityEvents.collect { event ->
                val popupEnabled = getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE).getBoolean("enable_notifications", true)
                val isSafe = event.description.contains("[STATUS_SAFE]") || event.description.contains("CLEAR")
                
                if (popupEnabled && !isSafe && event.type != "REMOTE_ALERT") {
                    showHeadsUpNotification(event.description)
                }
            }
        }

        if (mode == "Local WiFi") {
            startLocalTcpListener()
        } else {
            initFirebaseAndListen()
        }
    }

    private fun startLocalTcpListener() {
        val prefs = getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        val ip = prefs.getString("target_ip", "") ?: ""
        val token = prefs.getString("security_token", "") ?: ""
        if (ip.isBlank()) return
        
        isRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            while(isRunning) {
                try {
                    localSocket = Socket(ip, 8081)
                    val out = PrintWriter(localSocket!!.getOutputStream(), true)
                    out.println(token)
                    
                    val reader = BufferedReader(InputStreamReader(localSocket!!.inputStream))
                    while(isRunning && !localSocket!!.isClosed) {
                        val line = reader.readLine() ?: break
                        val map = Gson().fromJson(line, Map::class.java)
                        
                        if (map["type"] == "ALERT") {
                            val text = map["text"] as? String ?: ""
                            CoroutineScope(Dispatchers.IO).launch {
                                eventRepository.emitEvent(SecurityEvent("REMOTE_ALERT", text, 1.0f))
                            }
                        }
                    }
                } catch(e: Exception) {
                    delay(5000)
                }
            }
        }
    }

    private fun initFirebaseAndListen() {
        try {
            val prefs = getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
            val dbUrl = prefs.getString("fb_db_url", "") ?: ""
            val apiKey = prefs.getString("fb_api_key", "") ?: ""
            val appId = prefs.getString("fb_app_id", "") ?: ""
            val token = prefs.getString("security_token", "") ?: ""

            if (dbUrl.isNotBlank() && apiKey.isNotBlank() && appId.isNotBlank() && token.isNotBlank()) {
                if (FirebaseApp.getApps(this).isEmpty()) {
                    val options = FirebaseOptions.Builder().setDatabaseUrl(dbUrl).setApiKey(apiKey).setApplicationId(appId).build()
                    FirebaseApp.initializeApp(this, options)
                }
                val db = FirebaseDatabase.getInstance()
                val query = db.getReference("securecam/alerts/$token").orderByChild("timestamp").limitToLast(1)

                query.addChildEventListener(object : ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                        val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                        val text = snapshot.child("text").getValue(String::class.java) ?: "Unknown Alert"
                        val currentPrefs = getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                        val lastProcessed = currentPrefs.getLong("last_processed_alert", 0L)

                        if (timestamp > lastProcessed) {
                            currentPrefs.edit().putLong("last_processed_alert", timestamp).apply()
                            CoroutineScope(Dispatchers.IO).launch {
                                eventRepository.emitEvent(SecurityEvent("REMOTE_ALERT", text, 1.0f))
                            }
                        }
                    }
                    override fun onChildChanged(s: DataSnapshot, p: String?) {}
                    override fun onChildRemoved(s: DataSnapshot) {}
                    override fun onChildMoved(s: DataSnapshot, p: String?) {}
                    override fun onCancelled(e: DatabaseError) {}
                })
            }
        } catch (e: Exception) { Log.e("AlertService", "Firebase Listener Failed", e) }
    }

    private fun showHeadsUpNotification(text: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, "securecam_alerts_v2")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 SecureCam Threat Detected")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX) 
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val serviceChannel = NotificationChannel("securecam_bg", "Background Service", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(serviceChannel)
            val alertChannel = NotificationChannel("securecam_alerts_v2", "High Priority Security Alerts", NotificationManager.IMPORTANCE_HIGH)
            alertChannel.description = "Popup alerts for AI threat detection"
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun createForegroundNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, "securecam_bg")
            .setContentTitle("SecureCam is Armed")
            .setContentText("Listening for offline security alerts...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { localSocket?.close() } catch(e: Exception) {}
    }
}