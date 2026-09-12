package com.alcohol.englishstudying.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        WordEntity::class,
        ReviewStateEntity::class,
        ReviewLogEntity::class,
        DeckEntity::class,
        DeckWordEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wordDao(): WordDao
    abstract fun reviewDao(): ReviewDao
    abstract fun deckDao(): DeckDao

    companion object {
        private const val DB_NAME = "english_studying.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                // 声明了 @ForeignKey 后 Room 会在 onOpen 里自动执行 PRAGMA foreign_keys = ON，
                // 级联删除无需额外配置。
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration()
                .build()
    }
}
