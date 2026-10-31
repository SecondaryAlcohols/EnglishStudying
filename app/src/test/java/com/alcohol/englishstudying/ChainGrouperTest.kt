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

    // ---- groupBySection：分段连排 ----

    /** 段号：0 = 已学卡，1 = 新词（与 StudyRepository.groupFormChains 的划分一致）。 */
    private fun sectionOf(id: Long) = if (id >= 100) 1 else 0

    private fun groupBySection(
        items: List<Long>,
        pairs: List<Pair<Long, Long>>
    ) = ChainGrouper.groupBySection(items, keyOf = { it }, pairs = pairs, sectionOf = ::sectionOf)

    @Test
    fun `新词不会被已学卡的同形词提前拉到复习流里`() {
        // 2026-10-26 真机现场：复习卡 knows(2)、leaves(4)，队尾新词 know(100)、leave(101)
        val learn = ids(1, 2, 3, 4, 5)          // 复习卡
        val fresh = ids(100, 101)               // 新词
        val items = learn + fresh
        val pairs = listOf(2L to 100L, 4L to 101L)

        // 整改前：know 会紧跟在 knows 后面（位置 1），leave 紧跟 leaves
        assertEquals(ids(1, 2, 100, 3, 4, 101, 5), group(items, pairs))
        // 整改后：已学卡、新词各自连排，新词仍在队尾，且队列没有变长
        assertEquals(learn, groupBySection(items, pairs).take(learn.size))
        assertEquals(fresh, groupBySection(items, pairs).drop(learn.size))
    }

    @Test
    fun `同类卡内部的连排照旧生效`() {
        // 已学卡 1-2 成链；新词 100-101 成链 —— 两段各自聚拢，段的先后不变
        val items = ids(5, 2, 1, 3, 101, 100)
        val pairs = listOf(2L to 1L, 101L to 100L)
        assertEquals(ids(5, 2, 1, 3, 101, 100), groupBySection(items, pairs))
    }

    @Test
    fun `跨段的边不把两段接上`() {
        // 若跨段边参与并查集，{1,100} 会被当成一组、整段搬到最前；
        // 分段后两段独立：已学卡仍是 1,2，新词仍是 100,101
        val items = ids(1, 100, 2, 101)
        val pairs = listOf(1L to 100L, 2L to 101L)
        assertEquals(ids(1, 2, 100, 101), groupBySection(items, pairs))
    }

    @Test
    fun `只有一个段时等价于整队连排`() {
        val items = ids(1, 2, 3, 4)
        val pairs = listOf(2L to 4L)
        assertEquals(group(items, pairs), groupBySection(items, pairs))
    }

    @Test
    fun `分段连排不会丢词也不会改变总长`() {
        val items = ids(1, 2, 3, 100, 101, 4)
        val pairs = listOf(1L to 3L, 100L to 101L)
        val result = groupBySection(items, pairs)
        assertEquals(items.size, result.size)
        assertEquals(items.toSet(), result.toSet())
    }
}
