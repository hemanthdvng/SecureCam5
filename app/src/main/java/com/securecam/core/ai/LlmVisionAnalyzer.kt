package com.securecam.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
        Log.d(TAG, "Closing LLM Engine and freeing GPU VRAM...")
        initialized.set(false)
        busy.set(false)
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing engine: ${e.message}")
        } finally {
            engine = null
        }
    }

    @OptIn(ExperimentalApi::class)
    fun initialize(onResult: (InitResult) -> Unit) {
        llmScope.launch {
            close() // Always ensure previous zombie engine is dead before booting

            val modelFile = LlmModelManager.getInstalledModel(context)
            if (modelFile == null) { 
                withContext(Dispatchers.Main) { onResult(InitResult.ModelNotFound) }
                return@launch 
            }

            var backendType = "CPU"
            try {
                val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                backendType = prefs.getString("ai_backend", "CPU") ?: "CPU"
                
                Log.d(TAG, "Booting Engine with Backend = $backendType on file: ${modelFile.name}")

                val backendConfig = when (backendType) {
                    "GPU" -> Backend.GPU()
                    "NPU" -> {
                        Log.w(TAG, "Native NPU unsupported without QNN. Falling back to CPU.")
                        Backend.CPU()
                    }
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
            } catch (e: Exception) {
                Log.e(TAG, "initialize() failed: ${e.message}")
                withContext(Dispatchers.Main) { 
                    onResult(InitResult.Error("Init failed ($backendType Error): ${e.message}")) 
                }
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    fun analyze(
        bitmap: Bitmap,
        systemPrompt: String,
        userPrompt: String,
        onToken: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!initialized.get() || !busy.compareAndSet(false, true)) {
            bitmap.recycle()
            return 
        }

        llmScope.launch {
            try {
                val eng = engine ?: throw IllegalStateException("Engine null")
                val conversation = eng.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.4),
                        systemInstruction = Contents.of(systemPrompt)
                    )
                )

                val imageBytes = bitmap.toJpegBytes(maxDim = 512)
                val contents = Contents.of(listOf(
                    Content.ImageBytes(imageBytes),
                    Content.Text(userPrompt)
                ))

                val sb = StringBuilder()
                conversation.sendMessageAsync(contents).collect { message ->
                    sb.append(message.toString())
                }
                
                withContext(Dispatchers.Main) { onDone(sb.toString().trim()) }
                conversation.close()

            } catch (e: Exception) {
                Log.e(TAG, "Inference error", e)
                withContext(Dispatchers.Main) { onError(e.message ?: "Inference error") }
            } finally {
                busy.set(false)
                bitmap.recycle() 
            }
        }
    }

    private fun Bitmap.toJpegBytes(maxDim: Int = 512): ByteArray {
        val scale = if (maxOf(width, height) > maxDim) maxDim.toFloat() / maxOf(width, height) else 1f
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true) else this
        return ByteArrayOutputStream().also {
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, it)
        }.toByteArray()
    }
}