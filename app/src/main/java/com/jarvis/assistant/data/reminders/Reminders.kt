package com.jarvis.assistant.data.reminders

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** A reminder's life: waiting, then shown (fired), then done or cancelled. */
object ReminderState {
    const val PENDING = "PENDING"
    const val FIRED = "FIRED"
    const val DONE = "DONE"
    const val CANCELLED = "CANCELLED"
}

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** Epoch millis. */
    val triggerAt: Long,
    val createdAt: Long,
    val state: String = ReminderState.PENDING,
)

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(entity: ReminderEntity): Long

    @Query("SELECT * FROM reminders WHERE state = 'PENDING' ORDER BY triggerAt")
    suspend fun pending(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): ReminderEntity?

    @Query("UPDATE reminders SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)

    @Query("UPDATE reminders SET triggerAt = :triggerAt, state = 'PENDING' WHERE id = :id")
    suspend fun reschedule(id: Long, triggerAt: Long)

    /** Finished reminders are kept a while, then dropped so the table never grows. */
    @Query("DELETE FROM reminders WHERE state != 'PENDING' AND triggerAt < :before")
    suspend fun pruneFinished(before: Long)
}

/** Version 2 adds reminders; the action history from version 1 is kept. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reminders` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, " +
                "`triggerAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `state` TEXT NOT NULL)",
        )
    }
}
