package com.alcohol.englishstudying.domain.relations

/**
 * 把队列里互相连通的词排到一起背。
 *
 * 输入 pair 集合（如变形关系的边）和一组条目，输出稳定重排的结果：
 * 同一连通分量的成员连续出现；各分量的先后按其**第一个成员在原列表中的位置**，
 * 分量内部保持原相对顺序。不与任何词相连的条目原地不动。
 *
 * 纯函数、不关心 StudyCard 的形状 —— 便于单测，也给日后按同根词分组留口子。
 */
object ChainGrouper {

    fun <T> group(
        items: List<T>,
        keyOf: (T) -> Long,
        pairs: Collection<Pair<Long, Long>>
    ): List<T> {
        if (items.size < 2 || pairs.isEmpty()) return items

        // 并查集，键惰性初始化：pair 里可能有列表之外的词，忽略即可（find 时自动建单例）
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long {
            parent.putIfAbsent(x, x)
            var root = x
            while (parent[root] != root) root = parent[root]!!
            var cur = x
            while (parent[cur] != cur) {
                val next = parent[cur]!!
                parent[cur] = root
                cur = next
            }
            return root
        }

        pairs.forEach { (a, b) ->
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) {
                // 统一以较小的 id 为根，保证分组结果与 pair 的遍历顺序无关
                if (ra < rb) parent[rb] = ra else parent[ra] = rb
            }
        }

        val keys = items.map { find(keyOf(it)) }
        val firstIndex = HashMap<Long, Int>()
        keys.forEachIndexed { index, key -> firstIndex.putIfAbsent(key, index) }

        // List.sort 系列是稳定排序：同一组的成员保持原相对顺序
        return items.indices
            .sortedBy { index -> firstIndex.getValue(keys[index]) }
            .map { items[it] }
    }
}
