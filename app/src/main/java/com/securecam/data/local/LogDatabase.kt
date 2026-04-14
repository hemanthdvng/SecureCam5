package com.securecam.data.local

import android.content.Context
import androidx.room.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Entity(tableName = "security_logs")
data class SecurityLogEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Int = 0,
    val logTime: Long,
    val type: String,
    val description: String,
    val confidence: Float,
    val videoPath: String? = null
) var id: Int = 0, // FIX: Changed to var so Room KAPT can generate a setter
    val logTime: Long, // FIX: Renamed from timestamp to avoid SQLite keyword conflicts
    val type: String,
    val description: String,
    val confidence: Float
)

@Dao
interface LogDao {
    @Query("SELECT * FROM security_logs ORDER BY logTime DESC")
    fun getAllLogs(): Flow<List<SecurityLogEntity>>

    @Insert
    suspend fun insertLog(log: SecurityLogEntity)
}

@Database(entities = [SecurityLogEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "securecam_db").build()
    }

    @Provides
    fun provideLogDao(database: AppDatabase): LogDao = database.logDao()
}