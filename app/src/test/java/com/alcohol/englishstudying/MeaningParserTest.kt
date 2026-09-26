package com.alcohol.englishstudying

import com.alcohol.englishstudying.domain.model.MeaningParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeaningParserTest {

    @Test
    fun `英文缩写词性被解析并剥离`() {
        val item = MeaningParser.parse("n. 礼物；现在")
        assertEquals("n.", item.pos)
        assertEquals("礼物；现在", item.text)
    }

    @Test
    fun `长短相近的缩写不被截胡`() {
        assertEquals("adj.", MeaningParser.parse("adj. 现在的").pos)
        assertEquals("adv.", MeaningParser.parse("adv. 缓慢地").pos)
        assertEquals("interj.", MeaningParser.parse("interj. 哦").pos)
    }

    @Test
    fun `中文词性被解析并去掉后面的标点`() {
        val item = MeaningParser.parse("形容词，能够的;有能力的")
        assertEquals("形容词", item.pos)
        assertEquals("能够的;有能力的", item.text)
    }

    @Test
    fun `中文词性跟空格也算分隔符`() {
        val item = MeaningParser.parse("动词 展示")
        assertEquals("动词", item.pos)
        assertEquals("展示", item.text)
    }

    @Test
    fun `中文词性跟冒号也算分隔符`() {
        val item = MeaningParser.parse("名词：所有格形式")
        assertEquals("名词", item.pos)
        assertEquals("所有格形式", item.text)
    }

    /**
     * 回归：中文词性后面**没有**分隔符时不能剥离，否则会吃掉释义正文开头的词。
     *
     * 「名词所有格」是一个整体、「动词的过去式」里的「动词」也是正文的一部分 ——
     * 原先中文分支什么分隔符都不要求，这两行会被切成 `pos=名词/动词` + 残缺正文，
     * 卡片上显示的词性和释义就都是错的。
     */
    @Test
    fun `中文词性后面没有分隔符时不剥离`() {
        val whole = "名词所有格：表示所属"
        assertNull(MeaningParser.parse(whole).pos)
        assertEquals(whole, MeaningParser.parse(whole).text)

        val past = "动词的过去式"
        assertNull(MeaningParser.parse(past).pos)
        assertEquals(past, MeaningParser.parse(past).text)

        assertNull(MeaningParser.parse("形容词比较级").pos)
        assertNull(MeaningParser.parse("代词的用法").pos)
    }

    /** 整行只有一个词性时，词性照认，正文为空字符串。 */
    @Test
    fun `整行只有一个中文词性时正文为空`() {
        val item = MeaningParser.parse("名词")
        assertEquals("名词", item.pos)
        assertEquals("", item.text)
    }

    @Test
    fun `没有词性的行原样返回`() {
        val item = MeaningParser.parse("缺席的；不在场的")
        assertNull(item.pos)
        assertEquals("缺席的；不在场的", item.text)
    }

    @Test
    fun `没有词性的行保留序号语义不误吞数字`() {
        // 「1. 缺席的」是用户手写的编号，不该被当成词性
        val item = MeaningParser.parse("1. 缺席的")
        assertNull(item.pos)
        assertEquals("1. 缺席的", item.text)
    }

    @Test
    fun `解析整列并丢弃空行`() {
        val items = MeaningParser.parseAll(listOf("n. 缺席", " ", "adj. 心不在焉的"))
        assertEquals(2, items.size)
        assertEquals("n.", items[0].pos)
        assertEquals("adj.", items[1].pos)
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals("v.", MeaningParser.parse("V. 展示").pos)
        assertEquals("n.", MeaningParser.parse("N. 礼物").pos)
    }
}
