package com.securecam.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "security_logs")
data class SecurityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val logTime: Long,
    val type: String,
    val description: String,
    val confidence: Float,
    val videoPath: String? = null
)

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SecurityLogEntity)

    @Query("SELECT * FROM security_logs ORDER BY logTime DESC")
    fun getAllLogs(): Flow<List<SecurityLogEntity>>

    @Query("SELECT * FROM security_logs ORDER BY logTime DESC")
    fun getAllLogsSync(): List<SecurityLogEntity>

    @Query("DELETE FROM security_logs WHERE id = :id")
    suspend fun deleteLogById(id: Int)
}

@Database(entities = [SecurityLogEntity::class], version = 1, exportSchema = false)
abstract class LogDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
}