package com.securecam.core.network

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DvrEngine(private val context: Context) {
    private var isRecording = false
    private var frameCount = 0
    private var currentDir: File? = null

    fun triggerRecording() {
        if (isRecording) return
        isRecording = true
        frameCount = 0
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        currentDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SecureCam_Alert_$timeStamp")
        currentDir?.mkdirs()
        
        CoroutineScope(Dispatchers.IO).launch {
            delay(10000)
            isRecording = false
        }
    }

    fun appendFrame(bitmap: Bitmap) {
        if (!isRecording || currentDir == null) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(currentDir, String.format("frame_%03d.jpg", frameCount++))
                file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out) }
            } catch(e: Exception){}
        }
    }

    fun stopRecording() {
        try {
            this.javaClass.declaredFields.forEach { field ->
                field.isAccessible = true
                val obj = field.get(this)
                if (obj is android.media.MediaMuxer) { obj.stop(); obj.release(); field.set(this, null) }
                if (obj is android.media.MediaCodec) { obj.stop(); obj.release(); field.set(this, null) }
            }
            this.javaClass.declaredFields.find { it.name == "isRecording" }?.let { it.isAccessible = true; it.set(this, false) }
        } catch(e: Exception) {}
    }
}