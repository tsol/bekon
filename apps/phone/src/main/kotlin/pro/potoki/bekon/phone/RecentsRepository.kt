package pro.potoki.bekon.phone

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

class RecentsRepository(context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        PhoneDb::class.java,
        "bekon-phone.db",
    ).addMigrations(MIGRATION_1_2).build()

    private val dao = db.recents()

    fun observe(): Flow<List<RecentCall>> = dao.observeAll()

    suspend fun insertOut(number: String, name: String): Long =
        dao.insert(
            RecentCall(
                number = number,
                name = name,
                direction = RecentCall.OUT,
                startedAt = System.currentTimeMillis(),
                durationMs = 0,
                result = "Calling",
            ),
        )

    suspend fun insertIn(number: String, name: String): Long =
        dao.insert(
            RecentCall(
                number = number,
                name = name,
                direction = RecentCall.IN,
                startedAt = System.currentTimeMillis(),
                durationMs = 0,
                result = "Ringing",
            ),
        )

    suspend fun markMissed(id: Long) {
        val row = dao.get(id) ?: return
        dao.update(row.copy(direction = RecentCall.MISSED, result = "Missed"))
    }

    suspend fun setDuration(id: Long, durationMs: Long) {
        val row = dao.get(id) ?: return
        dao.update(row.copy(durationMs = durationMs))
    }

    suspend fun finish(id: Long, durationMs: Long, result: String) {
        val row = dao.get(id) ?: return
        dao.update(row.copy(durationMs = durationMs, result = result))
    }

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recent_calls ADD COLUMN result TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
