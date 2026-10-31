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
    version = 3,
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
         * 几千个词和全部学习进度，没有理由不写。
         *
         * （这里原先写着「`fallbackToDestructiveMigration()` 留作兜底：万一以后某次忘了
         * 写迁移，宁可清库也不要启动即崩」—— 2026-09-26 那次清库证明这个取舍是反的，
         * 兜底已经拿掉，理由见 [build]。）
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

        /**
         * v2 → v3：加 `words.priority` 列（优先学）。
         *
         * `ALTER TABLE ADD COLUMN` 必须带 `DEFAULT 0`，和实体上的
         * `@ColumnInfo(defaultValue = "0")` 对齐 —— Room 的 schema 校验会比较默认值，
         * 少了它迁移完会报 "Migration didn't properly handle words"。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `words` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                // 声明了 @ForeignKey 后 Room 会在 onOpen 里自动执行 PRAGMA foreign_keys = ON，
                // 级联删除无需额外配置。
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // ⚠️ 刻意**不**调用 `fallbackToDestructiveMigration()`，别再把它加回来。
                //
                // 它只兜「找不到迁移路径」这一种情况，而且兜得非常"成功"：不崩、不报错、
                // 只在 logcat 里留一行，直接把用户的表全删了重建。
                // 2026-09-26 就是这么丢掉了整个学习库 —— 3271 个词、554 条记忆状态、
                // 2032 条作答日志、7879 条词关联，一夜归零；现场唯一的症状是界面显示
                // 「词库还是空的」，看不出是清库。
                //
                // 不写它，缺迁移时 Room 会抛 `IllegalStateException` 当场崩 —— 这是**故意**的：
                // 崩了数据原样躺在盘上，补一个 Migration 就能救回来；清库没有任何补救路径。
                // 两害相权：宁可启动即崩，也不要静默清库。
                //
                // 于是有一条硬纪律：**改 entities / 任何 @Entity / @ColumnInfo，就必须同时
                // 升 version 并写对应的 Migration**。`MigrationChainTest` 会在单测里挡住漏写。
                .build()
    }
}
