package com.alcohol.englishstudying

import com.alcohol.englishstudying.domain.srs.SrsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrsSettingsTest {

    // ---- 每组词数 ----

    @Test
    fun `每组词数默认为十`() {
        assertEquals(10, SrsSettings.DEFAULT.groupSize)
    }

    @Test
    fun `每组词数下界钳到一`() {
        // 0 会让「攒够 groupSize 张」永远成立，每答一张弹一次小结
        assertEquals(1, SrsSettings(groupSize = 0).sanitized().groupSize)
        assertEquals(1, SrsSettings(groupSize = -5).sanitized().groupSize)
    }

    @Test
    fun `每组词数上界钳到五十`() {
        assertEquals(50, SrsSettings(groupSize = 999).sanitized().groupSize)
    }

    @Test
    fun `合法区间内的每组词数原样保留`() {
        listOf(1, 7, 10, 20, 50).forEach { value ->
            assertEquals(value, SrsSettings(groupSize = value).sanitized().groupSize)
        }
    }

    // ---- 既有参数的钳制没被改坏 ----

    @Test
    fun `脏输入收敛回合法范围`() {
        val dirty = SrsSettings(
            dailyNewLimit = -1,
            dailyReviewLimit = 99_999,
            startingEase = 0.0,
            minIntervalDays = 10.0,
            maxIntervalDays = 2.0
        ).sanitized()

        assertEquals(0, dirty.dailyNewLimit)
        assertEquals(5000, dirty.dailyReviewLimit)
        assertEquals(1.3, dirty.startingEase, 1e-9)
        // max 不能小于 min：用户把两者填反了也得给一个自洽的区间
        assertTrue(dirty.maxIntervalDays >= dirty.minIntervalDays)
    }
}
