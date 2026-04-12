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
import java.net.HttpURLConnection
import java.net.URL
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

    private fun dispatchWebhooks(description: String) {
        aiScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
            val tgToken = prefs.getString("tg_bot_token", "") ?: ""
            val tgChatId = prefs.getString("tg_chat_id", "") ?: ""
            val waUrl = prefs.getString("wa_webhook_url", "") ?: ""

            // Sanitize description for JSON payload
            val safeDesc = description.replace("\"", "\\\"").replace("\n", "\\n")

            if (tgToken.isNotBlank() && tgChatId.isNotBlank()) {
                try {
                    val url = URL("https://api.telegram.org/bot$tgToken/sendMessage")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    val payload = "{\"chat_id\": \"$tgChatId\", \"text\": \"🚨 *SecureCam Alert* 🚨\\n\\n$safeDesc\"}"
                    conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                    conn.disconnect()
                } catch (e: Exception) { Log.e(TAG, "Telegram Webhook Error", e) }
            }

            if (waUrl.isNotBlank()) {
                try {
                    val url = URL(waUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    val payload = "{\"text\": \"🚨 SecureCam Alert:\\n$safeDesc\"}"
                    conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                    conn.disconnect()
                } catch (e: Exception) { Log.e(TAG, "WhatsApp Webhook Error", e) }
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
                                dispatchWebhooks(text)
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
            isLlmBusy = false
        }
    }
}