package com.alcohol.englishstudying.domain.srs

import kotlin.math.roundToLong

/**
 * 改良版 SM-2。
 *
 * 与原始 SM-2 的差别有三处，都是为了配合「学习步 + 四档评分」这套交互：
 *  1. 新词先走 [SrsSettings.learningStepsMinutes] 里的分钟级学习步，走完才毕业到天级间隔；
 *  2. 四档评分的处理互相独立，而不是折算成一个 0–5 的 quality；
 *  3. 遗忘累计到 [SrsSettings.leechThreshold] 次自动挂起。
 *
 * 纯函数，不依赖 Android 与系统时钟 —— 时间由调用方以参数传入，因此可以直接单测。
 */
class Sm2Scheduler(settings: SrsSettings) {

    // 构造时就收敛参数，调用方即使传了脏数据也不会算出 NaN 或负间隔
    private val s: SrsSettings = settings.sanitized()

    fun review(
        current: SrsState,
        rating: Rating,
        nowMinutes: Long,
        todayEpochDay: Long,
        nowMillis: Long
    ): SrsState {
        // 挂起的卡不参与调度，只能由用户在词库里手动恢复
        if (current.state == CardState.SUSPENDED) return current

        var ease = current.easeFactor
        var interval = current.intervalDays
        var repetitions = current.repetitions
        var lapses = current.lapses
        var step = current.learningStep
        var state = current.state
        var due = current.dueAtMinutes

        val inLearning = state == CardState.NEW || state == CardState.LEARNING

        if (inLearning) {
            when (rating) {
                Rating.AGAIN -> {
                    // 回到第一步重来，当天稍后再考一次
                    step = 0
                    ease -= EASE_PENALTY_AGAIN
                    state = CardState.LEARNING
                    due = nowMinutes + s.learningStepsMinutes[0]
                }

                Rating.HARD -> {
                    // 原地踏步：用当前这一步的间隔再排一次，不前进也不毕业
                    val index = step.coerceIn(0, s.learningStepsMinutes.lastIndex)
                    state = CardState.LEARNING
                    due = nowMinutes + s.learningStepsMinutes[index]
                }

                Rating.GOOD -> {
                    val next = step + 1
                    if (next >= s.learningStepsMinutes.size) {
                        // 走完最后一步，毕业到天级间隔
                        interval = s.graduatingIntervalDays
                        repetitions = 1
                        state = CardState.REVIEW
                        due = dueForDays(todayEpochDay, interval)
                    } else {
                        step = next
                        state = CardState.LEARNING
                        due = nowMinutes + s.learningStepsMinutes[next]
                    }
                }

                Rating.EASY -> {
                    // 直接跳过剩余学习步毕业
                    interval = s.easyIntervalDays
                    repetitions = 1
                    state = CardState.REVIEW
                    due = dueForDays(todayEpochDay, interval)
                }
            }
        } else {
            when (rating) {
                Rating.AGAIN -> {
                    lapses += 1
                    repetitions = 0
                    interval = 0.0
                    step = 0
                    ease -= EASE_PENALTY_AGAIN
                    state = CardState.LEARNING
                    due = nowMinutes + s.learningStepsMinutes[0]
                }

                Rating.HARD -> {
                    interval = if (repetitions == 0) s.graduatingIntervalDays
                    else interval * s.hardMultiplier
                    ease -= EASE_PENALTY_HARD
                    repetitions += 1
                    due = dueForDays(todayEpochDay, interval)
                }

                Rating.GOOD -> {
                    interval = if (repetitions == 0) s.graduatingIntervalDays
                    else interval * ease
                    repetitions += 1
                    due = dueForDays(todayEpochDay, interval)
                }

                Rating.EASY -> {
                    interval = if (repetitions == 0) s.easyIntervalDays
                    else interval * ease * s.easyBonus
                    repetitions += 1
                    due = dueForDays(todayEpochDay, interval)
                }
            }
        }

        // 只有天级间隔才套用全局修正与上下限；学习步是分钟级的，不能被 minIntervalDays 压成 1 天
        if (state == CardState.REVIEW) {
            interval = (interval * s.intervalModifier)
                .coerceIn(s.minIntervalDays, s.maxIntervalDays)
            due = dueForDays(todayEpochDay, interval)
        }

        ease = ease.coerceIn(MIN_EASE, MAX_EASE)
        if (lapses >= s.leechThreshold) state = CardState.SUSPENDED

        return current.copy(
            easeFactor = ease,
            intervalDays = interval,
            repetitions = repetitions,
            learningStep = step,
            dueAtMinutes = due,
            lastReviewedAt = nowMillis,
            lapses = lapses,
            state = state
        )
    }

    /**
     * 四个按钮上显示的「下次间隔」预览。背单词时最怕按错，
     * 把结果直接印在按钮上比事后去翻统计有用。
     */
    fun previewIntervals(
        current: SrsState,
        nowMinutes: Long,
        todayEpochDay: Long
    ): Map<Rating, String> = Rating.entries.associateWith { rating ->
        val result = review(current, rating, nowMinutes, todayEpochDay, nowMillis = 0L)
        formatInterval(result, nowMinutes)
    }

    private fun formatInterval(state: SrsState, nowMinutes: Long): String = when (state.state) {
        CardState.SUSPENDED -> "挂起"
        CardState.LEARNING, CardState.NEW -> {
            val minutes = (state.dueAtMinutes - nowMinutes).coerceAtLeast(1L)
            when {
                minutes < 60 -> "$minutes 分钟"
                else -> "${minutes / 60} 小时"
            }
        }

        CardState.REVIEW -> {
            val days = state.intervalDays.roundToLong()
            when {
                days < 30 -> "$days 天"
                days < 365 -> "${(days / 30.0).roundToLong()} 个月"
                else -> String.format("%.1f 年", days / 365.0)
            }
        }
    }

    private fun dueForDays(todayEpochDay: Long, intervalDays: Double): Long =
        (todayEpochDay + intervalDays.roundToLong().coerceAtLeast(1L)) * SrsState.MINUTES_PER_DAY

    companion object {
        const val MIN_EASE = 1.3
        const val MAX_EASE = 3.0
        private const val EASE_PENALTY_AGAIN = 0.20
        private const val EASE_PENALTY_HARD = 0.15
    }
}
