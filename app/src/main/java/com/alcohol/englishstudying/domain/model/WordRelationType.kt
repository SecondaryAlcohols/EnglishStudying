package com.alcohol.englishstudying.domain.model

/**
 * 两个词之间的关系类型。
 *
 * 放在 domain 而不是 data/local，是因为推导逻辑（`domain/relations/RelationBuilder`）
 * 要产出它，而 domain 层不依赖 Android、也不依赖 Room。
 */
enum class WordRelationType {
    /** 共用同一个词根词缀，如 infer / refer / prefer 共享 -fer-。 */
    ROOT,

    /** 一个是另一个的变形，如 answer / answers、use / used。 */
    FORM,

    /** 近义、同义。 */
    SYNONYM;

    /** 界面上的名字，与 CardField 里对应字段的 displayName 保持一致。 */
    val displayName: String
        get() = when (this) {
            ROOT -> "同根词"
            FORM -> "变形"
            SYNONYM -> "近义词"
        }

    companion object {
        fun fromNameOrNull(name: String?): WordRelationType? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
