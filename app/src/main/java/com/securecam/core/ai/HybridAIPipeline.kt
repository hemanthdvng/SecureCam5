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
    private var isFaceRecogEnabledSetting = true
    
    private var firstFrameReceived = false

    init { 
        aiScope.launch { 
            try {
                settingsRepository.isLlmEnabled.collect { isLlmEnabledSetting = it } 
            } catch (e: Exception) {}
        } 
        
        aiScope.launch { 
            try {
                settingsRepository.isFaceRecogEnabled.collect { enabled ->
                    isFaceRecogEnabledSetting = enabled
                    try {
                        if (enabled) biometricEngine.initialize() else biometricEngine.close()
                    } catch (e: Exception) {
                        eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] Biometric Init Error: ${e.message}", 1.0f))
                        // CRITICAL ANTI-SPAM: Automatically disable the face engine if it fails to initialize so it doesn't spam the UI
                        isFaceRecogEnabledSetting = false
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun start() {
        firstFrameReceived = false
        llmAnalyzer.initialize { result -> 
            isLlmInitialized = (result is LlmVisionAnalyzer.InitResult.Success) 
            aiScope.launch {
                if (isLlmInitialized) {
                    eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] LLM Engine Initialized & Online.", 1.0f))
                } else {
                    eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] LLM Failed to load. Check your .litertlm file.", 1.0f))
                }
            }
        }
    }

    fun stop() {
        llmAnalyzer.close()
        try { biometricEngine.close() } catch (e: Exception) {}
        isLlmInitialized = false
        isLlmBusy = false
        firstFrameReceived = false
    }

    fun processFrame(bitmap: Bitmap) {
        aiScope.launch {
            try {
                if (!firstFrameReceived) {
                    firstFrameReceived = true
                    eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] Camera heartbeat established. AI is receiving live frames.", 1.0f))
                }

                var skipLlm = false
                
                if (isFaceRecogEnabledSetting) {
                    try {
                        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                        val savedFacesJson = prefs.getString("authorized_faces", "[]") ?: "[]"
                        val type = object : TypeToken<List<RegisteredFace>>() {}.type
                        val savedFaces: List<RegisteredFace> = Gson().fromJson(savedFacesJson, type) ?: emptyList()
                        
                        if (savedFaces.isNotEmpty()) {
                            val currentFaceVector = biometricEngine.getFaceEmbedding(bitmap)
                            if (currentFaceVector != null) {
                                for (face in savedFaces) {
                                    val similarity = biometricEngine.calculateCosineSimilarity(currentFaceVector, face.vector)
                                    if (similarity > 0.65f) {
                                        eventRepository.emitEvent(SecurityEvent("BIOMETRIC", "🛡️ Authorized Face Detected: ${face.name}", 1.0f))
                                        skipLlm = true
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {} // No longer emitting to UI, preventing spam
                }

                if (skipLlm) {
                    bitmap.recycle()
                    return@launch
                }
                
                if (!isLlmEnabledSetting || !isLlmInitialized || isLlmBusy) {
                    bitmap.recycle()
                    return@launch
                }
                
                triggerLlmAnalysis(bitmap)
                
            } catch (e: Throwable) { 
                bitmap.recycle() 
            }
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
        
        val basePrompt = prefs.getString("prompt_sys", "You are a visual analysis AI. Follow the user's trigger instructions exactly.") ?: ""
        val knownPersons = prefs.getString("known_persons", "") ?: ""
        
        val personaRule = if (knownPersons.isNotBlank()) {
            "AUTHORIZED PERSONNEL: $knownPersons. If you only see these authorized individuals, reply EXACTLY '[STATUS_SAFE]'. "
        } else ""

        // CRITICAL FIX: Prompt respects User custom triggers (e.g., "Trigger if you see a TV") instead of forcing "unknown threat".
        val enforcedPrompt = if (percentReq > 0) {
            "$basePrompt $personaRule Analyze the image based on the user's prompt. You must be at least $percentReq% confident to trigger an alert. If the user's conditions are NOT met, or there is nothing of interest, reply EXACTLY '[STATUS_SAFE]'."
        } else {
            "$basePrompt $personaRule Answer the user's prompt in detail. DO NOT output '[STATUS_SAFE]'."
        }
        
        val usrPrompt = prefs.getString("prompt_usr", "Report if you see any people or a TV turned on.") ?: ""

        try {
            val result = withTimeoutOrNull(15000L) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    llmAnalyzer.analyze(
                        bitmap = bitmap,
                        systemPrompt = enforcedPrompt,
                        userPrompt = usrPrompt,
                        onToken = { },
                        onDone = { text -> 
                            val output = text.trim()
                            val isSafe = if (percentReq == 0) false else (output.contains("[STATUS_SAFE]", ignoreCase = true) || output.contains("provide an image", ignoreCase = true))
                            
                            if (!isSafe || debugMode) {
                                aiScope.launch {
                                    val finalDesc = if (isSafe) "🔍 SCAN: Safe / No Trigger found" else "🚨 $output"
                                    eventRepository.emitEvent(SecurityEvent("LLM_INSIGHT", finalDesc, confThreshold))
                                    if (!isSafe) dispatchFirebaseAlert(output)
                                }
                            }
                            if (continuation.isActive) continuation.resume(true)
                        },
                        onError = { err -> 
                            aiScope.launch { eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] LLM Inference Error: $err", 1.0f)) }
                            if (continuation.isActive) continuation.resume(false) 
                        }
                    )
                }
            }
            
            if (result == null) {
                eventRepository.emitEvent(SecurityEvent("SYSTEM", "🚨 [SYSTEM] LLM Hardware Timed Out! Resetting engine lock.", 1.0f))
            }

        } finally {
            isLlmBusy = false
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}