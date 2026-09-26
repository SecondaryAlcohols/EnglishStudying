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

        val queue = DailyPlanBuilder.build(due, emptyList(), fresh, settings)

        assertEquals(listOf("due1", "due2", "new1", "new2"), queue)
    }

    @Test
    fun `每日上限会截断队列`() {
        val due = (1..50).map { "due$it" }
        val fresh = (1..50).map { "new$it" }
        val settings = SrsSettings(dailyNewLimit = 5, dailyReviewLimit = 8)

        val queue = DailyPlanBuilder.build(due, emptyList(), fresh, settings)

        assertEquals(13, queue.size)
        assertEquals(8, queue.count { it.toString().startsWith("due") })
        assertEquals(5, queue.count { it.toString().startsWith("new") })
    }

    @Test
    fun `新词上限为零时只复习`() {
        val settings = SrsSettings(dailyNewLimit = 0, dailyReviewLimit = 10)

        val queue = DailyPlanBuilder.build(listOf("due1"), emptyList(), listOf("new1"), settings)

        assertEquals(listOf("due1"), queue)
    }

    @Test
    fun `复习上限为零时只学新词`() {
        val settings = SrsSettings(dailyNewLimit = 10, dailyReviewLimit = 0)

        val queue = DailyPlanBuilder.build(listOf("due1"), emptyList(), listOf("new1"), settings)

        assertEquals(listOf("new1"), queue)
    }

    @Test
    fun `两边都空时队列为空`() {
        val queue = DailyPlanBuilder.build(
            emptyList<String>(),
            emptyList(),
            emptyList(),
            SrsSettings()
        )

        assertTrue(queue.isEmpty())
    }

    @Test
    fun `到期的卡比上限多时，先给最早到期的`() {
        // 传入顺序就是从最早到最晚，截断后应当保留前 N 个
        val due = listOf("最早", "次早", "第三", "第四")
        val settings = SrsSettings(dailyNewLimit = 0, dailyReviewLimit = 2)

        val queue = DailyPlanBuilder.build(due, emptyList(), emptyList(), settings)

        assertEquals(listOf("最早", "次早"), queue)
    }

    // ---- 复习与学习各自独立限额 ----

    @Test
    fun `复习卡超额时，当天到期的学习卡仍进队列`() {
        // 天级间隔落在当天零点、学习步落在「当天零点 + 偏移」，所以排序上复习卡恒在前。
        // 两者共用一份预算时，这里的两张学习卡会被 200 张复习卡整段挤掉。
        val review = (1..300).map { "review$it" }
        val learning = listOf("learn1", "learn2")
        val settings = SrsSettings(dailyNewLimit = 0, dailyReviewLimit = 200)

        val queue = DailyPlanBuilder.build(review, learning, emptyList(), settings)

        assertEquals(202, queue.size)
        assertEquals(200, queue.count { it.startsWith("review") })
        assertEquals(listOf("learn1", "learn2"), queue.takeLast(2))
    }

    @Test
    fun `学习卡与复习卡各有独立上限`() {
        val review = (1..10).map { "review$it" }
        val learning = (1..10).map { "learn$it" }
        val settings = SrsSettings(dailyNewLimit = 0, dailyReviewLimit = 3)

        val queue = DailyPlanBuilder.build(review, learning, emptyList(), settings)

        // 3 + 3，不是共用一份预算的 3
        assertEquals(6, queue.size)
        assertEquals(3, queue.count { it.startsWith("review") })
        assertEquals(3, queue.count { it.startsWith("learn") })
    }

    @Test
    fun `队列顺序为复习、学习、新词`() {
        val settings = SrsSettings(dailyNewLimit = 5, dailyReviewLimit = 5)

        val queue = DailyPlanBuilder.build(listOf("r1"), listOf("l1"), listOf("n1"), settings)

        assertEquals(listOf("r1", "l1", "n1"), queue)
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
