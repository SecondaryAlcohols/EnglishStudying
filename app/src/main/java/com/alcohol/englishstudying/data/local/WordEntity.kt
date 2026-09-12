package com.alcohol.englishstudying.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一个词条。mnemonic / roots / derivations 三列就是本应用的三个特色字段。
 *
 * 除 [headword] 外全部可空 —— 导入的词库往往字段残缺，强制非空只会逼用户去填占位符。
 */
@Entity(
    tableName = "words",
    indices = [
        Index(value = ["headword"], unique = true),
        Index(value = ["frequency"]),
        Index(value = ["updatedAt"])
    ]
)
data class WordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    val headword: String,
    val phonetic: String? = null,
    @ColumnInfo(name = "part_of_speech") val partOfSpeech: String? = null,

    /** 中文释义，多义项用 \n 分隔。 */
    val meanings: String? = null,

    /** 巧记。 */
    val mnemonic: String? = null,

    /** 词根词缀拆解，如 "ab-(离开) + sent(存在) → 不在场"。 */
    val roots: String? = null,

    /** 变形：复数、时态、派生词。 */
    val derivations: String? = null,

    /** 例句，中英成对，多条用 \n 分隔。 */
    val examples: String? = null,

    /** 标签，英文逗号分隔。 */
    val tags: String? = null,

    /** 重要度，越大越靠前。用于「按词频优先」抽新词。 */
    val frequency: Int = 0,

    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    val tagList: List<String>
        get() = tags.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val meaningList: List<String>
        get() = meanings.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    val exampleList: List<String>
        get() = examples.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}
