package me.huanlin.gbuca.data.local.room

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val rwh: String,
    val xnxq: String,
    val name: String,
    val nameEn: String?,
    val code: String?,
    val seq: String?,
    val className: String?,
    val credits: Double,
    val hours: Double,
    val nature: String?,
    val category: String?,
    val college: String?,
    val enrollTime: String?,
    val capacity: Int?,
    val enrolled: Int?,
    val rawKcxx: String,
    val unparsed: String, // JSON array
    val syncedAt: Long,
)

@Entity(tableName = "meetings")
data class MeetingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rwh: String,
    val xnxq: String,
    val role: String,
    val teachers: String, // 空格分隔
    val weeks: String,    // 逗号分隔
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startTime: String, // HH:mm
    val endTime: String,
    val room: String?,
    val rawText: String,
)

@Dao
interface CourseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<CourseEntity>)

    @Query("DELETE FROM courses WHERE xnxq = :xnxq")
    suspend fun deleteByXnxq(xnxq: String)

    @Query("SELECT * FROM courses WHERE xnxq = :xnxq ORDER BY code")
    fun observeByXnxq(xnxq: String): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE rwh = :rwh")
    suspend fun byRwh(rwh: String): CourseEntity?

    @Query("SELECT * FROM courses WHERE rwh = :rwh")
    fun observeByRwh(rwh: String): Flow<CourseEntity?>

    @Query("SELECT DISTINCT xnxq FROM courses")
    suspend fun allXnxq(): List<String>

    @Query("SELECT COUNT(*) FROM courses WHERE xnxq = :xnxq")
    suspend fun countByXnxq(xnxq: String): Int
}

@Dao
interface MeetingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(meetings: List<MeetingEntity>)

    @Query("DELETE FROM meetings WHERE xnxq = :xnxq")
    suspend fun deleteByXnxq(xnxq: String)

    @Query("SELECT * FROM meetings WHERE xnxq = :xnxq ORDER BY weekday, startTime")
    fun observeByXnxq(xnxq: String): Flow<List<MeetingEntity>>

    @Query("SELECT * FROM meetings WHERE xnxq = :xnxq")
    suspend fun byXnxq(xnxq: String): List<MeetingEntity>
}

@Database(entities = [CourseEntity::class, MeetingEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun meetingDao(): MeetingDao
}
