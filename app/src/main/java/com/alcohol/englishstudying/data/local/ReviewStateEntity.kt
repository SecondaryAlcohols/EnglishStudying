package com.alcohol.englishstudying.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.alcohol.englishstudying.domain.srs.CardState
import com.alcohol.englishstudying.domain.srs.SrsState

/**
 * 每词一条的记忆状态。[wordId] 既是主键也是外键，删词时级联删除。
 *
 * [dueAtMinutes] 用「距 epoch 的分钟数」而不是时间戳：天级间隔取当天零点，
 * 学习步取精确分钟，两者可以用同一个字段比较，排序与筛选都在 SQL 里完成。
 */
@Entity(
    tableName = "review_states",
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["dueAtMinutes"]), Index(value = ["state"])]
)
data class ReviewStateEntity(
    @PrimaryKey val wordId: Long,
    val easeFactor: Double = 2.5,
    val intervalDays: Double = 0.0,
    val repetitions: Int = 0,
    val learningStep: Int = 0,
    val dueAtMinutes: Long = 0L,
    val lastReviewedAt: Long? = null,
    val lapses: Int = 0,
    val state: CardState = CardState.NEW
) {
    fun toDomain(): SrsState = SrsState(
        easeFactor = easeFactor,
        intervalDays = intervalDays,
        repetitions = repetitions,
        learningStep = learningStep,
        dueAtMinutes = dueAtMinutes,
        lastReviewedAt = lastReviewedAt,
        lapses = lapses,
        state = state
    )

    companion object {
        fun fromDomain(wordId: Long, s: SrsState): ReviewStateEntity = ReviewStateEntity(
            wordId = wordId,
            easeFactor = s.easeFactor,
            intervalDays = s.intervalDays,
            repetitions = s.repetitions,
            learningStep = s.learningStep,
            dueAtMinutes = s.dueAtMinutes,
            lastReviewedAt = s.lastReviewedAt,
            lapses = s.lapses,
            state = s.state
        )
    }
}
