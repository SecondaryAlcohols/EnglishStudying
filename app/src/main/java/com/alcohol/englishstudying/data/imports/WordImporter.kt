package com.alcohol.englishstudying.data.imports

import androidx.room.withTransaction
import com.alcohol.englishstudying.data.local.AppDatabase
import com.alcohol.englishstudying.data.local.DeckEntity
import com.alcohol.englishstudying.data.local.DeckWordEntity
import com.alcohol.englishstudying.data.local.WordEntity

/** 归一后、准备写库的一条词。 */
data class ImportedWord(
    val headword: String,
    val phonetic: String?,
    val partOfSpeech: String?,
    val meanings: String?,
    val mnemonic: String?,
    val roots: String?,
    val derivations: String?,
    val examples: String?,
    val synonyms: String?,
    val tags: String?,
    val frequency: Int,
    val deckName: String?
)

/**
 * 把归一后的行写进数据库。
 *
 * 两条重要约定：
 *  1. **已存在的词走 UPDATE 而不是 REPLACE。** REPLACE 会删掉旧行再插新行，
 *     新行拿到新 id，外键级联会把该词的复习进度和作答记录一起抹掉 ——
 *     用户重新导入一次修订版词库就丢掉几个月的学习记录，这是不能接受的。
 *  2. **空值不覆盖已有内容。** 导入文件里某列为空表示「这次没提供」，
 *     而不是「把已有内容清掉」，否则用户手工补的巧记会被一次导入清空。
 */
