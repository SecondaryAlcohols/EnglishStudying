package com.alcohol.englishstudying

import com.alcohol.englishstudying.domain.srs.DailyPlanBuilder
import com.alcohol.englishstudying.domain.srs.SrsSettings
import com.alcohol.englishstudying.domain.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class DailyPlanBuilderTest {

    @Test
    fun `到期卡排在前面，新词补在后面`() {
        val due = listOf("due1", "due2")
        val fresh = listOf("new1", "new2")
        val settings = SrsSettings(dailyNewLimit = 10, dailyReviewLimit = 10)

        val queue = DailyPlanBuilder.build(due, fresh, settings)

        assertEquals(listOf("due1", "due2", "new1", "new2"), queue)
    }

    @Test
    fun `每日上限会截断队列`() {
        val due = (1..50).map { "due$it" }
        val fresh = (1..50).map { "new$it" }
        val settings = SrsSettings(dailyNewLimit = 5, dailyReviewLimit = 8)

        val queue = DailyPlanBuilder.build(due, fresh, settings)

        assertEquals(13, queue.size)
        assertEquals(8, queue.count { it.toString().startsWith("due") })
        assertEquals(5, queue.count { it.toString().startsWith("new") })
    }

    @Test
    fun `新词上限为零时只复习`() {
        val settings = SrsSettings(dailyNewLimit = 0, dailyReviewLimit = 10)

        val queue = DailyPlanBuilder.build(listOf("due1"), listOf("new1"), settings)

        assertEquals(listOf("due1"), queue)
    }

    @Test
    fun `复习上限为零时只学新词`() {
        val settings = SrsSettings(dailyNewLimit = 10, dailyReviewLimit = 0)

        val queue = DailyPlanBuilder.build(listOf("due1"), listOf("new1"), settings)

        assertEquals(listOf("new1"), queue)
    }

    @Test
    fun `两边都空时队列为空`() {
        val queue = DailyPlanBuilder.build(emptyList<String>(), emptyList<String>(), SrsSettings())

        assertTrue(queue.isEmpty())
    }

    @Test
    fun `到期的卡比上限多时，先给最早到期的`() {
        // 传入顺序就是从最早到最晚，截断后应当保留前 N 个
        val due = listOf("最早", "次早", "第三", "第四")
        val settings = SrsSettings(dailyNewLimit = 0, dailyReviewLimit = 2)

        val queue = DailyPlanBuilder.build(due, emptyList(), settings)

        assertEquals(listOf("最早", "次早"), queue)
    }

    @Test
    fun `当天零点与当前时刻的分钟数按本地时区对齐`() {
        val epochDay = 20_000L

        val start = DailyPlanBuilder.startOfDayMinutes(epochDay)
        val at9 = DailyPlanBuilder.nowMinutes(epochDay, hour = 9, minute = 30)

        assertEquals(epochDay * SrsState.MINUTES_PER_DAY, start)
        assertEquals(start + 9 * 60 + 30, at9)
    }

    @Test
    fun `今天的 epochDay 与给定日期一致`() {
        val date = LocalDate.of(2026, 9, 12)

        assertEquals(date.toEpochDay(), DailyPlanBuilder.todayEpochDay(date))
    }

    @Test
    fun `nowMinutes 落在当天的区间内`() {
        val date = LocalDate.of(2026, 9, 12)
        val time = LocalTime.of(23, 59)
        val epochDay = date.toEpochDay()

        val now = DailyPlanBuilder.nowMinutes(date, time)

        assertTrue(now >= DailyPlanBuilder.startOfDayMinutes(epochDay))
        assertTrue(now < DailyPlanBuilder.startOfDayMinutes(epochDay + 1))
    }
}
