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
     * 到期的天级复习卡。
     *
     * 只查 review_states 里已有的行 —— 新词不入这张表，只有第一次作答时才建行，
     * 所以新词不会被当成到期卡重复捞出来。白名单而非黑名单：将来加排除态不用改这里。
     *
     * 与 [dueLearningStates] **各自独立计数**，两条查询各拿一份 dailyReviewLimit。
     * 曾经两者共用一条查询、一个 LIMIT，而天级间隔落在当天零点、学习步落在
     * 「当天零点 + 偏移」，`ORDER BY dueAtMinutes ASC` 恒把复习卡排在前面 ——
     * 逾期复习卡一撞满上限，当天已经到期的学习卡就被整段截断，而它们已有
     * review_states 行、不再算新词，于是两条路都进不去，静默消失。
     */
    @Query(
        """
        SELECT * FROM review_states
        WHERE dueAtMinutes <= :nowMinutes
          AND state = 'REVIEW'
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY dueAtMinutes ASC
        LIMIT :limit
        """
    )
    suspend fun dueReviewStates(nowMinutes: Long, deckId: Long?, limit: Int): List<ReviewStateEntity>

    /**
     * 到期的「学习步内」卡。白名单同 [dueReviewStates]，将来加排除态也不用改这里。
     *
     * 学习卡是「在途」卡 —— 用户今天在卡片上作过答、还在分钟级重考循环里，
     * 丢掉它们没有补救路径，所以给一份独立于复习卡的预算。
     */
    @Query(
        """
        SELECT * FROM review_states
        WHERE dueAtMinutes <= :nowMinutes
          AND state = 'LEARNING'
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY dueAtMinutes ASC
        LIMIT :limit
        """
    )
    suspend fun dueLearningStates(nowMinutes: Long, deckId: Long?, limit: Int): List<ReviewStateEntity>

    /**
     * 全部学习中的卡片（包含正在冷却中的卡片），供提前复习使用。
     */
    @Query(
        """
        SELECT * FROM review_states
        WHERE state = 'LEARNING'
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY dueAtMinutes ASC
        LIMIT :limit
        """
    )
    suspend fun allLearningStates(deckId: Long?, limit: Int): List<ReviewStateEntity>

    @Query(
        """
        SELECT * FROM review_states
        WHERE dueAtMinutes <= :nowMinutes
          AND state IN ('LEARNING', 'REVIEW')
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY dueAtMinutes ASC
        """
    )
    suspend fun allDueStates(nowMinutes: Long, deckId: Long?): List<ReviewStateEntity>

    /**
     * 待复习表中的所有复习卡（直接查 review_states 表，包含 LEARNING 与 REVIEW 状态，不以到期时间为门槛）。
     */
    @Query(
        """
        SELECT * FROM review_states
        WHERE state IN ('LEARNING', 'REVIEW')
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY dueAtMinutes ASC
        """
    )
    suspend fun allReviewStates(deckId: Long?): List<ReviewStateEntity>

    @Query(
        """
        SELECT COUNT(*) FROM review_states
        WHERE dueAtMinutes <= :nowMinutes
          AND state IN ('LEARNING', 'REVIEW')
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    fun observeDueCount(nowMinutes: Long, deckId: Long?): Flow<Int>

    /**
     * 正在冷却中的学习卡数（state = 'LEARNING' 且 dueAtMinutes > :nowMinutes）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM review_states
        WHERE state = 'LEARNING'
          AND dueAtMinutes > :nowMinutes
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    fun observeCoolingCount(nowMinutes: Long, deckId: Long?): Flow<Int>

    /**
     * 冷却中卡片最早到期时刻（分钟数）。
     */
    @Query(
        """
        SELECT MIN(dueAtMinutes) FROM review_states
        WHERE state = 'LEARNING'
          AND dueAtMinutes > :nowMinutes
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    fun observeEarliestCoolingDueMinutes(nowMinutes: Long, deckId: Long?): Flow<Long?>

    /**
     * 待复习表中的复习词总数（直接查表，不限到期时间）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM review_states
        WHERE state IN ('LEARNING', 'REVIEW')
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    fun observeReviewCount(deckId: Long?): Flow<Int>

    /**
     * 处于 LEARNING 状态（学习中/需巩固）的卡片总数。
     */
    @Query(
        """
        SELECT COUNT(*) FROM review_states
        WHERE state = 'LEARNING'
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    fun observeLearningCount(deckId: Long?): Flow<Int>

    @Query("SELECT COUNT(*) FROM review_states WHERE state = :state")
    fun observeCountByState(state: String): Flow<Int>

    /** 今天已作答的卡片数（同一词当天多次作答只算一次）。 */
    @Query(
        """
        SELECT COUNT(DISTINCT wordId) FROM review_logs
        WHERE reviewedAt >= :startMillis AND reviewedAt < :endMillis
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    fun observeReviewedCount(startMillis: Long, endMillis: Long, deckId: Long? = null): Flow<Int>

    /**
     * 今天到期的复习词总数（当天恒定目标总量）：
     * 今天已复习的旧词 ∪ 截止到今天已到期但今天尚未复习的旧词（排除今天新词）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT DISTINCT l.wordId FROM review_logs l
            WHERE l.reviewedAt >= :startMillis AND l.reviewedAt < :endMillis
              AND (:deckId IS NULL OR l.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND l.wordId IN (
                  SELECT wordId FROM review_logs
                  GROUP BY wordId
                  HAVING MIN(reviewedAt) < :startMillis
              )
            UNION
            SELECT r.wordId FROM review_states r
            WHERE r.dueAtMinutes < :endOfDayMinutes
              AND r.state IN ('LEARNING', 'REVIEW')
              AND (:deckId IS NULL OR r.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND r.wordId NOT IN (
                  SELECT wordId FROM review_logs
                  WHERE reviewedAt >= :startMillis AND reviewedAt < :endMillis
                  GROUP BY wordId
                  HAVING MIN(reviewedAt) >= :startMillis
              )
        )
        """
    )
    fun observeTodayDueReviewCount(
        startMillis: Long,
        endMillis: Long,
        endOfDayMinutes: Long,
        deckId: Long?
    ): Flow<Int>

    /**
     * 今天新学且在第 0 步点击「认识」/「太简单」的词数（初学）。
     */
    @Query(
        """
        SELECT COUNT(DISTINCT r_words.wordId) FROM (
            SELECT l.wordId FROM review_logs l
            WHERE l.reviewedAt >= :startMillis AND l.reviewedAt < :endMillis
              AND l.rating IN ('GOOD', 'EASY')
              AND (:deckId IS NULL OR l.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND l.wordId IN (
                  SELECT wordId FROM review_logs
                  GROUP BY wordId
                  HAVING MIN(reviewedAt) >= :startMillis AND MIN(reviewedAt) < :endMillis
              )
            UNION
            SELECT r.wordId FROM review_states r
            WHERE r.state = 'TOO_EASY'
              AND (:deckId IS NULL OR r.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND r.wordId IN (
                  SELECT wordId FROM review_logs
                  GROUP BY wordId
                  HAVING MIN(reviewedAt) >= :startMillis AND MIN(reviewedAt) < :endMillis
              )
        ) r_words
        """
    )
    fun observeNewWordsRecognizedCount(
        startMillis: Long,
        endMillis: Long,
        deckId: Long?
    ): Flow<Int>

    /**
     * 今天新学且走完全部学习步、离开学习步正式进入复习阶段（或标记为太简单移出学习）的词数（掌握）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM review_states r
        WHERE r.state IN ('REVIEW', 'TOO_EASY')
          AND (:deckId IS NULL OR r.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
          AND r.wordId IN (
              SELECT wordId FROM review_logs
              GROUP BY wordId
              HAVING MIN(reviewedAt) >= :startMillis AND MIN(reviewedAt) < :endMillis
          )
        """
    )
    fun observeNewWordsGraduatedCount(
        startMillis: Long,
        endMillis: Long,
        deckId: Long?
    ): Flow<Int>

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

    /**
     * 今天作答过的词及其作答摘要，供「增强」页导出。
     * 一个词当天答几次只出一行，作答次数和忘记次数分开给。
     */
    @Query(
        """
        SELECT w.id AS wordId, w.headword, w.phonetic,
               w.part_of_speech AS partOfSpeech,
               w.meanings, w.mnemonic, w.roots, w.derivations, w.examples,
               COUNT(l.id) AS answerCount,
               IFNULL(SUM(CASE WHEN l.rating = 'AGAIN' THEN 1 ELSE 0 END), 0) AS againCount,
               MAX(l.reviewedAt) AS lastReviewedAt
        FROM review_logs l
        JOIN words w ON w.id = l.wordId
        WHERE l.reviewedAt >= :startMillis AND l.reviewedAt < :endMillis
        GROUP BY w.id
        ORDER BY lastReviewedAt ASC
        """
    )
    suspend fun todayStudiedWords(startMillis: Long, endMillis: Long): List<TodayStudiedWordRow>

    /**
     * 今天离开「未学池」的词数，也就是「今日新学词」—— 每日新词额度的唯一口径。
     *
     * **两条来源取并集**，因为词离开未学池有两条路：
     *  1. 正常作答：一个词的首次作答时刻就是它被学会的时刻，取 MIN(reviewedAt) 再筛今天；
     *  2. 没作答就被移出学习（太简单 / 太难）：`StudyRepository.exclude()` 刻意不写
     *     review_logs（标记移出不是一次作答，不该污染答题数），但它建了 review_states
     *     行并写了 lastReviewedAt —— 那个词确实从新词池里被拿走了。
     *
     * 漏掉第 2 条会让额度变成可以绕过的：标 20 个「太简单」，额度不减，离开学习页
     * 再进来又发一整批新词，用户设的「每天 20 个新词」形同虚设。
     *
     * 末尾那个 `wordId NOT IN (SELECT wordId FROM review_logs)` 保证不重复计 ——
     * 答过的词在首次作答那天已经计过一次了。（review_logs.wordId 非空，没有 NULL 陷阱。）
     *
     * 不能拿「每日新词上限 - 剩余新词」去算 —— 剩余新词是全库还没学过的词，
     * 不是今天的额度，两者相减毫无意义。
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT wordId FROM (
                SELECT wordId, MIN(reviewedAt) AS firstAt FROM review_logs GROUP BY wordId
            )
            WHERE firstAt >= :startMillis AND firstAt < :endMillis
            UNION
            SELECT wordId FROM review_states
            WHERE state IN ('TOO_EASY', 'TOO_HARD')
              AND lastReviewedAt >= :startMillis AND lastReviewedAt < :endMillis
              AND wordId NOT IN (SELECT wordId FROM review_logs)
        ) AS t
        WHERE (:deckId IS NULL OR t.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    suspend fun learnedCount(startMillis: Long, endMillis: Long, deckId: Long? = null): Int

    /** 与 [learnedCount] 同一个口径，差别只在这个是流式的（要跟着标记移出实时刷新）。 */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT wordId FROM (
                SELECT wordId, MIN(reviewedAt) AS firstAt FROM review_logs GROUP BY wordId
            )
            WHERE firstAt >= :startMillis AND firstAt < :endMillis
            UNION
            SELECT wordId FROM review_states
            WHERE state IN ('TOO_EASY', 'TOO_HARD')
              AND lastReviewedAt >= :startMillis AND lastReviewedAt < :endMillis
              AND wordId NOT IN (SELECT wordId FROM review_logs)
        ) AS t
        WHERE (:deckId IS NULL OR t.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    fun observeLearnedCount(startMillis: Long, endMillis: Long, deckId: Long? = null): Flow<Int>

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

/** [ReviewDao.todayStudiedWords] 的一行：一个词 + 它今天的作答摘要。 */
data class TodayStudiedWordRow(
    val wordId: Long,
    val headword: String,
    val phonetic: String?,
    val partOfSpeech: String?,
    val meanings: String?,
    val mnemonic: String?,
    val roots: String?,
    val derivations: String?,
    val examples: String?,
    val answerCount: Int,
    val againCount: Int,
    val lastReviewedAt: Long
)
