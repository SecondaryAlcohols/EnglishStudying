package com.alcohol.englishstudying.data.repo

import com.alcohol.englishstudying.data.local.AppDatabase
import com.alcohol.englishstudying.data.local.DeckEntity
import com.alcohol.englishstudying.data.local.DeckWithCount
import com.alcohol.englishstudying.data.local.DeckWordEntity
import com.alcohol.englishstudying.data.local.WordEntity
import kotlinx.coroutines.flow.Flow

class DeckRepository(
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis
) {

    fun observeAll(): Flow<List<DeckWithCount>> = db.deckDao().observeAllWithCount()

    suspend fun getById(id: Long): DeckEntity? = db.deckDao().getById(id)

    suspend fun findByName(name: String): DeckEntity? = db.deckDao().findByName(name.trim())

    /** 名字重复时返回已存在词表的 id，不新建。 */
    suspend fun createOrGet(name: String, description: String? = null): Long {
        val trimmed = name.trim()
        db.deckDao().findByName(trimmed)?.let { return it.id }
        val inserted = db.deckDao().insert(
            DeckEntity(name = trimmed, description = description, createdAt = now())
        )
        // insert 用了 IGNORE，并发下可能返回 -1，此时再查一次
        return if (inserted > 0L) inserted else db.deckDao().findByName(trimmed)?.id ?: -1L
    }

    suspend fun rename(id: Long, name: String, description: String?) =
        db.deckDao().rename(id, name.trim(), description)

    suspend fun delete(id: Long) = db.deckDao().deleteById(id)

    suspend fun addWord(deckId: Long, wordId: Long) =
        db.deckDao().addWords(listOf(DeckWordEntity(deckId, wordId)))

    suspend fun removeWord(deckId: Long, wordId: Long) =
        db.deckDao().removeWord(deckId, wordId)

    suspend fun decksForWord(wordId: Long): List<DeckEntity> =
        db.deckDao().decksForWord(wordId)

    suspend fun wordsIn(deckId: Long): List<WordEntity> {
        val ids = db.deckDao().wordIdsIn(deckId)
        return if (ids.isEmpty()) emptyList() else db.wordDao().getByIds(ids)
    }
}
