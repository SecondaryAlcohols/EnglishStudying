package com.alcohol.englishstudying

import com.alcohol.englishstudying.domain.srs.PracticeSessionBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 练习模式抽词。
 *
 * 这些用例**必须真的调用 [PracticeSessionBuilder.plan]**。原先那四个用例把
 * `practicedIds.intersect(allDueIds)`、`filter { it !in ... }` 之类的逻辑在测试体里
 * 手抄了一遍，再断言自己刚算出来的结果 —— 把生产实现整个删掉也照样全绿，等于没测。
 * 抽词逻辑因此从 Repository 里挪到了 domain 层的纯函数，就是为了能这样测。
 *
 * 断言用集合而不是列表：抽词结果经过打乱，顺序不稳定（除了专门测种子的那条）。
 */
class PracticeSessionTest {

    /** 固定种子，让打乱可复现 —— 否则任何涉及顺序的断言都会随机飘。 */
    private fun plan(
        dueIds: List<Long>,
        practiced: Set<Long> = emptySet(),
        limit: Int = 20
    ) = PracticeSessionBuilder.plan(
        dueIds = dueIds,
        practicedIds = practiced,
        limit = limit,
        random = Random(42)
    )

    @Test
    fun `待复习池为空时返回空`() {
        val result = plan(dueIds = emptyList(), practiced = setOf(1L, 2L))

        assertTrue(result.pickedIds.isEmpty())
        assertEquals(0, result.poolTotal)
        assertEquals(0, result.basePracticedCount)
        assertFalse(result.resetCycle)
    }

    @Test
    fun `全部练完后开启新一轮并把已练集合清空`() {
        val result = plan(dueIds = listOf(1L, 2L, 3L), practiced = setOf(1L, 2L, 3L))

        assertTrue("全部练过就该重置轮次", result.resetCycle)
        assertTrue("新一轮从零开始，已练集合必须是空的", result.practicedIds.isEmpty())
        assertEquals(3, result.poolTotal)
        assertEquals(setOf(1L, 2L, 3L), result.pickedIds.toSet())
    }

    @Test
    fun `未练完时只抽没练过的`() {
        val result = plan(dueIds = listOf(1L, 2L, 3L, 4L, 5L), practiced = setOf(1L, 2L))

        assertFalse(result.resetCycle)
        assertEquals(setOf(3L, 4L, 5L), result.pickedIds.toSet())
        assertEquals(setOf(1L, 2L), result.practicedIds)
        assertEquals(2, result.basePracticedCount)
        assertEquals(5, result.poolTotal)
    }

    @Test
    fun `新变到期的词自然进入候选`() {
        // 10 号是新变到期的：它不在已练池里，于是自动成为候选，不需要额外一步
        val result = plan(dueIds = listOf(1L, 2L, 10L), practiced = setOf(1L, 2L))

        assertEquals(listOf(10L), result.pickedIds)
        assertEquals(setOf(1L, 2L), result.practicedIds)
    }

    @Test
    fun `已练词若不在待复习池里则从已练集合中剔除`() {
        // 1 号在常规学习里复习完了，状态不再是待复习，于是从已练池里清掉
        val result = plan(dueIds = listOf(2L, 3L), practiced = setOf(1L, 2L))

        assertEquals(setOf(2L), result.practicedIds)
        assertEquals(listOf(3L), result.pickedIds)
    }

    @Test
    fun `抽取数量不超过limit且不重复`() {
        val result = plan(dueIds = (1L..100L).toList(), limit = 7)

        assertEquals(7, result.pickedIds.size)
        assertEquals(7, result.pickedIds.distinct().size)
        assertEquals(100, result.poolTotal)
    }

    @Test
    fun `池子小于limit时只返回池子里的词`() {
        val result = plan(dueIds = listOf(1L, 2L, 3L), limit = 20)

        assertEquals(setOf(1L, 2L, 3L), result.pickedIds.toSet())
    }

    /** limit 来自界面输入，而 `List.take` 对负数会抛 IllegalArgumentException。 */
    @Test
    fun `limit为零或负数时不抛异常`() {
        assertTrue(plan(dueIds = listOf(1L, 2L), limit = 0).pickedIds.isEmpty())
        assertTrue(plan(dueIds = listOf(1L, 2L), limit = -5).pickedIds.isEmpty())
    }

    @Test
    fun `抽出的词全部来自待复习池`() {
        val due = (1L..50L).toList()
        val result = plan(dueIds = due, practiced = setOf(1L, 2L, 3L))

        assertTrue(result.pickedIds.all { it in due })
    }

    /** 打乱是「不重复抽到同一批」的依据，同一个种子必须给出同样的顺序。 */
    @Test
    fun `同一个随机种子给出同样的抽取顺序`() {
        val due = (1L..50L).toList()

        val a = PracticeSessionBuilder.plan(due, emptySet(), 10, Random(7))
        val b = PracticeSessionBuilder.plan(due, emptySet(), 10, Random(7))

        assertEquals(a.pickedIds, b.pickedIds)
    }
}
