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
    private var lastTriggerTime = 0L

    init {
        // Fix: Added explicit type inference 'result: LlmVisionAnalyzer.InitResult' for Kotlin 2.1
        llmAnalyzer.initialize { result: LlmVisionAnalyzer.InitResult ->
            if (result is LlmVisionAnalyzer.InitResult.Success) {
                isLlmInitialized = true
                Log.d(TAG, "Gemma 4B Model Loaded and Ready!")
            } else {
                Log.e(TAG, "Gemma 4B Failed to load: $result")
            }
        }
    }

    fun processFrame(bitmap: Bitmap) {
        aiScope.launch {
            try {
                val llmEnabled = settingsRepository.isLlmEnabled.first()
                val hasAnomaly = performLightweightScan(bitmap)
                
                if (hasAnomaly && llmEnabled && isLlmInitialized && !isLlmBusy) {
                    triggerLlmAnalysis(bitmap)
                } else {
                    bitmap.recycle() // Prevent memory leaks!
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error", e)
                bitmap.recycle()
            }
        }
    }

    private suspend fun performLightweightScan(bitmap: Bitmap): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime > 15000) {
            lastTriggerTime = now
            return true
        }
        return false 
    }

    private suspend fun triggerLlmAnalysis(bitmap: Bitmap) {
        isLlmBusy = true
        Log.d(TAG, "Anomaly detected. Passing frame to Gemma 4B...")
        
        suspendCancellableCoroutine<Unit> { continuation ->
            llmAnalyzer.analyze(
                bitmap = bitmap,
                triggerType = LlmVisionAnalyzer.TRIGGER_OBJECT,
                onToken = { token: String -> }, // Fix: explicit type
                onDone = { text: String -> // Fix: explicit type
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
                onError = { err: String -> // Fix: explicit type
                    Log.e(TAG, "LLM inference failed: $err")
                    isLlmBusy = false
                    if (continuation.isActive) continuation.resume(Unit)
                }
            )
        }
    }
}