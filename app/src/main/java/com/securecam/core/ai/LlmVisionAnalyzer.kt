package com.securecam.core.ai

import android.content.Context
import android.os.Build
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LlmVisionAnalyzer(private val context: Context) {
    private val TAG = "LlmVisionAnalyzer"
    private var engine: Engine? = null
    
    private val llmDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val llmScope = CoroutineScope(llmDispatcher + SupervisorJob())
    
    private val initialized = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    sealed class InitResult {
        object Success : InitResult()
        data class Error(val message: String) : InitResult()
        object ModelNotFound : InitResult()
    }

    fun close() {
        initialized.set(false)
        busy.set(false)
        try { engine?.close() } catch (e: Exception) {} finally { engine = null }
        System.gc() // CRITICAL: Force clear VRAM instantly to prevent OOM on reopen
    }

    @OptIn(ExperimentalApi::class)
    fun initialize(onResult: (InitResult) -> Unit) {
        llmScope.launch {
            initialized.set(false)
            busy.set(false)
            try { engine?.close() } catch(e: Exception){} finally { engine = null }

            val modelFile = LlmModelManager.getInstalledModel(context)
            if (modelFile == null) { 
                withContext(Dispatchers.Main) { onResult(InitResult.ModelNotFound) }
                return@launch 
            }

            var backendType = "CPU"
            try {
                val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                backendType = prefs.getString("ai_backend", "CPU") ?: "CPU"

                val backendConfig = when (backendType) {
                    "GPU" -> Backend.GPU()
                    "NPU" -> Backend.CPU() // LiteRT NPU delegates require specific hardware bindings, falling back to CPU safely if unsupported
                    else -> Backend.CPU()
                }

                val cfg = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backendConfig, 
                    visionBackend = backendConfig,
                    cacheDir = context.cacheDir.absolutePath
                )
                
                engine = Engine(cfg).also { it.initialize() }
                initialized.set(true)
                withContext(Dispatchers.Main) { onResult(InitResult.Success) }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { onResult(InitResult.Error("Init failed ($backendType Error): ${e.message}")) }
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    fun analyze(bitmap: Bitmap, systemPrompt: String, userPrompt: String, onToken: (String) -> Unit, onDone: (String) -> Unit, onError: (String) -> Unit) {
        if (!initialized.get()) { bitmap.recycle(); onError("Engine not initialized yet"); return }
        if (!busy.compareAndSet(false, true)) { bitmap.recycle(); onError("Engine is busy"); return }

        llmScope.launch {
            try {
                val eng = engine ?: throw IllegalStateException("Engine null")
                val conversation = eng.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.4), systemInstruction = Contents.of(systemPrompt)))

                // CRITICAL FIX: Raw 1080p (1920px) image fed directly into AI to support 1120 token budget
                val imageBytes = bitmap.toFastBytes()
                val contents = Contents.of(listOf(Content.ImageBytes(imageBytes), Content.Text(userPrompt)))

                val sb = StringBuilder()
                conversation.sendMessageAsync(contents).collect { message -> sb.append(message.toString()) }
                
                withContext(Dispatchers.Main) { onDone(sb.toString().trim()) }
                conversation.close()

            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Native Inference Error") }
            } finally {
                busy.set(false)
                if (!bitmap.isRecycled) bitmap.recycle() 
            }
        }
    }

    private fun Bitmap.toFastBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.compress(Bitmap.CompressFormat.WEBP_LOSSY, 70, out) // Hardware accelerated on modern Android
        } else {
            this.compress(Bitmap.CompressFormat.JPEG, 60, out) // Lighter quality saves massive CPU cycles
        }
        return out.toByteArray()
    }