package com.alcohol.englishstudying.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.local.DeckWithCount
import com.alcohol.englishstudying.data.local.WordEntity
import com.alcohol.englishstudying.data.repo.DeckRepository
import com.alcohol.englishstudying.data.repo.StudyRepository
import com.alcohol.englishstudying.data.repo.WordRepository
import com.alcohol.englishstudying.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 编辑中的草稿。所有字段都是字符串，回写时再转成实体需要的类型。 */
data class WordDraft(
    val headword: String = "",
    val phonetic: String = "",
    val partOfSpeech: String = "",
    val meanings: String = "",
    val mnemonic: String = "",
    val roots: String = "",
    val derivations: String = "",
    val examples: String = "",
    val tags: String = "",
    val frequency: String = "0"
)

data class WordEditUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val draft: WordDraft = WordDraft(),
    val decks: List<DeckWithCount> = emptyList(),
    val selectedDeckIds: Set<Long> = emptySet(),
    val initialDeckIds: Set<Long> = emptySet(),
    val error: String? = null,
    /** 一次性的提示（重置进度成功等），展示后由界面调 [WordEditViewModel.consumeMessage] 清掉。 */
    val message: String? = null,
    val finished: Boolean = false
)

class WordEditViewModel(
    private val wordId: Long?,
    private val wordRepository: WordRepository,
    private val deckRepository: DeckRepository,
    private val studyRepository: StudyRepository
) : ViewModel() {

    private val _state = MutableStateFlow(WordEditUiState())
    val state: StateFlow<WordEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val decks = deckRepository.observeAll().first()
            val word = wordId?.let { wordRepository.getById(it) }
            val memberDeckIds = if (wordId != null) {
                deckRepository.decksForWord(wordId).map { it.id }.toSet()
            } else {
                emptySet()
            }

            _state.update {
                it.copy(
                    loading = false,
                    isNew = word == null,
                    draft = word?.toDraft() ?: WordDraft(),
                    decks = decks,
                    selectedDeckIds = memberDeckIds,
                    initialDeckIds = memberDeckIds
                )
            }
        }
    }

    fun update(transform: (WordDraft) -> WordDraft) =
        _state.update { it.copy(draft = transform(it.draft), error = null) }

    fun toggleDeck(deckId: Long) = _state.update { current ->
        val next = current.selectedDeckIds.toMutableSet()
        if (!next.add(deckId)) next.remove(deckId)
        current.copy(selectedDeckIds = next)
    }

    fun save() {
        val current = _state.value
        if (current.draft.headword.isBlank()) {
            _state.update { it.copy(error = "单词不能为空") }
            return
        }

        viewModelScope.launch {
            val savedId = wordRepository.save(current.draft.toEntity(wordId ?: 0L))

            val added = current.selectedDeckIds - current.initialDeckIds
            val removed = current.initialDeckIds - current.selectedDeckIds
            added.forEach { deckRepository.addWord(it, savedId) }
            removed.forEach { deckRepository.removeWord(it, savedId) }

            _state.update { it.copy(finished = true) }
        }
    }

    fun delete() {
        val id = wordId ?: return
        viewModelScope.launch {
            wordRepository.delete(id)
            _state.update { it.copy(finished = true) }
        }
    }

    /** 把这个词打回新词重学。挂起的词（连续遗忘太多次）也靠这个恢复。 */
    fun resetProgress() {
        val id = wordId ?: return
        viewModelScope.launch {
            studyRepository.resetProgress(id)
            _state.update { it.copy(message = "已重置为未学习，下次会重新出现在队列里") }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun WordEntity.toDraft() = WordDraft(
        headword = headword,
        phonetic = phonetic.orEmpty(),
        partOfSpeech = partOfSpeech.orEmpty(),
        meanings = meanings.orEmpty(),
        mnemonic = mnemonic.orEmpty(),
        roots = roots.orEmpty(),
        derivations = derivations.orEmpty(),
        examples = examples.orEmpty(),
        tags = tags.orEmpty(),
        frequency = frequency.toString()
    )

    private fun WordDraft.toEntity(id: Long) = WordEntity(
        id = id,
        headword = headword.trim(),
        phonetic = phonetic.trim().ifEmpty { null },
        partOfSpeech = partOfSpeech.trim().ifEmpty { null },
        meanings = meanings.trim().ifEmpty { null },
        mnemonic = mnemonic.trim().ifEmpty { null },
        roots = roots.trim().ifEmpty { null },
        derivations = derivations.trim().ifEmpty { null },
        examples = examples.trim().ifEmpty { null },
        tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","),
        frequency = frequency.trim().toIntOrNull() ?: 0
    )

    companion object {
        fun factory(container: AppContainer, wordId: Long?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    WordEditViewModel(
                        wordId = wordId,
                        wordRepository = container.words,
                        deckRepository = container.decks,
                        studyRepository = container.study
                    )
                }
            }
    }
}
