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
        aiScope.launch {
            settingsRepository.isLlmEnabled.collect { isLlmEnabledSetting = it }
        }
    }

    fun start() {
        Log.d(TAG, "Attempting to boot LLM Engine...")
        llmAnalyzer.initialize { result ->
            if (result is LlmVisionAnalyzer.InitResult.Success) {
                isLlmInitialized = true
                Log.d(TAG, "Model Loaded and Ready!")
            } else {
                isLlmInitialized = false
                Log.e(TAG, "Failed to load model: $result")
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping AI Pipeline...")
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
            } catch (e: Throwable) { 
                Log.e(TAG, "Frame processing error", e)
                bitmap.recycle()
            }
        }
    }

    private suspend fun triggerLlmAnalysis(bitmap: Bitmap) {
        isLlmBusy = true
        Log.d(TAG, "Passing frame to LLM...")
        
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        val sysPrompt = prefs.getString("prompt_sys", "You are a security camera AI assistant. Provide brief, factual security observations.") ?: ""
        val usrPrompt = prefs.getString("prompt_usr", "Describe what you see in this camera frame from a security perspective.") ?: ""

        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                llmAnalyzer.analyze(
                    bitmap = bitmap,
                    systemPrompt = sysPrompt,
                    userPrompt = usrPrompt,
                    onToken = { },
                    onDone = { text -> 
                        if (text.isNotBlank()) {
                            aiScope.launch {
                                eventRepository.emitEvent(SecurityEvent(
                                    type = "LLM_INSIGHT",
                                    description = text,
                                    confidence = 0.95f
                                ))
                            }
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    },
                    onError = { err -> 
                        Log.e(TAG, "LLM inference failed: $err")
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                )
            }
        } finally {
            // FIX: Guaranteed to unlock the busy state even if the coroutine crashes
            isLlmBusy = false
        }
    }
}