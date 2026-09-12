package com.alcohol.englishstudying.domain.srs

/**
 * 一张卡的记忆状态（纯领域模型，不带 Room 注解，便于单测）。
 *
 * 时间统一用「距 epoch 的分钟数」表示：天级间隔取当天零点，
 * 学习步取精确分钟。这样跨天与同天重排可以用同一套比较逻辑。
 */
data class SrsState(
    val easeFactor: Double = 2.5,
    val intervalDays: Double = 0.0,
    val repetitions: Int = 0,
    /** 当前处在学习步的第几步，下标从 0 开始。 */
    val learningStep: Int = 0,
    val dueAtMinutes: Long = 0L,
    val lastReviewedAt: Long? = null,
    val lapses: Int = 0,
    val state: CardState = CardState.NEW
) {
    companion object {
        const val MINUTES_PER_DAY = 1440L

        fun newCard(todayEpochDay: Long, ease: Double): SrsState = SrsState(
            easeFactor = ease,
            dueAtMinutes = todayEpochDay * MINUTES_PER_DAY,
            state = CardState.NEW
        )
    }
}
