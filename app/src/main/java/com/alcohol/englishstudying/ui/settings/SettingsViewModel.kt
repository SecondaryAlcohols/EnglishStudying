package com.alcohol.englishstudying.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.prefs.SettingsRepository
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
    private val studyRepository: StudyRepository
) : ViewModel() {

    val settings: StateFlow<SrsSettings> = settingsRepository.srsSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SrsSettings.DEFAULT)

    val hideHints: StateFlow<Boolean> = settingsRepository.hideHintsUntilRevealed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setDailyNewLimit(value: Int) = launch { settingsRepository.setDailyNewLimit(value) }
    fun setDailyReviewLimit(value: Int) = launch { settingsRepository.setDailyReviewLimit(value) }
    fun setNewWordOrder(value: NewWordOrder) = launch { settingsRepository.setNewWordOrder(value) }
    fun setLearningSteps(steps: List<Int>) = launch { settingsRepository.setLearningSteps(steps) }
    fun setGraduatingInterval(value: Double) = launch { settingsRepository.setGraduatingInterval(value) }
    fun setEasyInterval(value: Double) = launch { settingsRepository.setEasyInterval(value) }
    fun setStartingEase(value: Double) = launch { settingsRepository.setStartingEase(value) }
    fun setEasyBonus(value: Double) = launch { settingsRepository.setEasyBonus(value) }
    fun setHardMultiplier(value: Double) = launch { settingsRepository.setHardMultiplier(value) }
    fun setIntervalModifier(value: Double) = launch { settingsRepository.setIntervalModifier(value) }
    fun setMinInterval(value: Double) = launch { settingsRepository.setMinInterval(value) }
    fun setMaxInterval(value: Double) = launch { settingsRepository.setMaxInterval(value) }
    fun setLeechThreshold(value: Int) = launch { settingsRepository.setLeechThreshold(value) }

    fun setHideHints(value: Boolean) =
        launch { settingsRepository.setHideHintsUntilRevealed(value) }

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

    fun consumeMessage() {
        _message.value = null
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(container.settings, container.study)
            }
        }
    }
}
