package com.alcohol.englishstudying.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.alcohol.englishstudying.domain.srs.Rating
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
     * 今天点过「模糊」、还没再答对的**旧词**（`state` 仍是 `REVIEW`）。
     *
     * 用处是让「模糊 → 重考到认识为止」这条规则跨会话成立。第一次模糊时排期就按模糊
     * 算好并**定死**了，`dueAtMinutes` 因此落在未来 —— [dueReviewStates] 只捞到期卡，
     * 捞不到它们。会话内靠 `StudyViewModel.retryLater` 插回队列，可用户一退出应用，
     * 内存里的重考队列就没了，这几张卡当天再也不会出现：界面上的「今日复习」永远差
     * 这几张（2026-10-26 实测 139/143，差的正是 lot / reluctant / loss / revenue）。
     *
     * 判据用「今天有 HARD 日志 + 今天没有 GOOD/EASY」而不是「状态是什么」：一张卡在圈里
     * 点过认识之后，今天就不该再被捞回来（那时它已经有 GOOD 日志了）。
     * 新词在学习步里按模糊不算 —— 它们没有 `REVIEW` 状态行，天然排除。
     */
    @Query(
        """
        SELECT * FROM review_states r
        WHERE r.state = 'REVIEW'
          AND (:deckId IS NULL OR r.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
          AND EXISTS (
              SELECT 1 FROM review_logs l
              WHERE l.wordId = r.wordId
                AND l.reviewedAt >= :startMillis AND l.reviewedAt < :endMillis
                AND l.rating = 'HARD'
          )
          AND NOT EXISTS (
              SELECT 1 FROM review_logs g
              WHERE g.wordId = r.wordId
                AND g.reviewedAt >= :startMillis AND g.reviewedAt < :endMillis
                AND g.rating IN ('GOOD', 'EASY')
          )
        ORDER BY r.dueAtMinutes ASC
        """
    )
    suspend fun hazilyReviewedToday(
        startMillis: Long,
        endMillis: Long,
        deckId: Long?
    ): List<ReviewStateEntity>

    /** [hazilyReviewedToday] 的计数版，供卡片页顶部的「今日进度条」画浅绿段。 */
    @Query(
        """
        SELECT COUNT(*) FROM review_states r
        WHERE r.state = 'REVIEW'
          AND (:deckId IS NULL OR r.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
          AND EXISTS (
              SELECT 1 FROM review_logs l
              WHERE l.wordId = r.wordId
                AND l.reviewedAt >= :startMillis AND l.reviewedAt < :endMillis
                AND l.rating = 'HARD'
          )
          AND NOT EXISTS (
              SELECT 1 FROM review_logs g
              WHERE g.wordId = r.wordId
                AND g.reviewedAt >= :startMillis AND g.reviewedAt < :endMillis
                AND g.rating IN ('GOOD', 'EASY')
          )
        """
    )
    fun observeHazyCount(startMillis: Long, endMillis: Long, deckId: Long?): Flow<Int>

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
     *
     * 「今天已复习的旧词」既包括有作答的，也包括今天被移出学习的（`TOO_EASY` / `TOO_HARD`）——
     * 后者已经离开 [observeDueCount] 的白名单，不在这里补一段的话，它会从分母里消失，
     * 而分子（[observeReviewDoneCount]）却算着它，显示就会变成「5/0」，只能靠调用方的
     * `maxOf` 兜住。两边的「旧词」判据也保持一致：首次作答在今天之前的才算。
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
            WHERE r.state IN ('TOO_EASY', 'TOO_HARD')
              AND r.lastReviewedAt >= :startMillis AND r.lastReviewedAt < :endMillis
              AND (:deckId IS NULL OR r.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND r.wordId IN (
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
     * 今天「开始学」的词数（初学）。三部分合起来：
     *
     *  1. **今天新学且答对的词**：首次作答在今天，且在第 0 步点了 GOOD/EASY；
     *  2. **今天标为「太简单」移出学习的新词**：判据是 `lastReviewedAt` 落在今天**且没有
     *     任何作答日志** —— 后者才是「新词」的根据（没有日志说明从没作答过）。不能要求
     *     「今天有日志」：标太简单不写日志（那不算一次作答），那样写这一支永远命中不了。
     *     有日志的词也不会漏：它要么属于第 1 支，要么是个旧词。
     *     **「太难」的新词刻意不在这里** —— 它不占初学，只从当天上限里扣一个名额，
     *     见 [observeTooHardNewCount]；
     *  3. **今天忘了、又重新学起来的旧词**：今天先有「忘记」、之后又有 GOOD/EASY，
     *     且首次作答**不**在今天。**点忘记的瞬间不算** —— 那时只是掉进重学，还没学；
     *     等这张卡重新答对才计入初学。这个 `NOT IN` 与第 1 支的判据对称：
     *     首次作答在今天的词属于第 1 支，不在这里重复数。
     *
     * 第 1、3 支是**日志锚定**的（「今天有没有落下这一行」），不是「现在处于什么状态」——
     * 状态会随着用户把卡学走而变化，按状态数出来的值当天就会往下掉，那正是这个界面一开始
     * 要修的问题。
     *
     * 与每日新词**额度**的口径（`learnedCount`）刻意不同：额度数的是「今天离开未学池的词」，
     * 含答错的、也含标「太难」的；这里数的是「今天认识 / 认可的」。两者不是包含关系，
     * 所以界面上绝不能拿它们相减（见 [observeReviewDoneCount] 的说明）。
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
              AND r.lastReviewedAt >= :startMillis AND r.lastReviewedAt < :endMillis
              AND r.wordId NOT IN (SELECT wordId FROM review_logs)
            UNION
            SELECT g.wordId FROM review_logs g
            WHERE g.reviewedAt >= :startMillis AND g.reviewedAt < :endMillis
              AND g.rating IN ('GOOD', 'EASY')
              AND (:deckId IS NULL OR g.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND g.wordId NOT IN (
                  SELECT wordId FROM review_logs
                  GROUP BY wordId
                  HAVING MIN(reviewedAt) >= :startMillis AND MIN(reviewedAt) < :endMillis
              )
              AND EXISTS (
                  SELECT 1 FROM review_logs a
                  WHERE a.wordId = g.wordId
                    AND a.reviewedAt >= :startMillis AND a.reviewedAt < :endMillis
                    AND a.rating = 'AGAIN'
                    AND a.reviewedAt <= g.reviewedAt
              )
        ) w
        """
    )
    fun observeStartedTodayCount(startMillis: Long, endMillis: Long, deckId: Long?): Flow<Int>

    /**
     * 上面那些「今天开始学的词」里，**今天已经学完**的词数（掌握）。
     *
     *  1. 今天新学的词里已经走到 `REVIEW`/`TOO_EASY` 的；
     *  2. 今天新学、**没作答就被标为「太简单」**的：`TOO_EASY` + `lastReviewedAt` 落在今天
     *     + **一条日志都没有**。它和 [observeStartedTodayCount] 第 2 支是同一批词 ——
     *     初学和掌握必须成对出现，否则「太简单」的新词只进初学不进掌握，那个格子会一直
     *     差着一截；
     *  3. 今天忘了的已学过的词里，现在已经回到 `REVIEW` 的（今天忘、今天又学回来）。
     *     **只有毕业回 `REVIEW` 才算掌握**：还在 `RELEARNING`/`LEARNING` 里不算；
     *     点了忘记后直接标「太简单」的也不算 —— 那不是学回来，只是决定不再考。
     *
     * 「太难」的新词**不在这里**：它既不算初学也不算掌握，只从当天的上限里扣掉一个名额
     * （见 [observeTooHardNewCount]）。放弃一个词不等于学会它，也不等于没开始学。
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
            WHERE r.state = 'TOO_EASY'
              AND r.lastReviewedAt >= :startMillis AND r.lastReviewedAt < :endMillis
              AND (:deckId IS NULL OR r.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND r.wordId NOT IN (SELECT wordId FROM review_logs)
            UNION
            SELECT r.wordId FROM review_states r
            WHERE r.state = 'REVIEW'
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
     * 今天标为「太难」的**新词**数 —— 直接从当天的「新词上限」里扣掉的那几个名额。
     *
     * 「新词」的判据是**今天之前一条日志都没有**：真有作答日志的话，首次作答那天就已经
     * 占过额度了（见 [observeLearnedCount]），再扣一次就是重复记账。所以一支是「从没答过
     * 就标太难」，另一支是「今天才第一次答、随后标太难」—— 两者都是今天新学的词。
     *
     * 这批词**不进初学、也不进掌握**：用户是放弃了它们，而不是学会了它们。界面上的表达是
     * 「上限 20 扣掉 2 个 → 18」，这样「初学 1 / 掌握 1 / 上限 18」自己就是自洽的，
     * 不会出现「初学 1 / 上限 20」却只剩 17 个新词可发的错位。
     */
    @Query(
        """
        SELECT COUNT(*) FROM review_states r
        WHERE r.state = 'TOO_HARD'
          AND r.lastReviewedAt >= :startMillis AND r.lastReviewedAt < :endMillis
          AND (:deckId IS NULL OR r.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
          AND r.wordId NOT IN (
              SELECT wordId FROM review_logs
              GROUP BY wordId
              HAVING MIN(reviewedAt) < :startMillis
          )
        """
    )
    fun observeTooHardNewCount(startMillis: Long, endMillis: Long, deckId: Long?): Flow<Int>

    /**
     * 今天复习完成的**旧词**数 —— 「出圈」才算完成。两条来源取并集：
     *
     *  1. **今天答过「认识」**：日志里有 GOOD/EASY，且首次作答不在今天（首次作答在今天的是
     *     今天新学的词，它们进「初学」，不进「已复习」）。只点过「模糊」「忘记」的不算 ——
     *     它们还在复习循环里或已退回重学，都还没学完；
     *  2. **今天被移出学习**：今天在某张复习卡上点了「太简单 / 太难」。这也是过了一遍卡 ——
     *     用户看过它、并且对它的记忆质量下了判断，只是结论是「别再考了」。所以它算已复习，
     *     尽管 [com.alcohol.englishstudying.data.repo.StudyRepository.exclude] **不写日志**
     *     （移出不是一次作答，不该污染统计页的答题数），只能靠 `review_states.lastReviewedAt`
     *     落回今天来认。
     *
     * 「认识」这一条比「今天有作答」严：复习循环里点过「模糊」的词，在点出「认识」之前
     * 一直不算完成，否则顶部那个数会在刚点完模糊时就先涨到满分。
     *
     * 第 2 条的「旧词」判据是**有没有今天之前的作答日志**，不是「状态曾经是什么」——
     * 今天新学、今天就被移出的词要么有今天的日志、要么一条日志都没有，两种都不该算复习，
     * 它们已经记在 `observeStartedTodayCount` 的初学里了。
     *
     * **不要用 `answeredToday - learnedToday` 去减。** 那两个口径不是包含关系：
     * `learnedToday` 是「今天离开未学池的词」，包含今天新学又被移出的那些（它们不写日志），
     * 而 `answeredToday` 只数有日志的词 —— 一减就会把复习数多扣掉。2026-09-27 真机实测：
     * 当天有 5 个旧词没作答就被移出，于是「今日复习」显示 213 而正确是 218。
     *
     * 这里和 [observeTodayDueReviewCount]（今日复习的目标总量）是配套的，两边都只算旧词。
     * 分母那边仍然按「今天有作答」算 —— 模糊过的词今天确实占着一个到期名额，只是还没完成。
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT DISTINCT l.wordId FROM review_logs l
            WHERE l.reviewedAt >= :startMillis AND l.reviewedAt < :endMillis
              AND l.rating IN ('GOOD', 'EASY')
              AND (:deckId IS NULL OR l.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND l.wordId NOT IN (
                  SELECT wordId FROM review_logs
                  GROUP BY wordId
                  HAVING MIN(reviewedAt) >= :startMillis AND MIN(reviewedAt) < :endMillis
              )
            UNION
            SELECT r.wordId FROM review_states r
            WHERE r.state IN ('TOO_EASY', 'TOO_HARD')
              AND r.lastReviewedAt >= :startMillis AND r.lastReviewedAt < :endMillis
              AND (:deckId IS NULL OR r.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND r.wordId IN (
                  SELECT wordId FROM review_logs
                  GROUP BY wordId
                  HAVING MIN(reviewedAt) < :startMillis
              )
        )
        """
    )
    fun observeReviewDoneCount(startMillis: Long, endMillis: Long, deckId: Long?): Flow<Int>
    // 注：下面是「过卡数」——任意评分都算，用来扣每日复习上限；不要和上面的「完成数」混用

    /**
     * 今天已经「过过一遍」的旧卡数（**任意评分**），用来扣每日复习上限。
     *
     * 和 [observeReviewDoneCount] 只差一个 `rating IN ('GOOD','EASY')`：
     *  - 「完成」只认 GOOD/EASY 和移出学习；
     *  - 「过卡」把 AGAIN / HARD 也算进去 —— 用户确实看了这张卡、下了判断，
     *    只是结论是「没记住」。每日上限数的是**发出去多少张复习卡**，不是完成多少张。
     *
     * 只数首次作答在今天之前的词：今天新学的词走新词额度，不占复习上限。
     * 同一张卡今天答多次（忘记后重考、模糊后重考）按 DISTINCT wordId 去重，只算一张。
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT DISTINCT l.wordId FROM review_logs l
            WHERE l.reviewedAt >= :startMillis AND l.reviewedAt < :endMillis
              AND (:deckId IS NULL OR l.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND l.wordId NOT IN (
                  SELECT wordId FROM review_logs
                  GROUP BY wordId
                  HAVING MIN(reviewedAt) >= :startMillis AND MIN(reviewedAt) < :endMillis
              )
            UNION
            SELECT r.wordId FROM review_states r
            WHERE r.state IN ('TOO_EASY', 'TOO_HARD')
              AND r.lastReviewedAt >= :startMillis AND r.lastReviewedAt < :endMillis
              AND (:deckId IS NULL OR r.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND r.wordId IN (
                  SELECT wordId FROM review_logs
                  GROUP BY wordId
                  HAVING MIN(reviewedAt) < :startMillis
              )
        )
        """
    )
    fun observeReviewAttemptedCount(
        startMillis: Long,
        endMillis: Long,
        deckId: Long?
    ): Flow<Int>

    /** [observeReviewAttemptedCount] 的一次性版本，`buildSession` 抽卡前用它算剩余额度。 */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT DISTINCT l.wordId FROM review_logs l
            WHERE l.reviewedAt >= :startMillis AND l.reviewedAt < :endMillis
              AND (:deckId IS NULL OR l.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND l.wordId NOT IN (
                  SELECT wordId FROM review_logs
                  GROUP BY wordId
                  HAVING MIN(reviewedAt) >= :startMillis AND MIN(reviewedAt) < :endMillis
              )
            UNION
            SELECT r.wordId FROM review_states r
            WHERE r.state IN ('TOO_EASY', 'TOO_HARD')
              AND r.lastReviewedAt >= :startMillis AND r.lastReviewedAt < :endMillis
              AND (:deckId IS NULL OR r.wordId IN (SELECT wordId FROM deck_words WHERE deckId = :deckId))
              AND r.wordId IN (
                  SELECT wordId FROM review_logs
                  GROUP BY wordId
                  HAVING MIN(reviewedAt) < :startMillis
              )
        )
        """
    )
    suspend fun reviewAttemptedCount(
        startMillis: Long,
        endMillis: Long,
        deckId: Long?
    ): Int

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
     * 今天每一次作答的流水，最新在上 —— 学习页右上角「查看日志」弹窗用。
     *
     * 和 [todayStudiedWords] 的区别是**不按词聚合**：那边一个词一行、给导出用；
     * 这边一次作答一行，能看出「同一个词今天答了几次、每次给的是什么」。
     */
    @Query(
        """
        SELECT l.id AS id, l.wordId AS wordId, w.headword AS headword,
               l.rating AS rating, l.reviewedAt AS reviewedAt,
               l.intervalDaysAfter AS intervalDaysAfter
        FROM review_logs l
        JOIN words w ON w.id = l.wordId
        WHERE l.reviewedAt >= :startMillis AND l.reviewedAt < :endMillis
        ORDER BY l.reviewedAt DESC
        LIMIT :limit
        """
    )
    suspend fun todayLogs(startMillis: Long, endMillis: Long, limit: Int): List<TodayLogRow>

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

/** [ReviewDao.todayLogs] 的一行：一次作答。 */
data class TodayLogRow(
    val id: Long,
    val wordId: Long,
    val headword: String,
    val rating: Rating,
    val reviewedAt: Long,
    val intervalDaysAfter: Double
)
