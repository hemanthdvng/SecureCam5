package com.securecam.data.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SecurityEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String, // "MOTION", "FACE_KNOWN", "FACE_UNKNOWN", "LLM_INSIGHT"
    val description: String,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class EventRepository @Inject constructor() {
    
    // extraBufferCapacity ensures WebRTC/UI doesn't miss events if temporarily suspended
    private val _securityEvents = MutableSharedFlow<SecurityEvent>(extraBufferCapacity = 50)
    val securityEvents = _securityEvents.asSharedFlow()

    fun emitEvent(event: SecurityEvent) {
        _securityEvents.tryEmit(event)
    }
}