package com.alcohol.englishstudying.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeckDao {

    @Query(
        """
        SELECT d.id AS id,
               d.name AS name,
               d.description AS description,
               (SELECT COUNT(*) FROM deck_words dw WHERE dw.deckId = d.id) AS wordCount
        FROM decks d
        ORDER BY d.name COLLATE NOCASE ASC
        """
    )
    fun observeAllWithCount(): Flow<List<DeckWithCount>>

    @Query("SELECT * FROM decks WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): DeckEntity?

    @Query("SELECT * FROM decks WHERE id = :id")
    suspend fun getById(id: Long): DeckEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(deck: DeckEntity): Long

    @Query("UPDATE decks SET name = :name, description = :description WHERE id = :id")
    suspend fun rename(id: Long, name: String, description: String?)

    @Query("DELETE FROM decks WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** IGNORE：同一个词重复加进同一个词表时静默跳过，不要让导入整体失败。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addWords(links: List<DeckWordEntity>)

    @Query("DELETE FROM deck_words WHERE deckId = :deckId AND wordId = :wordId")
    suspend fun removeWord(deckId: Long, wordId: Long)

    @Query("SELECT wordId FROM deck_words WHERE deckId = :deckId")
    suspend fun wordIdsIn(deckId: Long): List<Long>

    @Query(
        """
        SELECT d.* FROM decks d
        INNER JOIN deck_words dw ON dw.deckId = d.id
        WHERE dw.wordId = :wordId
        ORDER BY d.name COLLATE NOCASE ASC
        """
    )
    suspend fun decksForWord(wordId: Long): List<DeckEntity>
}
