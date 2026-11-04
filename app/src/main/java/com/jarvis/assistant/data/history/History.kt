package com.jarvis.assistant.data.history

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.jarvis.assistant.data.reminders.ReminderDao
import com.jarvis.assistant.data.reminders.ReminderEntity
import com.jarvis.assistant.domain.ActionLog
import com.jarvis.assistant.domain.ActionLogEntry
import com.jarvis.assistant.domain.ActionStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "action_log")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val transcript: String,
    val tool: String,
    val summary: String,
    val status: String,
    val detail: String?,
)

@Dao
interface ActionLogDao {
    @Insert
    suspend fun insert(entity: ActionLogEntity): Long

    @Query("SELECT * FROM action_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActionLogEntity>>

    @Query("DELETE FROM action_log")
    suspend fun clear()
}

@Database(entities = [ActionLogEntity::class, ReminderEntity::class], version = 2, exportSchema = false)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun actionLogDao(): ActionLogDao
    abstract fun reminderDao(): ReminderDao
}

@Singleton
class HistoryRepository @Inject constructor(private val dao: ActionLogDao) : ActionLog {
    override suspend fun record(entry: ActionLogEntry) {
        dao.insert(
            ActionLogEntity(
                timestamp = entry.timestamp,
                transcript = entry.transcript,
                tool = entry.tool,
                summary = entry.summary,
                status = entry.status.name,
                detail = entry.detail,
            ),
        )
    }

    fun observeRecent(limit: Int = 200): Flow<List<ActionLogEntity>> = dao.observeRecent(limit)

    suspend fun clear() = dao.clear()
}

val ActionLogEntity.actionStatus: ActionStatus
    get() = runCatching { ActionStatus.valueOf(status) }.getOrDefault(ActionStatus.FAILED)
