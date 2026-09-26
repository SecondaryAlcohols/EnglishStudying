package com.alcohol.englishstudying

import com.alcohol.englishstudying.domain.srs.NewWordMixer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewWordMixerTest {

    /** 造一个按重要度降序的池子：值越小越易。 */
    private fun pool(n: Int) = (0 until n).toList()

    /** 按三等分统计结果里各难度的数量（0=易 1=中 2=难）。 */
    private fun countByThird(result: List<Int>, poolSize: Int): IntArray {
        val easyEnd = poolSize / 3
        val midEnd = poolSize * 2 / 3
        val counts = IntArray(3)
        result.forEach { w ->
            when {
                w < easyEnd -> counts[0]++
                w < midEnd -> counts[1]++
                else -> counts[2]++
            }
        }
        return counts
    }

    @Test
    fun `limit或池子为空时返回空`() {
        assertEquals(emptyList<Int>(), NewWordMixer.mix<Int>(emptyList(), 10, listOf(5, 3, 2)))
        assertEquals(emptyList<Int>(), NewWordMixer.mix(pool(30), 0, listOf(5, 3, 2)))
    }

    @Test
    fun `无效比例退化为直接取池子前limit个`() {
        assertEquals(listOf(0, 1, 2, 3), NewWordMixer.mix(pool(30), 4, listOf(0, 0, 0)))
        assertEquals(listOf(0, 1, 2, 3), NewWordMixer.mix(pool(30), 4, listOf(1, 2)))
    }

    @Test
    fun `5比3比2取10个按比例分布`() {
        val result = NewWordMixer.mix(pool(90), 10, listOf(5, 3, 2))
        assertEquals(10, result.size)
        val counts = countByThird(result, 90)
        assertEquals(intArrayOf(5, 3, 2).toList(), counts.toList())
    }

    @Test
    fun `不同难度交错出现而不是扎堆`() {
        val result = NewWordMixer.mix(pool(90), 10, listOf(5, 3, 2))
        // 前 9 个槽位里三个难度都应出现过（交错）；若扎堆，前 9 个会全是易
        val first9Thirds = result.take(9).map { w ->
            when {
                w < 30 -> 0
                w < 60 -> 1
                else -> 2
            }
        }
        assertTrue(first9Thirds.distinct().size == 3)
    }

    @Test
    fun `库存不足的难度由其他难度补足`() {
        // 池子只有 10 个词，三等分后是 3 易 / 3 中 / 4 难，而 5:3:2 在 limit=10 下
        // 向「易」要 5 个名额 —— 易桶只有 3 个，缺的 2 个只能由还有库存的难桶补上。
        //
        // 注意池子要小到「某桶库存 < 它的名额」，否则这段补给逻辑根本不会被走到：
        // 原先这里用 pool(90).take(60) + listOf(89)，那 61 个词里难桶占 20 个而名额只要 2 个，
        // 于是删掉 deal() 里的轮转补给也照样返回 10 个，用例等于没测。
        val result = NewWordMixer.mix(pool(10), 10, listOf(5, 3, 2))

        assertEquals(10, result.size)
        // 易桶的 5 个名额只兑现 3 个，剩下的给了难桶 —— 所以是 3/3/4 而不是 5/3/2
        assertEquals(listOf(3, 3, 4), countByThird(result, 10).toList())
    }

    @Test
    fun `结果不重复且都在池子里`() {
        val p = pool(30)
        val result = NewWordMixer.mix(p, 10, listOf(5, 3, 2))
        assertEquals(result.size, result.distinct().size)
        assertTrue(result.all { it in p })
    }

    @Test
    fun `池子小于limit时全部返回`() {
        val p = pool(7)
        assertEquals(7, NewWordMixer.mix(p, 10, listOf(5, 3, 2)).size)
    }

    /**
     * 回归：真实库里的坑 —— 池子如果只喂重要度最高的头部（旧实现取
     * limit*3+30 个），三等分切的全是易词，「难」名额永远拿不到尾部词。
     * 全量池 + 含难比例必须能选中尾部三分之一的词。
     */
    @Test
    fun `全量池时难名额能拿到尾部低重要度词`() {
        // 模拟真实未学库：3000 个词、重要度 11..30 的平缓梯度，降序排列
        val full = (0 until 3000).toList()
        val result = NewWordMixer.mix(full, 20, listOf(3, 22, 6))
        assertEquals(20, result.size)
        val hardWords = result.filter { it >= 2000 }
        // 6/31 的难名额 ≈ 3.87 → 至少 3 个来自尾部三分之一
        assertTrue("难的词应来自尾部三分之一，实际只有 ${hardWords.size} 个", hardWords.size >= 3)
        // 对照组：只喂头部 90 个（旧实现的池子）永远选不到尾部词
        val headOnly = NewWordMixer.mix(full.take(90), 20, listOf(3, 22, 6))
        assertTrue(headOnly.all { it < 90 })
    }

    // ---- 按词表比例 ----

    /** 造一个词表的候选桶：值是 (词表下标, 桶内序号)，便于断言归属与去重。 */
    private fun deckBucket(deck: Int, n: Int): List<Pair<Int, Int>> =
        (0 until n).map { deck to it }

    private fun countsByDeck(result: List<Pair<Int, Int>>, deckCount: Int): List<Int> =
        List(deckCount) { d -> result.count { it.first == d } }

    @Test
    fun `词表按未学词数分配名额`() {
        val buckets = listOf(deckBucket(0, 1000), deckBucket(1, 100), deckBucket(2, 5))

        val result = NewWordMixer.mixByDeckSizes(buckets, 20)

        assertEquals(20, result.size)
        // 先给每个桶扣 1 个保底，剩下 17 个按 1000:100:5 分 → 15 / 2 / 0
        assertEquals(listOf(16, 3, 1), countsByDeck(result, 3))
    }

    /**
     * 回归：不保底时 1000 / 100 / 5 三个词表分 20 个名额会算成 18 / 2 / 0，
     * 那个只有 5 个词的词表**一个都抽不到**，不管过多少天 —— 而用户是特意把它选进来的。
     */
    @Test
    fun `小词表不会饿死，保底拿到名额`() {
        val buckets = listOf(deckBucket(0, 1000), deckBucket(1, 100), deckBucket(2, 5))

        val result = NewWordMixer.mixByDeckSizes(buckets, 20)

        assertTrue("小词表应至少分到 1 个", result.any { it.first == 2 })
    }

    /** 名额不够给每个桶保底时退回纯比例，不能凭空多抽。 */
    @Test
    fun `名额少于词表数时不保底，总数仍是limit`() {
        val buckets = (0 until 5).map { deckBucket(it, 100) }

        val result = NewWordMixer.mixByDeckSizes(buckets, 3)

        assertEquals(3, result.size)
    }

    @Test
    fun `单桶时直接取前limit个`() {
        val bucket = deckBucket(0, 30)

        assertEquals(bucket.take(5), NewWordMixer.mixByDeckSizes(listOf(bucket), 5))
    }

    @Test
    fun `空桶不参与分配也不占名额`() {
        val buckets = listOf(deckBucket(0, 30), emptyList(), deckBucket(2, 30))

        val result = NewWordMixer.mixByDeckSizes(buckets, 10)

        assertEquals(10, result.size)
        assertEquals(listOf(5, 0, 5), countsByDeck(result, 3))
    }

    @Test
    fun `桶的总库存不够时全部返回`() {
        val buckets = listOf(deckBucket(0, 2), deckBucket(1, 3))

        val result = NewWordMixer.mixByDeckSizes(buckets, 10)

        assertEquals(5, result.size)
    }

    @Test
    fun `各词表交错出现而不是扎堆`() {
        val buckets = listOf(deckBucket(0, 50), deckBucket(1, 50))

        val result = NewWordMixer.mixByDeckSizes(buckets, 10)

        // 等权重的两个桶，槽位应当严格交替
        assertEquals(List(10) { it % 2 }, result.map { it.first })
    }

    @Test
    fun `结果不重复，且每个词都来自它所属的桶`() {
        val buckets = listOf(deckBucket(0, 20), deckBucket(1, 20), deckBucket(2, 20))

        val result = NewWordMixer.mixByDeckSizes(buckets, 15)

        assertEquals(15, result.size)
        assertEquals(result.size, result.distinct().size)
        assertTrue(result.all { it in buckets[it.first] })
    }

    @Test
    fun `limit为零或全是空桶时返回空`() {
        assertEquals(
            emptyList<Pair<Int, Int>>(),
            NewWordMixer.mixByDeckSizes(listOf(deckBucket(0, 5)), 0)
        )
        // 两个裸 emptyList 推不出 T，得显式给类型参数
        assertEquals(
            emptyList<Pair<Int, Int>>(),
            NewWordMixer.mixByDeckSizes<Pair<Int, Int>>(listOf(emptyList(), emptyList()), 10)
        )
    }
}
