package com.alcohol.englishstudying.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户自定义标签（易混 / 写作 / 听力错……）。
 *
 * **和 `words.tags` 不是一回事**：那一列装的是词库自带的系统标签（L0–L3、真题高频、
 * 考研、牛津3000、派生词……），由导入的数据决定，用户不该去改；这里的标签只由用户
 * 自己在「加标签」里创建，用来按自己的方式组织词库，同时用来筛选和搜索。
 *
 * 名字唯一（忽略大小写由仓库层保证）：同一个标签名不产生两条记录。
 */
@Entity(tableName = "user_tags", indices = [Index(value = ["name"], unique = true)])
data class UserTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long = 0L
)

/** 词 ↔ 用户标签的多对多关系。删词或删标签都会级联清掉这里。 */
@Entity(
    tableName = "word_user_tags",
    primaryKeys = ["wordId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = UserTagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tagId"])]
)
data class WordUserTagEntity(
    val wordId: Long,
    val tagId: Long
)

/** 标签 + 它挂了几个词，给「我的标签」列表和筛选芯片用。 */
data class UserTagWithCount(
    val id: Long,
    val name: String,
    val wordCount: Int
)
