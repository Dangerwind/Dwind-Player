package com.dwind.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dwind.data.local.dao.BpmDao
import com.dwind.data.local.dao.PlaylistDao
import com.dwind.data.local.dao.TrackDao
import com.dwind.data.local.entity.BpmEntity
import com.dwind.data.local.entity.PlaylistEntity
import com.dwind.data.local.entity.PlaylistTrackCrossRef
import com.dwind.data.local.entity.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        BpmEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun bpmDao(): BpmDao

    companion object {
        /** Добавление жанра и уверенности жанра к BPM-кэшу. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bpm_library ADD COLUMN genre TEXT")
                db.execSQL("ALTER TABLE bpm_library ADD COLUMN genreConfidence REAL")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dwindplayer.db"
                ).allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