class WordImporter(
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis
) {

    suspend fun import(
        rows: List<ImportRow>,
        defaultDeckName: String? = null
    ): ImportResult {
        val issues = mutableListOf<ImportIssue>()
        val accepted = mutableListOf<ImportedWord>()
        val firstSeenAtLine = mutableMapOf<String, Int>()

        rows.forEach { row ->
            val headword = row.value(ImportField.HEADWORD)
            if (headword == null) {
                issues += ImportIssue(row.rowNumber, "缺少单词，已跳过")
                return@forEach
            }
            if (headword.length > MAX_HEADWORD_LENGTH) {
                issues += ImportIssue(
                    row.rowNumber,
                    "单词长度 ${headword.length} 超过上限 $MAX_HEADWORD_LENGTH，已跳过"
                )
                return@forEach
            }

            val oversized = ImportField.entries.firstOrNull { field ->
                field != ImportField.HEADWORD &&
                    (row.value(field)?.length ?: 0) > MAX_FIELD_LENGTH
            }
            if (oversized != null) {
                issues += ImportIssue(
                    row.rowNumber,
                    "${oversized.displayName} 超过 $MAX_FIELD_LENGTH 字，已跳过"
                )
                return@forEach
            }

            val key = headword.lowercase()
            val previousLine = firstSeenAtLine[key]
            if (previousLine != null) {
                issues += ImportIssue(row.rowNumber, "与第 $previousLine 行的「$headword」重复，已跳过")
                return@forEach
            }
            firstSeenAtLine[key] = row.rowNumber
            accepted += row.toImportedWord(headword)
        }

        if (accepted.isEmpty()) {
            return ImportResult(
                inserted = 0,
                updated = 0,
                skipped = issues.size,
                deckName = null,
                issues = issues
            )
        }

        val timestamp = now()
        var inserted = 0
        var updated = 0
        var deckLabel: String? = null

        db.withTransaction {
            val existingByLowercase = mutableMapOf<String, WordEntity>()
            // 分块发查询：一次导入几千行，不分块会撞 SQLite 的绑定变量上限
            accepted.map { it.headword.lowercase() }
                .distinct()
                .chunked(AppDatabase.SQLITE_BIND_LIMIT)
                .forEach { chunk ->
                    db.wordDao().findByLowercaseHeadwords(chunk).forEach { word ->
                        existingByLowercase[word.headword.lowercase()] = word
                    }
                }

            val toInsert = mutableListOf<WordEntity>()
            val toUpdate = mutableListOf<WordEntity>()

            accepted.forEach { incoming ->
                val existing = existingByLowercase[incoming.headword.lowercase()]
                if (existing == null) {
                    toInsert += incoming.toEntity(timestamp)
                } else {
                    toUpdate += incoming.mergeInto(existing, timestamp)
                }
            }

            // insertAll 返回的 rowid 与传入顺序一致，正好用来建词表关联
            val insertedIds: List<Long> =
                if (toInsert.isEmpty()) emptyList() else db.wordDao().insertAll(toInsert)
            if (toUpdate.isNotEmpty()) db.wordDao().updateAll(toUpdate)

            inserted = insertedIds.size
            updated = toUpdate.size

            // 按行序把 id 收齐：新增的用 rowid，更新的用原有 id
            var newIndex = 0
            val allIds = accepted.map { incoming ->
                val existing = existingByLowercase[incoming.headword.lowercase()]
                if (existing == null) insertedIds[newIndex++] else existing.id
            }

            deckLabel = assignDecks(accepted, allIds, defaultDeckName, timestamp)
        }

        return ImportResult(
            inserted = inserted,
            updated = updated,
            skipped = issues.size,
            deckName = deckLabel,
            issues = issues
        )
    }

    /** 返回写入了几个词表，用于结果页文案。 */
    private suspend fun assignDecks(
        accepted: List<ImportedWord>,
        wordIds: List<Long>,
        defaultDeckName: String?,
        timestamp: Long
    ): String? {
        val byDeck = LinkedHashMap<String, MutableList<Long>>()
        accepted.forEachIndexed { index, word ->
            val name = word.deckName?.takeIf { it.isNotBlank() } ?: defaultDeckName
            if (name != null) byDeck.getOrPut(name.trim()) { mutableListOf() } += wordIds[index]
        }
        if (byDeck.isEmpty()) return null

        byDeck.forEach { (name, ids) ->
            val deckId = db.deckDao().findByName(name)?.id
                ?: db.deckDao().insert(DeckEntity(name = name, createdAt = timestamp))
                    .takeIf { it > 0L }
                ?: db.deckDao().findByName(name)?.id
                ?: return@forEach

            db.deckDao().addWords(ids.map { DeckWordEntity(deckId = deckId, wordId = it) })
        }

        return if (byDeck.size == 1) byDeck.keys.first() else "${byDeck.size} 个词表"
    }

    private fun ImportRow.toImportedWord(headword: String) = ImportedWord(
        headword = headword,
        phonetic = value(ImportField.PHONETIC),
        partOfSpeech = value(ImportField.PART_OF_SPEECH),
        meanings = value(ImportField.MEANINGS),
        mnemonic = value(ImportField.MNEMONIC),
        roots = value(ImportField.ROOTS),
        derivations = value(ImportField.DERIVATIONS),
        examples = value(ImportField.EXAMPLES),
        synonyms = value(ImportField.SYNONYMS),
        tags = value(ImportField.TAGS),
        frequency = value(ImportField.FREQUENCY).toFrequency(),
        deckName = value(ImportField.DECK)
    )

    /** 词频列可能是 "90"、"90.0"、"高频" —— 解析不出来就当 0，不要让整行失败。 */
    private fun String?.toFrequency(): Int {
        if (this == null) return 0
        toIntOrNull()?.let { return it }
        toDoubleOrNull()?.let { return it.toInt() }
        return 0
    }

    private fun ImportedWord.toEntity(timestamp: Long) = WordEntity(
        headword = headword,
        phonetic = phonetic,
        partOfSpeech = partOfSpeech,
        meanings = meanings,
        mnemonic = mnemonic,
        roots = roots,
        derivations = derivations,
        examples = examples,
        synonyms = synonyms,
        tags = tags,
        frequency = frequency,
        createdAt = timestamp,
        updatedAt = timestamp
    )

    private fun ImportedWord.mergeInto(existing: WordEntity, timestamp: Long) = existing.copy(
        phonetic = phonetic ?: existing.phonetic,
        partOfSpeech = partOfSpeech ?: existing.partOfSpeech,
        meanings = meanings ?: existing.meanings,
        mnemonic = mnemonic ?: existing.mnemonic,
        roots = roots ?: existing.roots,
        derivations = derivations ?: existing.derivations,
        examples = examples ?: existing.examples,
        synonyms = synonyms ?: existing.synonyms,
        tags = tags ?: existing.tags,
        frequency = if (frequency != 0) frequency else existing.frequency,
        updatedAt = timestamp
    )

    companion object {
        private const val MAX_HEADWORD_LENGTH = 100
        private const val MAX_FIELD_LENGTH = 8000
    }
}
