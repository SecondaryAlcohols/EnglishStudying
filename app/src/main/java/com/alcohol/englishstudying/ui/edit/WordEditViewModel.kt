package com.alcohol.englishstudying.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.local.DeckWithCount
import com.alcohol.englishstudying.data.local.RelatedWord
import com.alcohol.englishstudying.data.local.WordEntity
import com.alcohol.englishstudying.data.local.WordListRow
import com.alcohol.englishstudying.data.repo.DeckRepository
import com.alcohol.englishstudying.data.repo.RelationRepository
import com.alcohol.englishstudying.data.repo.StudyRepository
import com.alcohol.englishstudying.data.repo.WordRepository
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.model.WordRelationType
import kotlinx.coroutines.Job
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
    val synonyms: String = "",
    val tags: String = "",
    val frequency: String = "0"
)

data class WordEditUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val draft: WordDraft = WordDraft(),
    /**
     * 打开编辑器时那一份原始草稿，用来判断保存时要不要重算关联（见 [save]）。
     * null 表示这是新增的词条 —— 没有基线可比较。
     */
    val initialDraft: WordDraft? = null,
    val decks: List<DeckWithCount> = emptyList(),
    val selectedDeckIds: Set<Long> = emptySet(),
    val initialDeckIds: Set<Long> = emptySet(),
    val error: String? = null,
    /** 一次性的提示（重置进度成功等），展示后由界面调 [WordEditViewModel.consumeMessage] 清掉。 */
    val message: String? = null,
    val finished: Boolean = false,
    /** 当前词条的关联词，三类混在一起，界面按 type 分组。 */
    val relations: List<RelatedWord> = emptyList(),
    /** 添加关联对话框是否打开。 */
    val addingRelation: Boolean = false,
    /** 添加关联对话框里选中的关系类型。 */
    val newRelationType: WordRelationType = WordRelationType.FORM,
    /** 添加关联时正在搜索的关键词。 */
    val searchQuery: String = "",
    val searchResults: List<WordListRow> = emptyList()
)

class WordEditViewModel(
    private val wordId: Long?,
    private val wordRepository: WordRepository,
    private val deckRepository: DeckRepository,
    private val studyRepository: StudyRepository,
    private val relationRepository: RelationRepository
) : ViewModel() {

    private val _state = MutableStateFlow(WordEditUiState())
    val state: StateFlow<WordEditUiState> = _state.asStateFlow()

    /** 搜索是随输入触发的，旧的没跑完就取消，避免慢查询的结果盖掉新查询。 */
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val decks = deckRepository.observeAll().first()
            val word = wordId?.let { wordRepository.getById(it) }
            val memberDeckIds = if (wordId != null) {
                deckRepository.decksForWord(wordId).map { it.id }.toSet()
            } else {
                emptySet()
            }
            val loaded = word?.toDraft()

            _state.update {
                it.copy(
                    loading = false,
                    isNew = word == null,
                    draft = loaded ?: WordDraft(),
                    initialDraft = loaded,
                    decks = decks,
                    selectedDeckIds = memberDeckIds,
                    initialDeckIds = memberDeckIds
                )
            }
        }
        // 关联词是流式的：手动增删后会自动回流，不用手动刷新
        if (wordId != null) {
            viewModelScope.launch {
                relationRepository.observeFor(wordId).collect { relations ->
                    _state.update { it.copy(relations = relations) }
                }
            }
        }
    }

    // ---- 关联词 ----

    fun openAddRelation() = _state.update {
        it.copy(addingRelation = true, searchQuery = "", searchResults = emptyList())
    }

    fun closeAddRelation() = _state.update { it.copy(addingRelation = false) }

    fun setNewRelationType(type: WordRelationType) =
        _state.update { it.copy(newRelationType = type) }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            val rows = wordRepository.observeList(query = trimmed).first()
            // 搜到的结果里排掉自己：自己关联自己没有意义
            _state.update { it.copy(searchResults = rows.filter { row -> row.word.id != wordId }) }
        }
    }

    fun addRelation(otherId: Long) {
        val self = wordId ?: return
        val type = _state.value.newRelationType
        viewModelScope.launch {
            relationRepository.addManual(self, otherId, type)
            _state.update { it.copy(addingRelation = false) }
        }
    }

    fun removeRelation(otherId: Long, type: WordRelationType) {
        val self = wordId ?: return
        viewModelScope.launch {
            relationRepository.remove(self, otherId, type)
            _state.update { it.copy(message = "已删除该关联") }
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

            // 只有「关联会读的那几列」真的改了才重算（见 hasSameRelationInputs）。
            // 无条件重算有两个后果，都是用户能撞上的：
            //  1. `rebuild()` 是 deleteAuto() + 全量重推导，会把用户手动删掉的自动
            //     关联原样找回来 —— 删完一条再改个音标点保存，那条关联就复活了；
            //  2. 几千词的库上它要跑一两秒，而它卡在 finished 之前，保存看着像卡死。
            // 新增词条没有基线，一定重算：它自己就是新数据，可能和库里已有的词
            // 构成变形 / 同根 / 近义关系。
            val relationInputsChanged = current.initialDraft
                ?.let { !it.hasSameRelationInputs(current.draft) }
                ?: true
            if (relationInputsChanged) relationRepository.rebuild()

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
        synonyms = synonyms.orEmpty(),
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
        synonyms = synonyms.trim().ifEmpty { null },
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
                        studyRepository = container.study,
                        relationRepository = container.relations
                    )
                }
            }
    }
}

/**
 * 影响关联推导的那几列有没有变。
 *
 * 对应 `RelationBuilder.build` 实际读的四个输入：`headword` 定变形、`roots` 定同根、
 * `derivations` 定变形（从文本里提到的词）、`synonyms` 定近义。其余列（音标、释义、
 * 巧记、例句、标签、重要度）改了不影响任何一条关系，不该为此重算全库。
 *
 * 比的是 trim 之后的值 —— 落库时 `WordDraft.toEntity` 会 trim，只差个首尾空格不算改动。
 *
 * 放在文件级而不是 ViewModel 的成员：这是保存流程里唯一决定「要不要做那次破坏性全量
 * 重建」的条件，得能直接单测。做成 [WordDraft] 的成员扩展就够不着了。
 */
internal fun WordDraft.hasSameRelationInputs(other: WordDraft): Boolean =
    headword.trim() == other.headword.trim() &&
        roots.trim() == other.roots.trim() &&
        derivations.trim() == other.derivations.trim() &&
        synonyms.trim() == other.synonyms.trim()
