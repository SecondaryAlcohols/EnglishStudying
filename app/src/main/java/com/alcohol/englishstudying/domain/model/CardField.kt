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
    // 曾经还有一个 PART_OF_SPEECH（词性）字段单独渲染 pos 列；后来词性逐条挂到释义上，
    // pos 列只在整条词没有任何词性时兜底挂到第一条前（见 WordCardView 的 MEANINGS 分支）
    MEANINGS("释义", "中文释义，多条用换行分隔；行开头可写词性", true),
    MNEMONIC("巧记", "联想、谐音、段子", false),
    ROOTS("词根词缀", "拆词：前缀 + 词根 + 后缀", false),
    DERIVATIONS("变形", "复数、时态、派生词", false),
    EXAMPLES("例句", "真题例句与翻译", false),
    TAGS("标签", "高频 / 熟词僻义 / 易混", false),

    // 以下是「关联词」——与上面几条不同，这些是**库里真实存在、可点击跳转**的词。
    // 上面 DERIVATIONS 是自由文本，其中提到的库内词同样可点击跳转（见 WordCardView）。
    // 曾经还有一个 WORD_FORMS（变形链）字段单独渲染变形关系，后来并入「变形」字段。
    ROOT_FAMILY("同根词", "共用同一个词根的词", false),
    SYNONYMS("近义词", "同义、近义的词", false),

    /**
     * 这个词属于哪些词表（一个词可以同时属于多个）。
     *
     * 有意声明在枚举末尾：用户已保存的字段配置是**有序列表**，解码时新字段会被
     * 追加到末尾（见 [com.alcohol.englishstudying.data.prefs.SettingsRepository.decodeCardFields]）。
     * 声明在末尾，老用户看到的顺序才和新装用户的默认顺序一致。
     */
    DECKS("词表", "这个词属于哪些词表", false);

    /**
     * 关联词字段对应的关系类型。不是关联字段的返回 null。
     *
     * 放在枚举上而不是散在 UI 里做 when：加字段时编译器会在这里提醒你补分支。
     */
    val relationType: WordRelationType?
        get() = when (this) {
            ROOT_FAMILY -> WordRelationType.ROOT
            SYNONYMS -> WordRelationType.SYNONYM
            else -> null
        }

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
