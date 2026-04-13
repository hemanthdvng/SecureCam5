package com.securecam.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class BiometricEngine(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val IMAGE_SIZE = 112
    private val EMBEDDING_SIZE = 192
    
    private val MODEL_URL = "https://raw.githubusercontent.com/shubham0204/Face_Recognition_with_FaceNet_Android/master/app/src/main/assets/mobile_face_net.tflite"

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            val modelFile = File(context.filesDir, "mobilefacenet.tflite")
            
            if (!modelFile.exists()) {
                Log.d("BiometricEngine", "Downloading MobileFaceNet...")
                val url = java.net.URL(MODEL_URL)
                url.openStream().use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val fileInputStream = FileInputStream(modelFile)
            val fileChannel = fileInputStream.channel
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, modelFile.length())

            val options = Interpreter.Options().apply { numThreads = 4 }
            interpreter = Interpreter(modelBuffer, options)
            Log.d("BiometricEngine", "MobileFaceNet initialized successfully.")
        } catch (e: Exception) {
            Log.e("BiometricEngine", "Failed to initialize BiometricEngine", e)
        }
    }

    suspend fun getFaceEmbedding(bitmap: Bitmap): FloatArray? = withContext(Dispatchers.Default) {
        if (interpreter == null) {
            Log.e("BiometricEngine", "Interpreter is null. Extraction aborted.")
            return@withContext null
        }
        
        try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, false)
            val byteBuffer = ByteBuffer.allocateDirect(4 * IMAGE_SIZE * IMAGE_SIZE * 3)
            byteBuffer.order(ByteOrder.nativeOrder())
            
            val intValues = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            scaledBitmap.getPixels(intValues, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)
            
            var pixel = 0
            for (i in 0 until IMAGE_SIZE) {
                for (j in 0 until IMAGE_SIZE) {
                    val `val` = intValues[pixel++]
                    byteBuffer.putFloat((((`val` shr 16) and 0xFF) - 127.5f) / 128.0f)
                    byteBuffer.putFloat((((`val` shr 8) and 0xFF) - 127.5f) / 128.0f)
                    byteBuffer.putFloat(((`val` and 0xFF) - 127.5f) / 128.0f)
                }
            }
            
            val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
            interpreter?.run(byteBuffer, output)
            return@withContext output[0]
        } catch (e: Exception) {
            Log.e("BiometricEngine", "Embedding generation failed", e)
            return@withContext null
        }
    }

    fun calculateCosineSimilarity(emb1: FloatArray, emb2: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in emb1.indices) {
            dotProduct += emb1[i] * emb2[i]
            normA += emb1[i] * emb1[i]
            normB += emb2[i] * emb2[i]
        }
        return (dotProduct / (sqrt(normA.toDouble()) * sqrt(normB.toDouble()))).toFloat()
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}