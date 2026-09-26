package com.alcohol.englishstudying.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WordEntity::class,
        ReviewStateEntity::class,
        ReviewLogEntity::class,
        DeckEntity::class,
        DeckWordEntity::class,
        WordRelationEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wordDao(): WordDao
    abstract fun reviewDao(): ReviewDao
    abstract fun deckDao(): DeckDao
    abstract fun relationDao(): WordRelationDao

    companion object {
        private const val DB_NAME = "english_studying.db"

        /**
         * 往 `IN (:list)` 里塞 Kotlin List 时，每次查询允许绑定的变量数。
         *
         * SQLite 的上限是 999（Android 11 及更早的系统 SQLite 版本），这里留出余量。
         * 超限抛的是 `SQLiteException: too many SQL variables` —— 直接崩，不是降级。
         *
         * 分块只对「纯查找」安全。需要「两端成对出现」的查询（比如按关系表查边）
         * 不能分块，那会把一端在块内、另一端在块外的行整条漏掉，得改成全量取回 +
         * 内存过滤 —— 见 [com.alcohol.englishstudying.data.local.WordRelationDao.pairsOfType]。
         */
        const val SQLITE_BIND_LIMIT = 400

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /**
         * v1 → v2：加 `words.synonyms` 列与 `word_relations` 表。
         *
         * 用户说过不在意真机上的数据，但这两句的成本是五行、换来的是已经导进去的
         * 几千个词和全部学习进度，没有理由不写。`fallbackToDestructiveMigration()`
         * 留作兜底：万一以后某次忘了写迁移，宁可清库也不要启动即崩。
         *
         * `ALTER TABLE ADD COLUMN` 会把新列加到表末尾，与 Room 生成的顺序不同 ——
         * 这没关系，Room 的 schema 校验是按列名比对，不看顺序。
         *
         * 下面的建表语句逐字抄自 app/schemas/…/2.json 的 createSql，
         * 改表结构时请对照着改，不要手写。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `words` ADD COLUMN `synonyms` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `word_relations` (" +
                        "`fromWordId` INTEGER NOT NULL, `toWordId` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL, `label` TEXT, `source` TEXT NOT NULL, " +
                        "PRIMARY KEY(`fromWordId`, `toWordId`, `type`), " +
                        "FOREIGN KEY(`fromWordId`) REFERENCES `words`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`toWordId`) REFERENCES `words`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_word_relations_toWordId` " +
                        "ON `word_relations` (`toWordId`)"
                )
            }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                // 声明了 @ForeignKey 后 Room 会在 onOpen 里自动执行 PRAGMA foreign_keys = ON，
                // 级联删除无需额外配置。
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}
