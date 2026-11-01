package com.alcohol.englishstudying

import com.alcohol.englishstudying.domain.srs.TodayProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayProgressTest {

    private fun seg(
        reviewTarget: Int = 0,
        newLimit: Int = 0,
        reviewDone: Int = 0,
        masteredNew: Int = 0,
        hazy: Int = 0,
        startedNew: Int = 0
    ) = TodayProgress.segments(
        reviewTarget = reviewTarget,
        newLimit = newLimit,
        reviewDone = reviewDone,
        masteredNew = masteredNew,
        hazy = hazy,
        startedNew = startedNew
    )

    @Test
    fun `分母就是今日复习分母加新词上限`() {
        // 卡片页上写着「今日复习 93/361」「上限 50」
        val s = seg(reviewTarget = 361, newLimit = 50, reviewDone = 93)

        assertEquals(411, s.total)
        assertEquals(93, s.done)
        assertEquals(93f / 411f, s.doneFraction, 0.0001f)
    }

    @Test
    fun `早上还没动过时整条是空的`() {
        val s = seg(reviewTarget = 361, newLimit = 50)

        assertEquals(0, s.done)
        assertEquals(0, s.inProgress)
        assertEquals(411, s.total)
    }

    @Test
    fun `太简单与复习的太难都算深绿`() {
        // 新词标太简单已含在 masteredNew，旧词标太难已含在 reviewDone
        val s = seg(reviewTarget = 10, newLimit = 5, reviewDone = 10, masteredNew = 3)

        assertEquals(13, s.done)
        assertEquals(15, s.total)
    }

    @Test
    fun `新学太难由上限那一格扣掉，分母里不会再留白格`() {
        // 上限 50，今天标了 2 个太难 → 界面显示的上限是 48，条的分母直接用这个数
        val s = seg(newLimit = 48)

        assertEquals(48, s.total)
        assertEquals(0, s.done)
    }

    @Test
    fun `初学未掌握算浅绿`() {
        val s = seg(reviewTarget = 5, newLimit = 50, startedNew = 3)

        assertEquals(3, s.inProgress)
        assertEquals(55, s.total)
    }

    @Test
    fun `模糊待重考算浅绿`() {
        val s = seg(reviewTarget = 14, reviewDone = 10, hazy = 4)

        assertEquals(10, s.done)
        assertEquals(4, s.inProgress)
        assertEquals(14, s.total)
    }

    @Test
    fun `已掌握的新词不重复算进浅绿`() {
        // 5 个新词，其中 2 个已掌握：深绿 2、浅绿 3
        val s = seg(reviewTarget = 3, newLimit = 50, masteredNew = 2, startedNew = 5)

        assertEquals(2, s.done)
        assertEquals(3, s.inProgress)
        assertEquals(53, s.total)
    }

    @Test
    fun `总量不够时分段被截断，条不会画出界`() {
        // 把上限调小之后：已复习比「今日复习分母」还大
        val s = seg(reviewTarget = 100, newLimit = 20, reviewDone = 100, masteredNew = 50, startedNew = 50)

        assertTrue(s.done + s.inProgress <= s.total)
        assertTrue(s.doneFraction + s.inProgressFraction <= 1.0001f)
        assertEquals(s.total, s.total.coerceAtLeast(s.done))
    }

    @Test
    fun `全空的词库不会出现除零`() {
        val s = seg()

        assertEquals(0, s.total)
        assertEquals(0f, s.doneFraction)
        assertEquals(0f, s.inProgressFraction)
    }
}
