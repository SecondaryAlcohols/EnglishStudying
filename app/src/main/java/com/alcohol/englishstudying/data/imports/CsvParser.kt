package com.alcohol.englishstudying.data.imports

/**
 * RFC 4180 风格的 CSV 解析。
 *
 * 自己写而不用现成库，是因为要处理的边界很具体：Excel 导出的 BOM 与 CRLF、
 * 释义里带逗号、例句里带换行。这些在真实词库里全都会出现。
 *
 * 已知限制：不支持反斜杠转义引号（`\"`）。RFC 4180 用的是把引号写两遍（`""`），
 * 如果你的数据源是前者，需要在这里加一个分支。
 */
object CsvParser {

    private val CANDIDATE_DELIMITERS = listOf(',', '\t', ';', '|')
    private val BOM = 0xFEFF.toChar()

    /** 按候选分隔符在首行出现的次数投票。Excel 另存为「文本(制表符分隔)」时是 \t。 */
    fun detectDelimiter(text: String): Char {
        val firstLine = firstLogicalLine(text.removePrefix(BOM.toString()))
        var inQuotes = false
        val counts = mutableMapOf<Char, Int>()
        for (ch in firstLine) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                !inQuotes && ch in CANDIDATE_DELIMITERS ->
                    counts[ch] = (counts[ch] ?: 0) + 1
            }
        }
        return CANDIDATE_DELIMITERS.maxByOrNull { counts[it] ?: 0 }
            ?.takeIf { (counts[it] ?: 0) > 0 }
            ?: ','
    }

    fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val source = text.removePrefix(BOM.toString())
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }

        while (index < source.length) {
            val ch = source[index]
            when {
                inQuotes -> when {
                    ch != '"' -> field.append(ch)
                    index + 1 < source.length && source[index + 1] == '"' -> {
                        field.append('"')
                        index++ // 跳过被转义的那个引号
                    }

                    else -> inQuotes = false
                }

                ch == '"' -> inQuotes = true
                ch == delimiter -> endField()
                ch == '\r' -> {
                    if (index + 1 < source.length && source[index + 1] == '\n') index++
                    endRow()
                }

                ch == '\n' -> endRow()
                else -> field.append(ch)
            }
            index++
        }

        // 文件末尾没有换行时补最后一行；但纯粹的尾部空行不要造出一行空记录
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()

        return rows
    }

    /** 取第一「逻辑行」——引号内的换行不算换行。 */
    private fun firstLogicalLine(text: String): String {
        var inQuotes = false
        val builder = StringBuilder()
        for (ch in text) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    builder.append(ch)
                }

                !inQuotes && (ch == '\n' || ch == '\r') -> return builder.toString()
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }
}
