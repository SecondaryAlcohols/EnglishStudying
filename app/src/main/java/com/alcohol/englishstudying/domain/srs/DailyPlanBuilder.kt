package com.alcohol.englishstudying.domain.srs

import java.time.LocalDate
import java.time.LocalTime

/**
 * 组装今日队列：到期复习卡 → 到期学习卡 → 新词。
 *
 * 抽成纯函数是为了能直接单测数量与排序规则 —— 这部分逻辑一旦错了，
 * 用户会表现为「明明设置了每天 20 个新词却给了 200 个」，但界面上看不出来。
 */
object DailyPlanBuilder {

    /**
     * [reviewCards] 与 [learningCards] 各占一份 [SrsSettings.dailyReviewLimit]，
     * 不是共用一份。天级间隔落在当天零点、学习步落在「当天零点 + 偏移」，
     * 所以排序上复习卡恒在学习卡之前 —— 共用预算时，逾期复习卡一撞满上限，
     * 当天已经到期的学习卡就会被整段截断，静默从队列里消失。
     *
     * 新词用 [SrsSettings.dailyNewLimit]，本就是独立的一份。
     */
    fun <T> build(
        reviewCards: List<T>,
        learningCards: List<T>,
        newCards: List<T>,
        settings: SrsSettings
    ): List<T> {
        val review = reviewCards.take(settings.dailyReviewLimit)
        val learning = learningCards.take(settings.dailyReviewLimit)
        val fresh = newCards.take(settings.dailyNewLimit)
        return review + learning + fresh
    }

    /**
     * 一张「今天之内还要再考一次」的卡该插回 [remaining] 的哪个位置。
     *
     * - **复习 / 重学卡**（`wasNew = false`）：插到第一个新词之前。它们不能掉到新词后面，
     *   否则用户会觉得「复习没结束就进学习了」。
     * - **新词**（`wasNew = true`）：插到**队尾**。新词的学习步本来就是「过一会儿再考」，
     *   而复习阶段结束后队列里全是新词 —— 插到「第一个新词之前」等于插在队首，
     *   卡片会立刻原样再出现一次（2026-10-26 实测：black/student/yes/member 都是连着出两遍）。
     *
     * 纯函数，方便单测这条规则（它在真机上只表现为「怎么刚看完又来了」）。
     */
    fun <T> requeueIndex(remaining: List<T>, wasNew: Boolean, isNew: (T) -> Boolean): Int {
        if (wasNew) return remaining.size
        val firstNew = remaining.indexOfFirst(isNew)
        return if (firstNew >= 0) firstNew else remaining.size
    }

    /**
     * 把「今天点过模糊、还没再答对」的旧卡并回复习流：排在 [due] 前面，按 [keyOf] 去重。
     *
     * 这批卡在第一次模糊时就把排期按模糊算好并定死了，`dueAtMinutes` 已经落在未来，
     * 到期的复习查询捞不到它们 —— 会话一断（退出应用、重新进入）它们就彻底消失，
     * 界面上的「今日复习」永远差这几张（2026-10-26：139/143，差的正是
     * lot / reluctant / loss / revenue 这 4 个）。
     */
    fun <T> mergeOwed(owed: List<T>, due: List<T>, keyOf: (T) -> Long): List<T> {
        if (owed.isEmpty()) return due
        if (due.isEmpty()) return owed
        val owedKeys = owed.mapTo(HashSet(owed.size), keyOf)
        return owed + due.filterNot { keyOf(it) in owedKeys }
    }

    /** 本地时区的今天，作为 epochDay。所有到期判断都基于它。 */
    fun todayEpochDay(today: LocalDate = LocalDate.now()): Long = today.toEpochDay()

    /** 当天零点的分钟数，用于把「今天已到期」的卡捞出来。 */
    fun startOfDayMinutes(todayEpochDay: Long): Long = todayEpochDay * SrsState.MINUTES_PER_DAY

    /** 当前时刻（距 epoch 的分钟数，按本地时区的当天零点对齐）。 */
    fun nowMinutes(todayEpochDay: Long, hour: Int, minute: Int): Long =
        todayEpochDay * SrsState.MINUTES_PER_DAY + hour * 60L + minute

    fun nowMinutes(now: LocalDate = LocalDate.now(), time: LocalTime = LocalTime.now()): Long =
        nowMinutes(now.toEpochDay(), time.hour, time.minute)
}
