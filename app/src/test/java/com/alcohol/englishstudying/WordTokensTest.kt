package com.alcohol.englishstudying

import com.alcohol.englishstudying.domain.relations.WordTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class WordTokensTest {

    @Test
    fun `空文本返回空集`() {
        assertEquals(emptySet<String>(), WordTokens.extract(null))
        assertEquals(emptySet<String>(), WordTokens.extract(""))
        assertEquals(emptySet<String>(), WordTokens.extract("   "))
    }

    @Test
    fun `中英混排只提取英文词并转小写`() {
        val text = "absence n. 缺席; Absent-Minded adj. 心不在焉的"
        assertEquals(setOf("absence", "n", "absent-minded", "adj"), WordTokens.extract(text))
    }

    @Test
    fun `撇号和数字开头的片段处理正确`() {
        val text = "doesn't work in 2024"
        assertEquals(setOf("doesn't", "work", "in"), WordTokens.extract(text))
    }

    @Test
    fun `去重`() {
        assertEquals(setOf("use", "used"), WordTokens.extract("use / used / use"))
    }
}
