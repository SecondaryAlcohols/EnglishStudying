package com.alcohol.englishstudying

import com.alcohol.englishstudying.ui.edit.WordDraft
import com.alcohol.englishstudying.ui.edit.hasSameRelationInputs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「保存词条时要不要重算全部关联」的判据。
 *
 * 它把关的是一次破坏性操作：`RelationRepository.rebuild()` 会先删光所有 AUTO 关系
 * 再全量重推导，于是**用户手动删掉的自动关联会被找回来**。所以这个判据判宽了，用户
 * 删完关联再改个音标点保存，那条关联就复活了；判窄了，改了词根却看不到新的同根词。
 * 两个方向都有肉眼可见的后果，不能只靠看代码。
 */
class WordEditRelationInputsTest {

    private fun draft(
        headword: String = "absent",
        phonetic: String = "ˈæbsənt",
        meanings: String = "缺席的",
        roots: String = "ab- 离开",
        derivations: String = "absence n. 缺席",
        synonyms: String = "missing"
    ) = WordDraft(
        headword = headword,
        phonetic = phonetic,
        meanings = meanings,
        roots = roots,
        derivations = derivations,
        synonyms = synonyms
    )

    @Test
    fun `四个关联输入都没变时认为没变`() {
        assertTrue(draft().hasSameRelationInputs(draft()))
    }

    @Test
    fun `改音标 释义 巧记 例句 标签 重要度都不算改动`() {
        val before = draft()
        val after = before.copy(
            phonetic = "əbˈsent",
            meanings = "不在场的",
            partOfSpeech = "adj.",
            mnemonic = "ab（离开）+ sent（存在）",
            examples = "He was absent from school.",
            tags = "核心,高频",
            frequency = "99"
        )

        assertTrue("这些列不参与关联推导", after.hasSameRelationInputs(before))
        assertTrue("判据应当对称", before.hasSameRelationInputs(after))
    }

    @Test
    fun `改词条本身要重算`() {
        assertFalse(draft(headword = "absence").hasSameRelationInputs(draft()))
    }

    @Test
    fun `改词根要重算`() {
        assertFalse(draft(roots = "ab- 离开 + -sent- 存在").hasSameRelationInputs(draft()))
    }

    @Test
    fun `改变形要重算`() {
        assertFalse(
            draft(derivations = "absently adv. 心不在焉地").hasSameRelationInputs(draft())
        )
    }

    @Test
    fun `改近义词要重算`() {
        assertFalse(draft(synonyms = "missing,gone").hasSameRelationInputs(draft()))
    }

    /** 落库时 `toEntity` 会 trim，只差首尾空格不该触发一次全量重建。 */
    @Test
    fun `只差首尾空格不算改动`() {
        val after = draft(headword = "  absent  ", roots = "ab- 离开 ", synonyms = " missing")
        assertTrue(after.hasSameRelationInputs(draft()))
    }
}
