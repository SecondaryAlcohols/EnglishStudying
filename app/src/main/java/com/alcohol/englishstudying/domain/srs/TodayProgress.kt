package com.alcohol.englishstudying.domain.srs

/**
 * 卡片页顶部那条「今日进度条」的分段。
 *
 * 条是**今天的**：分母 = 界面上「今日复习」的分母（每日复习上限截断后的今日复习总量）
 * + 「上限」那一格的新词额度。前者是当天要过完的复习卡，后者是当天计划发的新词，
 * 两个都是「今天要做的事」，加起来才是完整的一天。
 *
 * 三段颜色：
 *  - [done]（深绿）：今天已经了结的 —— 已复习的旧词 + 已掌握的新词。
 *    旧词点「太简单 / 太难」也算已复习（`reviewDoneToday` 里含着了），新词点「太简单」
 *    算掌握（`masteredToday` 里含着了），不用单独再加。
 *  - [inProgress]（浅绿）：今天动过、还没了结的 —— 复习中点了模糊还没再答对的旧词，
 *    加上今天开始学、还没掌握的新词。
 *  - 其余（无色）：今天还没做的 —— 还没复习的卡 + 还没发出来的新词名额。
 *
 * 「今天标了太难的新词」不进任何一段：它们既不进 [done] 也不进 [inProgress]，
 * 而新词额度 [newLimit] 那一格本来就已经把它们扣掉了（见 `StatsStrip` 的 `tooHardNew`），
 * 所以分母不会留下一截永远填不上的白格。
 */
data class TodayProgressSegments(
    val done: Int,
    val inProgress: Int,
    val total: Int
) {
    /** 深绿占比。 */
    val doneFraction: Float get() = fractionOf(done)

    /** 浅绿占比。 */
    val inProgressFraction: Float get() = fractionOf(inProgress)

    private fun fractionOf(value: Int): Float =
        if (total <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)
}

object TodayProgress {

    /**
     * @param reviewTarget 界面「今日复习」的分母（已算进每日复习上限）
     * @param newLimit 界面「上限」那一格的新词额度（已扣掉今天标「太难」的新词）
     * @param reviewDone 今天已复习的旧词（含旧词被标太简单 / 太难）
     * @param masteredNew 今天已掌握的新词（含新词被标太简单）
     * @param hazy 今天点过模糊、还没再答对的旧词
     * @param startedNew 今天开始学的新词（含已掌握的）
     */
    fun segments(
        reviewTarget: Int,
        newLimit: Int,
        reviewDone: Int,
        masteredNew: Int,
        hazy: Int,
        startedNew: Int
    ): TodayProgressSegments {
        val total = (reviewTarget.coerceAtLeast(0) + newLimit.coerceAtLeast(0))
        val done = (reviewDone + masteredNew).coerceAtLeast(0)
        // 初学里已经掌握的那部分算深绿，这里只补「还在学」的
        val learningNew = (startedNew - masteredNew).coerceAtLeast(0)
        val inProgress = (hazy + learningNew).coerceAtLeast(0)

        val safeDone = done.coerceIn(0, total)
        val safeInProgress = inProgress.coerceIn(0, total - safeDone)
        return TodayProgressSegments(
            done = safeDone,
            inProgress = safeInProgress,
            total = total
        )
    }
}
