package com.alcohol.englishstudying

import com.alcohol.englishstudying.data.imports.ImportField
import com.alcohol.englishstudying.data.imports.ImportFormat
import com.alcohol.englishstudying.data.imports.ImportParser
import com.alcohol.englishstudying.data.imports.ParseOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportParserTest {

    private fun success(text: String, hasHeader: Boolean = true) =
        (ImportParser.parseCsv(text, hasHeader) as ParseOutcome.Success).preview

    // ---- 列名猜测 ----

    @Test
    fun `常见英文列名被自动识别`() {
        val preview = success("word,phonetic,pos,meaning\nabsent,/ˈæbsənt/,adj.,缺席的")

        assertEquals(ImportField.HEADWORD, preview.mapping[0])
        assertEquals(ImportField.PHONETIC, preview.mapping[1])
        assertEquals(ImportField.PART_OF_SPEECH, preview.mapping[2])
        assertEquals(ImportField.MEANINGS, preview.mapping[3])
        assertTrue(preview.headwordMapped)
    }

    @Test
    fun `中文列名被自动识别`() {
        val preview = success("单词,释义,巧记,词根词缀,变形\nabsent,缺席的,谐音,ab-,absence")

        assertEquals(ImportField.HEADWORD, preview.mapping[0])
        assertEquals(ImportField.MEANINGS, preview.mapping[1])
        assertEquals(ImportField.MNEMONIC, preview.mapping[2])
        assertEquals(ImportField.ROOTS, preview.mapping[3])
        assertEquals(ImportField.DERIVATIONS, preview.mapping[4])
    }

    @Test
    fun `列名的大小写下划线和空格都能容忍`() {
        val preview = success("Word,Part Of Speech,MNEMONIC\nabsent,adj.,谐音")

        assertEquals(ImportField.HEADWORD, preview.mapping[0])
        assertEquals(ImportField.PART_OF_SPEECH, preview.mapping[1])
        assertEquals(ImportField.MNEMONIC, preview.mapping[2])
    }

    @Test
    fun `认不出的列映射为 null，不会瞎猜`() {
        val preview = success("word,uid,remark\nabsent,1,无")

        assertEquals(ImportField.HEADWORD, preview.mapping[0])
        assertNull(preview.mapping[1])
    }

    // ---- 行解析 ----

    @Test
    fun `空单元格不会进入字段表`() {
        val preview = success("word,phonetic,meaning\nabsent,,缺席的")

        val row = preview.rows().first()
        assertNull(row.value(ImportField.PHONETIC))
        assertEquals("缺席的", row.value(ImportField.MEANINGS))
    }

    @Test
    fun `全空行被过滤掉`() {
        val preview = success("word,meaning\nabsent,缺席的\n,\npresent,现在的")

        assertEquals(2, preview.dataRowCount)
    }

    @Test
    fun `中间夹了空行时，报出的行号仍与表格软件一致`() {
        // 第 3 行是空的被丢掉，但 present 仍应报第 4 行，不能变成第 3 行
        val preview = success("word,meaning\nabsent,缺席的\n,\npresent,现在的")

        assertEquals(listOf(2, 4), preview.rows().map { it.rowNumber })
    }

    @Test
    fun `数据行的行号从 2 开始，对得上用户在表格软件里看到的行`() {
        val preview = success("word,meaning\nabsent,缺席的\npresent,现在的")

        assertEquals(listOf(2, 3), preview.rows().map { it.rowNumber })
    }

    @Test
    fun `关掉表头后第一行也当作数据，行号从 1 开始`() {
        val preview = success("absent,缺席的\npresent,现在的", hasHeader = false)

        assertEquals(2, preview.dataRowCount)
        assertEquals(1, preview.rows().first().rowNumber)
        // 没有表头时不猜列名，交给用户手动指定
        assertTrue(preview.mapping.all { it == null })
        assertFalse(preview.headwordMapped)
    }

    @Test
    fun `改了映射之后重新归一，用的是同一份原始数据`() {
        val preview = success("word,colA,colB\nabsent,巧记内容,词根内容")
        assertNull(preview.mapping[1])

        val remapped = preview.withMapping(
            listOf(ImportField.HEADWORD, ImportField.MNEMONIC, ImportField.ROOTS)
        )

        val row = remapped.rows().first()
        assertEquals("巧记内容", row.value(ImportField.MNEMONIC))
        assertEquals("词根内容", row.value(ImportField.ROOTS))
    }

    @Test
    fun `只有表头没有数据行时返回失败`() {
        val outcome = ImportParser.parseCsv("word,meaning")

        assertTrue(outcome is ParseOutcome.Failure)
    }

    @Test
    fun `空文件返回失败`() {
        assertTrue(ImportParser.parseCsv("") is ParseOutcome.Failure)
    }

    // ---- JSON ----

    @Test
    fun `JSON 数组按键名映射`() {
        val json = """
            [
              {"word": "absent", "meaning": "缺席的", "mnemonic": "谐音"},
              {"word": "present", "meaning": "现在的"}
            ]
        """.trimIndent()

        val preview = (ImportParser.parseJson(json) as ParseOutcome.Success).preview

        assertEquals(ImportFormat.JSON, preview.format)
        assertEquals(2, preview.dataRowCount)
        assertEquals(ImportField.HEADWORD, preview.mapping[preview.headers.indexOf("word")])
        assertEquals("谐音", preview.rows().first().value(ImportField.MNEMONIC))
    }

    @Test
    fun `JSON 里键不一致时取并集，缺的键留空`() {
        val json = """[{"word":"a","mnemonic":"x"},{"word":"b","roots":"y"}]"""

        val preview = (ImportParser.parseJson(json) as ParseOutcome.Success).preview

        assertTrue(preview.headers.containsAll(listOf("word", "mnemonic", "roots")))
        val rows = preview.rows()
        assertNull(rows[0].value(ImportField.ROOTS))
        assertNull(rows[1].value(ImportField.MNEMONIC))
        assertNotNull(rows[1].value(ImportField.ROOTS))
    }

    @Test
    fun `JSON 数字和布尔值被转成字符串`() {
        val json = """[{"word":"absent","frequency":90}]"""

        val preview = (ImportParser.parseJson(json) as ParseOutcome.Success).preview

        assertEquals("90", preview.rows().first().value(ImportField.FREQUENCY))
    }

    @Test
    fun `JSON 里的 null 值当作空`() {
        val json = """[{"word":"absent","mnemonic":null}]"""

        val preview = (ImportParser.parseJson(json) as ParseOutcome.Success).preview

        assertNull(preview.rows().first().value(ImportField.MNEMONIC))
    }

    @Test
    fun `非数组的 JSON 返回失败而不是崩溃`() {
        assertTrue(ImportParser.parseJson("""{"word":"absent"}""") is ParseOutcome.Failure)
        assertTrue(ImportParser.parseJson("not json at all") is ParseOutcome.Failure)
    }

    @Test
    fun `按内容嗅探格式`() {
        assertEquals(ImportFormat.JSON, ImportParser.detectFormat("  [{\"a\":1}]"))
        assertEquals(ImportFormat.JSON, ImportParser.detectFormat("﻿[{\"a\":1}]"))
        assertEquals(ImportFormat.CSV, ImportParser.detectFormat("word,meaning\nabsent,缺席的"))
    }
}
