package com.alcohol.englishstudying.domain.srs

/** 新词的抽取顺序。 */
enum class NewWordOrder {
    /** 词频高者优先（frequency 列降序）。 */
    FREQUENCY,

    /** 按入库顺序。 */
    SEQUENCE,

    /** 随机抽取。 */
    RANDOM
}

/**
 * 记忆算法的全部可调参数。这些值全部由用户在设置页控制，
 * 因此 [sanitized] 必须能把任意脏输入（DataStore 里存了旧值、用户输入越界）
 * 收敛回合法范围，而不是让算法在运行时算出 NaN 或负间隔。
 */
data class SrsSettings(
    val dailyNewLimit: Int = 20,
    val dailyReviewLimit: Int = 200,
    val newWordOrder: NewWordOrder = NewWordOrder.FREQUENCY,
    /** 学习步，单位分钟。走完这些步才毕业到天级间隔。 */
    val learningStepsMinutes: List<Int> = listOf(1, 10),
    val graduatingIntervalDays: Double = 1.0,
    val easyIntervalDays: Double = 4.0,
    val startingEase: Double = 2.5,
    val easyBonus: Double = 1.3,
    val hardMultiplier: Double = 1.2,
    val intervalModifier: Double = 1.0,
    val minIntervalDays: Double = 1.0,
    val maxIntervalDays: Double = 365.0,
    /** 累计遗忘达到该次数即自动挂起。 */
    val leechThreshold: Int = 8,
    /** 只学某个词表；null 表示全部词库。 */
    val studyDeckId: Long? = null
) {
    fun sanitized(): SrsSettings {
        val minInterval = minIntervalDays.coerceIn(1.0, 3650.0)
        val maxInterval = maxIntervalDays.coerceIn(minInterval, 36500.0)
        return copy(
            dailyNewLimit = dailyNewLimit.coerceIn(0, 1000),
            dailyReviewLimit = dailyReviewLimit.coerceIn(0, 5000),
            learningStepsMinutes = learningStepsMinutes
                .filter { it > 0 }
                .ifEmpty { listOf(1, 10) }
                .let { steps -> if (steps.size > 10) steps.take(10) else steps },
            graduatingIntervalDays = graduatingIntervalDays.coerceIn(1.0, 365.0),
            easyIntervalDays = easyIntervalDays.coerceIn(1.0, 3650.0),
            startingEase = startingEase.coerceIn(1.3, 3.0),
            easyBonus = easyBonus.coerceIn(1.0, 5.0),
            hardMultiplier = hardMultiplier.coerceIn(1.0, 5.0),
            intervalModifier = intervalModifier.coerceIn(0.1, 5.0),
            minIntervalDays = minInterval,
            maxIntervalDays = maxInterval,
            leechThreshold = leechThreshold.coerceIn(1, 100)
        )
    }

    companion object {
        val DEFAULT = SrsSettings()
    }
}
