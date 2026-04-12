package com.securecam.data.repository

import com.securecam.data.local.LogDao
import com.securecam.data.local.SecurityLogEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SecurityEvent(
    val type: String,
    val description: String,
    val confidence: Float
)

@Singleton
class EventRepository @Inject constructor(
    private val logDao: LogDao
) {
    private val _securityEvents = MutableSharedFlow<SecurityEvent>(replay = 1)
    val securityEvents = _securityEvents.asSharedFlow()

    suspend fun emitEvent(event: SecurityEvent) {
        _securityEvents.emit(event)
        
        logDao.insertLog(
            SecurityLogEntity(
                logTime = System.currentTimeMillis(),
                type = event.type,
                description = event.description,
                confidence = event.confidence
            )
        )
    }
}