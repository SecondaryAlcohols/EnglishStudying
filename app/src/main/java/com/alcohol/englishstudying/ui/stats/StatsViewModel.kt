package com.alcohol.englishstudying.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.local.AppDatabase
import com.alcohol.englishstudying.data.prefs.SettingsRepository
import com.alcohol.englishstudying.data.repo.DailyProgress
import com.alcohol.englishstudying.data.repo.StudyRepository
import com.alcohol.englishstudying.data.repo.WordRepository
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.srs.CardState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class StatsUiState(
    val totalWords: Int = 0,
    /** 学习范围内还没学过、也没被挂起的词。 */
    val newCount: Int = 0,
    val learningCount: Int = 0,
    val reviewCount: Int = 0,
    val suspendedCount: Int = 0,
    val progress: DailyProgress? = null
)

/** 把几个同类型计数先合成一个对象，避免用到无类型的 vararg combine。 */
private data class LibraryCounts(
    val total: Int,
    val newCount: Int,
    val learning: Int,
    val review: Int,
    val suspended: Int
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    database: AppDatabase,
    private val wordRepository: WordRepository,
    private val studyRepository: StudyRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    private val reviewDao = database.reviewDao()

    val state: StateFlow<StatsUiState> = settingsRepository.srsSettings
        .flatMapLatest { settings ->
            val counts = combine(
                wordRepository.observeCount(),
                // 用 DAO 里「没有 review_states 行」的定义，而不是拿总数去减 ——
                // 挂起的词也有状态行，减法会把它误算成新词
                wordRepository.observeNewCount(settings.studyDeckId),
                reviewDao.observeCountByState(CardState.LEARNING.name),
                reviewDao.observeCountByState(CardState.REVIEW.name),
                reviewDao.observeCountByState(CardState.SUSPENDED.name)
            ) { total, newCount, learning, review, suspended ->
                LibraryCounts(total, newCount, learning, review, suspended)
            }

            combine(counts, studyRepository.observeProgress(settings)) { c, progress ->
                StatsUiState(
                    totalWords = c.total,
                    newCount = c.newCount,
                    learningCount = c.learning,
                    reviewCount = c.review,
                    suspendedCount = c.suspended,
                    progress = progress
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                StatsViewModel(
                    database = container.database,
                    wordRepository = container.words,
                    studyRepository = container.study,
                    settingsRepository = container.settings
                )
            }
        }
    }
}
