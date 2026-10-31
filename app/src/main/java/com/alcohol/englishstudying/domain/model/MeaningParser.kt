package com.alcohol.englishstudying.domain.model

/**
 * 从一条释义里解析出它自己的词性。
 *
 * 词条的词性列（pos）往往只有一个值，而一条词常有多个词性——
 * 「present adj. 现在的 / n. 礼物 / v. 展示」。真正和释义挂钩的词性
 * 通常就写在释义行开头，两种常见埋法都支持：
 *
 * - 英文缩写：`n. 礼物`、`adj. 现在的`、`vt. 及物…`
 * - 中文词性（k3y 词库的格式）：`形容词，能够的`、`动词 展示`
 *
 * 解析不出的行（`缺席的；不在场的`）词性为 null，原样展示。
 * 纯函数，供卡片与小结两处渲染共用。
 */
object MeaningParser {

    /** 一条释义：解析出的词性（已规范化，如 `n.` / `形容词`）+ 去掉词性后的正文。 */
    data class MeaningWithPos(val pos: String?, val text: String)

    /**
     * 英文词性缩写。
     *
     * 长词放前面，避免 `adj.` 被 `adv.` 之前的 `a` 之类截胡。
     * 这份清单是照**库里实际出现的写法**列的（2026-10-26 全库统计：`n` 5140、`v` 4479、
     * `a` 1442、`ad` 335、`prep` 77、`pron` 72、`adj` 50、`vt` 46、`conj` 41、`num` 30、
     * `vi` 28、`adv` 17、`aux` 11、`interj` 6、`int` 5、`art` 2、`abbr` 1、`pref` 1）。
     * 漏掉 `ad`/`int` 时它们不会进绿色词性框，而是原样混在释义正文里。
     */
    private val EN = Regex(
        pattern = """^\s*(interj|phrase|abbr|conj|pron|prep|adv|adj|aux|art|int|pref|suff|vi|vt|num|pl|phr|n|v|ad|a)\s*[.．]""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * 短写的规范名。
     *
     * ECDICT 同一个词性有两种写法（`a.` 1442 条 vs `adj.` 50 条、`ad.` 335 条 vs `adv.` 17 条），
     * 照原样渲染会让同一张卡上出现 `adj.` 和 `a.` 两种绿框，看着像两个不同的词性。
     */
    private val POS_ALIAS = mapOf("a" to "adj", "ad" to "adv", "int" to "interj")

    /** `aux.v.` 是词典里的固定连写，别把 `v.` 留在正文里。 */
    private val AUX_V = Regex("""^v\s*[.．]\s*""", setOf(RegexOption.IGNORE_CASE))

    /**
     * 中文词性。**后面必须跟分隔符或到行尾**，这一点不能省。
     *
     * 不要求分隔符时会把释义正文开头的词整段吃掉：「名词所有格：表示所属」里的
     * 「名词所有格」是一个整体、「动词的过去式」里的「动词」也是正文的一部分，
     * 两者都会被切成 `pos=名词/动词` + 残缺的正文，卡片上的词性和释义就都错了。
     * 英文分支靠那个 `[.．]` 起同样的作用（所以「1. 缺席的」不会被误判），
     * 中文分支原本什么都没要求。
     *
     * 认的分隔符就是上面写的那几种，与 k3y 的 `形容词，能够的` / `动词 展示` 两种写法对应。
     */
    private val ZH = Regex(
        """^\s*(名词|动词|形容词|副词|介词|连词|代词|数词|感叹词|冠词|词组|短语)(?=[，。、：；,;:.\s]|$)"""
    )

    /** 中文词性后面常跟着的标点与连接符。 */
    private val LEADING_JUNK = Regex("""^[，。、：；,;:.\s]+""")

    fun parse(raw: String): MeaningWithPos {
        val en = EN.find(raw)
        if (en != null) {
            val code = en.groupValues[1].lowercase()
            var rest = raw.substring(en.range.last + 1)
            // aux.v.：两个缩写连写，整段算一个词性，剩下的才是正文
            if (code == "aux") {
                val auxV = AUX_V.find(rest)
                if (auxV != null) {
                    rest = rest.substring(auxV.range.last + 1)
                    return MeaningWithPos("aux.v.", clean(rest))
                }
            }
            return MeaningWithPos((POS_ALIAS[code] ?: code) + ".", clean(rest))
        }
        val zh = ZH.find(raw)
        if (zh != null) {
            val rest = raw.substring(zh.range.last + 1)
            return MeaningWithPos(zh.groupValues[1], clean(rest))
        }
        return MeaningWithPos(null, raw.trim())
    }

    /**
     * 把词条 `part_of_speech` 列的原始写法规范成卡片上显示的词性。
     *
     * 这一列**直接来自 ECDICT**，同样是短写混着长写（`a.` 776 个、`ad.` 122 个、`adj.` 338 个），
     * 不归一化的话，卡片首条释义的绿框会和下面几条不是一个写法。
     * 复合写法（`vt. / vi.`、`n. / v.`）按 `/` 拆开逐个归一化。
     */
    fun normalizePos(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return text.split("/")
            .joinToString(" / ") { part ->
                val trimmed = part.trim()
                val m = EN.find(trimmed) ?: return@joinToString trimmed
                val code = m.groupValues[1].lowercase()
                val tail = trimmed.substring(m.range.last + 1).trim()
                (POS_ALIAS[code] ?: code) + "." + tail
            }
    }

    /** 一列释义逐条解析。空行与纯空白条目丢弃。 */
    fun parseAll(raws: List<String>): List<MeaningWithPos> =
        raws.filter { it.isNotBlank() }.map { parse(it) }

    private fun clean(text: String): String = text.replace(LEADING_JUNK, "").trim()
}
