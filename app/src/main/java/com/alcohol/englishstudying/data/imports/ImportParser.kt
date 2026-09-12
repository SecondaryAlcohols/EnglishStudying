package com.alcohol.englishstudying.data.imports

import org.json.JSONArray
import org.json.JSONObject

private val BOM = 0xFEFF.toChar().toString()

/**
 * 把源文件文本解析成 [ImportPreview]。
 *
 * CSV 与 JSON 最终都归一到同一套原始网格，后续的列映射、校验与入库只写一遍。
 */
object ImportParser {

    /** 按内容嗅探格式：JSON 一定以 `[` 或 `{` 开头，其余按 CSV 处理。 */
    fun detectFormat(text: String): ImportFormat {
        val head = text.removePrefix(BOM).trimStart()
        return if (head.startsWith("[") || head.startsWith("{")) ImportFormat.JSON
        else ImportFormat.CSV
    }

    fun parse(
        text: String,
        format: ImportFormat = detectFormat(text),
        hasHeaderRow: Boolean = true
    ): ParseOutcome = when (format) {
        ImportFormat.CSV -> parseCsv(text, hasHeaderRow)
        ImportFormat.JSON -> parseJson(text)
    }

    fun parseCsv(text: String, hasHeaderRow: Boolean = true): ParseOutcome {
        val grid = CsvParser.parse(text)
        if (grid.isEmpty()) return ParseOutcome.Failure("文件是空的，没有解析到任何行。")

        val headers: List<String>
        val dataCells: List<List<String>>
        val firstDataLine: Int

        if (hasHeaderRow) {
            if (grid.size <= 1) return ParseOutcome.Failure("文件只有表头，没有数据行。")
            headers = grid.first().map { it.trim() }
            dataCells = grid.drop(1)
            firstDataLine = 2
        } else {
            // 没有表头时用「第 N 列」占位，用户在向导里自己指定含义
            headers = (1..grid.maxOf { it.size }).map { "第 $it 列" }
            dataCells = grid
            firstDataLine = 1
        }

        // 空行在这里才丢弃，但行号仍按原始下标算 —— 否则中间夹了空行之后，
        // 报给用户的行号会和他打开表格软件看到的对不上
        val rawRows = dataCells.mapIndexedNotNull { index, cells ->
            if (cells.all { it.isBlank() }) null
            else RawRow(rowNumber = firstDataLine + index, cells = cells)
        }
        if (rawRows.isEmpty()) return ParseOutcome.Failure("文件里没有有效的数据行。")

        return ParseOutcome.Success(
            ImportPreview(
                format = ImportFormat.CSV,
                headers = headers,
                mapping = if (hasHeaderRow) headers.map { ImportField.guess(it) } else headers.map { null },
                rawRows = rawRows,
                hasHeaderRow = hasHeaderRow
            )
        )
    }

    fun parseJson(text: String): ParseOutcome {
        val root = runCatching { JSONArray(text.removePrefix(BOM)) }.getOrNull()
            ?: return ParseOutcome.Failure(
                "JSON 解析失败。需要一个对象数组，形如 [{\"word\": \"absent\"}, ...]。"
            )

        if (root.length() == 0) return ParseOutcome.Failure("JSON 数组是空的。")

        // 键名并集，按首次出现顺序 —— 不同对象的键可能不一致
        val headers = LinkedHashSet<String>()
        val objects = mutableListOf<JSONObject>()
        for (index in 0 until root.length()) {
            val obj = root.optJSONObject(index)
                ?: return ParseOutcome.Failure("第 ${index + 1} 个元素不是对象。")
            objects.add(obj)
            obj.keys().forEach { headers.add(it) }
        }

        val headerList = headers.toList()

        return ParseOutcome.Success(
            ImportPreview(
                format = ImportFormat.JSON,
                headers = headerList,
                mapping =headerList.map { ImportField.guess(it) },
                rawRows = objects.mapIndexed { index, obj ->
                    RawRow(
                        rowNumber = index + 1,
                        cells = headerList.map { header ->
                            val value = obj.opt(header)
                            if (value == null || value == JSONObject.NULL) "" else value.toString()
                        }
                    )
                },
                hasHeaderRow = true
            )
        )
    }
}
