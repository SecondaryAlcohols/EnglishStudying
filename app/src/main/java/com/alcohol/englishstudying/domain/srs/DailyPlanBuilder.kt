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
