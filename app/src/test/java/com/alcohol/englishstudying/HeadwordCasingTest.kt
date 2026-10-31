package com.alcohol.englishstudying

import com.alcohol.englishstudying.data.imports.preferredHeadwordCasing
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 导入合并时的词头大小写取舍。规则是「大写是信息，小写是缺省」：
 * 能把小写修成大写，但绝不能让大写被小写洗掉。
 */
class HeadwordCasingTest {

    @Test
    fun `已有小写时采用新来的大写`() {
        assertEquals("England", preferredHeadwordCasing("england", "England"))
        assertEquals("China", preferredHeadwordCasing("china", "China"))
        assertEquals("DNA", preferredHeadwordCasing("dna", "DNA"))
    }

    @Test
    fun `已有大写时不被小写降级`() {
        assertEquals("China", preferredHeadwordCasing("China", "china"))
        assertEquals("England", preferredHeadwordCasing("England", "england"))
    }

    @Test
    fun `拼写相同时保持原样`() {
        assertEquals("Japan", preferredHeadwordCasing("Japan", "Japan"))
        assertEquals("abandon", preferredHeadwordCasing("abandon", "abandon"))
    }

    @Test
    fun `大小写不同的两个词不互相覆盖`() {
        // 调用方只在忽略大小写相等时才用它，这里兜底：真的不同就保留已有的
        assertEquals("abandon", preferredHeadwordCasing("abandon", "abandoned"))
    }
}
