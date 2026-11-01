package com.alcohol.englishstudying.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserTagDao {

    /**
     * 「我的标签」：标签 + 挂了多少词。
     *
     * `LEFT JOIN` 是必须的 —— 刚建好还没挂词的标签也要出现，否则新建完就看不见，
     * 用户会以为没建上。
     */
    @Query(
        """
        SELECT t.id AS id, t.name AS name, COUNT(wt.wordId) AS wordCount
        FROM user_tags t
        LEFT JOIN word_user_tags wt ON wt.tagId = t.id
        GROUP BY t.id, t.name
        ORDER BY wordCount DESC, t.name COLLATE NOCASE ASC
        """
    )
    fun observeWithCount(): Flow<List<UserTagWithCount>>

    @Query("SELECT * FROM user_tags ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<UserTagEntity>

    /** [observeWithCount] 的一次性版本，给弹窗同步取候选用。 */
    @Query(
        """
        SELECT t.id AS id, t.name AS name, COUNT(wt.wordId) AS wordCount
        FROM user_tags t
        LEFT JOIN word_user_tags wt ON wt.tagId = t.id
        GROUP BY t.id, t.name
        ORDER BY wordCount DESC, t.name COLLATE NOCASE ASC
        """
    )
    suspend fun allWithCount(): List<UserTagWithCount>

    @Query("SELECT * FROM user_tags WHERE id = :id")
    suspend fun byId(id: Long): UserTagEntity?

    /** 按名字查（忽略大小写），用于「同名标签不重复创建」。 */
    @Query("SELECT * FROM user_tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): UserTagEntity?

    /** 名字冲突时返回 -1（唯一索引挡住了），调用方随即回查拿 id。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: UserTagEntity): Long

    @Query("UPDATE user_tags SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM user_tags WHERE id = :id")
    suspend fun delete(id: Long)

    /** 这个词的标签（按名字排），弹窗打开时用。 */
    @Query(
        """
        SELECT t.* FROM user_tags t
        JOIN word_user_tags wt ON wt.tagId = t.id
        WHERE wt.wordId = :wordId
        ORDER BY t.name COLLATE NOCASE ASC
        """
    )
    suspend fun ofWord(wordId: Long): List<UserTagEntity>

    @Query("SELECT tagId FROM word_user_tags WHERE wordId = :wordId")
    suspend fun tagIdsOfWord(wordId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(link: WordUserTagEntity)

    @Query("DELETE FROM word_user_tags WHERE wordId = :wordId")
    suspend fun clearLinks(wordId: Long)

    @Query("DELETE FROM word_user_tags WHERE tagId = :tagId")
    suspend fun clearTagLinks(tagId: Long)
}
