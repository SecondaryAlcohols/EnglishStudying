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

    /**
     * 先按 [sectionOf] 把条目切成几段（段的先后按首次出现的顺序），每段各自 [group]，
     * 再按原段序拼回去 —— **段与段之间绝不互相搬运**。
     *
     * 用于「同一组变形的词放在一起背」：建队顺序是「复习卡 → 学习卡 → 新词」，而 [group]
     * 是按「组内第一个成员的位置」整组搬运的。整队一起排时，队尾的新词会被复习卡里的
     * 同形词（`knows` → `know`、`leaves` → `leave`）提到复习流中间 —— 复习只走了十几张
     * 就先学了两个新词，首页「初学 / 掌握」跟着提前跳数，看着像复习没完就进了学习
     * （2026-10-26 真机实测）。分段之后连排只在同类卡内部生效，新词留在新词区。
     */
    fun <T> groupBySection(
        items: List<T>,
        keyOf: (T) -> Long,
        pairs: Collection<Pair<Long, Long>>,
        sectionOf: (T) -> Int
    ): List<T> {
        if (items.size < 2) return items

        // LinkedHashMap：段的先后 = 首次出现的先后，拼回来仍是「已学卡 → 新词」
        val sections = LinkedHashMap<Int, MutableList<T>>()
        items.forEach { sections.getOrPut(sectionOf(it)) { ArrayList() }.add(it) }
        if (sections.size < 2) return group(items, keyOf, pairs)

        return sections.values.flatMap { section ->
            // 只留下两端都在本段里的边：跨段的边一律不参与，否则并查集会把两段接上
            val ids = section.mapTo(HashSet(section.size), keyOf)
            group(section, keyOf, pairs.filter { it.first in ids && it.second in ids })
        }
    }
}
