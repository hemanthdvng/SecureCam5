package com.securecam.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import com.securecam.data.repository.EventRepository
import com.securecam.data.repository.SecurityEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridAIPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventRepository: EventRepository
) {
    private val aiDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val aiScope = CoroutineScope(aiDispatcher + SupervisorJob())
    
    private val llmAnalyzer = LlmVisionAnalyzer(context)
    private val biometricEngine = BiometricEngine(context)
    
    // CRITICAL BUG 4 FIX: Instantiate expensive FaceDetector once, not per frame
    private val faceDetectorOptions = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)
    
    private var isLlmBusy = false
    private var isLlmInitialized = false
    private var firstFrameReceived = false

    fun start() {
        firstFrameReceived = false
        llmAnalyzer.initialize { result -> 
            isLlmInitialized = (result is LlmVisionAnalyzer.InitResult.Success) 
            aiScope.launch {
                if (isLlmInitialized) eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] LLM Engine Initialized & Online.", 1.0f))
            }
        }
        aiScope.launch {
            try {
                biometricEngine.initialize()
            } catch (e: Exception) {
                eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] Biometric Init Error: ${e.message}", 1.0f))
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

                // CRITICAL BUG 3 FIX: Decoupled entirely from DataStore. Unified state by reading directly from SharedPreferences.
                val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                val isFaceRecogEnabledSetting = prefs.getBoolean("face_recog_enabled", false)
                val isLlmEnabledSetting = prefs.getBoolean("llm_enabled", true)

                var skipLlm = false
                
                if (isFaceRecogEnabledSetting) {
                    try {
                        val inputImage = InputImage.fromBitmap(bitmap, 0)
                        val facesList = faceDetector.process(inputImage).await()

                        if (facesList.isNotEmpty()) {
                            val savedFacesJson = prefs.getString("authorized_faces", "[]") ?: "[]"
                            val type = object : TypeToken<List<RegisteredFace>>() {}.type
                            val savedFaces: List<RegisteredFace> = Gson().fromJson(savedFacesJson, type) ?: emptyList()
                            
                            val recognizedNames = mutableSetOf<String>()

                            if (savedFaces.isNotEmpty()) {
                                for (mlFace in facesList) {
                                    val bounds = mlFace.boundingBox
                                    val size = maxOf(bounds.width(), bounds.height())
                                    var left = bounds.centerX() - size / 2
                                    var top = bounds.centerY() - size / 2
                                    
                                    left = left.coerceAtLeast(0)
                                    top = top.coerceAtLeast(0)
                                    val w = minOf(size, bitmap.width - left).coerceAtLeast(1)
                                    val h = minOf(size, bitmap.height - top).coerceAtLeast(1)

                                    val croppedFace = Bitmap.createBitmap(bitmap, left, top, w, h)
                                    val currentFaceVector = biometricEngine.getFaceEmbedding(croppedFace)
                                    
                                    if (currentFaceVector != null) {
                                        for (face in savedFaces) {
                                            val similarity = biometricEngine.calculateCosineSimilarity(currentFaceVector, face.vector)
                                            if (similarity > 0.65f) {
                                                recognizedNames.add(face.name)
                                                break 
                                            }
                                        }
                                    }
                                }
                                
                                if (recognizedNames.isNotEmpty()) {
                                    val namesList = recognizedNames.joinToString(", ")
                                    eventRepository.emitEvent(SecurityEvent("BIOMETRIC", "🛡️ Authorized Face(s) Detected: $namesList", 1.0f))
                                    skipLlm = true
                                }
                            }
                        }
                    } catch (e: Exception) {} 
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

    private suspend fun triggerLlmAnalysis(bitmap: Bitmap) {
        isLlmBusy = true
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        val confThreshold = prefs.getFloat("confidence_threshold", 0.60f)
        val debugMode = prefs.getBoolean("debug_mode", false)
        
        val basePrompt = prefs.getString("prompt_sys", "You are an AI security camera. Answer the user's prompt based ONLY on the image provided.") ?: ""
        val usrPrompt = prefs.getString("prompt_usr", "Report if you see any clock.") ?: ""
        
        val enforcedPrompt = "$basePrompt\n\nCRITICAL RULE: Evaluate the image based ONLY on the user's specific trigger. If the requested object/event IS present in the image, reply 'ALERT: <brief description>'. If the requested object/event is NOT present, you must reply EXACTLY with the word 'CLEAR'. Do not explain yourself."

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
                            val isSafe = output.equals("CLEAR", ignoreCase = true) || output.contains("[STATUS_SAFE]", ignoreCase = true)
                            
                            if (!isSafe || debugMode) {
                                aiScope.launch {
                                    val finalDesc = if (isSafe) "🔍 SCAN: Safe / No Trigger found" else "🚨 $output"
                                    eventRepository.emitEvent(SecurityEvent("LLM_INSIGHT", finalDesc, confThreshold))
                                }
                            }
                            if (continuation.isActive) continuation.resume(true)
                        },
                        onError = { err -> 
                            // CRITICAL BUG 5 FIX: Suppress the persistent "busy" error log so it doesn't flood the UI
                            if (!err.contains("busy", ignoreCase = true)) {
                                aiScope.launch { eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] LLM Error: $err", 1.0f)) }
                            }
                            if (continuation.isActive) continuation.resume(false) 
                        }
                    )
                }
            }
            if (result == null) eventRepository.emitEvent(SecurityEvent("SYSTEM", "🚨 [SYSTEM] LLM Hardware Timed Out!", 1.0f))
        } finally {
            isLlmBusy = false
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}