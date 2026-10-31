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
    /**
     * 今天标为「太难」的新词数 —— 已从 [newLimit] 里扣掉。它们不进 [startedToday] /
     * [masteredToday]：放弃的词不装作学过，但名额照扣，所以上限那个数字要跟着往下走。
     */
    val tooHardNewToday: Int = 0,
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
    /**
     * 今日常规新词配额剩余。
     *
     * 要把 [tooHardNewToday] 加回来：它已经体现在 [newLimit] 的扣减里，而 [newDoneToday]
     * 数的是「今天离开未学池的词」，里面本来就含这批太难词 —— 不补回来的话，
     * 每标一次「太难」就把剩余额度多扣一次。
     */
    val newQuotaRemaining: Int
        get() = (newLimit + tooHardNewToday - newDoneToday).coerceAtLeast(0)

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
    val reviewDoneToday: Int,
    val regularDueCount: Int,
    val reviewTableCount: Int,
    val startedToday: Int,
    val masteredToday: Int,
    val tooHardNewToday: Int,
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
            // 只在从没写过快照时补算一次 —— 之后只在复习结束时更新，见
            // StudyRepository.bootstrapRelearnBacklogIfAbsent 的说明（里面记着一次教训）。
            studyRepository.bootstrapRelearnBacklogIfAbsent(
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
                    reviewDoneToday = progress.reviewDoneToday,
                    // 「待复习」= 今天还发得出来的复习卡 + 学习步里已经到期的卡。
                    // 复习卡按每日上限剩余额度截断（用完就是 0）；学习卡是在途的卡，
                    // 不受复习上限影响，照常算进来。
                    regularDueCount = run {
                        val learningDue = (progress.learningCount - progress.coolingCount).coerceAtLeast(0)
                        val reviewDue = (progress.dueCount - learningDue).coerceAtLeast(0)
                        minOf(reviewDue, progress.reviewBudgetRemaining) + learningDue
                    },
                    reviewTableCount = reviewTableCount,
                    startedToday = progress.startedToday,
                    masteredToday = progress.masteredToday,
                    tooHardNewToday = progress.tooHardNewToday,
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

                // 今日复习完成的旧词数：DAO 直接查，**不能**拿 answeredToday - learnedToday 去减。
                // learnedToday 含「今天新学、没作答就被标为太简单 / 太难」的词（不写日志），
                // 两者不是包含关系，减出来会把复习数多扣掉。旧词被移出学习的那些反而**要算**
                // 进复习数（DAO 里按 lastReviewedAt 认），减法更是数不出来。
                val reviewDone = c.reviewDoneToday
                // 上限 = 每日新词上限 − 今天标「太难」的新词数。maxOf 是「把上限调小之后
                // 已学超过上限」的托底（显示 36/36 而不是 36/20）；扣减放在 maxOf 外面，
                // 否则太难词会从 learnedToday 那边把扣掉的名额又顶回来。
                val effectiveLimit =
                    (maxOf(settings.dailyNewLimit, c.learnedToday) - c.tooHardNewToday)
                        .coerceAtLeast(0)

                StudyHomeUiState(
                    loading = false,
                    libraryEmpty = c.total == 0,
                    deckName = deckName,
                    totalWords = c.total,
                    learnedWords = c.total - c.newRemaining,
                    newDoneToday = c.learnedToday,
                    startedToday = c.startedToday,
                    masteredToday = c.masteredToday,
                    tooHardNewToday = c.tooHardNewToday,
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
