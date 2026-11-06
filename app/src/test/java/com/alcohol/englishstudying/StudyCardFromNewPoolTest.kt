package com.alcohol.englishstudying

import com.alcohol.englishstudying.data.local.WordEntity
import com.alcohol.englishstudying.data.repo.StudyCard
import com.alcohol.englishstudying.domain.srs.CardState
import com.alcohol.englishstudying.domain.srs.SrsState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学习队列里「新词流」的判定回归测试。
 *
 * 2026-11-06 线上事故：真机上点「模糊」会让同一张卡原样连出（trail 连出 6 次）。
 * 根因是回队位置只按 `state == NEW` 判断，新词答过一次后状态已写成 LEARNING，
 * 于是被当成复习卡「插到第一个新词之前」——队列里只剩它一张时就插回自己前面，
 * 看起来就是立刻原样重现。
 */
class StudyCardFromNewPoolTest {

    private fun card(state: CardState) = StudyCard(
        word = WordEntity(id = 1L, headword = "trail"),
        state = SrsState(state = state)
    )

    @Test
    fun `新词和初学中的卡都算新词流`() {
        assertTrue(card(CardState.NEW).fromNewPool)
        assertTrue(card(CardState.LEARNING).fromNewPool)
    }

    @Test
    fun `复习和重学不算新词流`() {
        assertFalse(card(CardState.REVIEW).fromNewPool)
        assertFalse(card(CardState.RELEARNING).fromNewPool)
    }
}
