package com.alcohol.englishstudying

import com.alcohol.englishstudying.data.imports.CsvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvParserTest {

    @Test
    fun `解析普通逗号分隔`() {
        val rows = CsvParser.parse("a,b,c\n1,2,3")

        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b", "c"), rows[0])
        assertEquals(listOf("1", "2", "3"), rows[1])
    }

    @Test
    fun `释义里的逗号被引号保护`() {
        val rows = CsvParser.parse("word,meaning\nabsent,\"缺席的,不在场的\"")

        assertEquals(listOf("absent", "缺席的,不在场的"), rows[1])
    }

    @Test
    fun `引号内的换行不算换行`() {
        val csv = "word,example\nabsent,\"He was absent.\n他缺课了。\""

        val rows = CsvParser.parse(csv)
        assertEquals(2, rows.size)
        assertEquals("He was absent.\n他缺课了。", rows[1][1])
    }

    @Test
    fun `两个连续引号表示一个字面引号`() {
        val rows = CsvParser.parse("word,meaning\nsay,\"他说\"\"你好\"\"\"")

        assertEquals("他说\"你好\"", rows[1][1])
    }

    @Test
    fun `文件开头的 BOM 被去掉`() {
        val rows = CsvParser.parse("﻿word,meaning\nabsent,缺席的")

        assertEquals("word", rows[0][0])
    }

    @Test
    fun `CRLF 换行不会在字段里留下回车`() {
        val rows = CsvParser.parse("word,meaning\r\nabsent,缺席的\r\n")

        assertEquals(2, rows.size)
        assertEquals(listOf("absent", "缺席的"), rows[1])
    }

    @Test
    fun `末尾换行不会多出一行空记录`() {
        val rows = CsvParser.parse("a,b\n1,2\n")

        assertEquals(2, rows.size)
    }

    @Test
    fun `空字段保留为空串`() {
        val rows = CsvParser.parse("a,b,c\n1,,3")

        assertEquals(listOf("1", "", "3"), rows[1])
    }

    @Test
    fun `制表符分隔被自动识别`() {
        val text = "word\tmeaning\nabsent\t缺席的"

        assertEquals('\t', CsvParser.detectDelimiter(text))
        assertEquals(listOf("absent", "缺席的"), CsvParser.parse(text)[1])
    }

    @Test
    fun `分号分隔被自动识别`() {
        val text = "word;meaning\nabsent;缺席的"

        assertEquals(';', CsvParser.detectDelimiter(text))
    }

    @Test
    fun `识别分隔符时忽略引号内的逗号`() {
        // 首行里逗号全在引号内，真正的分隔符是分号。
        // 如果实现不跟踪引号状态，会数出 4 个逗号而误判成逗号分隔。
        val text = "\"a,b,c,d,e\";x\ny;z"

        assertEquals(';', CsvParser.detectDelimiter(text))
    }

    @Test
    fun `没有分隔符时退回逗号`() {
        assertEquals(',', CsvParser.detectDelimiter("word\nabsent"))
    }

    @Test
    fun `空字符串解析为空列表`() {
        assertTrue(CsvParser.parse("").isEmpty())
    }

    @Test
    fun `只有换行符时产出一个空单元格`() {
        // 解析层不做业务过滤，空行的丢弃是 ImportParser 的职责
        assertEquals(listOf(listOf("")), CsvParser.parse("\n"))
    }

    @Test
    fun `只含一个字段没有分隔符也能解析`() {
        val rows = CsvParser.parse("word\nabsent\npresent")

        assertEquals(3, rows.size)
        assertEquals(listOf("absent"), rows[1])
    }
}
