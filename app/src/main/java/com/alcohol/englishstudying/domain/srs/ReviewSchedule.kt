package com.alcohol.englishstudying.domain.srs

import java.time.LocalDate

/**
 * 「还有几天到期」的换算与文案。
 *
 * 抽成纯函数有两个理由：词库列表和词条详情页都要用同一套说法，散在两处迟早会不一致；
 * 而且边界容易写错 —— 到期当天算「今天」还是「已到期」、学习步的卡该取整到哪一天。
 */
object ReviewSchedule {

    /**
     * [dueAtMinutes] 落在几天后。负数表示已经过期。
     *
     * 天级间隔存的是「某天零点」（1440 的整数倍），学习步存的是「当天零点 + 偏移」，
     * 两者整除 1440 都落在同一天，所以一个式子同时覆盖两种卡。
     */
    fun daysUntil(dueAtMinutes: Long, todayEpochDay: Long): Long =
        dueAtMinutes / SrsState.MINUTES_PER_DAY - todayEpochDay

    fun daysUntil(dueAtMinutes: Long, today: LocalDate = LocalDate.now()): Long =
        daysUntil(dueAtMinutes, today.toEpochDay())

    /**
     * 「今天复习 / 明天复习 / N 天后复习」。
     *
     * 过期的卡一律说「已到期」而不报具体拖了几天 —— 那个数字只会让人焦虑，
     * 而且首页的「待复习」已经说了有多少张。
     */
    fun label(dueAtMinutes: Long, todayEpochDay: Long): String {
        val days = daysUntil(dueAtMinutes, todayEpochDay)
        return when {
            days < 0L -> "已到期"
            days == 0L -> "今天复习"
            days == 1L -> "明天复习"
            days <= 30L -> "$days 天后复习"
            days < 365L -> "${days / 30} 个月后复习"
            else -> "${days / 365} 年后复习"
        }
    }

    fun label(dueAtMinutes: Long, today: LocalDate = LocalDate.now()): String =
        label(dueAtMinutes, today.toEpochDay())

    /**
     * 手动把下次复习排到 [days] 天后时，该写的到期时刻。
     *
     * 与 [Sm2Scheduler] 的 `dueForDays` 保持一致：天级间隔一律落在当天零点，
     * 这样它和自动排出来的卡在 `ORDER BY dueAtMinutes` 里可比。
     */
    fun dueAtMinutesFor(todayEpochDay: Long, days: Int): Long =
        (todayEpochDay + days.coerceAtLeast(0)) * SrsState.MINUTES_PER_DAY
}
