package com.alcohol.englishstudying.data.repo

import androidx.room.withTransaction
import com.alcohol.englishstudying.data.local.AppDatabase
import com.alcohol.englishstudying.data.local.UserTagEntity
import com.alcohol.englishstudying.data.local.UserTagWithCount
import com.alcohol.englishstudying.data.local.WordUserTagEntity
import kotlinx.coroutines.flow.Flow

/**
 * 用户自定义标签（易混 / 写作 / 听力错……）。
 *
 * 和 `WordRepository` 里那些 `words.tags` 的读写是两套东西：那一列是词库自带的
 * 系统标签（L0–L3、真题高频、考研……），这一套只属于用户自己。
 */
class UserTagRepository(
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis
) {

    fun observeAll(): Flow<List<UserTagWithCount>> = db.userTagDao().observeWithCount()

    suspend fun ofWord(wordId: Long): List<UserTagEntity> = db.userTagDao().ofWord(wordId)

    suspend fun all(): List<UserTagEntity> = db.userTagDao().all()

    suspend fun allWithCount(): List<UserTagWithCount> = db.userTagDao().allWithCount()

    /**
     * 覆盖一个词的标签，**按名字**给：库里没有的标签当场建。
     *
     * 弹窗里用户看到、输入的都是名字，不该先让他去「标签管理」建好再回来勾。
     * 名字比较忽略大小写（`Writing` 和 `writing` 视为同一个），落库时保留第一次的写法。
     */
    suspend fun setWordTags(wordId: Long, names: List<String>) {
        val cleaned = names
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        db.withTransaction {
            db.userTagDao().clearLinks(wordId)
            cleaned.forEach { name ->
                val tag = ensureTag(name)
                db.userTagDao().link(WordUserTagEntity(wordId = wordId, tagId = tag.id))
            }
        }
    }

    /** 按名字拿标签，没有就建一个。 */
    suspend fun ensureTag(name: String): UserTagEntity {
        val trimmed = name.trim()
        db.userTagDao().byName(trimmed)?.let { return it }
        val id = db.userTagDao().insert(UserTagEntity(name = trimmed, createdAt = now()))
        // 并发插入撞上唯一索引时 insert 返回 -1，回查即可
        if (id > 0) return UserTagEntity(id = id, name = trimmed, createdAt = now())
        return db.userTagDao().byName(trimmed)
            ?: error("标签「$trimmed」创建失败")
    }

    suspend fun create(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (db.userTagDao().byName(trimmed) != null) return false
        return db.userTagDao().insert(UserTagEntity(name = trimmed, createdAt = now())) > 0
    }

    /** 改名。返回 false 表示空名或与现有标签重名。 */
    suspend fun rename(id: Long, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val existing = db.userTagDao().byName(trimmed)
        if (existing != null && existing.id != id) return false
        db.userTagDao().rename(id, trimmed)
        return true
    }

    /**
     * 删标签。`word_user_tags` 上的外键是 CASCADE，挂载关系会跟着一起清掉；
     * 词条本身一个字都不动。
     */
    suspend fun delete(id: Long) = db.userTagDao().delete(id)
}
