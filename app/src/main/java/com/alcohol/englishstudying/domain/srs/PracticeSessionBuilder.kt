package com.alcohol.englishstudying.domain.srs

import kotlin.random.Random

/**
 * 单纯复习（练习模式）的抽词编排。
 *
 * 从 `StudyRepository.buildPracticeSession` 里抽出来的纯函数部分 —— 理由和
 * [DailyPlanBuilder] 一样：这段逻辑错了用户看得见（同一批词反复出现、或者练完一轮
 * 不重置），但它原本长在 Repository 里、要连数据库才能跑，于是那几个测试只能把逻辑
 * 手抄一遍再断言自己抄的结果 —— 把实现整个删掉也照样绿。
 *
 * 不碰数据库也不读时钟：候选词 id 由调用方查好传进来。
 */
object PracticeSessionBuilder {

    /**
     * 一轮抽词的结果。
     *
     * [practicedIds] 是**这一轮结束后**该记的已练集合，调用方直接存下来即可 ——
     * 重置轮次时它是空的（新的一轮从头开始，所有词都还没练过），所以调用方不需要
     * 单独处理 [resetCycle]。
     */
    data class Plan(
        val pickedIds: List<Long>,
        /** 本次是否开启了新一轮（上一轮全部练完）。 */
        val resetCycle: Boolean,
        val practicedIds: Set<Long>,
        /** 待复习池的总词数，与本次抽了多少无关。 */
        val poolTotal: Int,
        val basePracticedCount: Int
    )

    /**
     * 从 [dueIds] 里抽 [limit] 个词。
     *
     * 三条规则：
     *  1. **只抽没练过的** —— [practicedIds] 里那些已经不在待复习池中的词会被剔除
     *     （它们在常规学习里复习完、状态不再是待复习了），剩下的是本轮真正还没练的；
     *  2. **新变到期的词自然纳入** —— 它们不在已练集合里，于是自动成为候选，
     *     不需要额外的「到期了就移出已练池」这一步；
     *  3. **练完一轮自动重置** —— 候选为空时，把整个池子重新当作候选（[resetCycle]），
     *     保证点多少次都还有词可练，不会一直重复同一批。
     *
     * [dueIds] 的顺序会被保留（除打乱之外）—— 调用方传的是按到期时间排好的顺序。
     * [random] 只为单测可复现而存在，生产走默认值。
     */
    fun plan(
        dueIds: List<Long>,
        practicedIds: Set<Long>,
        limit: Int,
        random: Random = Random.Default
    ): Plan {
        if (dueIds.isEmpty()) {
            return Plan(emptyList(), resetCycle = false, practicedIds = emptySet(), poolTotal = 0, basePracticedCount = 0)
        }

        // 1. 与当前真实待复习的词取交集：清理掉已不在待复习状态的词
        val validPracticed = practicedIds.intersect(dueIds.toSet())
        // 2. 剩余未抽取的词（新变到期的词会自然出现在这里）
        val unpracticed = dueIds.filter { it !in validPracticed }

        val (candidates, resetCycle, basePracticed) = if (unpracticed.isEmpty()) {
            // 当前全部待复习词都已练过一轮，开启新轮次
            Triple(dueIds, true, emptySet<Long>())
        } else {
            Triple(unpracticed, false, validPracticed)
        }

        // take 不接受负数，而 limit 来自界面设置
        return Plan(
            pickedIds = candidates.shuffled(random).take(limit.coerceAtLeast(0)),
            resetCycle = resetCycle,
            practicedIds = basePracticed,
            poolTotal = dueIds.size,
            basePracticedCount = basePracticed.size
        )
    }
}
