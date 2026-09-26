package com.alcohol.englishstudying.data.imports

/**
 * 导入时可识别的目标字段及其同义词。
 *
 * 词库由用户自己准备，表头命名无法预期，所以这里尽量把常见中英文写法都列上。
 * 猜不中的会在导入向导里让用户手动指定 —— 自动猜错的代价比让人点一下更大。
 */
enum class ImportField(
    val displayName: String,
    val aliases: Set<String>
) {
    HEADWORD(
        "单词",
        setOf("word", "headword", "term", "spelling", "english", "单词", "词汇", "英文")
    ),
    PHONETIC(
        "音标",
        setOf("phonetic", "phonetics", "pronunciation", "ipa", "音标", "发音")
    ),
    PART_OF_SPEECH(
        "词性",
        setOf("pos", "partofspeech", "speech", "词性", "词类")
    ),
    MEANINGS(
        "释义",
        setOf(
            "meaning", "meanings", "definition", "translation", "chinese", "cn",
            "释义", "中文", "翻译", "意思", "词义"
        )
    ),
    MNEMONIC(
        "巧记",
        setOf(
            "mnemonic", "mnemonics", "memory", "memoryaid", "tip",
            "巧记", "助记", "记忆", "记忆法", "联想"
        )
    ),
    ROOTS(
        "词根词缀",
        setOf("root", "roots", "etymology", "affix", "affixes", "词根", "词根词缀", "词缀", "构词")
    ),
    DERIVATIONS(
        "变形",
        setOf(
            "derivation", "derivations", "forms", "form", "inflection", "variants",
            "变形", "词形变化", "派生", "派生词"
        )
    ),
    EXAMPLES(
        "例句",
        setOf("example", "examples", "sentence", "sentences", "例句", "真题例句", "示例")
    ),
    TAGS(
        "标签",
        setOf("tag", "tags", "label", "labels", "category", "标签", "分类", "标记")
    ),
    SYNONYMS(
        "近义词",
        setOf(
            "synonym", "synonyms", "synonymy", "near", "similar",
            "近义词", "同义词", "近义", "同义"
        )
    ),
    FREQUENCY(
        "重要度",
        setOf("frequency", "freq", "rank", "weight", "importance", "重要度", "词频", "频率", "权重", "序号")
    ),
    DECK(
        "词表",
        setOf("deck", "list", "book", "source", "词表", "分组", "来源", "书")
    );

    companion object {
        private val BOM = 0xFEFF.toChar()
        private val NBSP = 0x00A0.toChar()

        /** 去掉 BOM、空格、下划线、连字符并转小写，让 "Part Of Speech" 也能匹配上。 */
        fun normalizeHeader(header: String): String =
            header.trim()
                .removePrefix(BOM.toString())
                .trim()
                .lowercase()
                .filterNot { it == ' ' || it == NBSP || it == '_' || it == '-' }

        private val aliasIndex: Map<String, ImportField> by lazy {
            buildMap {
                // 必须写全 ImportField.entries：buildMap 的 receiver 是 MutableMap，
                // 它自带的 entries 会遮蔽枚举的 entries，field 就被推断成 MutableEntry 了
                ImportField.entries.forEach { field ->
                    put(normalizeHeader(field.name), field)
                    put(normalizeHeader(field.displayName), field)
                    field.aliases.forEach { alias -> putIfAbsent(normalizeHeader(alias), field) }
                }
            }
        }

        fun guess(header: String): ImportField? = aliasIndex[normalizeHeader(header)]
    }
}
