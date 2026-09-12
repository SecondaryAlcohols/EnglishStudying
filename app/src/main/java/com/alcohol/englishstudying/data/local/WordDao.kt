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
     */
    @Query("SELECT * FROM words WHERE LOWER(headword) IN (:lowercaseHeadwords)")
    suspend fun findByLowercaseHeadwords(lowercaseHeadwords: List<String>): List<WordEntity>

    @Query("SELECT * FROM words WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<WordEntity>

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
               s.lapses AS lapses
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
          AND (:stateFilter IS NULL
               OR IFNULL(s.state, 'NEW') = :stateFilter)
        ORDER BY w.headword COLLATE NOCASE ASC
        """
    )
    fun observeList(
        query: String,
        deckId: Long?,
        stateFilter: CardState?
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

    @Query(
        """
        SELECT * FROM words
        WHERE id NOT IN (SELECT wordId FROM review_states)
          AND (:deckId IS NULL OR id IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY frequency DESC, id ASC
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

    @Query("SELECT DISTINCT tags FROM words WHERE tags IS NOT NULL AND tags != ''")
    suspend fun allTagStrings(): List<String>

    @Insert
    suspend fun insert(word: WordEntity): Long

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
