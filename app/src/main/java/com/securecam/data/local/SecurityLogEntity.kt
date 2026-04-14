package com.securecam.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "security_logs")
data class SecurityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val logTime: Long,
    val type: String,
    val description: String,
    val confidence: Float,
    val videoPath: String? = null
)