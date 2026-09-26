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
     * 到期的「学习步内」卡，含正在重学的（`RELEARNING`）。白名单式过滤，
     * 将来加排除态也不用改这里。
     *
     * 学习卡是「在途」卡 —— 用户今天在卡片上作过答、还在分钟级重考循环里，
     * 丢掉它们没有补救路径，所以给一份独立于复习卡的预算。
     */
    @Query(
        """
        SELECT * FROM review_states
        WHERE dueAtMinutes <= :nowMinutes
          AND state IN ('LEARNING', 'RELEARNING')
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
        WHERE state IN ('LEARNING', 'RELEARNING')
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
          AND state IN ('LEARNING', 'RELEARNING', 'REVIEW')
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY dueAtMinutes ASC
        """
    )
    suspend fun allDueStates(nowMinutes: Long, deckId: Long?): List<ReviewStateEntity>

    /**
     * 待复习表中的所有复习卡（直接查 review_states 表，包含 LEARNING / RELEARNING / REVIEW
     * 状态，不以到期时间为门槛）。
     */
    @Query(
        """
        SELECT * FROM review_states
        WHERE state IN ('LEARNING', 'RELEARNING', 'REVIEW')
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        ORDER BY dueAtMinutes ASC
        """
    )
    suspend fun allReviewStates(deckId: Long?): List<ReviewStateEntity>

    @Query(
        """
        SELECT COUNT(*) FROM review_states
        WHERE dueAtMinutes <= :nowMinutes
          AND state IN ('LEARNING', 'RELEARNING', 'REVIEW')
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    fun observeDueCount(nowMinutes: Long, deckId: Long?): Flow<Int>

    /**
     * 正在冷却中的学习卡数（`LEARNING` / `RELEARNING`，且 dueAtMinutes > :nowMinutes）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM review_states
        WHERE state IN ('LEARNING', 'RELEARNING')
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
        WHERE state IN ('LEARNING', 'RELEARNING')
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
        WHERE state IN ('LEARNING', 'RELEARNING', 'REVIEW')
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    fun observeReviewCount(deckId: Long?): Flow<Int>

    /**
     * 处于学习步内（`LEARNING` / `RELEARNING`，即学习中或重学中）的卡片总数。
     */
    @Query(
        """
        SELECT COUNT(*) FROM review_states
        WHERE state IN ('LEARNING', 'RELEARNING')
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    fun observeLearningCount(deckId: Long?): Flow<Int>

    @Query("SELECT COUNT(*) FROM review_states WHERE state = :state")
    fun observeCountByState(state: String): Flow<Int>

    /**
     * 学习步内的卡数（`LEARNING` + `RELEARNING`）。
     *
     * 统计页把这两类合起来叫「学习中」。分开数的话，重学中的词在统计页上**哪个数字都不算**
     * —— 看着像凭空少了几张卡，而它们其实就在队列里。
     */
    @Query("SELECT COUNT(*) FROM review_states WHERE state IN ('LEARNING', 'RELEARNING')")
    fun observeInLearningCount(): Flow<Int>

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
              AND r.state IN ('LEARNING', 'RELEARNING', 'REVIEW')
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
     * 今天「开始学」的词数（初学）。两部分合起来：
     *
     *  1. **今天新学的词**：首次作答在今天，且在第 0 步答对（GOOD/EASY）、或被标记为
     *     「太简单」移出学习（移出不写日志，所以那一支只能从状态行看）；
     *  2. **今天开始重学的词**：今天答了「忘记」、但首次作答**不**在今天 —— 也就是学过
     *     的词今天忘了。`NOT IN (首次作答在今天)` 与第 1 部分的判据正好对称：新词在学习
     *     步里按「忘记」算初学，不算重学。
     *
     * 两部分都是**日志锚定**的（「今天有没有落下这一行」），而不是「现在处于什么状态」。
     * 状态会随着用户把卡学走而变化，按状态数出来的值当天就会往下掉 —— 那正是这个界面
     * 一开始要修的问题。
     */
    @Query(
        """
        SELECT COUNT(DISTINCT w.wordId) FROM (
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
            UNION
            SELECT l.wordId FROM review_logs l
            WHERE l.reviewedAt >= :startMillis AND l.reviewedAt < :endMillis
              AND l.rating = 'AGAIN'
              AND (:deckId IS NULL OR l.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND l.wordId NOT IN (
                  SELECT wordId FROM review_logs
                  GROUP BY wordId
                  HAVING MIN(reviewedAt) >= :startMillis AND MIN(reviewedAt) < :endMillis
              )
        ) w
        """
    )
    fun observeStartedTodayCount(startMillis: Long, endMillis: Long, deckId: Long?): Flow<Int>

    /**
     * 上面那些「今天开始学的词」里，**今天已经学完**的词数（掌握）。
     *
     *  1. 今天新学的词里已经走到 `REVIEW`/`TOO_EASY` 的；
     *  2. 今天忘了的已学过的词里，现在已经回到 `REVIEW`/`TOO_EASY` 的（今天忘、今天又学回来）。
     *
     * 于是恒有 `掌握 <= 初学` —— 这个关系就是它的定义。
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT r.wordId FROM review_states r
            WHERE r.state IN ('REVIEW', 'TOO_EASY')
              AND (:deckId IS NULL OR r.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND r.wordId IN (
                  SELECT wordId FROM review_logs
                  GROUP BY wordId
                  HAVING MIN(reviewedAt) >= :startMillis AND MIN(reviewedAt) < :endMillis
              )
            UNION
            SELECT r.wordId FROM review_states r
            WHERE r.state IN ('REVIEW', 'TOO_EASY')
              AND (:deckId IS NULL OR r.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND r.wordId IN (
                  SELECT wordId FROM review_logs
                  WHERE reviewedAt >= :startMillis AND reviewedAt < :endMillis
                    AND rating = 'AGAIN'
              )
        ) w
        """
    )
    fun observeMasteredTodayCount(startMillis: Long, endMillis: Long, deckId: Long?): Flow<Int>

    /**
     * 还压在重学里的词数：**遗忘过（`lapses > 0`）且仍在学习步内**。
     *
     * 这是「重学积压」的真实值。复习结束时由 `StudyRepository.snapshotRelearnBacklog` 取
     * 一次存进 DataStore，界面上显示成 `上限 50+N` —— 取一次是因为这个值会在用户重学完一个
     * 词的瞬间变小，而界面上那个数要能稳定地跨天摆着，直到下次复习结束才更新。
     *
     * 用 `lapses > 0` 而不是「有过 AGAIN 日志」：新词在学习步里按「忘记」**不**累加
     * `lapses`（见 `Sm2Scheduler` 的 inLearning 分支），所以这个判据天然排除掉了新词。
     * 也不能用「lapses > 0」单独当判据 —— 那样会把「上个月忘过、今天只是正常复习」的
     * 词也算进来。
     *
     * 用 `state IN ('LEARNING','RELEARNING')` 而不是只认 `RELEARNING`：重学卡一作答就被
     * 写成 `LEARNING`，只认 `RELEARNING` 会让这个数在用户学了一半的时候凭空掉下去。
     */
    @Query(
        """
        SELECT COUNT(*) FROM review_states
        WHERE lapses > 0
          AND state IN ('LEARNING', 'RELEARNING')
          AND (:deckId IS NULL OR wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
        """
    )
    suspend fun countPendingRelearn(deckId: Long?): Int

    /**
     * 这个词有没有被作答过。
     *
     * 用来区分「真的一次都没学过」和「学过、只是被遗忘了」—— 两者的
     * `repetitions` / `intervalDays` 都是 0，光看状态行分不开。见
     * `StudyRepository.resumeExcluded`。
     */
    @Query("SELECT EXISTS(SELECT 1 FROM review_logs WHERE wordId = :wordId)")
    suspend fun hasLogs(wordId: Long): Boolean

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
