package com.alcohol.englishstudying.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewDao {

    @Query("SELECT * FROM review_states WHERE wordId = :wordId")
    suspend fun get(wordId: Long): ReviewStateEntity?

    /**
     * 到期待复习的卡。注意这里只查 review_states 里已有的行 ——
     * 新词不入这张表，只有第一次作答时才建行，所以新词不会被当成到期卡重复捞出来。
     */
    @Query(
        """
        SELECT * FROM review_states
        WHERE dueAtMinutes <= :nowMinutes
          AND state != 'SUSPENDED'
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY dueAtMinutes ASC
        LIMIT :limit
        """
    )
    suspend fun dueStates(nowMinutes: Long, deckId: Long?, limit: Int): List<ReviewStateEntity>

    @Query(
        """
        SELECT COUNT(*) FROM review_states
        WHERE dueAtMinutes <= :nowMinutes
          AND state != 'SUSPENDED'
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    fun observeDueCount(nowMinutes: Long, deckId: Long?): Flow<Int>

    @Query("SELECT COUNT(*) FROM review_states WHERE state = :state")
    fun observeCountByState(state: String): Flow<Int>

    /** 今天已作答的卡片数（同一词当天多次作答只算一次）。 */
    @Query(
        """
        SELECT COUNT(DISTINCT wordId) FROM review_logs
        WHERE reviewedAt >= :startMillis AND reviewedAt < :endMillis
        """
    )
    fun observeReviewedCount(startMillis: Long, endMillis: Long): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM review_logs
        WHERE reviewedAt >= :startMillis AND reviewedAt < :endMillis
        """
    )
    fun observeAnswerCount(startMillis: Long, endMillis: Long): Flow<Int>

    @Query(
        """
        SELECT IFNULL(SUM(elapsedMs), 0) FROM review_logs
        WHERE reviewedAt >= :startMillis AND reviewedAt < :endMillis
        """
    )
    fun observeElapsedMs(startMillis: Long, endMillis: Long): Flow<Long>

    @Upsert
    suspend fun upsert(state: ReviewStateEntity)

    @Insert
    suspend fun insertLog(log: ReviewLogEntity)

    @Query("DELETE FROM review_states WHERE wordId = :wordId")
    suspend fun resetWord(wordId: Long)

    @Query("UPDATE review_states SET state = 'REVIEW', lapses = 0 WHERE wordId = :wordId")
    suspend fun unsuspend(wordId: Long)

    @Query("DELETE FROM review_states")
    suspend fun resetAll()

    @Query("DELETE FROM review_logs")
    suspend fun clearLogs()
}
