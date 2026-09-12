package com.alcohol.englishstudying.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.local.DeckWithCount
import com.alcohol.englishstudying.data.local.WordListRow
import com.alcohol.englishstudying.data.repo.DeckRepository
import com.alcohol.englishstudying.data.repo.WordRepository
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.srs.CardState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryFilters(
    val query: String = "",
    val deckId: Long? = null,
    val stateFilter: CardState? = null
)

data class LibraryUiState(
    val filters: LibraryFilters = LibraryFilters(),
    val rows: List<WordListRow> = emptyList(),
    val decks: List<DeckWithCount> = emptyList(),
    val totalCount: Int = 0,
    val loading: Boolean = true
) {
    val isEmptyLibrary: Boolean get() = !loading && totalCount == 0
    val noMatches: Boolean get() = !loading && totalCount > 0 && rows.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val wordRepository: WordRepository,
    private val deckRepository: DeckRepository
) : ViewModel() {

    private val filters = MutableStateFlow(LibraryFilters())

    val state: StateFlow<LibraryUiState> = combine(
        filters.flatMapLatest { f ->
            wordRepository.observeList(f.query, f.deckId, f.stateFilter)
        },
        filters,
        deckRepository.observeAll(),
        wordRepository.observeCount(),
        { rows, currentFilters, decks, total ->
            LibraryUiState(
                filters = currentFilters,
                rows = rows,
                decks = decks,
                totalCount = total,
                loading = false
            )
        }
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(value: String) = filters.update { it.copy(query = value) }

    fun setStateFilter(value: CardState?) = filters.update { it.copy(stateFilter = value) }

    fun setDeckFilter(value: Long?) = filters.update { it.copy(deckId = value) }

    fun clearFilters() = filters.update { LibraryFilters() }

    fun deleteWord(id: Long) {
        viewModelScope.launch { wordRepository.delete(id) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { LibraryViewModel(container.words, container.decks) }
        }
    }
}
