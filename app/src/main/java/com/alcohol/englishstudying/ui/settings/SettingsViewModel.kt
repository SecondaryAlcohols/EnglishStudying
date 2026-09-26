package com.alcohol.englishstudying.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.local.DeckWithCount
import com.alcohol.englishstudying.data.prefs.SettingsRepository
import com.alcohol.englishstudying.data.repo.DeckRepository
import com.alcohol.englishstudying.data.repo.RelationRepository
import com.alcohol.englishstudying.data.repo.StudyRepository
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.srs.NewWordOrder
import com.alcohol.englishstudying.domain.srs.SrsSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val studyRepository: StudyRepository,
    private val relationRepository: RelationRepository,
    deckRepository: DeckRepository
) : ViewModel() {

    val settings: StateFlow<SrsSettings> = settingsRepository.srsSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SrsSettings.DEFAULT)

    /** 「按词表比例」要列出所有词表供多选，所以这里也订阅一份。 */
    val decks: StateFlow<List<DeckWithCount>> = deckRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hideHints: StateFlow<Boolean> = settingsRepository.hideHintsUntilRevealed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val groupRelated: StateFlow<Boolean> = settingsRepository.groupRelatedWords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setDailyNewLimit(value: Int) = launch { settingsRepository.setDailyNewLimit(value) }
    fun setDailyReviewLimit(value: Int) = launch { settingsRepository.setDailyReviewLimit(value) }
    fun setNewWordOrder(value: NewWordOrder) = launch { settingsRepository.setNewWordOrder(value) }
    fun setLearningSteps(steps: List<Int>) = launch { settingsRepository.setLearningSteps(steps) }
    fun setGraduatingInterval(value: Double) = launch { settingsRepository.setGraduatingInterval(value) }
    fun setStartingEase(value: Double) = launch { settingsRepository.setStartingEase(value) }
    fun setHardMultiplier(value: Double) = launch { settingsRepository.setHardMultiplier(value) }
    fun setIntervalModifier(value: Double) = launch { settingsRepository.setIntervalModifier(value) }
    fun setMinInterval(value: Double) = launch { settingsRepository.setMinInterval(value) }
    fun setMaxInterval(value: Double) = launch { settingsRepository.setMaxInterval(value) }
    fun setLeechThreshold(value: Int) = launch { settingsRepository.setLeechThreshold(value) }
    fun setGroupSize(value: Int) = launch { settingsRepository.setGroupSize(value) }
    fun setNewWordMix(value: List<Int>) = launch { settingsRepository.setNewWordMix(value) }

    /** 「按词表比例」参与抽取的词表；传空列表即不限制词表。 */
    fun setWeightedDeckIds(ids: List<Long>) = launch { settingsRepository.setWeightedDeckIds(ids) }

    fun setHideHints(value: Boolean) =
        launch { settingsRepository.setHideHintsUntilRevealed(value) }

    fun setGroupRelated(value: Boolean) =
        launch { settingsRepository.setGroupRelatedWords(value) }

    fun restoreDefaults() {
        launch {
            settingsRepository.resetSrsSettings()
            _message.value = "算法参数已恢复默认，词库和学习进度没有动"
        }
    }

    fun resetAllProgress() {
        launch {
            studyRepository.resetAllProgress()
            _message.value = "所有词的复习进度已清空，全部回到新词状态"
        }
    }

    /**
     * 重算自动关联。
     *
     * 用户改完词根 / 近义词重新导入后需要一个明确的触发点 —— 自动跑的话，
     * 几千词的库每次启动都要算一遍，而绝大多数时候结果没变。
     */
    fun rebuildRelations() {
        launch {
            val result = relationRepository.rebuild()
            _message.value = if (result.total == 0) {
                "没有推导出任何关联。同根词需要「词根词缀」列，近义词需要「近义词」列。"
            } else {
                "关联已重建：同根词 ${result.root}、变形 ${result.form}、" +
                    "近义词 ${result.synonym}，共 ${result.total} 条"
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = container.settings,
                    studyRepository = container.study,
                    relationRepository = container.relations,
                    deckRepository = container.decks
                )
            }
        }
    }
}
