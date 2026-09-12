package com.alcohol.englishstudying.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.repo.DailyProgress
import com.alcohol.englishstudying.data.repo.StudyCard
import com.alcohol.englishstudying.data.repo.StudyRepository
import com.alcohol.englishstudying.data.repo.WordRepository
import com.alcohol.englishstudying.data.prefs.SettingsRepository
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.model.CardField
import com.alcohol.englishstudying.domain.model.CardFieldConfig
import com.alcohol.englishstudying.domain.model.CardFieldConfigDefaults
import com.alcohol.englishstudying.domain.srs.CardState
import com.alcohol.englishstudying.domain.srs.DailyPlanBuilder
import com.alcohol.englishstudying.domain.srs.Rating
import com.alcohol.englishstudying.domain.srs.Sm2Scheduler
import com.alcohol.englishstudying.domain.srs.SrsSettings
import com.alcohol.englishstudying.domain.srs.SrsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

data class StudyUiState(
    val loading: Boolean = true,
    val queue: List<StudyCard> = emptyList(),
    val revealed: Boolean = false,
    val settings: SrsSettings = SrsSettings.DEFAULT,
    val cardFields: List<CardFieldConfig> = CardFieldConfigDefaults.default(),
    val hideHintsUntilRevealed: Boolean = true,
    val progress: DailyProgress? = null,
    /** 四档评分各自会导致的下次间隔，直接印在按钮上。 */
    val previewIntervals: Map<Rating, String> = emptyMap(),
    val reviewedThisSession: Int = 0,
    val libraryEmpty: Boolean = false
) {
    val current: StudyCard? get() = queue.firstOrNull()
    val finished: Boolean get() = !loading && queue.isEmpty()

    /** 未揭晓且开启隐藏时，只露出单词与音标，先让用户自己回忆。 */
    val visibleFields: List<CardFieldConfig>
        get() = cardFields.filter { config ->
            config.enabled && (revealed || !hideHintsUntilRevealed || config.field in FRONT_FIELDS)
        }

    private companion object {
        val FRONT_FIELDS = setOf(CardField.HEADWORD, CardField.PHONETIC)
    }
}

class StudyViewModel(
    private val studyRepository: StudyRepository,
    private val settingsRepository: SettingsRepository,
    private val wordRepository: WordRepository
) : ViewModel() {

    private val _state = MutableStateFlow(StudyUiState())
    val state: StateFlow<StudyUiState> = _state.asStateFlow()

    /** 当前这张卡是什么时候亮出来的，用来算作答耗时。 */
    private var cardShownAt: Long = System.currentTimeMillis()

    init {
        viewModelScope.launch {
            settingsRepository.cardFields.collect { fields ->
                _state.update { it.copy(cardFields = fields) }
            }
        }
        viewModelScope.launch {
            settingsRepository.hideHintsUntilRevealed.collect { hide ->
                _state.update { it.copy(hideHintsUntilRevealed = hide) }
            }
        }
        viewModelScope.launch {
            // 只取一次：会话进行到一半时改设置不应该把队列重建，那会丢掉当前的进度
            val settings = settingsRepository.srsSettings.first()
            _state.update { it.copy(settings = settings) }
            observeProgress(settings)
            startSession(settings)
        }
        // 词数跟着数据库走，而不是在进入页面时算一次。
        // ViewModel 会被 saveState/restoreState 保留下来，一次性算出的值在用户导入完
        // 词库返回后会一直是旧的 —— 表现为「明明导入了词，学习页还说词库是空的」，
        // 而那个空状态只有「去导入词库」一个按钮，用户会卡死在里面。
        viewModelScope.launch {
            wordRepository.observeCount()
                .distinctUntilChanged()
                .collect { count ->
                    val wasEmpty = _state.value.libraryEmpty
                    val nowEmpty = count == 0
                    _state.update { it.copy(libraryEmpty = nowEmpty) }
                    // 从空库变成有词，自动重建一次会话，否则队列还是上次那份空的
                    if (wasEmpty && !nowEmpty) startSession()
                }
        }
    }

    fun startSession(settings: SrsSettings = _state.value.settings) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, revealed = false, reviewedThisSession = 0) }
            val queue = studyRepository.buildSession(settings)
            // libraryEmpty 由上面那个词数观察者维护，这里不要碰：
            // 写死 false 会把「词库是空的，去导入」引导整个顶掉，
            // 而那是这个不内置词库的应用唯一的入口。
            _state.update { it.copy(queue = queue, loading = false) }
            cardShownAt = System.currentTimeMillis()
            refreshPreview()
        }
    }

    fun reveal() {
        _state.update { it.copy(revealed = true) }
    }

    fun rate(rating: Rating) {
        val card = _state.value.current ?: return
        val elapsed = System.currentTimeMillis() - cardShownAt

        viewModelScope.launch {
            val next = studyRepository.submit(card, rating, elapsed, _state.value.settings)

            val remaining = _state.value.queue.drop(1).toMutableList()
            if (shouldRequeueToday(next)) {
                // 学习步还没走完（比如按了「忘记」），今天之内还要再考一次，放回队尾
                remaining.add(card.copy(state = next))
            }

            _state.update {
                it.copy(
                    queue = remaining,
                    revealed = false,
                    reviewedThisSession = it.reviewedThisSession + 1
                )
            }
            cardShownAt = System.currentTimeMillis()
            refreshPreview()
        }
    }

    /** 只把今天之内还要再考的卡放回队列；排到明天或更晚的交给下一次会话。 */
    private fun shouldRequeueToday(next: SrsState): Boolean {
        if (next.state != CardState.LEARNING) return false
        val todayEpochDay = LocalDate.now().toEpochDay()
        val endOfToday = (todayEpochDay + 1) * SrsState.MINUTES_PER_DAY
        val nowMinutes = DailyPlanBuilder.nowMinutes(
            todayEpochDay,
            LocalTime.now().hour,
            LocalTime.now().minute
        )
        // 到期时刻落在今天剩余时间里，且确实还没到点（到点了说明马上又该考，但队列里已有）
        return next.dueAtMinutes in nowMinutes until endOfToday
    }

    private fun refreshPreview() {
        val card = _state.value.current
        if (card == null) {
            _state.update { it.copy(previewIntervals = emptyMap()) }
            return
        }
        val todayEpochDay = LocalDate.now().toEpochDay()
        val nowMinutes = DailyPlanBuilder.nowMinutes(
            todayEpochDay,
            LocalTime.now().hour,
            LocalTime.now().minute
        )
        val preview = Sm2Scheduler(_state.value.settings)
            .previewIntervals(card.state, nowMinutes, todayEpochDay)
        _state.update { it.copy(previewIntervals = preview) }
    }

    private fun observeProgress(settings: SrsSettings) {
        viewModelScope.launch {
            studyRepository.observeProgress(settings).collect { progress ->
                _state.update { it.copy(progress = progress) }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                StudyViewModel(container.study, container.settings, container.words)
            }
        }
    }
}
