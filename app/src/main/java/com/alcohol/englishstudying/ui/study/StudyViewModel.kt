package com.alcohol.englishstudying.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.local.RelatedWord
import com.alcohol.englishstudying.data.local.WordEntity
import com.alcohol.englishstudying.data.repo.DailyProgress
import com.alcohol.englishstudying.data.repo.DeckRepository
import com.alcohol.englishstudying.data.repo.RelationRepository
import com.alcohol.englishstudying.data.repo.StudyCard
import com.alcohol.englishstudying.data.repo.StudyRepository
import com.alcohol.englishstudying.data.repo.WordRepository
import com.alcohol.englishstudying.data.prefs.SettingsRepository
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.model.CardField
import com.alcohol.englishstudying.domain.model.CardFieldConfig
import com.alcohol.englishstudying.domain.model.CardFieldConfigDefaults
import com.alcohol.englishstudying.domain.relations.WordTokens
import com.alcohol.englishstudying.domain.srs.CardState
import com.alcohol.englishstudying.domain.srs.DailyPlanBuilder
import com.alcohol.englishstudying.domain.srs.Rating
import com.alcohol.englishstudying.domain.srs.Sm2Scheduler
import com.alcohol.englishstudying.domain.srs.SrsSettings
import com.alcohol.englishstudying.domain.srs.SrsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** 小结列表里的一个词：词条本体 + 刚才的自评。移出学习（太难）没有自评，为 null。 */
data class GroupItem(
    val word: WordEntity,
    val rating: Rating?
)

