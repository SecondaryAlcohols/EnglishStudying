package com.alcohol.englishstudying.data.imports

import androidx.room.withTransaction
import com.alcohol.englishstudying.data.local.AppDatabase
import com.alcohol.englishstudying.data.local.DeckEntity
import com.alcohol.englishstudying.data.local.DeckWordEntity
import com.alcohol.englishstudying.data.local.WordEntity
import com.alcohol.englishstudying.domain.model.MeaningParser

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
        val acceptedIndexByKey = mutableMapOf<String, Int>()

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
                // 同一份文件里「england」和「England」是同一个词，只留一条 —— 但留**带大写**的那个拼写。
                // 大小写在这里是数据而不是格式：专有名词一旦被压成小写，界面上就再也修不回来了。
                acceptedIndexByKey[key]?.let { index ->
                    val previous = accepted[index]
                    accepted[index] = previous.copy(
                        headword = preferredHeadwordCasing(previous.headword, headword)
                    )
                }
                issues += ImportIssue(row.rowNumber, "与第 $previousLine 行的「$headword」重复，已跳过")
                return@forEach
            }
            firstSeenAtLine[key] = row.rowNumber
            acceptedIndexByKey[key] = accepted.size
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
            val takenHeadwords = existingByLowercase.values.mapTo(mutableSetOf()) { it.headword }

            accepted.forEach { incoming ->
                val existing = existingByLowercase[incoming.headword.lowercase()]
                if (existing == null) {
                    toInsert += incoming.toEntity(timestamp)
                } else {
                    val merged = incoming.mergeInto(existing, timestamp)
                    // 大小写升级要避开唯一索引：库里若同时有「china」和「China」这种历史脏数据，
                    // 把前者改名就会撞上后者，宁可放弃这次改名也不能让整批导入回滚。
                    val safe = if (
                        merged.headword != existing.headword && merged.headword in takenHeadwords
                    ) {
                        merged.copy(headword = existing.headword)
                    } else {
                        merged
                    }
                    toUpdate += safe
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
        // 词性列统一成长写（`a.`→`adj.`、`ad.`→`adv.`、`int.`→`interj.`），
        // 与卡片上的绿框、以及库里已有的数据保持一致；认不出的写法原样保留。
        partOfSpeech = MeaningParser.normalizePos(value(ImportField.PART_OF_SPEECH)),
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
        // 词头本身也参与合并：已有的是全小写、新来的带大写（England），就用新的拼写。
        // 反过来不会降级 —— 否则重新导入一份全小写的旧词库会把 China 又压回 china。
        headword = preferredHeadwordCasing(existing.headword, headword),
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

/**
 * 同一个词（忽略大小写相同）在库里只留一个拼写时，选哪一个。
 *
 * 规则只有一条：**大写是信息，小写是缺省**。
 *  - 旧的是 `england`、新来的是 `England` → 采用 `England`，专有名词的拼写被修正；
 *  - 旧的是 `China`、新来的是 `china` → 保留 `China`，重新导入一份全小写词库不会把大写洗掉；
 *  - 拼写完全相同 → 原样保留。
 *
 * 不做「谁后到谁赢」：那会让同一份词库分两次导入得到不同结果。
 */
internal fun preferredHeadwordCasing(existing: String, incoming: String): String = when {
    !existing.equals(incoming, ignoreCase = true) -> existing
    existing == existing.lowercase() && incoming != incoming.lowercase() -> incoming
    else -> existing
}
