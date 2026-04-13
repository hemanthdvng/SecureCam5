package com.securecam.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.securecam.data.repository.EventRepository
import com.securecam.data.repository.SecurityEvent
import com.securecam.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AlertService : LifecycleService() {
    @Inject lateinit var eventRepository: EventRepository

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(202, createForegroundNotification())
        initFirebaseAndListen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun initFirebaseAndListen() {
        try {
            val prefs = getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
            val dbUrl = prefs.getString("fb_db_url", "") ?: ""
            val apiKey = prefs.getString("fb_api_key", "") ?: ""
            val appId = prefs.getString("fb_app_id", "") ?: ""

            if (dbUrl.isNotBlank() && apiKey.isNotBlank() && appId.isNotBlank()) {
                if (FirebaseApp.getApps(this).isEmpty()) {
                    val options = FirebaseOptions.Builder()
                        .setDatabaseUrl(dbUrl)
                        .setApiKey(apiKey)
                        .setApplicationId(appId)
                        .build()
                    FirebaseApp.initializeApp(this, options)
                }

                val db = FirebaseDatabase.getInstance()
                
                // CRITICAL FIX: limitToLast(1) stops history crash. 
                val query = db.getReference("securecam/alerts").orderByChild("timestamp").limitToLast(1)

                query.addChildEventListener(object : ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                        val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                        val text = snapshot.child("text").getValue(String::class.java) ?: "Unknown Alert"
                        
                        val currentPrefs = getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                        val lastProcessed = currentPrefs.getLong("last_processed_alert", 0L)
                        val popupEnabled = currentPrefs.getBoolean("enable_notifications", true)

                        // CRITICAL FIX: Only trigger if the timestamp strictly moves forward
                        if (timestamp > lastProcessed) {
                            currentPrefs.edit().putLong("last_processed_alert", timestamp).apply()

                            CoroutineScope(Dispatchers.IO).launch {
                                eventRepository.emitEvent(SecurityEvent("REMOTE_ALERT", text, 1.0f))
                            }
                            
                            if (popupEnabled) {
                                showHeadsUpNotification(text)
                            }
                        }
                    }
                    override fun onChildChanged(s: DataSnapshot, p: String?) {}
                    override fun onChildRemoved(s: DataSnapshot) {}
                    override fun onChildMoved(s: DataSnapshot, p: String?) {}
                    override fun onCancelled(e: DatabaseError) {}
                })
            }
        } catch (e: Exception) {
            Log.e("AlertService", "Failed to bind Firebase Listener", e)
        }
    }

    private fun showHeadsUpNotification(text: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, "securecam_alerts_v2")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 SecureCam Threat Detected")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX) // Forced to Max
            .setCategory(NotificationCompat.CATEGORY_ALARM) // Treated as an Alarm by Android
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

            // Generate a fresh channel ID so Android doesn't cache old, broken priority states
            val alertChannel = NotificationChannel("securecam_alerts_v2", "High Priority Security Alerts", NotificationManager.IMPORTANCE_HIGH)
            alertChannel.description = "Popup alerts for AI threat detection"
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun createForegroundNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, "securecam_bg")
            .setContentTitle("SecureCam is Armed")
            .setContentText("Listening for offline security alerts...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }
}