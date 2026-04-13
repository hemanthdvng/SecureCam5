package com.securecam.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    private val aiDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val aiScope = CoroutineScope(aiDispatcher + SupervisorJob())
    
    private val llmAnalyzer = LlmVisionAnalyzer(context)
    private val biometricEngine = BiometricEngine(context)
    
    private var isLlmBusy = false
    private var isLlmInitialized = false
    private var isLlmEnabledSetting = true

    init { 
        aiScope.launch { settingsRepository.isLlmEnabled.collect { isLlmEnabledSetting = it } } 
    }

    fun start() {
        llmAnalyzer.initialize { result -> isLlmInitialized = (result is LlmVisionAnalyzer.InitResult.Success) }
        biometricEngine.initialize()
    }

    fun stop() {
        llmAnalyzer.close()
        biometricEngine.close()
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
                
                // BIOMETRIC INTERCEPT: Check for authorized faces before hitting the LLM
                val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                val savedEmbeddingStr = prefs.getString("authorized_face_vector", "") ?: ""
                var isFaceAuthorized = false
                
                if (savedEmbeddingStr.isNotBlank()) {
                    val currentFaceVector = biometricEngine.getFaceEmbedding(bitmap)
                    if (currentFaceVector != null) {
                        val type = object : TypeToken<FloatArray>() {}.type
                        val savedFaceVector: FloatArray = Gson().fromJson(savedEmbeddingStr, type)
                        
                        val similarity = biometricEngine.calculateCosineSimilarity(currentFaceVector, savedFaceVector)
                        Log.d("HybridAIPipeline", "Biometric Similarity Score: $similarity")
                        
                        // 0.65 threshold is standard for MobileFaceNet Cosine Similarity
                        if (similarity > 0.65f) {
                            isFaceAuthorized = true
                        }
                    }
                }

                if (isFaceAuthorized) {
                    eventRepository.emitEvent(SecurityEvent("BIOMETRIC", "🛡️ Authorized Face Detected (Local Match). Disabling Alerts.", 1.0f))
                    bitmap.recycle()
                    return@launch
                }
                
                // If no authorized face is found, pass to LLM for threat detection
                triggerLlmAnalysis(bitmap)
            } catch (e: Throwable) { bitmap.recycle() }
        }
    }

    private fun dispatchFirebaseAlert(description: String) {
        aiScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                val dbUrl = prefs.getString("fb_db_url", "") ?: ""
                val token = prefs.getString("security_token", "") ?: ""
                if (dbUrl.isNotBlank() && token.isNotBlank()) {
                    val db = com.google.firebase.database.FirebaseDatabase.getInstance()
                    val payload = mapOf("timestamp" to System.currentTimeMillis(), "text" to description)
                    db.getReference("securecam/alerts/$token").push().setValue(payload)
                }
            } catch (e: Exception) {}
        }
    }

    private suspend fun triggerLlmAnalysis(bitmap: Bitmap) {
        isLlmBusy = true
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        val confThreshold = prefs.getFloat("confidence_threshold", 0.85f)
        val debugMode = prefs.getBoolean("debug_mode", true)
        val percentReq = (confThreshold * 100).toInt()
        
        val basePrompt = prefs.getString("prompt_sys", "You are a security camera AI assistant. Provide brief, factual security observations.") ?: ""
        
        val enforcedPrompt = if (percentReq > 0) {
            "$basePrompt ONLY report if you are at least $percentReq% confident there is an UNKNOWN threat or unauthorized person. If there is no threat, or the image is dark, reply EXACTLY '[STATUS_SAFE]'."
        } else {
            "$basePrompt If there is no threat, reply EXACTLY '[STATUS_SAFE]'."
        }
        
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
                        val isSafe = output.contains("[STATUS_SAFE]", ignoreCase = true) || output.contains("provide an image", ignoreCase = true)
                        
                        if (!isSafe || debugMode) {
                            aiScope.launch {
                                val finalDesc = if (isSafe) "🔍 SCAN: Safe / No Threat" else "🚨 $output"
                                eventRepository.emitEvent(SecurityEvent("LLM_INSIGHT", finalDesc, confThreshold))
                                if (!isSafe) dispatchFirebaseAlert(output)
                            }
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    },
                    onError = { if (continuation.isActive) continuation.resume(Unit) }
                )
            }
        } finally {
            isLlmBusy = false
            bitmap.recycle()
        }
    }
}