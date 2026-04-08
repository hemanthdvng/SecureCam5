package com.securecam.core.ai

import android.content.Context
import java.io.File

object LlmModelManager {
    fun getInstalledModel(context: Context): File? {
        val modelFile = File(context.filesDir, "gemma-4b.litertlm")
        return if (modelFile.exists()) modelFile else null
    }
}