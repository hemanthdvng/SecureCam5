package com.securecam.core.webrtc

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class FirebaseSignalingClient(context: Context) {
    private val TAG = "SignalingClient"
    private var database: FirebaseDatabase? = null
    
    var onConnected: (() -> Unit)? = null
    var onJoinReceived: (() -> Unit)? = null
    var onOfferReceived: ((String) -> Unit)? = null
    var onAnswerReceived: ((String) -> Unit)? = null

    init {
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        val dbUrl = prefs.getString("fb_db_url", "") ?: ""
        val apiKey = prefs.getString("fb_api_key", "") ?: ""
        val appId = prefs.getString("fb_app_id", "") ?: ""

        if (dbUrl.isNotBlank() && apiKey.isNotBlank() && appId.isNotBlank()) {
            try {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    val options = FirebaseOptions.Builder()
                        .setDatabaseUrl(dbUrl)
                        .setApiKey(apiKey)
                        .setApplicationId(appId)
                        .build()
                    FirebaseApp.initializeApp(context, options)
                }
                database = FirebaseDatabase.getInstance()
                
                // Track physical connection to Firebase servers
                database?.getReference(".info/connected")?.addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val connected = snapshot.getValue(Boolean::class.java) ?: false
                        if (connected) onConnected?.invoke()
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })

                listenForSignals()
            } catch (e: Exception) {
                Log.e(TAG, "Firebase Manual Init Failed", e)
            }
        }
    }

    private fun listenForSignals() {
        database?.getReference("securecam/sdp")?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val type = snapshot.child("type").getValue(String::class.java)
                val sdp = snapshot.child("sdp").getValue(String::class.java)
                
                if (type == "JOIN") onJoinReceived?.invoke()
                if (type == "OFFER" && sdp != null) onOfferReceived?.invoke(sdp)
                if (type == "ANSWER" && sdp != null) onAnswerReceived?.invoke(sdp)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun sendSignal(type: String, sdp: String = "") {
        val payload = mapOf("type" to type, "sdp" to sdp)
        database?.getReference("securecam/sdp")?.setValue(payload)
    }
}