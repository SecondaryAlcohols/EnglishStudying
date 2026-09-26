package com.alcohol.englishstudying.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.alcohol.englishstudying.domain.model.WordRelationType

/**
 * 关系的来源。
 *
 * 重建关联时只删除并重算 [AUTO]，用户手工加的 [MANUAL] 不会被碰。
 */
enum class RelationSource {
    AUTO,
    MANUAL;

    companion object {
        fun fromNameOrNull(name: String?): RelationSource? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * 词语之间的关联。
 *
 * 三类关系都是**对称**的，所以写入时统一规范化成 `fromWordId < toWordId`，
 * 读取时两个方向都查。这样不会出现「A 指向 B、B 却不指向 A」的脏数据，
 * 主键也天然去重。
 *
 * 两张外键都指向 words 并级联删除：删掉一个词，它的所有关联一起走。
 */
@Entity(
    tableName = "word_relations",
    primaryKeys = ["fromWordId", "toWordId", "type"],
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["fromWordId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["toWordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["toWordId"])]
)
data class WordRelationEntity(
    val fromWordId: Long,
    val toWordId: Long,
    val type: WordRelationType,
    /** ROOT 专用：共享的词根文本，如 "-fer-"。其余类型为 null。 */
    val label: String? = null,
    val source: RelationSource = RelationSource.AUTO
)

/**
 * 查出来的关联词：关系本体 + 对方词条。
 *
 * 对方词条一定是存在的 —— 外键保证了这点，所以这里不是可空的。
 */
data class RelatedWord(
    @Embedded val relation: WordRelationEntity,
    @Embedded val word: WordEntity
) {
    val type: WordRelationType get() = relation.type
    val label: String? get() = relation.label
}

/**
 * 一对已规范化的词 id（`from < to`）。
 *
 * 规范化集中在这一处，别在别的地方手写 min/max —— 漏一处就会写出一条
 * 反方向的关系，而查询要两个方向都扫，多出来的那条会变成重复显示。
 */
data class WordPair(val from: Long, val to: Long) {
    companion object {
        fun of(a: Long, b: Long): WordPair =
            if (a <= b) WordPair(a, b) else WordPair(b, a)
    }
}
