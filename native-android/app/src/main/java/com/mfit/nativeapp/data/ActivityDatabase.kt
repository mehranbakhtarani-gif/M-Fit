package com.mfit.nativeapp.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "activity_sessions")
data class ActivitySessionEntity(
    @PrimaryKey val id: String,
    val activityType: String,
    val status: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val elapsedMillis: Long = 0L,
    val distanceMeters: Double = 0.0,
    val calories: Int = 0
)

@Entity(
    tableName = "activity_track_points",
    primaryKeys = ["sessionId", "timestampMillis"],
    indices = [Index("sessionId")]
)
data class ActivityTrackPointEntity(
    val sessionId: String,
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val accuracyMeters: Float,
    val segmentDistanceMeters: Double
)

@Dao
interface ActivityDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: ActivitySessionEntity)

    @Query("UPDATE activity_sessions SET status = :status, endedAtMillis = :endedAt, elapsedMillis = :elapsed, distanceMeters = :distance, calories = :calories WHERE id = :id")
    suspend fun updateSession(
        id: String,
        status: String,
        endedAt: Long?,
        elapsed: Long,
        distance: Double,
        calories: Int
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPoint(point: ActivityTrackPointEntity)

    @Query("SELECT * FROM activity_sessions WHERE status IN ('RUNNING', 'PAUSED') ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun findUnfinishedSession(): ActivitySessionEntity?

    @Query("SELECT * FROM activity_track_points WHERE sessionId = :sessionId ORDER BY timestampMillis")
    suspend fun getPoints(sessionId: String): List<ActivityTrackPointEntity>

    @Query("SELECT * FROM activity_sessions ORDER BY startedAtMillis DESC")
    suspend fun getSessions(): List<ActivitySessionEntity>
}

@Database(
    entities = [ActivitySessionEntity::class, ActivityTrackPointEntity::class],
    version = 1,
    exportSchema = true
)
abstract class ActivityDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao

    companion object {
        @Volatile private var INSTANCE: ActivityDatabase? = null

        fun get(context: Context): ActivityDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ActivityDatabase::class.java,
                    "mfit-activities.db"
                ).build().also { INSTANCE = it }
            }
    }
}
