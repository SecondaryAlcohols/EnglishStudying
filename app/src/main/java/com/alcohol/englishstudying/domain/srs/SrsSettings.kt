package com.alcohol.englishstudying.domain.srs

/** 新词的抽取顺序。 */
enum class NewWordOrder {
    /** COCA 排名靠前（数字小 = 越常用）优先。0 表示没有排名，排在最后。 */
    FREQUENCY,

    /** 按入库顺序。 */
    SEQUENCE,

    /** 随机抽取。 */
    RANDOM,

    /**
     * 按难度混合。难度按 COCA 排名三等分：排名最靠前（最常用）的三分之一算「易」，
     * 最后三分之一（含无排名的）算「难」，中间算「中」，按 [SrsSettings.newWordMix]
     * 的比例交错抽取。
     */
    MIXED,

    /**
     * 按词表比例。名额按各词表的**未学词数**分配，每个选中的词表保底 1 个名额。
     *
     * 参与的词表由 [SrsSettings.weightedDeckIds] 指定；一个都没选时退化成 [FREQUENCY]。
     */
    DECK_WEIGHTED
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
    val startingEase: Double = 2.5,
    val hardMultiplier: Double = 1.2,
    val intervalModifier: Double = 1.0,
    val minIntervalDays: Double = 1.0,
    val maxIntervalDays: Double = 365.0,
    /** 累计遗忘达到该次数即自动挂起。 */
    val leechThreshold: Int = 8,
    /** 只学某个词表；null 表示全部词库。 */
    val studyDeckId: Long? = null,
    /**
     * 每连续作答这么多张卡，弹一次「单词小结」。
     *
     * 严格说这不是算法参数，而是会话的呈现节奏。放进这个类只是因为它同样由用户在
     * 设置页控制、同样需要钳制越界输入，没必要为它另起一条读写链路。
     */
    val groupSize: Int = 10,
    /** 新词按难度混合抽取时的比例「易:中:难」，仅 newWordOrder == MIXED 时生效。 */
    val newWordMix: List<Int> = listOf(5, 3, 2),

    /**
     * 「按词表比例」参与抽取的词表 id，仅 newWordOrder == DECK_WEIGHTED 时生效。
     *
     * **空列表 = 不限制词表**（退化成「按词频排名」），而不是「什么都不抽」——
     * 后者会让一个没配完的设置看起来像坏了。
     */
    val weightedDeckIds: List<Long> = emptyList()
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
            startingEase = startingEase.coerceIn(1.3, 3.0),
            hardMultiplier = hardMultiplier.coerceIn(1.0, 5.0),
            intervalModifier = intervalModifier.coerceIn(0.1, 5.0),
            minIntervalDays = minInterval,
            maxIntervalDays = maxInterval,
            leechThreshold = leechThreshold.coerceIn(1, 100),
            groupSize = groupSize.coerceIn(1, 50),
            newWordMix = newWordMix
                .map { it.coerceAtLeast(0) }
                .let { if (it.size == 3 && it.sum() > 0) it else listOf(5, 3, 2) },
            // 词表 id 只做「去重 + 丢掉非正数」—— id 是自增主键，负数只可能来自脏数据
            weightedDeckIds = weightedDeckIds.filter { it > 0L }.distinct()
        )
    }

    companion object {
        val DEFAULT = SrsSettings()
    }
}
