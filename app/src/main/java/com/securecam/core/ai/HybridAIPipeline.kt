package com.securecam.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.securecam.data.repository.EventRepository
import com.securecam.data.repository.SecurityEvent
import com.securecam.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridAIPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventRepository: EventRepository,
    private val settingsRepository: SettingsRepository
) {
    private val TAG = "HybridAIPipeline"
    private val aiDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val aiScope = CoroutineScope(aiDispatcher + SupervisorJob())
    private val llmAnalyzer = LlmVisionAnalyzer(context)
    
    private var isLlmBusy = false
    private var isLlmInitialized = false
    private var isLlmEnabledSetting = true

    init {
        aiScope.launch { settingsRepository.isLlmEnabled.collect { isLlmEnabledSetting = it } }
    }

    fun start() {
        llmAnalyzer.initialize { result -> isLlmInitialized = (result is LlmVisionAnalyzer.InitResult.Success) }
    }

    fun stop() {
        llmAnalyzer.close()
        isLlmInitialized = false
        isLlmBusy = false
    }

    fun processFrame(bitmap: Bitmap) {
        aiScope.launch {
            try {
                if (!isLlmEnabledSetting || !isLlmInitialized || isLlmBusy) {
                    bitmap.recycle()
                    return@launch
                }
                triggerLlmAnalysis(bitmap)
            } catch (e: Throwable) { bitmap.recycle() }
        }
    }

    private fun dispatchFirebaseAlert(description: String) {
        aiScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                val dbUrl = prefs.getString("fb_db_url", "") ?: ""
                
                if (dbUrl.isNotBlank()) {
                    val db = com.google.firebase.database.FirebaseDatabase.getInstance()
                    val payload = mapOf(
                        "timestamp" to System.currentTimeMillis(),
                        "text" to description
                    )
                    // Push to the new alerts node
                    db.getReference("securecam/alerts").push().setValue(payload)
                }
            } catch (e: Exception) { Log.e(TAG, "Firebase Alert Error", e) }
        }
    }

    private suspend fun triggerLlmAnalysis(bitmap: Bitmap) {
        isLlmBusy = true
        
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        val confThreshold = prefs.getFloat("confidence_threshold", 0.85f)
        val debugMode = prefs.getBoolean("debug_mode", true)
        val percentReq = (confThreshold * 100).toInt()
        
        val basePrompt = prefs.getString("prompt_sys", "You are a security camera AI assistant. Provide brief, factual security observations.") ?: ""
        val enforcedPrompt = "$basePrompt ONLY report if you are at least $percentReq% confident there is a distinct threat or anomaly. Otherwise reply 'CLEAR'."
        val usrPrompt = prefs.getString("prompt_usr", "Describe what you see in this camera frame from a security perspective.") ?: ""

        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                llmAnalyzer.analyze(
                    bitmap = bitmap,
                    systemPrompt = enforcedPrompt,
                    userPrompt = usrPrompt,
                    onToken = { },
                    onDone = { text -> 
                        val output = text.trim()
                        val isClear = output.contains("CLEAR", ignoreCase = true)
                        
                        if (!isClear || debugMode) {
                            aiScope.launch {
                                val finalDesc = if (isClear) "🔍 SCAN: CLEAR (No threats)" else "🚨 $output"
                                eventRepository.emitEvent(SecurityEvent("LLM_INSIGHT", finalDesc, confThreshold))
                                
                                if (!isClear) {
                                    dispatchFirebaseAlert(output)
                                }
                            }
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    },
                    onError = { if (continuation.isActive) continuation.resume(Unit) }
                )
            }
        } finally {
            isLlmBusy = false
        }
    }
}