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
        stateFilter: CardState? = null
    ): Flow<List<WordListRow>> = db.wordDao().observeList(query.trim(), deckId, stateFilter)

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

    suspend fun count(): Int = db.wordDao().count()

    /** id 为 0 视为新增，否则更新。两种情况都会刷新 updatedAt。 */
    suspend fun save(word: WordEntity): Long {
        val timestamp = now()
        return if (word.id == 0L) {
            db.wordDao().insert(word.copy(createdAt = timestamp, updatedAt = timestamp))
        } else {
            db.wordDao().update(word.copy(updatedAt = timestamp))
            word.id
        }
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
