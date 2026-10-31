package com.alcohol.englishstudying.domain.srs

import kotlin.math.floor

/**
 * 新词的抽取编排。
 *
 * 两个入口共用同一套「定额 + 铺槽」：[mix] 按难度混合（易/中/难三等分），
 * [mixByDeckSizes] 按词表比例（权重就是各词表的未学词数）。两者都只是决定
 * **每个候选桶分到几个名额、以及这些名额以什么次序交错**，纯粹是编排，不碰算法参数。
 *
 * 纯函数便于单测；名额用最大余额法，槽位用分数槽位法铺开（天然交错），
 * 某个桶库存不足时名额回收、轮转补给还有库存的桶。
 */
object NewWordMixer {

    /**
     * 新词按难度混合抽取。
     *
     * 难度不是新字段，而是**COCA 排名的三等分**：调用方把候选池按排名升序
     * （越常用越靠前）排好传进来，前三分之一算「易」，最后三分之一算「难」，中间算「中」。
     * 没有排名的词（frequency = 0）由调用方排在末尾，自然落进「难」档。然后按 [ratio]
     * （如 5:3:2）分配名额，交错铺到今日队列里 —— 背的时候易中难是穿插出现的，
     * 不是先背完易的再背难的。
     *
     * **池子必须是范围内全部未学词**，不能只喂排名最靠前的头部一小段 —— 否则三等分
     * 切的是同一段易词，用户配的「难」名额拿不到真正生僻的词，混合名存实亡。
     *
     * [ratio] 无效（非三份或全零）时退化为直接取池子前 [limit] 个。
     */
    fun <T> mix(pool: List<T>, limit: Int, ratio: List<Int>): List<T> {
        if (limit <= 0 || pool.isEmpty()) return emptyList()
        val cleanRatio = ratio.map { it.coerceAtLeast(0) }
        if (cleanRatio.size != 3 || cleanRatio.sum() <= 0) return pool.take(limit)

        val n = pool.size
        // 池子不足三条也照常分：边界取整后保证 easy <= mid 区间合法
        val easyEnd = floor(n.toDouble() / 3).roundToIntCompat().coerceIn(1, n)
        val midEnd = (floor(n.toDouble() * 2 / 3) + 0.5).toInt().coerceIn(easyEnd, n)
        val buckets = listOf(
            pool.subList(0, easyEnd),
            pool.subList(easyEnd, midEnd),
            pool.subList(midEnd, n)
        )

        return deal(buckets, limit, cleanRatio, minPerBucket = 0)
    }

    /**
     * 新词按词表比例抽取。
     *
     * [buckets] 是每个候选词表的未学词（各自已按 COCA 排名升序排好），**权重就是各自的词数** ——
     * 所以调用方必须传进来完整的候选列表，只取头部一小段会让权重变成那个截断值。
     *
     * 每个非空词表**保底 1 个名额**（名额够分时）。不保底的话小词表会永久饿死：
     * 1000 / 100 / 5 三个词表分 20 个名额，纯比例算出来是 18 / 2 / 0，那个 5 词的
     * 词表一个词都抽不到 —— 而用户明明是特意把它选进来的。
     *
     * 同一批桶之间**不做去重**：一个词可能同时属于多个词表，去重是调用方在组桶时
     * 的责任（先到先得），这里只管编排。
     */
    fun <T> mixByDeckSizes(buckets: List<List<T>>, limit: Int): List<T> {
        if (limit <= 0) return emptyList()
        val nonEmpty = buckets.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return emptyList()
        // 只有一个词表时不必绕定额这一圈，直接按排名取
        if (nonEmpty.size == 1) return nonEmpty[0].take(limit)
        return deal(nonEmpty, limit, nonEmpty.map { it.size }, minPerBucket = 1)
    }

    // ---- 内部：定额与铺槽 ----

    private fun <T> deal(
        buckets: List<List<T>>,
        limit: Int,
        ratio: List<Int>,
        minPerBucket: Int
    ): List<T> {
        val quota = quotaOf(ratio, limit, minPerBucket)

        // 槽位：每个桶的名额按分数位置铺开，天然把各个桶交错起来
        val slots = buckets.indices
            .flatMap { b ->
                (0 until quota[b]).map { k -> Pair((k + 0.5) / quota[b], b) }
            }
            .sortedWith(compareBy({ it.first }, { it.second }))

        val cursors = IntArray(buckets.size)
        val out = ArrayList<T>(limit)
        for ((_, bucket) in slots) {
            if (cursors[bucket] < buckets[bucket].size) {
                out += buckets[bucket][cursors[bucket]++]
            }
        }

        // 名额有剩但库存不足（如难的词太少、小词表已经抽干）：轮转补给还有库存的桶
        var fallback = 0
        while (out.size < limit) {
            val available = buckets.indices.filter { cursors[it] < buckets[it].size }
            if (available.isEmpty()) break
            val pick = available[fallback++ % available.size]
            out += buckets[pick][cursors[pick]++]
        }
        return out
    }

    /**
     * 把 [limit] 个名额按 [ratio] 分到各个桶。
     *
     * [minPerBucket] > 0 且名额够分（`limit >= 桶数 × 保底数`）时，先给每个桶扣下保底名额，
     * **剩下的**再按比例分 —— 这样保底不会把大桶的比例整个掀翻。名额不够保底时退回纯比例。
     */
    private fun quotaOf(ratio: List<Int>, limit: Int, minPerBucket: Int): List<Int> {
        val count = ratio.size
        if (minPerBucket <= 0 || limit < count * minPerBucket) {
            return largestRemainder(ratio, limit)
        }
        val rest = limit - count * minPerBucket
        val extra = largestRemainder(ratio, rest)
        return List(count) { minPerBucket + extra[it] }
    }

    /**
     * 最大余额法：先按比例取整数部分，余下的名额给小数部分最大的那几个。
     *
     * 全程整数运算 —— 换成浮点再 floor 会在 5:3:2 这类边界上飘。
     */
    private fun largestRemainder(ratio: List<Int>, amount: Int): List<Int> {
        val total = ratio.sum()
        if (amount <= 0 || total <= 0) return List(ratio.size) { 0 }

        val quota = MutableList(ratio.size) { ratio[it] * amount / total }
        var remainder = amount - quota.sum()
        val byFraction = ratio.indices
            .sortedByDescending { ratio[it].toDouble() * amount / total - quota[it] }

        var i = 0
        while (remainder > 0 && byFraction.isNotEmpty()) {
            quota[byFraction[i % byFraction.size]]++
            remainder--
            i++
        }
        return quota
    }

    private fun Double.roundToIntCompat(): Int {
        val f = floor(this + 0.5)
        return f.toInt()
    }
}
