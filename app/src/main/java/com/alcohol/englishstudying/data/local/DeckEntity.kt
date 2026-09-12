package com.alcohol.englishstudying.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 词表。导入一个文件默认建一个，这样不同来源的词库可以分开背。
 */
@Entity(
    tableName = "decks",
    indices = [Index(value = ["name"], unique = true)]
)
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String? = null,
    val createdAt: Long = 0L
)

/** 词表与词条的多对多关系。 */
@Entity(
    tableName = "deck_words",
    primaryKeys = ["deckId", "wordId"],
    foreignKeys = [
        ForeignKey(
            entity = DeckEntity::class,
            parentColumns = ["id"],
            childColumns = ["deckId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["wordId"])]
)
data class DeckWordEntity(
    val deckId: Long,
    val wordId: Long
)

/** 词表 + 词数，列表页用。 */
data class DeckWithCount(
    val id: Long,
    val name: String,
    val description: String?,
    val wordCount: Int
)
