package com.securecam

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SecureCamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize global resources here if needed
    }
}