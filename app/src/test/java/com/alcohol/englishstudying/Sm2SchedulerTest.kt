package com.alcohol.englishstudying

import com.alcohol.englishstudying.domain.srs.CardState
import com.alcohol.englishstudying.domain.srs.Rating
import com.alcohol.englishstudying.domain.srs.Sm2Scheduler
import com.alcohol.englishstudying.domain.srs.SrsSettings
import com.alcohol.englishstudying.domain.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Sm2SchedulerTest {

    private val today = 20_000L
    private val nowMinutes = today * SrsState.MINUTES_PER_DAY + 600L
    private val defaultSettings = SrsSettings.DEFAULT
    private val scheduler = Sm2Scheduler(defaultSettings)

    private fun newCard(ease: Double = 2.5) = SrsState.newCard(today, ease)

    private fun reviewCard(
        interval: Double = 10.0,
        ease: Double = 2.5,
        repetitions: Int = 3,
        lapses: Int = 0
    ) = SrsState(
        easeFactor = ease,
        intervalDays = interval,
        repetitions = repetitions,
        learningStep = 0,
        dueAtMinutes = nowMinutes,
        lapses = lapses,
        state = CardState.REVIEW
    )

    private fun rate(card: SrsState, rating: Rating, settings: SrsSettings = defaultSettings) =
        Sm2Scheduler(settings).review(card, rating, nowMinutes, today, nowMillis = 1_000L)

    // ---- 学习步 ----

    @Test
    fun `新词按认识只前进一步，不直接毕业`() {
        val next = rate(newCard(), Rating.GOOD)

        assertEquals(CardState.LEARNING, next.state)
        assertEquals(1, next.learningStep)
        // 默认学习步是 [1, 10]，第二步应在 10 分钟后
        assertEquals(nowMinutes + 10, next.dueAtMinutes)
    }

    @Test
    fun `走完最后一步学习步才毕业到天级间隔`() {
        val afterFirst = rate(newCard(), Rating.GOOD)
        val afterSecond = rate(afterFirst, Rating.GOOD)

        assertEquals(CardState.REVIEW, afterSecond.state)
        assertEquals(1, afterSecond.repetitions)
        assertEquals(1.0, afterSecond.intervalDays, 0.001)
    }

    @Test
    fun `新词按太简单直接毕业，间隔用简单间隔`() {
        val next = rate(newCard(), Rating.EASY)

        assertEquals(CardState.REVIEW, next.state)
        assertEquals(defaultSettings.easyIntervalDays, next.intervalDays, 0.001)
    }

    @Test
    fun `学习中按忘记回到第一步并降低难度系数`() {
        val learning = rate(newCard(), Rating.GOOD) // 走到第 2 步
        val backToStart = rate(learning, Rating.AGAIN)

        assertEquals(CardState.LEARNING, backToStart.state)
        assertEquals(0, backToStart.learningStep)
        assertEquals(2.5 - 0.20, backToStart.easeFactor, 0.001)
        assertEquals(nowMinutes + 1, backToStart.dueAtMinutes)
    }

    @Test
    fun `学习中按模糊原地踏步，不改难度系数`() {
        val learning = rate(newCard(), Rating.GOOD)
        val stuck = rate(learning, Rating.HARD)

        assertEquals(CardState.LEARNING, stuck.state)
        assertEquals(learning.learningStep, stuck.learningStep)
        assertEquals(learning.easeFactor, stuck.easeFactor, 0.001)
    }

    // ---- 天级间隔 ----

    @Test
    fun `复习中按认识，间隔乘以难度系数`() {
        val next = rate(reviewCard(interval = 10.0, ease = 2.5), Rating.GOOD)

        assertEquals(CardState.REVIEW, next.state)
        assertEquals(25.0, next.intervalDays, 0.001)
        assertEquals(4, next.repetitions)
        assertEquals((today + 25) * SrsState.MINUTES_PER_DAY, next.dueAtMinutes)
    }

    @Test
    fun `复习中按模糊，间隔乘以模糊系数并降难度`() {
        val next = rate(reviewCard(interval = 10.0, ease = 2.5), Rating.HARD)

        assertEquals(12.0, next.intervalDays, 0.001)
        assertEquals(2.35, next.easeFactor, 0.001)
    }

    @Test
    fun `复习中按太简单，额外乘以简单奖励`() {
        val next = rate(reviewCard(interval = 10.0, ease = 2.5), Rating.EASY)

        // 10 * 2.5 * 1.3
        assertEquals(32.5, next.intervalDays, 0.001)
    }

    @Test
    fun `复习中按忘记，回到学习步并累计遗忘次数`() {
        val next = rate(reviewCard(interval = 10.0, ease = 2.5, lapses = 2), Rating.AGAIN)

        assertEquals(CardState.LEARNING, next.state)
        assertEquals(3, next.lapses)
        assertEquals(0, next.repetitions)
        assertEquals(2.3, next.easeFactor, 0.001)
        assertEquals(nowMinutes + 1, next.dueAtMinutes)
    }

    // ---- 边界与钳制 ----

    @Test
    fun `难度系数不会低于下限`() {
        val next = rate(reviewCard(ease = 1.4), Rating.AGAIN)

        assertEquals(Sm2Scheduler.MIN_EASE, next.easeFactor, 0.001)
    }

    @Test
    fun `连续评分后难度系数始终落在上下限内`() {
        // 四种评分轮着来，ease 无论被怎么加减都不能跑出 [1.3, 3.0]
        val ratings = listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY)
        var card = reviewCard(ease = 2.5)
        repeat(40) { index ->
            card = rate(card, ratings[index % ratings.size])
            assertTrue(
                "第 $index 次评分后 ease=${card.easeFactor} 越界",
                card.easeFactor in Sm2Scheduler.MIN_EASE..Sm2Scheduler.MAX_EASE
            )
        }
    }

    @Test
    fun `间隔不会超过设置的上限`() {
        val next = rate(reviewCard(interval = 300.0, ease = 2.5), Rating.GOOD)

        assertEquals(defaultSettings.maxIntervalDays, next.intervalDays, 0.001)
    }

    @Test
    fun `学习步不受最小间隔钳制`() {
        // 学习步是分钟级的，如果被 minIntervalDays(=1 天) 压掉，新词会立刻排到明天
        val next = rate(newCard(), Rating.GOOD)

        assertTrue(next.dueAtMinutes < (today + 1) * SrsState.MINUTES_PER_DAY)
    }

    @Test
    fun `全局间隔修正会乘进天级间隔`() {
        val settings = defaultSettings.copy(intervalModifier = 0.5)
        val next = rate(reviewCard(interval = 10.0, ease = 2.5), Rating.GOOD, settings)

        assertEquals(12.5, next.intervalDays, 0.001)
    }

    @Test
    fun `挂起的卡不参与调度`() {
        val suspended = reviewCard().copy(state = CardState.SUSPENDED)
        val next = rate(suspended, Rating.GOOD)

        assertEquals(suspended, next)
    }

    @Test
    fun `遗忘次数达到阈值自动挂起`() {
        val settings = defaultSettings.copy(leechThreshold = 3)
        val next = rate(reviewCard(lapses = 2), Rating.AGAIN, settings)

        assertEquals(CardState.SUSPENDED, next.state)
        assertEquals(3, next.lapses)
    }

    // ---- 参数清洗 ----

    @Test
    fun `非法参数被收敛回合法范围`() {
        val dirty = SrsSettings(
            dailyNewLimit = -5,
            dailyReviewLimit = 99_999,
            learningStepsMinutes = emptyList(),
            startingEase = 10.0,
            minIntervalDays = 100.0,
            maxIntervalDays = 5.0,
            leechThreshold = 0
        ).sanitized()

        assertEquals(0, dirty.dailyNewLimit)
        assertEquals(5000, dirty.dailyReviewLimit)
        assertEquals(listOf(1, 10), dirty.learningStepsMinutes)
        assertEquals(3.0, dirty.startingEase, 0.001)
        assertEquals(100.0, dirty.minIntervalDays, 0.001)
        // 最大间隔不能小于最小间隔
        assertEquals(100.0, dirty.maxIntervalDays, 0.001)
        assertEquals(1, dirty.leechThreshold)
    }

    @Test
    fun `调度器自己会清洗参数，不需要调用方先处理`() {
        val sloppy = Sm2Scheduler(SrsSettings(learningStepsMinutes = emptyList()))
        val next = sloppy.review(newCard(), Rating.AGAIN, nowMinutes, today, 0L)

        assertTrue(next.dueAtMinutes > nowMinutes)
    }

    // ---- 按钮预览 ----

    @Test
    fun `四档预览都给出下次间隔文案`() {
        val preview = scheduler.previewIntervals(newCard(), nowMinutes, today)

        assertEquals(4, preview.size)
        assertTrue(preview.getValue(Rating.AGAIN).isNotBlank())
        assertTrue(preview.getValue(Rating.GOOD).isNotBlank())
    }
}
