package com.alcohol.englishstudying.ui.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.local.DeckWithCount
import com.alcohol.englishstudying.data.repo.DeckRepository
import com.alcohol.englishstudying.data.repo.WordRepository
import com.alcohol.englishstudying.data.prefs.SettingsRepository
import com.alcohol.englishstudying.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeckUiState(
    val decks: List<DeckWithCount> = emptyList(),
    val totalWords: Int = 0,
    val studyDeckId: Long? = null,
    val loading: Boolean = true
)

class DeckViewModel(
    private val deckRepository: DeckRepository,
    private val wordRepository: WordRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<DeckUiState> = combine(
        deckRepository.observeAll(),
        wordRepository.observeCount(),
        settingsRepository.srsSettings
    ) { decks, total, settings ->
        DeckUiState(
            decks = decks,
            totalWords = total,
            studyDeckId = settings.studyDeckId,
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeckUiState())

    fun create(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _message.value = "词表名不能为空"
            return
        }
        viewModelScope.launch {
            deckRepository.createOrGet(trimmed)
            _message.value = "已创建词表「$trimmed」"
        }
    }

    fun rename(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _message.value = "词表名不能为空"
            return
        }
        // 改名字不影响 id，学习范围设置不用动
        viewModelScope.launch { deckRepository.rename(id, trimmed, null) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            if (state.value.studyDeckId == id) settingsRepository.setStudyDeckId(null)
            deckRepository.delete(id)
            _message.value = "词表已删除，里面的词条仍保留在词库中"
        }
    }

    /** 「全部词库」也走这里，传 null 表示不限词表。 */
    fun setStudyDeck(id: Long?) {
        viewModelScope.launch { settingsRepository.setStudyDeckId(id) }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DeckViewModel(
                    deckRepository = container.decks,
                    wordRepository = container.words,
                    settingsRepository = container.settings
                )
            }
        }
    }
}
