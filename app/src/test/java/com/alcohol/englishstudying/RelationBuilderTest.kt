package com.alcohol.englishstudying

import com.alcohol.englishstudying.domain.model.WordRelationType
import com.alcohol.englishstudying.domain.relations.RelationBuilder
import com.alcohol.englishstudying.domain.relations.RelationBuilder.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationBuilderTest {

    private var nextId = 1L
    private fun w(
        headword: String,
        roots: String? = null,
        derivations: String? = null,
        synonyms: String? = null
    ) = Input(nextId++, headword, roots, derivations, synonyms)

    private fun RelationBuilder.Relation.pair() = setOf(from, to)

    // ---- 词形匹配 ----

    @Test
    fun `复数与单数连成变形`() {
        val answer = w("answer")
        val answers = w("answers")

        val forms = RelationBuilder.deriveForms(listOf(answer, answers))

        assertEquals(1, forms.size)
        assertEquals(WordRelationType.FORM, forms[0].type)
        assertEquals(setOf(answer.id, answers.id), forms[0].pair())
    }

    @Test
    fun `过去式与动词原形连成变形`() {
        val use = w("use")
        val used = w("used")

        val forms = RelationBuilder.deriveForms(listOf(use, used))

        assertEquals(1, forms.size)
        assertEquals(setOf(use.id, used.id), forms[0].pair())
    }

    @Test
    fun `ing 与 y 结尾的规则都覆盖`() {
        val study = w("study")
        val studied = w("studied")
        val studies = w("studies")
        val studying = w("studying")
        val city = w("city")
        val cities = w("cities")

        val forms = RelationBuilder.deriveForms(
            listOf(study, studied, studies, studying, city, cities)
        )
        val pairs = forms.map { it.pair() }.toSet()

        assertTrue("studied 应连到 study", setOf(study.id, studied.id) in pairs)
        assertTrue("studies 应连到 study", setOf(study.id, studies.id) in pairs)
        assertTrue("studying 应连到 study", setOf(study.id, studying.id) in pairs)
        assertTrue("cities 应连到 city", setOf(city.id, cities.id) in pairs)
    }

    @Test
    fun `同形不同源的词不能连起来`() {
        // likely 的 -ly 与 like 无关；longer 的 -er 与 long 是两回事；
        // were / we、his / hi 都是短词干，必须挡住
        val words = listOf(
            w("likely"), w("like"),
            w("longer"), w("long"),
            w("were"), w("we"),
            w("his"), w("hi"),
            w("is"), w("i")
        )

        val forms = RelationBuilder.deriveForms(words)

        assertEquals("不该推出任何变形关系，却推到了 ${forms.map { it.pair() }}", 0, forms.size)
    }

    /**
     * 回归：截断规则会切出一个「碰巧也是真词」的词干，而补 e 的那条读法才是对的。
     *
     * `noted` 是 note 的过去式，不是「not 的过去式」；`cares` 是 care 的复数/三单，
     * 不是「car 的复数」。原先两条规则各自都会命中、两条关系都会写库，于是卡片上的
     * 「变形」同时指向 not 和 note —— 点进 not 那条就是错的。
     *
     * 考研词库里 not / car / rat / unit / star / sit / tap / rid 都是常用词，
     * 所以这不是边角情况，是成批存在的误连。
     */
    @Test
    fun `截断出的词干碰巧是真词时只连正确的那条`() {
        // (错误词干, 正确词干, 派生词)
        val cases = listOf(
            Triple("not", "note", "notes"),
            Triple("not", "note", "noted"),
            Triple("not", "note", "noting"),
            Triple("car", "care", "cares"),
            Triple("car", "care", "caring"),
            Triple("rat", "rate", "rates"),
            Triple("unit", "unite", "unites"),
            Triple("star", "stare", "stares"),
            Triple("star", "stare", "staring"),
            Triple("sit", "site", "sites"),
            Triple("tap", "tape", "taped"),
            Triple("rid", "ride", "riding"),
        )

        for ((wrongBase, rightBase, derived) in cases) {
            val wrong = w(wrongBase)
            val right = w(rightBase)
            val form = w(derived)

            val pairs = RelationBuilder.deriveForms(listOf(wrong, right, form))
                .map { it.pair() }
                .toSet()

            assertTrue("$derived 应连到 $rightBase", setOf(right.id, form.id) in pairs)
            assertFalse("$derived 不该连到 $wrongBase", setOf(wrong.id, form.id) in pairs)
        }
    }

    /**
     * 反方向：库里**只有裸词干**时要照常连上。
     *
     * 防止修上面那条时矫枉过正 —— 如果改成「必须能补 e 才算变形」，
     * worked / working 就会连不到 work，那是把正确的连丢掉了。
     */
    @Test
    fun `库里只有裸词干时仍然连得上`() {
        val work = w("work")
        val worked = w("worked")
        val working = w("working")

        val pairs = RelationBuilder.deriveForms(listOf(work, worked, working))
            .map { it.pair() }
            .toSet()

        assertTrue("worked 应连到 work", setOf(work.id, worked.id) in pairs)
        assertTrue("working 应连到 work", setOf(work.id, working.id) in pairs)
    }

    @Test
    fun `同一个词不会和自己连`() {
        // 「s」规则作用在已经是单数的词上时，词干会算成自己以外的字符串；
        // 这里确保自己连自己永远不会发生
        val words = listOf(w("bus"), w("us"), w("s"))

        assertTrue(RelationBuilder.deriveForms(words).none { it.from == it.to })
    }

    @Test
    fun `词形匹配产出的 pair 一定满足 from 小于 to`() {
        val words = listOf(w("answer"), w("answers"), w("use"), w("used"), w("study"), w("studies"))

        assertTrue(RelationBuilder.deriveForms(words).all { it.from < it.to })
    }

    // ---- 同根词 ----

    @Test
    fun `从 roots 文本里抽出带连字符的词缀`() {
        val affixes = RelationBuilder.extractAffixes("se- 没有 + -cur- 关心 + -ity 状态")

        assertEquals(listOf("se-", "-cur-", "-ity"), affixes)
    }

    @Test
    fun `不带连字符的整词不算词缀`() {
        // sent 既是词根也是单词的过去式，拿它分组会串起无关的词
        assertEquals(emptyList<String>(), RelationBuilder.extractAffixes("ab(离开)+sent(存在)"))
    }

    @Test
    fun `共用词根的两个词连成同根词并带上词根标签`() {
        val infer = w("infer", roots = "in- 进入 + -fer- 带来")
        val refer = w("refer", roots = "re- 再 + -fer- 带来")

        val roots = RelationBuilder.deriveRoots(listOf(infer, refer))

        assertEquals(1, roots.size)
        assertEquals(WordRelationType.ROOT, roots[0].type)
        assertEquals("-fer-", roots[0].label)
    }

    @Test
    fun `同根词组超过上限时被截断`() {
        // 10 个词共享 -ate，上限 3 时只保留前 4 个（3 + 自己）
        val words = (1..10).map { w("word$it", roots = "-ate 动词后缀") }

        val roots = RelationBuilder.deriveRoots(words, maxPeers = 3)

        // 4 个成员两两相连 = C(4,2) = 6 条
        assertEquals(6, roots.size)
        val involved = roots.flatMap { listOf(it.from, it.to) }.toSet()
        assertEquals("只有前 4 个词参与", words.take(4).map { it.id }.toSet(), involved)
    }

    @Test
    fun `只出现一次的词缀不成组`() {
        val only = w("solo", roots = "un- 否定")

        assertEquals(0, RelationBuilder.deriveRoots(listOf(only)).size)
    }

    // ---- 近义词 / 文本提到的词 ----

    @Test
    fun `近义词里库中没有的词被丢弃`() {
        val important = w("important", synonyms = "significant；关键的；vital")
        val significant = w("significant")

        val rels = RelationBuilder.deriveFromText(
            listOf(important, significant),
            WordRelationType.SYNONYM
        ) { it.synonyms }

        assertEquals(1, rels.size)
        assertEquals(setOf(important.id, significant.id), rels[0].pair())
    }

    @Test
    fun `derivations 文本里提到的库内词连成变形`() {
        val differ = w("differ", derivations = "difference n. 差别；different adj. 不同的")
        val difference = w("difference")
        val different = w("different")

        val rels = RelationBuilder.deriveFromText(
            listOf(differ, difference, different),
            WordRelationType.FORM
        ) { it.derivations }

        val pairs = rels.map { it.pair() }.toSet()
        assertEquals(2, rels.size)
        assertTrue(setOf(differ.id, difference.id) in pairs)
        assertTrue(setOf(differ.id, different.id) in pairs)
    }

    @Test
    fun `derivations 里语义相关但并非变形的词不能连`() {
        // 真机上抓到的误连：present 的 derivations 里写了 "represent v. 代表"，
        // 但 represent 不是 present 的变形
        val present = w("present", derivations = "presentation n. 展示；represent v. 代表")
        val presentation = w("presentation")
        val represent = w("represent")

        val rels = RelationBuilder.deriveFromText(
            listOf(present, presentation, represent),
            WordRelationType.FORM,
            requireSharedStem = true
        ) { it.derivations }

        val pairs = rels.map { it.pair() }.toSet()
        assertTrue("presentation 是真变形，该连", setOf(present.id, presentation.id) in pairs)
        assertFalse("represent 不是变形，不该连", setOf(present.id, represent.id) in pairs)
    }

    @Test
    fun `derivations 里的词性缩写不能连到同名词条`() {
        // k3y 词库里有字面就叫 "adj" 的垃圾词条。derivations 文本里
        // "compensatory adj. 补偿性的" 中的 adj 会被分词器当成一个词，
        // 于是 compensate 和 adj 被连了起来 —— 真机上确实发生了
        val compensate = w("compensate", derivations = "compensation n. 补偿；compensatory adj. 补偿性的")
        val compensation = w("compensation")
        val adj = w("adj")

        val rels = RelationBuilder.deriveFromText(
            listOf(compensate, compensation, adj),
            WordRelationType.FORM,
            requireSharedStem = true
        ) { it.derivations }

        val pairs = rels.map { it.pair() }.toSet()
        assertTrue(setOf(compensate.id, compensation.id) in pairs)
        assertFalse("词性缩写不该连进来", setOf(compensate.id, adj.id) in pairs)
    }

    @Test
    fun `近义词不要求词头相同`() {
        // important / significant 长得完全不像，但它们确实是近义词
        val important = w("important", synonyms = "significant")
        val significant = w("significant")

        val rels = RelationBuilder.deriveFromText(
            listOf(important, significant),
            WordRelationType.SYNONYM
        ) { it.synonyms }

        assertEquals(1, rels.size)
    }

    @Test
    fun `带连字符的派生词也能被匹配到`() {
        val absent = w("absent", derivations = "absent-minded adj. 心不在焉的")
        val absentMinded = w("absent-minded")

        val rels = RelationBuilder.deriveFromText(
            listOf(absent, absentMinded),
            WordRelationType.FORM
        ) { it.derivations }

        assertEquals(1, rels.size)
    }

    // ---- 组装 ----

    @Test
    fun `build 会对同时被多条规则命中的同一对词去重`() {
        // differ↔difference 既是词形匹配（s 规则不适用，但 derivations 提到了），
        // 又在 derivations 文本里出现 —— 两条路径指向同一个 pair
        val differ = w("differ", derivations = "difference n. 差别")
        val difference = w("difference", synonyms = "differ")

        val all = RelationBuilder.build(listOf(differ, difference))

        // FORM 一条、SYNONYM 一条，各一条不重复
        val form = all.filter { it.type == WordRelationType.FORM }
        assertTrue("FORM 不该重复", form.size <= 1)
        assertFalse(all.any { it.from == it.to })
    }

    @Test
    fun `build 的结果里没有任何重复的 from to type 组合`() {
        val words = listOf(
            w("infer", roots = "-fer- 带来", synonyms = "refer"),
            w("refer", roots = "-fer- 带来"),
            w("refers", roots = "-fer- 带来", synonyms = "refer")
        )

        val all = RelationBuilder.build(words)
        val keys = all.map { Triple(it.from, it.to, it.type) }

        assertEquals(keys.size, keys.distinct().size)
        assertTrue(all.all { it.from < it.to })
    }
}
