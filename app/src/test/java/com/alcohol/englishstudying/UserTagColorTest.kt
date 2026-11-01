package com.alcohol.englishstudying

import com.alcohol.englishstudying.ui.components.userTagColorIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTagColorTest {

    @Test
    fun `同一个标签名永远落在同一个颜色`() {
        repeat(3) {
            assertEquals(userTagColorIndex("写作"), userTagColorIndex("写作"))
        }
    }

    @Test
    fun `不同名字可以不同色，但都落在调色板内`() {
        val names = listOf("易混", "写作", "听力", "重点", "熟词僻义", "translation")
        names.forEach { name ->
            val index = userTagColorIndex(name)
            assertTrue("$name -> $index 越界", index in 0 until 5)
        }
    }

    @Test
    fun `调色板大小可配，不会除零`() {
        assertEquals(0, userTagColorIndex("任意", paletteSize = 0))
        assertEquals(userTagColorIndex("任意", 3), userTagColorIndex("任意", 3))
    }
}
