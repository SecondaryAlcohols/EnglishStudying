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

    /** 已毕业，按天级间隔复习。 */
    REVIEW,

    /** 遗忘次数过多被自动挂起，不再出现在每日队列里，需手动恢复。 */
    SUSPENDED;

    companion object {
        fun fromNameOrNull(name: String?): CardState? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
