package com.alcohol.englishstudying.domain.srs

/** 学习时用户对一张卡的四档自评。 */
enum class Rating {
    AGAIN,
    HARD,
    GOOD,
    EASY;

    companion object {
        fun fromNameOrNull(name: String?): Rating? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** 一张卡的记忆阶段。 */
enum class CardState {
    /** 从未学过。 */
    NEW,

    /** 正在学习步内（分钟级重排），尚未毕业到天级间隔。 */
    LEARNING,

    /**
     * 学过的词被遗忘了，回到学习步重学。
     *
     * 与 [LEARNING] 的区别**只在语义与统计上**：两者共用同一套学习步长
     * （`SrsSettings.learningStepsMinutes`），调度行为完全一致。分开是为了能回答
     * 「今天有几个学过的词忘了」，以及让词库能筛出正在重学的词 —— 合成一个状态时
     * 「新词在学习步」和「学过的词忘了在重学」在数据上无法区分。
     *
     * **它会被消耗掉**：一旦作答进入学习步，[Sm2Scheduler] 就把它写成 [LEARNING]，
     * 走完学习步再毕业回 [REVIEW]。所以要拿它量什么，别用
     * `COUNT(state = 'RELEARNING')` —— 那个数会随着用户把卡学走而衰减。今日重新学习的
     * 积压值由 `ReviewDao.countPendingRelearn` 取一次、存进 DataStore，见
     * `SettingsRepository.relearnBacklog`。
     */
    RELEARNING,

    /** 已毕业，按天级间隔复习。 */
    REVIEW,

    /** 遗忘次数过多被自动挂起，不再出现在每日队列里，需手动恢复。 */
    SUSPENDED,

    /** 用户标记「太简单」，移出学习；在词库里移出名单后恢复。 */
    TOO_EASY,

    /** 用户标记「太难」，移出学习；在词库里移出名单后恢复。 */
    TOO_HARD;

    companion object {
        fun fromNameOrNull(name: String?): CardState? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
