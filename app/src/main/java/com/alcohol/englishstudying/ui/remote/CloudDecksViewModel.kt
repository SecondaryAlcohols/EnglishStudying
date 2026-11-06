package com.alcohol.englishstudying.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.remote.DeckDto
import com.alcohol.englishstudying.data.remote.RemoteRepository
import com.alcohol.englishstudying.data.remote.WordBriefDto
import com.alcohol.englishstudying.data.repo.DeckRepository
import com.alcohol.englishstudying.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 四个页签。计数器都基于「服务端返回的全量（已按 q 过滤）」在本地算。 */
object CloudScope {
    const val ALL = "all"
    const val OFFICIAL = "official"
    const val PUBLIC = "public"
    const val MINE = "mine"
}

data class CloudDecksUiState(
    val loggedIn: Boolean = false,
    val userName: String = "",
    val serverUrl: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val decks: List<DeckDto> = emptyList(),
    val scope: String = CloudScope.ALL,
    val query: String = "",
    /** 手风琴：同时只展开一张。 */
    val expandedId: Long? = null,
    val previews: Map<Long, List<WordBriefDto>> = emptyMap(),
    val downloadingId: Long? = null,
    val busy: Boolean = false,
    /** 本机已有的词表名（下载过的），用来标「已在本机」。 */
    val localNames: Set<String> = emptySet(),
    val message: String? = null,
    /** 搜索词是一个分享码并且查到了。 */
    val codeHit: DeckDto? = null,
    val codeMiss: Boolean = false
) {
    // 归属以服务端的 `mine` 为准：公开之后 ownerName 可能没带回来，
    // 用名字比会把「我刚公开的表」误判成别人的。
    private fun ownedByMe(deck: DeckDto) = deck.mine

    val officialCount: Int get() = decks.count { it.official }
    /** 「公开」只放别人公开的；自己的公开表仍归「我的」，可以继续改。 */
    val publicCount: Int get() = decks.count { !it.official && !ownedByMe(it) && it.visibility == "public" }
    val mineCount: Int get() = decks.count { ownedByMe(it) }

    val visibleDecks: List<DeckDto>
        get() = when (scope) {
            CloudScope.OFFICIAL -> decks.filter { it.official }
            CloudScope.PUBLIC -> decks.filter { !it.official && !ownedByMe(it) && it.visibility == "public" }
            CloudScope.MINE -> decks.filter { ownedByMe(it) }
            else -> decks
        }

    fun isMine(deck: DeckDto) = ownedByMe(deck)

    fun isLocal(deck: DeckDto) = deck.name in localNames
}

/**
 * 云端词表页。云图标不再弹对话框，而是进这一页：
 * 账号条常驻、搜索框认分享码、卡片就地展开改一切。
 */
