package com.alcohol.englishstudying

import com.alcohol.englishstudying.ui.remote.CloudDecksViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索框要同时干两件事：按名字搜、按分享码找。
 * 判断「这串东西像不像分享码」的规则放在这里钉死。
 */
class CloudDecksCodeTest {

    @Test
    fun `8 位随机码算分享码`() {
        assertTrue(CloudDecksViewModel.looksLikeCode("K7Q29F3M"))
        assertTrue(CloudDecksViewModel.looksLikeCode("k7q2-9f3m"))
        assertTrue(CloudDecksViewModel.looksLikeCode(" K7Q2 9F3M "))
    }

    @Test
    fun `自定义码也算`() {
        assertTrue(CloudDecksViewModel.looksLikeCode("WRITING-01"))
        assertTrue(CloudDecksViewModel.looksLikeCode("abcd"))
    }

    @Test
    fun `中文和太短的输入不当码`() {
        assertFalse(CloudDecksViewModel.looksLikeCode("写作"))
        assertFalse(CloudDecksViewModel.looksLikeCode("考研5500 词汇"))
        assertFalse(CloudDecksViewModel.looksLikeCode("abc"))
        assertFalse(CloudDecksViewModel.looksLikeCode("   "))
        assertFalse(CloudDecksViewModel.looksLikeCode(""))
    }

    @Test
    fun `带标点的不当码`() {
        assertFalse(CloudDecksViewModel.looksLikeCode("kaoyan_5500"))
        assertFalse(CloudDecksViewModel.looksLikeCode("a.b.c.d"))
    }
}
