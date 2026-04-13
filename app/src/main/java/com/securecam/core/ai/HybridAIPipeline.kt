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
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun start() {
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
    }

    fun processFrame(bitmap: Bitmap) {
        aiScope.launch {
            try {
                var skipLlm = false
                
                // --- PHASE 1: BIOMETRIC ENGINE ---
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
                    } catch (e: Exception) {
                        eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] Biometric Engine Error: ${e.message}", 1.0f))
                    }
                }

                if (skipLlm) {
                    bitmap.recycle()
                    return@launch
                }
                
                // --- PHASE 2: LLM ENGINE ---
                if (!isLlmEnabledSetting) {
                    bitmap.recycle()
                    return@launch
                }
                
                if (!isLlmInitialized) {
                    eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] Waiting for LLM to boot...", 1.0f))
                    bitmap.recycle()
                    return@launch
                }
                
                if (isLlmBusy) {
                    bitmap.recycle()
                    return@launch
                }
                
                triggerLlmAnalysis(bitmap)
                
            } catch (e: Throwable) { 
                eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] Pipeline Critical Crash: ${e.message}", 1.0f))
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
        
        val basePrompt = prefs.getString("prompt_sys", "You are a security camera AI assistant. Provide brief, factual security observations.") ?: ""
        val knownPersons = prefs.getString("known_persons", "") ?: ""
        
        val personaRule = if (knownPersons.isNotBlank()) {
            "AUTHORIZED PERSONNEL: $knownPersons. If you only see these authorized individuals, reply EXACTLY '[STATUS_SAFE]'. "
        } else ""

        val enforcedPrompt = if (percentReq > 0) {
            "$basePrompt $personaRule ONLY report if you are at least $percentReq% confident there is an UNKNOWN threat or unauthorized person. If there is no threat, or the image is dark, reply EXACTLY '[STATUS_SAFE]'."
        } else {
            "$basePrompt $personaRule Describe EVERYTHING you see in the image. DO NOT output '[STATUS_SAFE]'."
        }
        
        val usrPrompt = prefs.getString("prompt_usr", "Describe what you see in this camera frame from a security perspective.") ?: ""

        try {
            // CRITICAL FIX: 15-second safety timeout prevents hardware from hanging the loop forever
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
                                    val finalDesc = if (isSafe) "🔍 SCAN: Safe / No Threat" else "🚨 $output"
                                    eventRepository.emitEvent(SecurityEvent("LLM_INSIGHT", finalDesc, confThreshold))
                                    if (!isSafe) dispatchFirebaseAlert(output)
                                }
                            }
                            if (continuation.isActive) continuation.resume(true)
                        },
                        onError = { err -> 
                            // CRITICAL FIX: Prevent silent failures by logging the exact error to the UI
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