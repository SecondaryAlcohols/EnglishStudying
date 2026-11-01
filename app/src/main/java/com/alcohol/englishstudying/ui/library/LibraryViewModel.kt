package com.alcohol.englishstudying.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.local.DeckWithCount
import com.alcohol.englishstudying.data.local.UserTagWithCount
import com.alcohol.englishstudying.data.local.WordListRow
import com.alcohol.englishstudying.data.repo.DeckRepository
import com.alcohol.englishstudying.data.repo.StudyRepository
import com.alcohol.englishstudying.data.repo.UserTagRepository
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
    val stateFilter: CardState? = null,
    /** 只看「优先学」；和状态/词表/标签筛选叠加时取交集。 */
    val priorityOnly: Boolean = false,
    /** 选中的用户标签；多选是「任一命中」。 */
    val tagIds: Set<Long> = emptySet()
)

data class LibraryUiState(
    val filters: LibraryFilters = LibraryFilters(),
    val rows: List<WordListRow> = emptyList(),
    val decks: List<DeckWithCount> = emptyList(),
    val tags: List<UserTagWithCount> = emptyList(),
    val totalCount: Int = 0,
    val loading: Boolean = true
) {
    val isEmptyLibrary: Boolean get() = !loading && totalCount == 0
    val noMatches: Boolean get() = !loading && totalCount > 0 && rows.isEmpty()

    /** 当前选中的标签（给页头的筛选提示用）。 */
    val selectedTags: List<UserTagWithCount>
        get() = tags.filter { it.id in filters.tagIds }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val wordRepository: WordRepository,
    private val deckRepository: DeckRepository,
    private val studyRepository: StudyRepository,
    private val userTagRepository: UserTagRepository
) : ViewModel() {

    private val filters = MutableStateFlow(LibraryFilters())

    val state: StateFlow<LibraryUiState> = combine(
        filters.flatMapLatest { f ->
            wordRepository.observeList(
                query = f.query,
                deckId = f.deckId,
                stateFilter = f.stateFilter,
                priorityOnly = f.priorityOnly,
                tagIds = f.tagIds
            )
        },
        filters,
        deckRepository.observeAll(),
        wordRepository.observeCount(),
        userTagRepository.observeAll(),
        { rows, currentFilters, decks, total, tags ->
            LibraryUiState(
                filters = currentFilters,
                rows = rows,
                decks = decks,
                tags = tags,
                totalCount = total,
                loading = false
            )
        }
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(value: String) = filters.update { it.copy(query = value) }

    fun setStateFilter(value: CardState?) = filters.update { it.copy(stateFilter = value) }

    fun setDeckFilter(value: Long?) = filters.update { it.copy(deckId = value) }

    fun setPriorityOnly(value: Boolean) = filters.update { it.copy(priorityOnly = value) }

    /** 标签多选：再点一次取消。 */
    fun toggleTag(id: Long) = filters.update {
        it.copy(tagIds = if (id in it.tagIds) it.tagIds - id else it.tagIds + id)
    }

    fun clearFilters() = filters.update { LibraryFilters() }

    /** 太简单/太难名单里的词：移出名单，恢复学习。 */
    fun resumeExcluded(id: Long) {
        viewModelScope.launch { studyRepository.resumeExcluded(id) }
    }

    fun deleteWord(id: Long) {
        viewModelScope.launch { wordRepository.delete(id) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    wordRepository = container.words,
                    deckRepository = container.decks,
                    studyRepository = container.study,
                    userTagRepository = container.userTags
                )
            }
        }
    }
}
