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

    /** 英文词性缩写。长词放前面，避免 `adj.` 被 `adv.` 之前的 `a` 之类截胡。 */
    private val EN = Regex(
        pattern = """^\s*(interj|conj|pron|prep|adv|adj|aux|art|vi|vt|num|pl|phr|phrase|n|v|a)\s*[.．]""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

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
            val rest = raw.substring(en.range.last + 1)
            return MeaningWithPos(en.groupValues[1].lowercase() + ".", clean(rest))
        }
        val zh = ZH.find(raw)
        if (zh != null) {
            val rest = raw.substring(zh.range.last + 1)
            return MeaningWithPos(zh.groupValues[1], clean(rest))
        }
        return MeaningWithPos(null, raw.trim())
    }

    /** 一列释义逐条解析。空行与纯空白条目丢弃。 */
    fun parseAll(raws: List<String>): List<MeaningWithPos> =
        raws.filter { it.isNotBlank() }.map { parse(it) }

    private fun clean(text: String): String = text.replace(LEADING_JUNK, "").trim()
}
