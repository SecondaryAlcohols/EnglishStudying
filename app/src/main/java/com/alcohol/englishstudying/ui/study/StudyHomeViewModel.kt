package com.alcohol.englishstudying.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.prefs.SettingsRepository
import com.alcohol.englishstudying.data.repo.DeckRepository
import com.alcohol.englishstudying.data.repo.StudyRepository
import com.alcohol.englishstudying.data.repo.WordRepository
import com.alcohol.englishstudying.di.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StudyHomeUiState(
    val loading: Boolean = true,
    val libraryEmpty: Boolean = false,
    /** 当前学习词表的名字；没限定词表时是「全部词库」。 */
    val deckName: String = "全部词库",
    val totalWords: Int = 0,
    val learnedWords: Int = 0,
    val newDoneToday: Int = 0,
    val startedToday: Int = 0,
    val masteredToday: Int = 0,
    /**
     * 今天重新学习的词数（学过的词今天忘了）。
     *
     * 与 [masteredToday] 不同，它**不占** [newLimit] 的额度 —— 额度算的始终是新词，
     * 重学词只是额外贴上来显示的一项，所以界面上写成 `50+17` 而不是并进那个数字。
     */
    val relearnPending: Int = 0,
    val newLimit: Int = 0,
    val reviewDoneToday: Int = 0,
    /** 处于学习步内（学习中或重学中）的卡数。 */
    val learningCount: Int = 0,
    /**
     * 待复习表中的复习词总数（直接查待复习表，不限到期时间）。
     */
    val reviewDueCount: Int = 0,
    /** 到期且需在常规会话中复习的卡数。 */
    val regularDueCount: Int = 0,
    val answeredToday: Int = 0,
    /** 学习范围内还没学过的词数（挂起的词不算）。 */
    val newRemaining: Int = 0,
    /** 正在冷却中的学习步卡片数。 */
    val coolingCount: Int = 0,
    /** 冷却中最快到期的卡片剩余等待分钟数。 */
    val coolingWaitMinutes: Int = 0
) {
    /** 今日常规新词配额剩余。 */
    val newQuotaRemaining: Int
        get() = (newLimit - newDoneToday).coerceAtLeast(0)

    /** 今日常规计划是否已经完成（无到期复习、无冷却中词、且新词配额已达标）。 */
    val isDailyGoalAchieved: Boolean
        get() = regularDueCount == 0 && coolingCount == 0 && (newLimit == 0 || newQuotaRemaining == 0)

    /** 词库中是否还有未学的生词（无论是否超出今日限额）。 */
    val hasUnlearnedInLibrary: Boolean
        get() = newRemaining > 0

    /**
     * 有卡可学：
     * 1. 还有到期复习卡；
     * 2. 或还有冷却中的卡片可供提前复习；
     * 3. 或今日新词配额尚有剩余且词库有生词；
     * 4. 或今日常规配额已满，但词库仍有生词可供「再背新词（超额学习）」。
     */
    val hasCards: Boolean
        get() = regularDueCount > 0 || coolingCount > 0 || hasUnlearnedInLibrary

    /** 学过的比例，喂给进度条。 */
    val learnedFraction: Float
        get() = if (totalWords == 0) 0f else learnedWords.toFloat() / totalWords
}

/** 把几个计数合成一个对象，避免用无类型的 vararg combine。 */
private data class HomeCounts(
    val total: Int,
    val newRemaining: Int,
    val learnedToday: Int,
    val answeredToday: Int,
    val regularDueCount: Int,
    val reviewTableCount: Int,
    val startedToday: Int,
    val masteredToday: Int,
    val relearnPending: Int,
    val coolingCount: Int,
    val coolingWaitMinutes: Int,
    val learningCount: Int
)

/**
 * 学习首页的数据。
 *
 * 结构与 `StatsViewModel` 同构：设置一变就重建整条订阅，因为每日上限和学习范围
 * 都会直接影响这里显示的每个数字。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudyHomeViewModel(
    private val settingsRepository: SettingsRepository,
    private val wordRepository: WordRepository,
    private val studyRepository: StudyRepository,
    private val deckRepository: DeckRepository
) : ViewModel() {

    private val ticker = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(Unit)
            kotlinx.coroutines.delay(15_000)
        }
    }

    init {
        viewModelScope.launch {
            // 每天首次进首页时补算一次重学积压快照 —— 快照不是今天写的就刷新，见
            // StudyRepository.refreshRelearnBacklogIfStale 的说明。
            studyRepository.refreshRelearnBacklogIfStale(
                settingsRepository.srsSettings.first().studyDeckId
            )
        }
    }

    val state: StateFlow<StudyHomeUiState> = combine(settingsRepository.srsSettings, ticker) { settings, _ -> settings }
        .flatMapLatest { settings ->
            val counts = combine(
                wordRepository.observeCount(settings.studyDeckId),
                // 用 DAO 里「没有 review_states 行」的定义，而不是拿总数去减 ——
                // 挂起的词也有状态行，减法会把它误算成新词
                wordRepository.observeNewCount(settings.studyDeckId),
                studyRepository.observeLearnedTodayCount(settings.studyDeckId),
                studyRepository.observeProgress(settings),
                studyRepository.observeReviewCount(settings.studyDeckId)
            ) { total, newRemaining, learnedToday, progress, reviewTableCount ->
                HomeCounts(
                    total = total,
                    newRemaining = newRemaining,
                    learnedToday = learnedToday,
                    answeredToday = progress.answeredToday,
                    regularDueCount = progress.dueCount,
                    reviewTableCount = reviewTableCount,
                    startedToday = progress.startedToday,
                    masteredToday = progress.masteredToday,
                    relearnPending = progress.relearnPending,
                    coolingCount = progress.coolingCount,
                    coolingWaitMinutes = progress.coolingWaitMinutes,
                    learningCount = progress.learningCount
                )
            }

            combine(counts, deckRepository.observeAll()) { c, decks ->
                // 当前学习词表的名字。studyDeckId 为 null 表示不限词表。
                // 找不到对应行（词表被删了但设置还指着它）时也退回「全部词库」。
                val deckName = settings.studyDeckId
                    ?.let { id -> decks.firstOrNull { it.id == id }?.name }
                    ?: "全部词库"

                // 今日已作答的词里，有一部分是今天新学的。剩下的就是复习。
                val reviewDone = (c.answeredToday - c.learnedToday).coerceAtLeast(0)
                val effectiveLimit = maxOf(settings.dailyNewLimit, c.learnedToday)

                StudyHomeUiState(
                    loading = false,
                    libraryEmpty = c.total == 0,
                    deckName = deckName,
                    totalWords = c.total,
                    learnedWords = c.total - c.newRemaining,
                    newDoneToday = c.learnedToday,
                    startedToday = c.startedToday,
                    masteredToday = c.masteredToday,
                    relearnPending = c.relearnPending,
                    newLimit = effectiveLimit,
                    reviewDoneToday = reviewDone,
                    learningCount = c.learningCount,
                    reviewDueCount = c.reviewTableCount,
                    regularDueCount = c.regularDueCount,
                    answeredToday = c.answeredToday,
                    newRemaining = c.newRemaining,
                    coolingCount = c.coolingCount,
                    coolingWaitMinutes = c.coolingWaitMinutes
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudyHomeUiState())

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                StudyHomeViewModel(
                    settingsRepository = container.settings,
                    wordRepository = container.words,
                    studyRepository = container.study,
                    deckRepository = container.decks
                )
            }
        }
    }
}
