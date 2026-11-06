package com.alcohol.englishstudying

import com.alcohol.englishstudying.data.remote.DeckDto
import com.alcohol.englishstudying.ui.remote.CloudDecksUiState
import com.alcohol.englishstudying.ui.remote.CloudScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页签归属：自己的公开表要留在「我的」（可编辑），
 * 「公开」页签只放**别人**公开的。
 */
class CloudDecksScopeTest {

    private fun deck(
        id: Long,
        name: String,
        official: Boolean = false,
        mine: Boolean = false,
        visibility: String = "private",
        owner: String? = null
    ) = DeckDto(
        id = id,
        name = name,
        official = official,
        mine = mine,
        visibility = visibility,
        ownerName = owner
    )

    private val officialDeck = deck(1, "考研5500", official = true, visibility = "public")
    private val myPrivate = deck(2, "我的生词本", mine = true, owner = "me")
    private val myPublic = deck(3, "我公开的", mine = true, visibility = "public", owner = "me")
    private val otherPublic = deck(4, "别人的公开", visibility = "public", owner = "someone")

    private fun state() = CloudDecksUiState(
        loggedIn = true,
        userName = "me",
        // 服务端 all 只会给自己看得到的：官方 + 别人公开的 + 自己的，别人的私有表根本不会下发
        decks = listOf(officialDeck, myPrivate, myPublic, otherPublic)
    ).also { assertFalse("别人的私有表不应该出现在列表里", it.decks.any { d -> d.id == 5L }) }

    @Test
    fun `计数按归属分`() {
        val s = state()
        assertEquals(1, s.officialCount)
        assertEquals("只统计别人公开的", 1, s.publicCount)
        assertEquals("自己两张都算我的", 2, s.mineCount)
    }

    @Test
    fun `我的页签包含自己公开的那张`() {
        val s = state()
        val mine = s.copy(scope = CloudScope.MINE).visibleDecks.map { it.id }
        assertTrue(mine.contains(3L))
        assertTrue(mine.contains(2L))
        assertFalse(mine.contains(4L))
        assertFalse(mine.contains(1L))
    }

    @Test
    fun `公开页签不含自己的公开表`() {
        val s = state()
        val pub = s.copy(scope = CloudScope.PUBLIC).visibleDecks.map { it.id }
        assertEquals(listOf(4L), pub)
    }

    @Test
    fun `公开之后仍然算我的、仍然可编辑`() {
        val publicMine = myPublic
        assertTrue(publicMine.mine)
        // App 只读服务端的 mine，不再拿 ownerName 比
        val s = CloudDecksUiState(loggedIn = true, userName = "", decks = listOf(publicMine))
        assertEquals(1, s.mineCount)
        assertEquals(0, s.publicCount)
        assertTrue(s.isMine(publicMine))
    }
}
