package com.alcohol.englishstudying.data.repo

import androidx.room.withTransaction
import com.alcohol.englishstudying.data.local.AppDatabase
import com.alcohol.englishstudying.data.local.ReviewLogEntity
import com.alcohol.englishstudying.data.local.ReviewStateEntity
import com.alcohol.englishstudying.data.local.WordEntity
import com.alcohol.englishstudying.domain.srs.DailyPlanBuilder
import com.alcohol.englishstudying.domain.srs.NewWordOrder
import com.alcohol.englishstudying.domain.srs.Rating
import com.alcohol.englishstudying.domain.srs.Sm2Scheduler
import com.alcohol.englishstudying.domain.srs.SrsSettings
import com.alcohol.englishstudying.domain.srs.SrsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 学习队列里的一张卡：词条本体 + 它当前的记忆状态。 */
data class StudyCard(
    val word: WordEntity,
    val state: SrsState
)

data class DailyProgress(
    val dueCount: Int,
    val newCount: Int,
    val answeredToday: Int,
    val elapsedTodayMs: Long
)

class StudyRepository(
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val currentTime: () -> LocalTime = { LocalTime.now() }
) {

    /**
     * 组装今日队列：到期复习卡在前，新词在后。
     *
     * 新词不预建 review_states 行 —— 只有第一次作答时才建。
     * 否则新词会被到期查询当成「到期卡」捞出来，每日限额就形同虚设。
     */
    suspend fun buildSession(settings: SrsSettings): List<StudyCard> {
        val todayEpochDay = today().toEpochDay()
        val nowMinutes = nowMinutes(todayEpochDay)
        val deckId = settings.studyDeckId

        val dueStates = db.reviewDao().dueStates(nowMinutes, deckId, settings.dailyReviewLimit)
        val dueCards = if (dueStates.isEmpty()) {
            emptyList()
        } else {
            val wordsById = db.wordDao().getByIds(dueStates.map { it.wordId }).associateBy { it.id }
            // 按 dueStates 的顺序取词，getByIds 不保证顺序
            dueStates.mapNotNull { state ->
                wordsById[state.wordId]?.let { StudyCard(it, state.toDomain()) }
            }
        }

        val newWords = when (settings.newWordOrder) {
            NewWordOrder.FREQUENCY -> db.wordDao().newWordsByFrequency(settings.dailyNewLimit, deckId)
            NewWordOrder.SEQUENCE -> db.wordDao().newWordsBySequence(settings.dailyNewLimit, deckId)
            NewWordOrder.RANDOM -> db.wordDao().newWordsByRandom(settings.dailyNewLimit, deckId)
        }
        val newCards = newWords.map {
            StudyCard(it, SrsState.newCard(todayEpochDay, settings.startingEase))
        }

        return DailyPlanBuilder.build(dueCards, newCards, settings)
    }

    /** 评分并落库。返回评分后的新状态，供界面直接展示「下次复习」。 */
    suspend fun submit(
        card: StudyCard,
        rating: Rating,
        elapsedMs: Long,
        settings: SrsSettings
    ): SrsState {
        val todayEpochDay = today().toEpochDay()
        val nowMinutes = nowMinutes(todayEpochDay)
        val millis = now()

        val next = Sm2Scheduler(settings).review(
            current = card.state,
            rating = rating,
            nowMinutes = nowMinutes,
            todayEpochDay = todayEpochDay,
            nowMillis = millis
        )

        db.withTransaction {
            db.reviewDao().upsert(ReviewStateEntity.fromDomain(card.word.id, next))
            db.reviewDao().insertLog(
                ReviewLogEntity(
                    wordId = card.word.id,
                    reviewedAt = millis,
                    rating = rating,
                    elapsedMs = elapsedMs,
                    intervalDaysAfter = next.intervalDays
                )
            )
        }
        return next
    }

    /**
     * 今日进度。
     *
     * 注意 [dueCount] 里的「现在」是在订阅时算好并固定的，不会随时间推进自动刷新。
     * 对一次学习会话来说够用 —— 进入学习页时重新订阅即可拿到新值。
     */
    fun observeProgress(settings: SrsSettings): Flow<DailyProgress> {
        val todayEpochDay = today().toEpochDay()
        val nowMinutes = nowMinutes(todayEpochDay)
        val startMillis = today().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMillis = startMillis + MILLIS_PER_DAY

        return combine(
            db.reviewDao().observeDueCount(nowMinutes, settings.studyDeckId),
            db.wordDao().observeNewCount(settings.studyDeckId),
            db.reviewDao().observeReviewedCount(startMillis, endMillis),
            db.reviewDao().observeElapsedMs(startMillis, endMillis)
        ) { due, new, answered, elapsed ->
            DailyProgress(
                dueCount = due,
                newCount = new,
                answeredToday = answered,
                elapsedTodayMs = elapsed
            )
        }
    }

    /** 挂起的卡不在每日队列里，只能在词库里手动恢复。 */
    suspend fun unsuspend(wordId: Long) = db.reviewDao().unsuspend(wordId)

    suspend fun resetProgress(wordId: Long) = db.reviewDao().resetWord(wordId)

    suspend fun resetAllProgress() {
        db.withTransaction {
            db.reviewDao().resetAll()
            db.reviewDao().clearLogs()
        }
    }

    private fun nowMinutes(todayEpochDay: Long): Long =
        DailyPlanBuilder.nowMinutes(todayEpochDay, currentTime().hour, currentTime().minute)

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
