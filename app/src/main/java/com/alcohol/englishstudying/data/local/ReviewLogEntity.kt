package com.alcohol.englishstudying.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.alcohol.englishstudying.domain.srs.Rating

/** 每次作答留痕，用于统计与「今天学了多久」。 */
@Entity(
    tableName = "review_logs",
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["wordId"]), Index(value = ["reviewedAt"])]
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val wordId: Long,
    val reviewedAt: Long,
    val rating: Rating,
    val elapsedMs: Long = 0L,
    /** 本次评分后该词的间隔，便于事后回看算法是否合理。 */
    val intervalDaysAfter: Double = 0.0
)