data class StudyUiState(
    val loading: Boolean = true,
    val queue: List<StudyCard> = emptyList(),
    val revealed: Boolean = false,
    val settings: SrsSettings = SrsSettings.DEFAULT,
    val cardFields: List<CardFieldConfig> = CardFieldConfigDefaults.default(),
    val hideHintsUntilRevealed: Boolean = true,
    val progress: DailyProgress? = null,
    /** 超额学习时记录的扩展新词上限（背完一组点“再背一组”后生效）。 */
    val extraNewLimit: Int = 0,
    /** 四档评分各自会导致的下次间隔，直接印在按钮上。 */
    val previewIntervals: Map<Rating, String> = emptyMap(),
    val reviewedThisSession: Int = 0,
    val libraryEmpty: Boolean = false,
    /** 当前是否处于单纯复习（练习模式），该模式下零 SQL 落库，不改写任何 SRS 属性。 */
    val isPracticeMode: Boolean = false,
    /** 暂存的常规学习队列长度（练习模式期间保持常规队列计数）。 */
    val savedQueueCount: Int = 0,
    /** 单纯复习：复习池总词数。 */
    val practicePoolTotal: Int = 0,
    /** 单纯复习：当前轮次已复习池中词数。 */
    val practicePoolReviewedCount: Int = 0,
    /** 词库中未学单词总数，供完成页判断是否还能「再背一组新词」。 */
    val unlearnedInLibrary: Int = 0,
    /** 本组已作答的词，攒够 settings.groupSize 就弹小结。 */
    val group: List<GroupItem> = emptyList(),
    val summaryVisible: Boolean = false,
    /** 当前这张卡的关联词（同根词 / 近义词）；变形的可点击词在 [derivationsIndex]。 */
    val relations: List<RelatedWord> = emptyList(),
    /** 当前这张卡所属的词表名，供「词表」字段渲染。换卡时重新查。 */
    val decks: List<String> = emptyList(),
    /** 当前卡「变形」文本里提到的词（小写）→ 词 id，命中的可点击跳转。 */
    val derivationsIndex: Map<String, Long> = emptyMap(),
    /**
     * 正面按钮给出的预选评分。揭晓后印在四档栏上（描一圈边），
     * 用户确认或改判 —— 不直接落库，看了释义再定，改判的机会必须留。
     */
    val pendingRating: Rating? = null,
    /**
     * 这张卡刚被移出学习（[CardState.TOO_EASY] / [CardState.TOO_HARD]），
     * 落库已生效、但**队列还没推进** —— 等用户点「下一个」。
     *
     * null 表示没有待处理的标记，底部照常显示评分栏。这是「误按有得救」的全部依据：
     * 卡片不走，行上的两个按钮就换成「撤销」，点一下整行写回标记前的状态。
     */
    val markedAs: CardState? = null
) {
    val current: StudyCard? get() = queue.firstOrNull()
    val finished: Boolean get() = !loading && queue.isEmpty()
    val regularQueueSize: Int get() = if (isPracticeMode) savedQueueCount else queue.size

    /** 未揭晓且开启隐藏时，只露出单词与音标，先让用户自己回忆。 */
    val visibleFields: List<CardFieldConfig>
        get() = cardFields.filter { config ->
            config.enabled && (revealed || !hideHintsUntilRevealed || config.field in FRONT_FIELDS)
        }

    /**
     * 揭晓后交给 [com.alcohol.englishstudying.ui.study.WordCardView] 的字段。
     *
     * 单词和音标由页头单独渲染（要配喇叭、要居中放大），这里剔掉，否则会画两遍。
     */
    val detailFields: List<CardFieldConfig>
        get() = visibleFields.filter { it.field !in FRONT_FIELDS }

    private companion object {
        val FRONT_FIELDS = setOf(CardField.HEADWORD, CardField.PHONETIC)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StudyViewModel(
    private val studyRepository: StudyRepository,
    private val settingsRepository: SettingsRepository,
    private val wordRepository: WordRepository,
    private val relationRepository: RelationRepository,
    private val deckRepository: DeckRepository
) : ViewModel() {

    private val _state = MutableStateFlow(StudyUiState())
    val state: StateFlow<StudyUiState> = _state.asStateFlow()

    /** 当前这张卡是什么时候亮出来的，用来算作答耗时。 */
    private var cardShownAt: Long = System.currentTimeMillis()

    /** 单纯复习已抽取的词 ID 集合（支持跨组防重复、到期新词自动纳入与轮换重置）。 */
    private var practicedWordIds = mutableSetOf<Long>()

    /** 进入单纯复习前暂存的常规学习队列，退出练习时无缝恢复。 */
    private var savedRegularQueue: List<StudyCard> = emptyList()

    /**
     * 被移出学习那张卡标记后的状态，点「下一个」时交给 [advance]。
     *
     * 与 [StudyUiState.markedAs] 同进同出。不必放进 UI 状态 —— 界面只关心
     * 「有没有待处理的标记」，这份状态只有 [advance] 用得到，而它算出来必然
     * 是 TOO_EASY / TOO_HARD，[shouldRequeueToday] 对这两种恒返回 false。
     */
    private var markedNext: SrsState? = null

    /**
     * 一次作答/标记动作是否正在落库途中，用来挡住重复点击。
     *
     * 评分按钮没有「提交中」的视觉反馈，而 [StudyRepository.submit] 要跑一次 Room 事务。
     * 在这几毫秒到几十毫秒的窗口里连点两次，第二次读到的 [StudyUiState.current] 还是
     * 同一张卡：于是写两条 review_logs，并把 [advance] 里的 `queue.drop(1)` 执行两次 ——
     * 队列 `[A, B, C]` 连点两次「忘记」会变成 `[C, A, A]`，**B 被静默跳过**。
     *
     * 只在主线程读写（点击回调与 viewModelScope 都在 `Dispatchers.Main.immediate`），
     * 所以普通 Boolean 就够，不需要原子类型。
     */
    private var acting = false

    /**
     * 把 [block] 包成「同一时刻只跑一个」的动作，重入的调用直接丢弃。
     *
     * 丢弃而不是排队：窗口只有一次事务那么长，而动作执行完界面立刻换卡，
     * 排队会让用户看到「点了一下却过了两张」。丢弃后按钮所在的卡片已经推进，
     * 用户的下一次点击落在新卡上，语义是对的。
     */
    private fun actOnce(block: suspend () -> Unit) {
        if (acting) return
        acting = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                acting = false
            }
        }
    }

    /**
     * 当前正在订阅关联词的那个词 id。
     *
     * 关联只在换卡时重新订阅 —— 不要一次把全库关系读进来，那在几千词的库上
     * 会白白拉出几万条边。
     */
    private var relationsJob: Job? = null
    private var derivationsJob: Job? = null
    private var decksJob: Job? = null

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
            // 实时订阅词库中未学总数，供结算页等判断是否还能「再背一组新词」
            settingsRepository.srsSettings.flatMapLatest { settings ->
                wordRepository.observeNewCount(settings.studyDeckId)
            }.collect { count ->
                _state.update { it.copy(unlearnedInLibrary = count) }
            }
        }
        viewModelScope.launch {
            // 只取一次：会话进行到一半时改设置不应该把队列重建，那会丢掉当前的进度
            val settings = settingsRepository.srsSettings.first()
            val groupRelated = settingsRepository.groupRelatedWords.first()
            _state.update { it.copy(settings = settings) }
            observeProgress(settings)
            startSession(settings, groupRelated)
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

    fun startSession(
        settings: SrsSettings? = null,
        groupRelated: Boolean? = null,
        extraNewWords: Boolean = false,
        includeCooling: Boolean = false
    ) {
        viewModelScope.launch {
            markedNext = null
            savedRegularQueue = emptyList()
            val srsSettings = settings ?: settingsRepository.srsSettings.first()
            _state.update {
                it.copy(
                    loading = true,
                    isPracticeMode = false,
                    savedQueueCount = 0,
                    settings = srsSettings,
                    revealed = false,
                    reviewedThisSession = 0,
                    group = emptyList(),
                    summaryVisible = false,
                    pendingRating = null,
                    markedAs = null
                )
            }
            // groupRelated 缺省时现读一次：这是唯一能拿到最新开关值的地方
            val groupFlag = groupRelated ?: settingsRepository.groupRelatedWords.first()
            val queue = studyRepository.buildSession(
                srsSettings,
                groupFlag,
                extraNewWords = extraNewWords,
                includeCooling = includeCooling
            )
            val currentExtraLimit = if (extraNewWords) {
                val newCount = queue.count { it.state.state == CardState.NEW }
                val learned = _state.value.progress?.learnedToday ?: 0
                maxOf(_state.value.extraNewLimit, srsSettings.dailyNewLimit, learned + newCount)
            } else {
                _state.value.extraNewLimit
            }
            // libraryEmpty 由上面那个词数观察者维护，这里不要碰：
            // 写死 false 会把「词库是空的，去导入」引导整个顶掉，
            // 而那是这个不内置词库的应用唯一的入口。
            _state.update { it.copy(queue = queue, loading = false, extraNewLimit = currentExtraLimit) }
            cardShownAt = System.currentTimeMillis()
            refreshPreview()
            observeRelationsForCurrent()
        }
    }

    /**
     * 开启单纯复习（练习模式）：
     * - 暂存当前的常规队列；
     * - 从待复习卡片中抽取（支持不重复抽词机制）；
     * - 绝不改动任何 SRS 调度属性。
     */
    fun startPracticeSession(limit: Int = 20) {
        viewModelScope.launch {
            val currentQueue = _state.value.queue
            val currentMode = _state.value.isPracticeMode
            if (!currentMode) {
                savedRegularQueue = currentQueue
            }
            val regularCount = if (!currentMode) currentQueue.size else savedRegularQueue.size
            markedNext = null
            val settings = settingsRepository.srsSettings.first()
            val groupRelated = settingsRepository.groupRelatedWords.first()
            _state.update {
                it.copy(
                    loading = true,
                    isPracticeMode = true,
                    savedQueueCount = regularCount,
                    settings = settings,
                    revealed = false,
                    reviewedThisSession = 0,
                    group = emptyList(),
                    summaryVisible = false,
                    pendingRating = null,
                    markedAs = null
                )
            }
            val result = studyRepository.buildPracticeSession(
                limit = limit,
                practicedWordIds = practicedWordIds,
                deckId = settings.studyDeckId,
                groupRelated = groupRelated
            )
            practicedWordIds = result.updatedPracticedIds.toMutableSet()
            _state.update {
                it.copy(
                    queue = result.cards,
                    loading = false,
                    previewIntervals = emptyMap(),
                    practicePoolTotal = result.poolTotal,
                    practicePoolReviewedCount = practicedWordIds.size
                )
            }
            cardShownAt = System.currentTimeMillis()
            observeRelationsForCurrent()
        }
    }

    /**
     * 从单纯复习退回或切换回常规学习时恢复暂存队列。
     */
    fun restoreRegularSession() {
        if (!_state.value.isPracticeMode) return
        val restored = savedRegularQueue
        savedRegularQueue = emptyList()
        _state.update {
            it.copy(
                isPracticeMode = false,
                savedQueueCount = 0,
                queue = restored,
                revealed = false,
                reviewedThisSession = 0,
                group = emptyList(),
                summaryVisible = false,
                pendingRating = null,
                markedAs = null
            )
        }
        if (restored.isEmpty()) {
            startSession()
        } else {
            cardShownAt = System.currentTimeMillis()
            refreshPreview()
            observeRelationsForCurrent()
        }
    }

    /**
     * 进入卡片界面时调用：
     * 如果在练习模式中，保持练习队列；
     * 如果内存中已存在未学完的队列（比如中途退到首页再进来），保持现状继续学习；
     * 只有在队列为空且非 loading 时，才初始化构建会话。
     */
    fun startSessionIfNeeded(includeCooling: Boolean = false) {
        if (_state.value.isPracticeMode) {
            return
        }
        if (_state.value.queue.isEmpty() && !_state.value.loading) {
            startSession(includeCooling = includeCooling)
        }
    }

    /** 换卡时重新订阅关联词，旧订阅先取消，避免上一个词的结果晚一步覆盖上来。 */
    private fun observeRelationsForCurrent() {
        relationsJob?.cancel()
        val wordId = _state.value.current?.word?.id
        if (wordId == null) {
            decksJob?.cancel()
            _state.update { it.copy(relations = emptyList(), decks = emptyList()) }
            return
        }
        relationsJob = viewModelScope.launch {
            relationRepository.observeFor(wordId).collect { relations ->
                // 订阅是异步建立的，等结果回来时可能已经换到下一张卡了
                if (_state.value.current?.word?.id == wordId) {
                    _state.update { it.copy(relations = relations) }
                }
            }
        }
        observeDerivationsIndexForCurrent(wordId)
        loadDecksForCurrent(wordId)
    }

    /**
     * 查出当前卡属于哪些词表，供「词表」字段渲染。
     * 同样只在换卡时查一次 —— 一次连接两表的查询，开销可忽略。
     */
    private fun loadDecksForCurrent(wordId: Long) {
        decksJob?.cancel()
        decksJob = viewModelScope.launch {
            val names = deckRepository.decksForWord(wordId).map { it.name }
            if (_state.value.current?.word?.id == wordId) {
                _state.update { it.copy(decks = names) }
            }
        }
    }

    /**
     * 查出「变形」文本里提到的库内词，供渲染成可点击。
     * 与关联词一样只在换卡时算一次，候选词就几个，开销可忽略。
     */
    private fun observeDerivationsIndexForCurrent(wordId: Long) {
        val word = _state.value.current?.word ?: return
        derivationsJob?.cancel()
        derivationsJob = viewModelScope.launch {
            val index = wordRepository.idsByHeadwords(WordTokens.extract(word.derivations))
            if (_state.value.current?.word?.id == wordId) {
                _state.update { it.copy(derivationsIndex = index) }
            }
        }
    }

    /** 点空白揭晓：不带预选，四档由用户自己挑。 */
    fun reveal() {
        _state.update { it.copy(revealed = true, pendingRating = null) }
    }

    /** 正面按钮揭晓：亮内容 + 预选对应档位，等用户在四档栏里确认或改判。 */
    fun revealWithRating(rating: Rating) {
        _state.update { it.copy(revealed = true, pendingRating = rating) }
    }

    fun rate(rating: Rating) {
        actOnce {
            val card = _state.value.current ?: return@actOnce
            val elapsed = System.currentTimeMillis() - cardShownAt

            if (_state.value.isPracticeMode) {
                // 练习模式全同步，本来就不存在「同一张卡被评两次」的窗口
                advancePractice(card, rating)
                return@actOnce
            }

            val next = studyRepository.submit(card, rating, elapsed, _state.value.settings)
            advance(card, next, rating)
        }
    }

    /**
     * 单纯复习推进：
     * 1. 零 SQL 落库，不调用 submit，不写入 review_logs，不改动任何 SRS 调度属性；
     * 2. 若选择「没记住」（AGAIN / HARD），排到练习队列末尾以便本轮再次巩固；
     * 3. 若选择「记住了」（GOOD），该词在当前轮次复习完成，计入复习池已练习集合；
     * 4. 更新会话已复习计数、复习池已练计数与当前卡片。
     */
    private fun advancePractice(card: StudyCard, rating: Rating) {
        val remaining = _state.value.queue.drop(1).toMutableList()
        if (rating == Rating.AGAIN || rating == Rating.HARD) {
            remaining.add(card)
        } else {
            practicedWordIds.add(card.word.id)
        }

        _state.update {
            it.copy(
                queue = remaining,
                revealed = false,
                pendingRating = null,
                markedAs = null,
                reviewedThisSession = it.reviewedThisSession + 1,
                practicePoolReviewedCount = practicedWordIds.size,
                previewIntervals = emptyMap()
            )
        }
        cardShownAt = System.currentTimeMillis()
        observeRelationsForCurrent()
    }

    /** 「太简单」：移出学习，词库的太简单名单里可移出恢复。 */
    fun markTooEasy() = markExcluded(CardState.TOO_EASY)

    /** 「太难了」：移出学习，词库的太难名单里可移出恢复。 */
    fun markTooHard() = markExcluded(CardState.TOO_HARD)

    /**
     * 两个「移出学习」动作的共同实现。
     *
     * **落库立即生效，但队列不推进** —— 卡片留在原地，行上的两个按钮换成「撤销」、
     * 底部换成「下一个」，让误按有挽回余地。真正出队发生在 [nextAfterMark]。
     *
     * 这是「移出学习」与「忘记 / 模糊 / 认识」的分界：[rate] 走 SM-2 算出下次间隔并立即前进，
     * 而这三个是移出学习，不进单词小结（[advance] 的 rating 传 null）。
     */
    private fun markExcluded(target: CardState) {
        if (_state.value.isPracticeMode) return
        actOnce {
            val card = _state.value.current ?: return@actOnce
            markedNext = studyRepository.exclude(card, target)
            _state.update {
                it.copy(
                    // 正面的胶囊点完要直接揭晓。移出学习是不可逆动作，不该在没看到答案时生效；
                    // 揭晓页的行上按钮走同一条路径，那时 revealed 本来就是 true，赋值无副作用。
                    revealed = true,
                    markedAs = target,
                    // 预选是给底部评分栏的，而评分栏已经换成「下一个」了；
                    // 留着的话撤销回来会带出一圈陈旧的高亮
                    pendingRating = null
                )
            }
        }
    }

    /**
     * 撤销刚做的移出学习，把词恢复成标记前的样子（新词删行，学过的词写回原状态）。
     *
     * 走 [actOnce] 的关系：撤销的 DB 写入还没回来时点「下一个」，[nextAfterMark] 会被
     * 丢弃而不是照常出队 —— 否则词仍是移出学习状态，用户的撤销被吞掉，随后撤销协程
     * 还会把 `markedAs = null` 写到下一张卡上。
     */
    fun undoMark() {
        actOnce {
            val card = _state.value.current ?: return@actOnce
            studyRepository.undoExclude(card)
            markedNext = null
            _state.update { it.copy(markedAs = null) }
        }
    }

    /** 「下一个」：标记之后才真正出队。移出学习没有自评，不进小结。 */
    fun nextAfterMark() {
        actOnce {
            val card = _state.value.current ?: return@actOnce
            val next = markedNext ?: card.state
            markedNext = null
            advance(card, next, rating = null)
        }
    }

    /**
     * 记分之后的统一推进：出队（按「忘记」回队尾）、攒组、换卡刷新。
     * [rating] 为 null 表示这次是移出学习而不是自评，不进小结的评分记录。
     *
     * **全同步，不自己起协程**：起协程会让调用方读完 [StudyUiState.current] 之后
     * 还能再被点一次（见 [acting]），而这里做的全是内存操作，没有要等的 IO。
     */
    private fun advance(card: StudyCard, next: SrsState, rating: Rating?) {
        val remaining = _state.value.queue.drop(1).toMutableList()
        if (shouldRequeueToday(next)) {
            // 学习步还没走完（比如按了「忘记」），今天之内还要再考一次，放回队尾
            remaining.add(card.copy(state = next))
        }

        _state.update {
            val group = if (rating != null) it.group + GroupItem(card.word, rating) else it.group
            it.copy(
                queue = remaining,
                revealed = false,
                pendingRating = null,
                markedAs = null,
                reviewedThisSession = it.reviewedThisSession + 1,
                group = group,
                // 小结优先于「今日完成」：最后一张卡正好凑满一组时，这一组的回顾
                // 仍然值得看，所以小结照弹，只是按钮文案变成「完成」。
                summaryVisible = group.size >= it.settings.groupSize
            )
        }
        cardShownAt = System.currentTimeMillis()
        refreshPreview()
        observeRelationsForCurrent()
    }

    /** 「下一组」：清掉本组记录，接着刷剩下的。 */
    fun nextGroup() {
        _state.update { it.copy(group = emptyList(), summaryVisible = false) }
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
        if (_state.value.isPracticeMode) {
            _state.update { it.copy(previewIntervals = emptyMap()) }
            return
        }
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
            .toMutableMap()
        // 「太简单」已经离开评分栏、搬到卡片行上成为移出学习动作，这里就不给它留预览了 ——
        // 留着的话，SrsScheduler 算出的「4 天」将来某天被谁加回评分栏就会显示成错的间隔
        preview.remove(Rating.EASY)
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
                StudyViewModel(
                    studyRepository = container.study,
                    settingsRepository = container.settings,
                    wordRepository = container.words,
                    relationRepository = container.relations,
                    deckRepository = container.decks
                )
            }
        }
    }
}
