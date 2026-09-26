package com.alcohol.englishstudying.domain.relations

import com.alcohol.englishstudying.domain.model.WordRelationType

/**
 * 从词条内容推导词语关联。
 *
 * 纯函数，不碰数据库也不读时钟 —— 输入是词条列表，输出是待写入的关系。
 * 放在 domain 层是为了能直接单测：这类「文本 → 关系」的推导最容易出错，
 * 而且错了不容易被发现（把 likely 和 like 连起来，看着还挺像回事）。
 */
object RelationBuilder {

    /** 同一个词根下的关联词上限。 */
    const val DEFAULT_MAX_PEERS = 8

    /**
     * 参与推导所需的最小词干长度。
     *
     * 定 3 而不是 4：定 4 会把 use/used、age/ages 这类短词漏掉，
     * 而定 3 时 is/his/was 这些依然被挡住（词干只有 1-2 个字母）。
     */
    private const val MIN_STEM = 3

    /**
     * 一个词条参与推导所需的全部信息。
     *
     * 刻意不直接用 WordEntity —— 那个类在 data 层，domain 不该依赖它。
     */
    data class Input(
        val id: Long,
        val headword: String,
        val roots: String? = null,
        val derivations: String? = null,
        val synonyms: String? = null
    )

    /** 推导出的一条关系，已规范化成 `from < to`。 */
    data class Relation(
        val from: Long,
        val to: Long,
        val type: WordRelationType,
        /** ROOT 专用：共享的词根文本。 */
        val label: String? = null
    )

    /**
     * 词形变化规则：`后缀 to 去掉后缀后要补上的字符`。
     *
     * 同一个后缀给两条规则（补 `e` / 不补），两条都是候选，具体认哪条由 [deriveForms]
     * 按词干长短决定 —— 所以这里补 `e` 的那条排在前面只是为了读起来顺，顺序不参与判定。
     *
     * 刻意**不含** `-ly` / `-er` / `-est`：它们会把 likely/like、longer/long
     * 这类同形不同源的词连起来，而这正是最该避免的错误。宁可少连不可错连。
     */
    private val INFLECTIONS: List<Pair<String, String>> = listOf(
        "ies" to "y",
        "ied" to "y",
        "ing" to "e",
        "ing" to "",
        "ed" to "e",
        "ed" to "",
        "es" to "",
        "s" to ""
    )

    // ---- 变形 ----

    /**
     * 库内词形匹配：`answer ↔ answers`、`use ↔ used`、`study ↔ studied`。
     *
     * 不需要任何额外数据，导完就能用 —— 三类关系里唯一立刻有内容的一类。
     *
     * **每个词只连一条变形关系**，取所有规则里能切出的**最长**的那个词干。这条约束
     * 不能省：截断规则很容易切出一个「碰巧也是真词」的词干，而两个词干都在库里时
     * 两个都连，就会连出错的那一条 ——
     *  - `noted` 按 `ed → ""` 切出 `not`、按 `ed → e` 切出 `note`，两个都在库里；
     *  - `cares` 按 `es → ""` 切出 `car`、按 `s → ""` 切出 `care`；
     *  - `rates` 按 `es → ""` 切出 `rat`、按 `s → ""` 切出 `rate`。
     *
     * 补字符的那条读法给出的词干总是更长，也正是对的那一个（`noted` 是 note 的过去式，
     * 不是「not 的过去式」）。等长时先出现的规则优先，结果只取决于规则表的顺序。
     */
    fun deriveForms(words: List<Input>): List<Relation> {
        val byLower = words.associateBy { it.headword.lowercase() }
        val out = Collector()

        for (word in words) {
            val lower = word.headword.lowercase()
            var best: Input? = null
            var bestStemLength = 0
            for ((suffix, replacement) in INFLECTIONS) {
                if (!lower.endsWith(suffix)) continue
                val stem = lower.dropLast(suffix.length) + replacement
                // 词干太短不算，已经有更长的候选也不再考虑
                if (stem.length < MIN_STEM || stem.length <= bestStemLength) continue

                val base = byLower[stem] ?: continue
                if (base.id == word.id) continue
                best = base
                bestStemLength = stem.length
            }
            best?.let { out.add(word.id, it.id, WordRelationType.FORM) }
        }
        return out.relations()
    }

    // ---- 文本里提到的词 ----

    private val WORD_TOKEN = Regex("""[\p{L}][\p{L}'-]*[\p{L}]|[\p{L}]""")

    /**
     * 解析一段文本里提到的词，取库中确实存在的那些。
     *
     * 输入形如 `absence n. 缺席；absent-minded adj. 心不在焉的`，
     * 库中没有的词直接丢弃 —— 卡片上的关联词必须可点击跳转，
     * 指向一个不存在的词没有意义。
     */
    fun extractMentionedWords(text: String?, known: Map<String, Long>): List<Long> {
        if (text.isNullOrBlank()) return emptyList()
        return WORD_TOKEN.findAll(text)
            .map { it.value.lowercase() }
            .mapNotNull { known[it] }
            .distinct()
            .toList()
    }

