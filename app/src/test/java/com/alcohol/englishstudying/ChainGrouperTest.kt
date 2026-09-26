package com.alcohol.englishstudying

import com.alcohol.englishstudying.domain.relations.ChainGrouper
import org.junit.Assert.assertEquals
import org.junit.Test

class ChainGrouperTest {

    private fun ids(vararg values: Long) = values.toList()
    private fun group(
        items: List<Long>,
        pairs: List<Pair<Long, Long>>
    ) = ChainGrouper.group(items, keyOf = { it }, pairs = pairs)

    @Test
    fun `没有边时原样返回`() {
        val items = ids(5, 3, 9)
        assertEquals(items, group(items, emptyList()))
    }

    @Test
    fun `少于两个条目时原样返回`() {
        val items = ids(1)
        assertEquals(items, group(items, listOf(1L to 2L)))
    }

    @Test
    fun `同一条链的词聚到一起`() {
        // 队列 1..7，其中 2-4、3-4 成链，6-7 成链
        val items = ids(1, 2, 3, 4, 5, 6, 7)
        val pairs = listOf(2L to 4L, 3L to 4L, 6L to 7L)
        // 链 {2,3,4} 的第一个成员在位置 1，链 {6,7} 在位置 5，其余单飞
        assertEquals(ids(1, 2, 3, 4, 5, 6, 7), group(items, pairs))
    }

    @Test
    fun `组的先后按第一个成员的原位置`() {
        // 交错的两条链：{8,5} 的第一个成员在位置 1，先于 {7,3}（位置 2）
        val items = ids(1, 8, 7, 5, 3)
        val pairs = listOf(8L to 5L, 7L to 3L)
        assertEquals(ids(1, 8, 5, 7, 3), group(items, pairs))
    }

    @Test
    fun `组内保持原相对顺序`() {
        val items = ids(9, 2, 1, 4)
        val pairs = listOf(9L to 4L, 4L to 1L)
        // 链 {9,2?} —— 2 不在任何边里；{9,4,1} 整条聚到最前，内部仍是 9,1,4 的相对序
        assertEquals(ids(9, 1, 4, 2), group(items, pairs))
    }

    @Test
    fun `边指向队列外的词不影响分组`() {
        val items = ids(1, 2, 3)
        // 2-99 的 99 不在队列里，这条边不应把任何东西拉到一起
        val pairs = listOf(2L to 99L)
        assertEquals(items, group(items, pairs))
    }

    @Test
    fun `分组结果与边的遍历顺序无关`() {
        val items = ids(1, 2, 3)
        val forward = listOf(1L to 2L, 2L to 3L)
        val backward = listOf(3L to 2L, 2L to 1L)
        assertEquals(group(items, forward), group(items, backward))
    }

    @Test
    fun `长链全组连续`() {
        // 5 条边连成一条链，5 个成员全部连续出现
        val items = ids(10, 20, 30, 1, 40, 50, 2)
        val pairs = listOf(10L to 20L, 20L to 30L, 30L to 40L, 40L to 50L)
        assertEquals(ids(10, 20, 30, 40, 50, 1, 2), group(items, pairs))
    }
}
