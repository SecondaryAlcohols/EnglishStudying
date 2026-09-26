package com.alcohol.englishstudying.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alcohol.englishstudying.domain.model.WordRelationType
import kotlinx.coroutines.flow.Flow

@Dao
interface WordRelationDao {

    /**
     * 某个词的全部关联词。
     *
     * 两个方向都要查 —— 写入时规范化成了 `from < to`，所以「我是 from」和
     * 「我是 to」都可能命中。只查一边会漏掉一半。
     *
     * 两张表的列名没有重叠（word_relations 没有 id 列），所以 `r.*, w.*` 能直接
     * 映到 RelatedWord 的两个 @Embedded 字段上，不用逐个起别名。
     */
    @Query(
        """
        SELECT r.*, w.*
        FROM word_relations r
        INNER JOIN words w
                ON w.id = CASE WHEN r.fromWordId = :wordId THEN r.toWordId ELSE r.fromWordId END
        WHERE r.fromWordId = :wordId OR r.toWordId = :wordId
        ORDER BY r.type ASC, w.frequency DESC, w.headword COLLATE NOCASE ASC
        """
    )
    fun observeFor(wordId: Long): Flow<List<RelatedWord>>

    @Query(
        """
        SELECT r.*, w.*
        FROM word_relations r
        INNER JOIN words w
                ON w.id = CASE WHEN r.fromWordId = :wordId THEN r.toWordId ELSE r.fromWordId END
        WHERE r.fromWordId = :wordId OR r.toWordId = :wordId
        ORDER BY r.type ASC, w.frequency DESC, w.headword COLLATE NOCASE ASC
        """
    )
    suspend fun getFor(wordId: Long): List<RelatedWord>

    /** 主键冲突就跳过：推导和手动添加可能撞上同一条。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(relations: List<WordRelationEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(relation: WordRelationEntity)

    /** 删除一条。两个方向都试，调用方不用关心规范化过的顺序。 */
    @Query(
        """
        DELETE FROM word_relations
        WHERE type = :type
          AND ((fromWordId = :a AND toWordId = :b) OR (fromWordId = :b AND toWordId = :a))
        """
    )
    suspend fun deletePair(a: Long, b: Long, type: WordRelationType)

    /** 重建用：只清掉自动推导出来的，用户手工加的一条不动。 */
    @Query("DELETE FROM word_relations WHERE source = 'AUTO'")
    suspend fun deleteAuto()

    @Query("SELECT COUNT(*) FROM word_relations WHERE type = :type")
    suspend fun countByType(type: WordRelationType): Int

    @Query("SELECT COUNT(*) FROM word_relations")
    suspend fun countAll(): Int

    /**
     * 某一类关系的全部边，只取两端 id，供队列聚组时在内存里筛。
     *
     * **刻意不接 id 列表。** 聚组要的是「两端都在今天队列里」的边，但那件事必须在
     * Kotlin 侧过滤：`fromWordId IN (:ids) AND toWordId IN (:ids)` 会绑 2N+1 个变量，
     * 队列超过 499 就撞上 SQLite 的 999 变量上限（Android 11 及更早的系统 SQLite），
     * 抛 SQLiteException 直接崩 —— 而「每日复习上限」允许填到 5000，够得着。
     *
     * 也不能改成对 id 列表分块查询：那样会把「一端在块内、另一端在块外」的边整条漏掉，
     * 而那正是要聚到一起的变形对。这类边的量级是千级（真机 3271 词的库上 FORM 只有
     * 1532 条），全取回来再筛又便宜又不会错。
     */
    @Query("SELECT fromWordId, toWordId FROM word_relations WHERE type = :type")
    suspend fun pairsOfType(type: WordRelationType): List<RelationPairRow>

    /**
     * 重建推导要用的全部输入。
     *
     * 只取推导真正会读的五列，不 `SELECT *` —— words 表里的 meanings / examples
     * 可以很长，全库拉一遍会平白多占几 MB 内存。
     *
     * 按重要度降序：deriveRoots 截断同根词组时取的是靠前的词，顺序在这里就定好。
     */
    @Query(
        """
        SELECT id, headword, roots, derivations, synonyms
        FROM words
        ORDER BY frequency DESC, id ASC
        """
    )
    suspend fun relationInputs(): List<RelationInputRow>
}

/** 推导用的轻量投影。 */
data class RelationInputRow(
    val id: Long,
    val headword: String,
    val roots: String?,
    val derivations: String?,
    val synonyms: String?
)

/** 只含两端的轻量投影，供队列聚组用。 */
data class RelationPairRow(
    val fromWordId: Long,
    val toWordId: Long
)
