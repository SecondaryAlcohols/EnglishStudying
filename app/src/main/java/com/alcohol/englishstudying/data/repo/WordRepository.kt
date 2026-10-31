package com.alcohol.englishstudying.data.repo

import com.alcohol.englishstudying.data.local.AppDatabase
import com.alcohol.englishstudying.data.local.WordEntity
import com.alcohol.englishstudying.data.local.WordListRow
import com.alcohol.englishstudying.domain.srs.CardState
import kotlinx.coroutines.flow.Flow

class WordRepository(
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis
) {

    fun observeList(
        query: String = "",
        deckId: Long? = null,
        stateFilter: CardState? = null,
        priorityOnly: Boolean = false
    ): Flow<List<WordListRow>> =
        db.wordDao().observeList(query.trim(), deckId, stateFilter, priorityOnly)

    fun observeCount(deckId: Long? = null): Flow<Int> = db.wordDao().observeCount(deckId)

    /**
     * 学习范围内还没学过的词数。
     *
     * 定义是「没有 review_states 行」，不能用「总数 - 已学」去减 ——
     * 挂起的词也有状态行，减法会把它误算成新词，统计页的数字就对不上了。
     */
    fun observeNewCount(deckId: Long? = null): Flow<Int> = db.wordDao().observeNewCount(deckId)

    fun observeById(id: Long): Flow<WordEntity?> = db.wordDao().observeById(id)

    suspend fun getById(id: Long): WordEntity? = db.wordDao().getById(id)

    /**
     * 词名（需已小写）→ 词 id，大小写不敏感，只含库中真实存在的词。
     * 供「变形」文本把提到的词变成可点击跳转 —— 点不动的词条没有意义。
     */
    suspend fun idsByHeadwords(lowercaseHeadwords: Collection<String>): Map<String, Long> {
        if (lowercaseHeadwords.isEmpty()) return emptyMap()
        // 分块：输入是单个词的「变形」文本切出来的 token，正常只有几十个，但那个字段
        // 本身允许到 8000 字符（见 WordImporter.MAX_FIELD_LENGTH），病理输入能凑到
        // 上千个 token，整条塞进 IN (:list) 就是崩。
        return lowercaseHeadwords.toList()
            .chunked(AppDatabase.SQLITE_BIND_LIMIT)
            .flatMap { db.wordDao().findByLowercaseHeadwords(it) }
            .associate { it.headword.lowercase() to it.id }
    }

    suspend fun count(): Int = db.wordDao().count()

    /** id 为 0 视为新增，否则更新。两种情况都会刷新 updatedAt。 */
    suspend fun save(word: WordEntity): Long {
        val timestamp = now()
        return if (word.id == 0L) {
            db.wordDao().insert(word.copy(createdAt = timestamp, updatedAt = timestamp))
        } else {
            // 编辑页的表单里没有 priority，它构造出来的 WordEntity 会带默认值 false。
            // 更新时沿用库里的值 —— 否则「只改一个音标」也会把优先学悄悄取消。
            val existing = db.wordDao().getById(word.id)
            db.wordDao().update(
                word.copy(
                    priority = existing?.priority ?: word.priority,
                    updatedAt = timestamp
                )
            )
            word.id
        }
    }

    /** 「优先学」开关；只对还没学过的词有意义，调用方负责判断。 */
    suspend fun setPriority(id: Long, priority: Boolean) {
        db.wordDao().setPriority(id, priority, now())
    }

    /** 被抽走之后清掉优先标记。抽词在 [com.alcohol.englishstudying.data.repo.StudyRepository] 里做。 */
    suspend fun clearPriority(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        ids.toList()
            .chunked(AppDatabase.SQLITE_BIND_LIMIT)
            .forEach { db.wordDao().clearPriority(it) }
    }

    suspend fun delete(id: Long) = db.wordDao().deleteById(id)

    suspend fun delete(word: WordEntity) = db.wordDao().delete(word)

    /**
     * 词库里出现过的所有标签，去重并排序。
     * tags 列存的是逗号分隔的字符串，这里在内存里摊平 —— 词库规模下这点开销可以忽略，
     * 换来的是不用为标签单独建一张表。
     */
    suspend fun allTags(): List<String> = db.wordDao().allTagStrings()
        .flatMap { it.split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
}
