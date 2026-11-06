package com.alcohol.englishstudying.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.remote.RemoteRepository
import com.alcohol.englishstudying.data.remote.WordSearchHitDto
import com.alcohol.englishstudying.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeckDetailUiState(
    val deckId: Long = 0,
    val name: String = "",
    val wordCount: Int = 0,
    val query: String = "",
    val results: List<WordSearchHitDto> = emptyList(),
    val searching: Boolean = false,
    val busy: Boolean = false,
    val uploading: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

/**
 * 服务器上的单张词表：搜词加词、摘词、上传 CSV。
 *
 * 加/摘走的是 `/api/decks/{id}/words`，只动挂载；上传走 `/private-words/import`，
 * 写的是自己的 `user_words`，不会碰公共词库。
 */
class MyDeckDetailViewModel(
    private val remote: RemoteRepository,
    private val deckId: Long
) : ViewModel() {

    private val _state = MutableStateFlow(DeckDetailUiState(deckId = deckId))
    val state: StateFlow<DeckDetailUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        refreshDeck()
    }

    fun refreshDeck() {
        viewModelScope.launch {
            runCatching { remote.deckDetail(deckId) }
                .onSuccess { deck ->
                    _state.update { it.copy(name = deck.name, wordCount = deck.wordCount) }
                }
                .onFailure { e -> _state.update { it.copy(error = e.friendly()) } }
        }
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
        searchJob?.cancel()
        if (value.isBlank()) {
            _state.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // 边打边搜，别每敲一个字母就发一次请求
            _state.update { it.copy(searching = true, error = null) }
            runCatching { remote.searchWords(value, deckId) }
                .onSuccess { hits ->
                    if (_state.value.query == value) {
                        _state.update { it.copy(searching = false, results = hits) }
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(searching = false, error = e.friendly()) }
                }
        }
    }

    /** 结果行右侧按钮：已在表里就摘掉，不在就加进去。 */
    fun toggle(hit: WordSearchHitDto) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val action = if (hit.inDeck) {
                runCatching { remote.removeWords(deckId, listOf(hit.headword)) }
                    .map { "已移除「${hit.headword}」" }
            } else {
                runCatching { remote.addWords(deckId, listOf(hit.headword)) }
                    .map { "已加入「${hit.headword}」" }
            }
            action
                .onSuccess { message ->
                    _state.update { s ->
                        s.copy(
                            busy = false,
                            message = message,
                            results = s.results.map {
                                if (it.id == hit.id && it.source == hit.source) {
                                    it.copy(inDeck = !hit.inDeck)
                                } else {
                                    it
                                }
                            },
                            wordCount = (if (hit.inDeck) s.wordCount - 1 else s.wordCount + 1)
                                .coerceAtLeast(0)
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.friendly()) } }
        }
    }

    fun upload(fileName: String, text: String) {
        viewModelScope.launch {
            _state.update { it.copy(uploading = true, error = null, message = null) }
            runCatching { remote.uploadPrivateWords(deckId, fileName, text.toByteArray()) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            uploading = false,
                            message = "上传完成：新增 ${result.created} · 更新 ${result.updated} · " +
                                "已挂到词表 ${result.linked}",
                            wordCount = it.wordCount + result.linked
                        )
                    }
                    refreshDeck()
                    if (_state.value.query.isNotBlank()) setQuery(_state.value.query)
                }
                .onFailure { e -> _state.update { it.copy(uploading = false, error = e.friendly()) } }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory(container: AppContainer, deckId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { MyDeckDetailViewModel(container.remote, deckId) }
            }
    }
}