class CloudDecksViewModel(
    private val remote: RemoteRepository,
    private val decks: DeckRepository
) : ViewModel() {

    private val _state = MutableStateFlow(CloudDecksUiState())
    val state: StateFlow<CloudDecksUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            remote.session.collect { session ->
                val loggedIn = session.loggedIn
                _state.update {
                    it.copy(
                        loggedIn = loggedIn,
                        userName = session.userName,
                        serverUrl = session.url,
                        decks = if (loggedIn) it.decks else emptyList()
                    )
                }
                if (loggedIn) load()
            }
        }
        // 本机已有哪些词表 —— 用来在卡片上标「已在本机」，下载完会自己刷新
        viewModelScope.launch {
            decks.observeAll().collect { list ->
                _state.update { it.copy(localNames = list.map { row -> row.name }.toSet()) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun setScope(scope: String) {
        _state.update { it.copy(scope = scope) }
    }

    fun onQueryChange(raw: String) {
        _state.update { it.copy(query = raw) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // 边打边搜会打爆局域网，350ms 停下来才发
            delay(350)
            load()
        }
    }

    fun clearQuery() {
        searchJob?.cancel()
        _state.update { it.copy(query = "", codeHit = null, codeMiss = false) }
        viewModelScope.launch { load() }
    }

    fun toggle(deck: DeckDto) {
        val open = _state.value.expandedId != deck.id
        _state.update { it.copy(expandedId = if (open) deck.id else null) }
        if (open && _state.value.previews[deck.id] == null) {
            viewModelScope.launch {
                runCatching { remote.deckWords(deck.id) }
                    .onSuccess { page ->
                        _state.update { it.copy(previews = it.previews + (deck.id to page.items)) }
                    }
            }
        }
    }

    fun download(deck: DeckDto) {
        if (deck.wordCount == 0) {
            _state.update { it.copy(message = "「${deck.name}」还是空的，没有词可以下载") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(downloadingId = deck.id, error = null) }
            runCatching { remote.downloadAndImport(deck) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            downloadingId = null,
                            message = "已导入「${result.deckName ?: deck.name}」：" +
                                "新增 ${result.inserted}，更新 ${result.updated}，跳过 ${result.skipped}"
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(downloadingId = null, message = error.friendly()) }
                }
        }
    }

    fun createDeck(
        name: String,
        description: String?,
        visibility: String,
        seedFromDeckId: Long?
    ) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { remote.createDeck(name, description, visibility, seedFromDeckId) }
                .onSuccess { deck ->
                    _state.update {
                        it.copy(
                            busy = false,
                            expandedId = deck.id,
                            message = if (deck.shareCode != null) "已创建，分享码 ${deck.prettyShareCode}" else "已创建「${deck.name}」"
                        )
                    }
                    load()
                }
                .onFailure { error -> _state.update { it.copy(busy = false, message = error.friendly()) } }
        }
    }

    fun updateDeck(
        deckId: Long,
        name: String? = null,
        description: String? = null,
        visibility: String? = null,
        shareCode: String? = null
    ) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { remote.updateDeck(deckId, name, description, visibility, shareCode) }
                .onSuccess { deck ->
                    _state.update { st ->
                        st.copy(
                            busy = false,
                            decks = st.decks.map { if (it.id == deck.id) deck else it },
                            message = when {
                                visibility == "public" -> "已公开，分享码 ${deck.prettyShareCode}"
                                visibility == "private" -> "已改为私有，别人按分享码也打不开了"
                                shareCode != null -> "分享码已改成 ${deck.prettyShareCode}"
                                else -> "已保存"
                            }
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(busy = false, message = error.friendly()) } }
        }
    }

    fun resetShareCode(deckId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { remote.resetShareCode(deckId) }
                .onSuccess { deck ->
                    _state.update { st ->
                        st.copy(
                            busy = false,
                            decks = st.decks.map { if (it.id == deck.id) deck else it },
                            message = "新分享码 ${deck.prettyShareCode}，旧的已失效"
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(busy = false, message = error.friendly()) } }
        }
    }

    fun deleteDeck(deck: DeckDto) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { remote.deleteDeck(deck.id) }
                .onSuccess {
                    _state.update { st ->
                        st.copy(
                            busy = false,
                            decks = st.decks.filterNot { it.id == deck.id },
                            expandedId = if (st.expandedId == deck.id) null else st.expandedId,
                            message = "已删除「${deck.name}」（本机那份不受影响）"
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(busy = false, message = error.friendly()) } }
        }
    }

    /**
     * 把 [source] 的词挂到 [target] 上。`mode=replace` 会先清空目标表已有挂载。
     * 只动挂载关系：词条本身还是原来那份，公共库、私有词都不复制。
     */
    fun importFrom(target: DeckDto, source: DeckDto, mode: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { remote.importFrom(target.id, source.id, mode) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            busy = false,
                            expandedId = target.id,
                            message = "已导入 ${result.added} 个词" + run {
                                val extra = buildList {
                                    if (result.removed > 0) add("清掉 ${result.removed}")
                                    if (result.skippedPrivate > 0) add("跳过 ${result.skippedPrivate} 个他人私有词")
                                }
                                if (extra.isEmpty()) "" else "（${extra.joinToString("，")}）"
                            }
                        )
                    }
                    load()
                }
                .onFailure { error -> _state.update { it.copy(busy = false, message = error.friendly()) } }
        }
    }

    /** 界面上发生的小事（比如复制了分享码），借同一套 snackbar 显示。 */
    fun note(text: String) {
        _state.update { it.copy(message = text) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun load() {
        if (!_state.value.loggedIn) return
        val query = _state.value.query.trim()
        _state.update { it.copy(loading = true, error = null) }
        runCatching {
            val list = remote.decks(CloudScope.ALL, query.ifBlank { null })
            val hit = if (looksLikeCode(query)) {
                runCatching { remote.deckByCode(query) }.getOrNull()
            } else {
                null
            }
            list to hit
        }.onSuccess { (list, hit) ->
            _state.update { st ->
                val merged = if (hit != null && list.none { it.id == hit.id }) {
                    listOf(hit) + list
                } else {
                    list
                }
                st.copy(
                    loading = false,
                    decks = merged,
                    codeHit = hit,
                    codeMiss = hit == null && looksLikeCode(query) && merged.isEmpty()
                )
            }
        }.onFailure { error ->
            _state.update { it.copy(loading = false, error = error.friendly()) }
        }
    }

    companion object {
        /** 4–32 位字母数字（可带横线）就当分享码再查一次；查不到也不影响关键词结果。 */
        private val CODE = Regex("^[A-Za-z0-9-]{4,32}$")

        fun looksLikeCode(raw: String): Boolean {
            // 粘贴时常带上空格（"K7Q2 9F3M"），先去掉再判；横线是码本身允许的字符
            val text = raw.trim().replace(" ", "")
            return text.isNotEmpty() && CODE.matches(text)
        }

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { CloudDecksViewModel(container.remote, container.decks) }
        }
    }
}
