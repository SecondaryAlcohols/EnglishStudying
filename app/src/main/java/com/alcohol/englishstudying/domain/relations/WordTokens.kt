package com.alcohol.englishstudying.domain.relations

/**
 * 从自由文本里提取英文词形候选。
 *
 * 「变形」列是自由文本（"absence n. 缺席; absent-minded adj. 心不在焉的"），
 * 要把其中提到的、库里真实存在的词变成可点击，第一步就是把英文词形抠出来：
 * 字母开头，允许内部出现撇号和连字符（don't、well-known）。输出统一小写，
 * 供查库时做大小写无关匹配；中文、标点、数字天然被排除。
 */
object WordTokens {

    private val TOKEN = Regex("[A-Za-z][A-Za-z'’\\-]*")

    fun extract(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        return TOKEN.findAll(text)
            .map { it.value.lowercase() }
            .toSet()
    }
}
