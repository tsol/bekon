package pro.potoki.bekon.phone

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_calls")
data class RecentCall(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val name: String,
    val direction: String,
    val startedAt: Long,
    val durationMs: Long,
    val result: String = "",
) {
    companion object {
        const val IN = "IN"
        const val OUT = "OUT"
        const val MISSED = "MISSED"
    }
}
