package com.securecam.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.securecam.data.repository.EventRepository
import com.securecam.data.repository.SecurityEvent
import com.securecam.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
        reinitialize()
    }

    fun reinitialize() {
        Log.d(TAG, "Attempting to initialize LLM Engine...")
        llmAnalyzer.initialize { result: LlmVisionAnalyzer.InitResult ->
            if (result is LlmVisionAnalyzer.InitResult.Success) {
                isLlmInitialized = true
                Log.d(TAG, "Gemma 4B Model Loaded and Ready!")
            } else {
                isLlmInitialized = false
                Log.e(TAG, "Gemma 4B Failed to load: $result")
            }
        }
    }

    fun processFrame(bitmap: Bitmap) {
        aiScope.launch {
            try {
                if (!isLlmEnabledSetting) {
                    Log.d(TAG, "Anomaly detected, but LLM is disabled.")
                    bitmap.recycle()
                } else if (!isLlmInitialized) {
                    Log.d(TAG, "Anomaly detected, but LLM NOT INITIALIZED.")
                    bitmap.recycle()
                } else if (isLlmBusy) {
                    Log.d(TAG, "Anomaly detected, but LLM is busy.")
                    bitmap.recycle()
                } else {
                    triggerLlmAnalysis(bitmap)
                }
            } catch (e: Throwable) { 
                Log.e(TAG, "Frame processing error", e)
                bitmap.recycle()
            }
        }
    }

    private suspend fun triggerLlmAnalysis(bitmap: Bitmap) {
        isLlmBusy = true
        Log.d(TAG, "Anomaly detected. Passing frame to Gemma...")
        
        suspendCancellableCoroutine<Unit> { continuation ->
            llmAnalyzer.analyze(
                bitmap = bitmap,
                triggerType = LlmVisionAnalyzer.TRIGGER_OBJECT,
                onToken = { token: String -> },
                onDone = { text: String -> 
                    if (text.isNotBlank()) {
                        eventRepository.emitEvent(SecurityEvent(
                            type = "LLM_INSIGHT",
                            description = text,
                            confidence = 0.95f
                        ))
                    }
                    isLlmBusy = false
                    if (continuation.isActive) continuation.resume(Unit)
                },
                onError = { err: String -> 
                    Log.e(TAG, "LLM inference failed: $err")
                    isLlmBusy = false
                    if (continuation.isActive) continuation.resume(Unit)
                }
            )
        }
    }
}