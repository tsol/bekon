package pro.potoki.bekon.phone

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [RecentCall::class], version = 2, exportSchema = false)
abstract class PhoneDb : RoomDatabase() {
    abstract fun recents(): RecentsDao
}
