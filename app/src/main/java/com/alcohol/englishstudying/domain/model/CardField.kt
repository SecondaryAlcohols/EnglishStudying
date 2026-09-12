package com.alcohol.englishstudying.domain.model

/**
 * 卡片上可显示的字段。枚举的声明顺序就是默认显示顺序。
 *
 * 巧记 / 词根词缀 / 变形默认折叠：先让用户自己回忆，点一下才展开看提示，
 * 这是这三项内容真正产生记忆效果的前提。
 */
enum class CardField(
    val displayName: String,
    val hint: String,
    val defaultExpanded: Boolean
) {
    HEADWORD("单词", "词条本身", true),
    PHONETIC("音标", "英式/美式读音", true),
    PART_OF_SPEECH("词性", "n. / v. / adj. 等", true),
    MEANINGS("释义", "中文释义，多条用换行分隔", true),
    MNEMONIC("巧记", "联想、谐音、段子", false),
    ROOTS("词根词缀", "拆词：前缀 + 词根 + 后缀", false),
    DERIVATIONS("变形", "复数、时态、派生词", false),
    EXAMPLES("例句", "真题例句与翻译", false),
    TAGS("标签", "高频 / 熟词僻义 / 易混", false);

    companion object {
        fun fromNameOrNull(name: String?): CardField? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** 单个字段的显示配置。 */
data class CardFieldConfig(
    val field: CardField,
    val enabled: Boolean,
    val expanded: Boolean
)

object CardFieldConfigDefaults {
    /** 默认：全部启用，按枚举声明顺序排列。 */
    fun default(): List<CardFieldConfig> =
        CardField.entries.map { CardFieldConfig(it, enabled = true, expanded = it.defaultExpanded) }
}
