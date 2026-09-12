package com.alcohol.englishstudying.data.imports

/** 源文件格式。 */
enum class ImportFormat { CSV, JSON }

/** 原始网格里的一行，单元格顺序与 [ImportPreview.headers] 对齐。 */
data class RawRow(
    val rowNumber: Int,
    val cells: List<String>
)

/** 按当前列映射归一后的一行。 */
data class ImportRow(
    val rowNumber: Int,
    val values: Map<ImportField, String>
) {
    fun value(field: ImportField): String? = values[field]?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * 解析结果，供导入向导的「列映射 + 预览」界面使用。
 *
 * 保留原始网格 [rawRows] 而不是只留映射后的结果，这样用户在向导里改列映射时
 * 可以直接 [withMapping] 重新归一，不必回头再解析一遍文件。
 */
data class ImportPreview(
    val format: ImportFormat,
    /** 源文件的原始列名（JSON 取键名并集）。 */
    val headers: List<String>,
    /** 与 [headers] 一一对应；null 表示该列未被映射，导入时忽略。 */
    val mapping: List<ImportField?>,
    val rawRows: List<RawRow>,
    val hasHeaderRow: Boolean
) {
    val headwordMapped: Boolean get() = mapping.any { it == ImportField.HEADWORD }

    val dataRowCount: Int get() = rawRows.size

    /** 用户改了列映射之后重新归一，不重新读文件。 */
    fun withMapping(newMapping: List<ImportField?>): ImportPreview = copy(mapping = newMapping)

    /** 按 [mapping] 把原始网格转成归一后的行。 */
    fun rows(): List<ImportRow> = rawRows.map { raw ->
        ImportRow(
            rowNumber = raw.rowNumber,
            values = headers.indices.mapNotNull { column ->
                val field = mapping.getOrNull(column) ?: return@mapNotNull null
                val text = raw.cells.getOrNull(column).orEmpty().trim()
                if (text.isEmpty()) null else field to text
            }.toMap()
        )
    }

    /** 前 [limit] 行，给预览表格用。 */
    fun previewRows(limit: Int = 20): List<ImportRow> = rows().take(limit)
}

sealed interface ParseOutcome {
    data class Success(val preview: ImportPreview) : ParseOutcome
    data class Failure(val message: String) : ParseOutcome
}

/** 校验或写入时发现的问题，直接展示给用户定位。 */
data class ImportIssue(
    val rowNumber: Int,
    val message: String
) {
    val display: String get() = "第 $rowNumber 行：$message"
}

data class ImportResult(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
    val deckName: String?,
    val issues: List<ImportIssue>
) {
    val total: Int get() = inserted + updated
}
