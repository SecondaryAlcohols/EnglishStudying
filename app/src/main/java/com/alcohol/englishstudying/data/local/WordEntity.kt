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

    /**
     * 近义词，多个用顿号/分号/逗号分隔。
     *
     * 与 roots / derivations 一样是自由文本 —— 里面写的词不一定在库里。
     * 真正可点击跳转的关联在 word_relations 表里，由重建流程从这个列解析出来。
     */
    val synonyms: String? = null,

    /** 标签，英文逗号分隔。 */
    val tags: String? = null,

    /**
     * COCA 词频排名：**数字越小越常用**，抽新词时优先。0 表示查不到排名，排在最后。
     *
     * 别把它当「重要度」用 —— 旧词库里那种 0-90 的分数已经被统一成排名了。
     */
    val frequency: Int = 0,

    /**
     * 「优先学」：下一次抽新词时排在最前，**被抽走的那一刻自动清零**。
     *
     * 只对还没学过的词有意义 —— 已经有 `review_states` 行的词不进新词池，标了也不会被抽。
     * 默认值写在列上（而不是只写 Kotlin 默认参数），迁移 `ALTER TABLE ADD COLUMN`
     * 才能和 Room 的 schema 校验对上。
     */
    @ColumnInfo(defaultValue = "0")
    val priority: Boolean = false,

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