    /**
     * 两个词共有的开头长度。
     *
     * 用来判断「像不像同一个词的变形」。`absent` / `absence` 共有 5 个字母，
     * `present` / `represent` 一个都没有。
     */
    private fun sharedPrefixLength(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    /** 变形关系要求的最短公共开头。 */
    private const val MIN_SHARED_PREFIX = 4

    /**
     * 按某个自由文本列建关联。
     *
     * [requireSharedStem] 用于 `derivations` 这一列：它写的是**相关词**而不是严格的
     * 变形，`present` 那条里就有 `represent v. 代表` —— 那是近亲不是变形，直接连
     * 会把「变形链」变成一锅杂烩。要求两边有共同的词头才收，能挡住这类误连。
     *
     * 近义词列则**不能**加这个要求：important 和 significant 本来就长得不像。
     */
    fun deriveFromText(
        words: List<Input>,
        type: WordRelationType,
        requireSharedStem: Boolean = false,
        text: (Input) -> String?
    ): List<Relation> {
        val known = words.associate { it.headword.lowercase() to it.id }
        val lowerById = words.associate { it.id to it.headword.lowercase() }
        val out = Collector()
        for (word in words) {
            val self = word.headword.lowercase()
            for (otherId in extractMentionedWords(text(word), known)) {
                if (requireSharedStem) {
                    val other = lowerById[otherId] ?: continue
                    if (sharedPrefixLength(self, other) < MIN_SHARED_PREFIX) continue
                }
                out.add(word.id, otherId, type)
            }
        }
        return out.relations()
    }

    // ---- 同根词 ----

    /** 带连字符的词缀 token：`se-`、`-cur-`、`-ity`、`un-`。 */
    private val AFFIX = Regex("""(?<![\p{L}\p{N}])(-?[\p{L}]{2,}-?)(?![\p{L}\p{N}])""")

    /**
     * 从 `roots` 文本里抽出词缀。
     *
     * 输入形如 `se- 没有 + -cur- 关心 + -ity 状态`，只取**带连字符**的 token。
     * 不取不带连字符的整词（如 `sent`）——「sent」既是词根也是单词的过去式，
     * 拿它去分组会把无关的词串在一起。精度换覆盖。
     */
    fun extractAffixes(roots: String?): List<String> {
        if (roots.isNullOrBlank()) return emptyList()
        return AFFIX.findAll(roots)
            .map { it.value.lowercase() }
            .filter { it.contains('-') }
            .distinct()
            .toList()
    }

    /**
     * 同根词：按词缀分组，组内两两相连。
     *
     * **每组只取前 [maxPeers] + 1 个词**（即除自己外最多 [maxPeers] 个同根词），
     * 组内再两两全连。不截断的话 `-ate`、`-ion` 这种高频后缀会把一组撑成几百个词：
     * 卡片上没法看，边数还会按平方涨（300 个词就是 4.5 万条边）。
     *
     * 截断按输入顺序取，所以调用方应该按重要度排好序再传进来。
     */
    fun deriveRoots(
        words: List<Input>,
        maxPeers: Int = DEFAULT_MAX_PEERS
    ): List<Relation> {
        val byAffix = LinkedHashMap<String, MutableList<Long>>()
        for (word in words) {
            for (affix in extractAffixes(word.roots)) {
                byAffix.getOrPut(affix) { mutableListOf() }.add(word.id)
            }
        }

        val out = Collector()
        for ((affix, rawIds) in byAffix) {
            val group = rawIds.distinct().take(maxPeers + 1)
            if (group.size < 2) continue
            for (i in group.indices) {
                for (j in i + 1 until group.size) {
                    out.add(group[i], group[j], WordRelationType.ROOT, affix)
                }
            }
        }
        return out.relations()
    }

    // ---- 组装 ----

    /** 三类一起推导。同一对词可能被多条规则命中，结果按 (from, to, type) 去重。 */
    fun build(words: List<Input>, maxPeers: Int = DEFAULT_MAX_PEERS): List<Relation> =
        buildList {
            addAll(deriveForms(words))
            addAll(deriveRoots(words, maxPeers))
            // 近义词只看语义，变形要求词头相同 —— 见 deriveFromText 的说明
            addAll(deriveFromText(words, WordRelationType.SYNONYM) { it.synonyms })
            addAll(
                deriveFromText(words, WordRelationType.FORM, requireSharedStem = true) {
                    it.derivations
                }
            )
        }.distinctBy { Triple(it.from, it.to, it.type) }

    /**
     * 收集器：负责规范化（from < to）与去重。
     *
     * 规范化只在这一处发生。散在各分支里手写 min/max 的话，漏一处就会写出一条
     * 反方向的关系，而查询是双向扫的，多出来的那条会变成重复显示。
     */
    private class Collector {
        private val map = LinkedHashMap<Triple<Long, Long, WordRelationType>, Relation>()

        fun add(a: Long, b: Long, type: WordRelationType, label: String? = null) {
            if (a == b) return
            val from = if (a <= b) a else b
            val to = if (a <= b) b else a
            map.putIfAbsent(Triple(from, to, type), Relation(from, to, type, label))
        }

        fun relations(): List<Relation> = map.values.toList()
    }
}
