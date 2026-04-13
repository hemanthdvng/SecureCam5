package com.securecam.data.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SecurityEvent(val type: String, val description: String, val confidence: Float)

@Singleton // CRITICAL FIX: Forces Hilt to share the exact same memory instance across CameraScreen, AI Pipeline, and AlertService
class EventRepository @Inject constructor() {
    private val _securityEvents = MutableSharedFlow<SecurityEvent>(replay = 10, extraBufferCapacity = 64)
    val securityEvents = _securityEvents.asSharedFlow()

    suspend fun emitEvent(event: SecurityEvent) {
        _securityEvents.emit(event)
    }
}