package com.alcohol.englishstudying

import com.alcohol.englishstudying.domain.srs.ReviewSchedule
import com.alcohol.englishstudying.domain.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewScheduleTest {

    private val today = 20_000L

    private fun dueIn(days: Long): Long = (today + days) * SrsState.MINUTES_PER_DAY

    @Test
    fun `过期、今天、明天各有说法`() {
        assertEquals("已到期", ReviewSchedule.label(dueIn(-1), today))
        assertEquals("今天复习", ReviewSchedule.label(dueIn(0), today))
        assertEquals("明天复习", ReviewSchedule.label(dueIn(1), today))
    }

    @Test
    fun `一个月内按天说`() {
        assertEquals("2 天后复习", ReviewSchedule.label(dueIn(2), today))
        assertEquals("30 天后复习", ReviewSchedule.label(dueIn(30), today))
    }

    @Test
    fun `超过一个月换算成月`() {
        assertEquals("1 个月后复习", ReviewSchedule.label(dueIn(31), today))
        assertEquals("2 个月后复习", ReviewSchedule.label(dueIn(60), today))
    }

    @Test
    fun `超过一年换算成年`() {
        assertEquals("1 年后复习", ReviewSchedule.label(dueIn(365), today))
        assertEquals("2 年后复习", ReviewSchedule.label(dueIn(730), today))
    }

    @Test
    fun `学习步的卡按当天算，不会因为偏移量多出一天`() {
        // 天级间隔落在当天零点，学习步落在「当天零点 + 偏移」。两者必须算成同一天 ——
        // 否则一张 10 分钟后到期的卡会被说成「明天复习」，而它今天就要再考一次
        val startOfDay = today * SrsState.MINUTES_PER_DAY

        assertEquals("今天复习", ReviewSchedule.label(startOfDay, today))
        assertEquals("今天复习", ReviewSchedule.label(startOfDay + 10, today))
        assertEquals("今天复习", ReviewSchedule.label(startOfDay + 1439, today))
    }

    @Test
    fun `daysUntil 对过期返回负数`() {
        assertEquals(-3L, ReviewSchedule.daysUntil(dueIn(-3), today))
        assertEquals(0L, ReviewSchedule.daysUntil(dueIn(0), today))
        assertEquals(5L, ReviewSchedule.daysUntil(dueIn(5), today))
    }

    @Test
    fun `手动排期落在当天零点，与自动排的卡可比`() {
        // 0 天给出的是「今天零点」，也就是 <= 此刻的 nowMinutes —— 今天就算到期
        assertEquals(today * SrsState.MINUTES_PER_DAY, ReviewSchedule.dueAtMinutesFor(today, 0))
        assertEquals(
            (today + 7) * SrsState.MINUTES_PER_DAY,
            ReviewSchedule.dueAtMinutesFor(today, 7)
        )
        // 负数收敛到 0，不产生落在昨天的到期时刻
        assertEquals(today * SrsState.MINUTES_PER_DAY, ReviewSchedule.dueAtMinutesFor(today, -5))
    }
}
