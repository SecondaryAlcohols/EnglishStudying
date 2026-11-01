package com.alcohol.englishstudying.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.alcohol.englishstudying.domain.srs.CardState
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {

    @Query("SELECT * FROM words ORDER BY headword COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<WordEntity>>

    @Query("SELECT * FROM words WHERE id = :id")
    fun observeById(id: Long): Flow<WordEntity?>

    @Query("SELECT * FROM words WHERE id = :id")
    suspend fun getById(id: Long): WordEntity?

    @Query("SELECT * FROM words WHERE headword = :headword LIMIT 1")
    suspend fun findByHeadword(headword: String): WordEntity?

    /**
     * 按小写比对。单词的大小写不该产生两个词条 —— 「Absent」和「absent」必须视为同一个，
     * 否则重复导入同一份词库会不断堆出副本。传进来的 key 需要预先转小写。
     *
     * 比对忽略大小写 ≠ 存储忽略大小写：词头始终按原样保存，`England` 不会变成 `england`。
     * 合并时只会把全小写的旧拼写升级成带大写的写法，见 `preferredHeadwordCasing`。
     */
    @Query("SELECT * FROM words WHERE LOWER(headword) IN (:lowercaseHeadwords)")
    suspend fun findByLowercaseHeadwords(lowercaseHeadwords: List<String>): List<WordEntity>

    @Query("SELECT * FROM words WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<WordEntity>

    /**
     * 学习页的「搜索单词」弹窗用：词头或释义里包含 [query] 的词，最多 [limit] 条。
     *
     * 排序先是「词头以它开头」，再按 COCA 排名（数字小的常用；0 表示查不到，排最后）——
     * 边打字边出结果时，最想找的那个词得在第一屏。
     */
    @Query(
        """
        SELECT * FROM words
        WHERE headword LIKE '%' || :query || '%'
           OR IFNULL(meanings, '') LIKE '%' || :query || '%'
        ORDER BY (headword LIKE :query || '%') DESC,
                 (frequency <= 0) ASC,
                 frequency ASC,
                 headword COLLATE NOCASE ASC
        LIMIT :limit
        """
    )
    suspend fun searchByHeadword(query: String, limit: Int): List<WordEntity>

    /** 只改标签列。用整行 [update] 会连带刷新别的字段，学习中途打标签不该动那些。 */
    @Query("UPDATE words SET tags = :tags, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setTags(id: Long, tags: String?, updatedAt: Long)

    /**
     * 词库列表。三个筛选条件都做成可空参数，null / 空串表示不过滤，
     * 这样只需要一条 SQL 就能覆盖所有组合，不用拼字符串。
     */
    @Query(
        """
        SELECT w.*,
               s.state AS stateName,
               s.dueAtMinutes AS dueAtMinutes,
               s.intervalDays AS intervalDays,
               s.lapses AS lapses,
               -- 用户标签：按名字拼成逗号串，交给 WordListRow.userTagList 拆。
               -- 子查询里再排序，GROUP_CONCAT 本身不支持 ORDER BY。
               (SELECT GROUP_CONCAT(name, ',') FROM (
                   SELECT t.name AS name FROM word_user_tags wt
                   JOIN user_tags t ON t.id = wt.tagId
                   WHERE wt.wordId = w.id
                   ORDER BY t.name COLLATE NOCASE)) AS userTags
        FROM words w
        LEFT JOIN review_states s ON s.wordId = w.id
        WHERE (:query = ''
               OR w.headword LIKE '%' || :query || '%'
               OR IFNULL(w.meanings, '') LIKE '%' || :query || '%'
               OR IFNULL(w.mnemonic, '') LIKE '%' || :query || '%'
               OR IFNULL(w.roots, '') LIKE '%' || :query || '%'
               OR IFNULL(w.derivations, '') LIKE '%' || :query || '%')
          AND (:deckId IS NULL
               OR w.id IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
          AND (
               :stateFilter IS NULL
               -- 「重学中」筛的是**积压**而不是状态：`RELEARNING` 只在「忘记」到「下次作答」
               -- 之间短暂存在，一作答就被写成 `LEARNING`，而那时这个词仍然没重学完。
               -- 拿状态筛它几乎永远筛不出东西（真机上就是 0 条）。
               OR (
                   :stateFilter = 'RELEARNING'
                   AND IFNULL(s.lapses, 0) > 0
                   AND IFNULL(s.state, '') IN ('LEARNING', 'RELEARNING')
               )
               OR (
                   :stateFilter <> 'RELEARNING'
                   AND IFNULL(s.state, 'NEW') = :stateFilter
               )
          )
          -- 「优先学」是独立于学习状态的一类；和其他筛选项叠加时取交集。
          -- 再要求没有 review_states 行：万一某次抽词后进程被杀、标记没来得及清，
          -- 已经学起来的词也不会赖在「优先学」里。
          AND (:priorityOnly = 0 OR (w.priority = 1 AND s.state IS NULL))
          -- 标签筛选：tagFilter=0 表示没选标签；选了就取并集（任一命中即可），
          -- 和词表 / 状态之间是「与」。tagIds 在没选时传 [-1] 占位，避免 IN () 语法错。
          AND (:tagFilter = 0
               OR w.id IN (SELECT wordId FROM word_user_tags WHERE tagId IN (:tagIds)))
        ORDER BY w.headword COLLATE NOCASE ASC
        """
    )
    fun observeList(
        query: String,
        deckId: Long?,
        stateFilter: CardState?,
        priorityOnly: Boolean,
        tagFilter: Int,
        tagIds: List<Long>
    ): Flow<List<WordListRow>>

    @Query(
        """
        SELECT COUNT(*) FROM words
        WHERE :deckId IS NULL
           OR id IN (SELECT wordId FROM deck_words WHERE deckId = :deckId)
        """
    )
    fun observeCount(deckId: Long?): Flow<Int>

    @Query("SELECT COUNT(*) FROM words")
    suspend fun count(): Int

    // ---- 抽取新词：三种顺序各一条 SQL，避免在 Kotlin 里对全表排序 ----

    /**
     * 「优先学」池：当前范围内还没学过、且被标为优先的词。
     *
     * 抽新词时先取它，剩下的名额再按用户选的顺序填 —— 所以这里的排序只影响
     * 优先池内部的先后（仍按 COCA 排名，越常用越靠前）。
     */
    @Query(
        """
        SELECT * FROM words
        WHERE priority = 1
          AND id NOT IN (SELECT wordId FROM review_states)
          AND (:deckId IS NULL OR id IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY (frequency <= 0) ASC, frequency ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun priorityNewWords(limit: Int, deckId: Long?): List<WordEntity>

    /**
     * 按 COCA 排名抽新词：**数字越小越常用，越先学**。
     *
     * 排序里那两个 `(frequency <= 0)` 是给「没有排名」的词留的：0 不是「第一名」，
     * 而是查不到，必须排在所有有排名的词后面，否则没词频的专有名词会全部挤到最前。
     */
    @Query(
        """
        SELECT * FROM words
        WHERE id NOT IN (SELECT wordId FROM review_states)
          AND (:deckId IS NULL OR id IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY (frequency <= 0) ASC, frequency ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun newWordsByFrequency(limit: Int, deckId: Long?): List<WordEntity>

    @Query(
        """
        SELECT * FROM words
        WHERE id NOT IN (SELECT wordId FROM review_states)
          AND (:deckId IS NULL OR id IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY id ASC
        LIMIT :limit
        """
    )
    suspend fun newWordsBySequence(limit: Int, deckId: Long?): List<WordEntity>

    @Query(
        """
        SELECT * FROM words
        WHERE id NOT IN (SELECT wordId FROM review_states)
          AND (:deckId IS NULL OR id IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY RANDOM()
        LIMIT :limit
        """
    )
    suspend fun newWordsByRandom(limit: Int, deckId: Long?): List<WordEntity>

    @Query(
        """
        SELECT COUNT(*) FROM words
        WHERE id NOT IN (SELECT wordId FROM review_states)
          AND (:deckId IS NULL OR id IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    fun observeNewCount(deckId: Long?): Flow<Int>

    /**
     * 范围内全部未学词的 id 和 COCA 排名（轻量行，不拉整个词条）。
     * 供「按难度混合」在全量上做三等分 —— 只取头部一小段的话，
     * 三档全是高频词，难度混合名存实亡。
     *
     * 顺序是 **排名升序（越常用越靠前）**，没有排名的（0）垫底；
     * [com.alcohol.englishstudying.domain.srs.NewWordMixer.mix] 依赖这个顺序切三等分。
     */
    @Query(
        """
        SELECT id, frequency FROM words
        WHERE id NOT IN (SELECT wordId FROM review_states)
          AND (:deckId IS NULL OR id IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY (frequency <= 0) ASC, frequency ASC, id ASC
        """
    )
    suspend fun unlearnedFrequencies(deckId: Long?): List<UnlearnedFrequency>

    @Query("SELECT DISTINCT tags FROM words WHERE tags IS NOT NULL AND tags != ''")
    suspend fun allTagStrings(): List<String>

    @Insert
    suspend fun insert(word: WordEntity): Long

    /** 「优先学」开关。只改这一列，不碰 updatedAt 以外的任何内容。 */
    @Query("UPDATE words SET priority = :priority, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPriority(id: Long, priority: Boolean, updatedAt: Long)

    /**
     * 抽走之后清掉优先标记。调用方负责分块（见 [AppDatabase.SQLITE_BIND_LIMIT]）。
     */
    @Query("UPDATE words SET priority = 0 WHERE id IN (:ids)")
    suspend fun clearPriority(ids: List<Long>)

    @Insert
    suspend fun insertAll(words: List<WordEntity>): List<Long>

    @Update
    suspend fun update(word: WordEntity)

    @Update
    suspend fun updateAll(words: List<WordEntity>)

    @Delete
    suspend fun delete(word: WordEntity)

    @Query("DELETE FROM words WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/** [WordDao.unlearnedFrequencies] 的轻量结果行。 */
data class UnlearnedFrequency(
    val id: Long,
    val frequency: Int
)
