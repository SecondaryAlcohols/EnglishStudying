package com.alcohol.englishstudying.ui.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.data.local.RelatedWord
import com.alcohol.englishstudying.data.repo.DailyProgress
import com.alcohol.englishstudying.data.repo.StudyCard
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.model.CardField
import com.alcohol.englishstudying.domain.model.CardFieldConfig
import com.alcohol.englishstudying.domain.srs.CardState
import com.alcohol.englishstudying.domain.srs.Rating
import com.alcohol.englishstudying.domain.srs.SrsSettings
import com.alcohol.englishstudying.domain.srs.TodayProgress
import com.alcohol.englishstudying.ui.components.EmptyState
import com.alcohol.englishstudying.ui.theme.FrontHeadwordStyle
import com.alcohol.englishstudying.ui.theme.RatingAgain
import com.alcohol.englishstudying.ui.theme.RatingEasy
import com.alcohol.englishstudying.ui.theme.RatingGood
import com.alcohol.englishstudying.ui.theme.RatingHard

/**
 * 底部评分栏的档位与顺序。
 *
 * 只有三档 —— 「太简单」已经搬到卡片行上、和「太难」并排成为「移出学习」动作。
 * 它和这三档不是一类东西：评分是记忆质量自评、由 SM-2 算出下次间隔，
 * 移出学习则不参与调度且不可逆，混在一排里误按代价太高。
 */
private val RATING_ORDER = listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD)

private fun labelOf(rating: Rating): String = when (rating) {
    Rating.AGAIN -> "忘记"
    Rating.HARD -> "模糊"
    Rating.GOOD -> "认识"
    Rating.EASY -> "太简单"
}

private fun colorOf(rating: Rating): Color = when (rating) {
    Rating.AGAIN -> RatingAgain
    Rating.HARD -> RatingHard
    Rating.GOOD -> RatingGood
    Rating.EASY -> RatingEasy
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenWord: (Long) -> Unit,
    /** 编辑当前这张卡的词条（改错别字、补词根）。参数是词条 id。 */
    onEditWord: (Long) -> Unit,
    viewModel: StudyViewModel = viewModel(factory = StudyViewModel.factory(container))
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val card = state.current
    val speaker = rememberWordSpeaker()
    val scope = rememberCoroutineScope()
    // 三个工具弹窗互斥：同一时刻只开一个
    var toolDialog by remember { mutableStateOf<StudyTool?>(null) }

    val remainingCount = state.queue.size
    val isRetryOnly = remainingCount > 0 && state.queue.all {
        it.state.state == CardState.LEARNING ||
            it.state.state == CardState.RELEARNING ||
            it.state.lastReviewedAt != null
    }

    LaunchedEffect(Unit) {
        viewModel.startSessionIfNeeded()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回首页")
                    }
                },
                title = { Text(if (state.isPracticeMode) "单纯复习" else "学习") },
                actions = {
                    // 从左到右：搜索 → 日志 → 编辑。
                    // 加标签已经挪到卡片底部（和标签显示在一起），顶栏不再留入口。
                    // 搜索和日志不依赖「手里有没有卡」，永远可点；编辑要针对当前词，
                    // 没卡时置灰而不是隐藏 —— 隐藏会让顶栏在「有卡 / 没卡」之间来回跳。
                    IconButton(onClick = { toolDialog = StudyTool.SEARCH }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索单词")
                    }
                    IconButton(onClick = { toolDialog = StudyTool.LOGS }) {
                        Icon(Icons.Filled.ReceiptLong, contentDescription = "查看今天的答题日志")
                    }
                    // 卡片页要改的是「手里这张卡」，所以放编辑入口；导入留给了词库页和学习首页
                    // （以及下面「词库还是空的」那个空状态）。
                    IconButton(
                        onClick = { card?.let { onEditWord(it.word.id) } },
                        enabled = card != null
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑这个词")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.loading -> Column(Modifier.weight(1f)) { LoadingBox() }

                state.libraryEmpty -> Column(Modifier.weight(1f)) {
                    EmptyState(
                        icon = Icons.Filled.FileUpload,
                        title = "词库还是空的",
                        message = "这个应用不内置词条。准备好你的考研词库后导入进来，" +
                            "巧记、词根词缀、变形这三块会在卡片上显示出来。",
                        actionLabel = "去导入词库",
                        onAction = onOpenImport
                    )
                }

                // 小结排在 finished 前面：最后一张卡正好凑满一组时，
                // 这一组的回顾仍然值得看，看完再走「今日完成」
                state.summaryVisible -> StudySummary(
                    items = state.group,
                    speaker = speaker,
                    hasMore = state.queue.isNotEmpty(),
                    remainingCount = remainingCount,
                    isRetryOnly = isRetryOnly,
                    onNextGroup = viewModel::nextGroup,
                    // 点小结里的卡片 → 只读详情页。和卡片上关联词 chip 同一个去处
                    onOpenWord = onOpenWord
                )

                state.finished -> Column(Modifier.weight(1f)) {
                    if (state.isPracticeMode) {
                        PracticeSessionFinished(
                            reviewed = state.reviewedThisSession,
                            onPracticeAgain = { viewModel.startPracticeSession() },
                            onBack = onBack
                        )
                    } else {
                        SessionFinished(
                            reviewed = state.reviewedThisSession,
                            unlearnedInLibrary = state.unlearnedInLibrary,
                            coolingCount = state.progress?.coolingCount ?: 0,
                            coolingWaitMinutes = state.progress?.coolingWaitMinutes ?: 0,
                            onRestart = { viewModel.startSession() },
                            onStartEarlyReview = { viewModel.startSession(includeCooling = true) },
                            onStartExtraNewWords = { viewModel.startSession(extraNewWords = true) },
                            onBack = onBack,
                            onOpenLibrary = onOpenLibrary
                        )
                    }
                }

                card != null -> {
                    // 进入一张卡、以及揭晓时，各自动播放一次发音。
                    // 以 (wordId, revealed) 为 key：换卡重放，正面→揭晓重放，
                    // 同一状态下重组不重放
                    LaunchedEffect(card.word.id, state.revealed) {
                        speaker.speak(card.word.headword)
                    }
                    Column(Modifier.fillMaxSize()) {
                        StatsStrip(
                            progress = state.progress,
                            settings = state.settings,
                            extraNewLimit = state.extraNewLimit,
                            isPracticeMode = state.isPracticeMode,
                            practicePoolReviewedCount = state.practicePoolReviewedCount,
                            practicePoolTotal = state.practicePoolTotal
                        )
                        if (state.revealed) {
                            RevealedCard(
                                card = card,
                                isPracticeMode = state.isPracticeMode,
                                // 揭晓页一律全展开：揭晓的意义就是看内容，先折叠再逐个点开
                                // 只是把同样的点击重复一遍。手动收起某个字段仍然可以
                                fields = state.detailFields.map { it.copy(expanded = true) },
                                relations = state.relations,
                                previews = state.previewIntervals,
                                pending = state.pendingRating,
                                markedAs = state.markedAs,
                                wordIndex = state.derivationsIndex,
                                decks = state.decks,
                                userTags = state.userTags,
                                onEditTags = { toolDialog = StudyTool.TAGS },
                                speaker = speaker,
                                onRate = viewModel::rate,
                                onMarkTooEasy = viewModel::markTooEasy,
                                onMarkTooHard = viewModel::markTooHard,
                                onUndoMark = viewModel::undoMark,
                                onNextAfterMark = viewModel::nextAfterMark,
                                onOpenWord = onOpenWord
                            )
                        } else {
                            AnswerFront(
                                card = card,
                                isPracticeMode = state.isPracticeMode,
                                fields = state.visibleFields,
                                onSpeak = { speaker.speak(card.word.headword) },
                                onReveal = viewModel::reveal,
                                onRevealWithRating = viewModel::revealWithRating,
                                onMarkTooEasy = viewModel::markTooEasy
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 顶栏三个工具：搜索 / 打标签 / 看日志 ----
    when (toolDialog) {
        StudyTool.SEARCH -> WordSearchDialog(
            onDismiss = { toolDialog = null },
            onPick = { id ->
                toolDialog = null
                onOpenWord(id)
            },
            search = { query -> viewModel.searchWords(query) }
        )

        StudyTool.TAGS -> card?.let { current ->
            TagsDialog(
                headword = current.word.headword,
                wordId = current.word.id,
                loadAllTags = { viewModel.allUserTags() },
                loadCurrentTags = { id -> viewModel.userTagsOf(id) },
                onDismiss = { toolDialog = null },
                onSave = { names ->
                    toolDialog = null
                    scope.launch { viewModel.saveUserTags(current.word.id, names) }
                }
            )
        }

        StudyTool.LOGS -> TodayLogDialog(
            onDismiss = { toolDialog = null },
            load = { viewModel.todayLogs() },
            onOpenWord = { id ->
                toolDialog = null
                onOpenWord(id)
            }
        )

        null -> Unit
    }
}

/**
 * 正面：先自己回忆，再自评「认识 / 不认识」。
 *
 * 两个按钮不直接记分 —— 按下先揭晓内容并预选对应档位（不认识→忘记、我认识→认识），
 * 用户看过释义后在评分栏里确认或改判。点空白处是不带预选的普通揭晓。
 *
 * 「太简单，移出学习」是另一类动作：不可逆，所以按下后**先揭晓**，
 * 让用户在揭晓页看清内容（以及行上的「撤销」）再确认 —— 一眼就会的词不该
 * 在没看到答案的情况下就被移出学习。
 */
@Composable
private fun AnswerFront(
    card: StudyCard,
    isPracticeMode: Boolean = false,
    fields: List<CardFieldConfig>,
    onSpeak: () -> Unit,
    onReveal: () -> Unit,
    onRevealWithRating: (Rating) -> Unit,
    onMarkTooEasy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onReveal)
    ) {
        // 中段整体居中：词、音标、太简单胶囊、提示语
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 注意不能写成 `?: return`：Column 的内容 lambda 是 inline 的，
            // 裸 return 会把整个 AnswerFront 提前退出，卡片直接一片空白
            card.state.lastReviewedAt?.let { lastSeenBadge(it) }

            Text(
                card.word.headword,
                style = FrontHeadwordStyle,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )

            if (fields.any { it.field == CardField.PHONETIC }) {
                // 喇叭独立于音标内容：没有音标的词也要能点着听
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                ) {
                    card.word.phonetic?.takeIf { it.isNotBlank() }?.let { phonetic ->
                        Text(
                            phonetic,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = onSpeak) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "朗读 ${card.word.headword}",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (!isPracticeMode) {
                OutlinedButton(
                    onClick = onMarkTooEasy,
                    shape = CircleShape,
                    modifier = Modifier.padding(top = 20.dp)
                ) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("太简单，移出学习")
                }
            }

            Spacer(Modifier.height(28.dp))

            Text(
                "尝试回想释义",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "点击空白处查看答案",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FrontAnswerButton(
                label = if (isPracticeMode) "没记住" else "不认识",
                color = colorOf(Rating.AGAIN),
                onClick = { onRevealWithRating(Rating.AGAIN) },
                modifier = Modifier.weight(1f)
            )
            FrontAnswerButton(
                label = if (isPracticeMode) "记住了" else "我认识",
                color = colorOf(Rating.GOOD),
                onClick = { onRevealWithRating(Rating.GOOD) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FrontAnswerButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

// 「多久以前学过」徽章已移到 WordCardView.kt 的 lastSeenBadge，三处页头共用

// ---- 揭晓后 ----

/**
 * 揭晓：紧凑的页头 + 音标与移出学习行 + 可滚动的卡片详情 + 底部评分栏或「下一个」。
 *
 * 底部按 [markedAs] 二选一：没标记时是三档评分，标记之后换成单个「下一个」——
 * 卡片停在这一张不前进，误按才有得救。
 */
@Composable
private fun RevealedCard(
    card: StudyCard,
    isPracticeMode: Boolean = false,
    fields: List<CardFieldConfig>,
    relations: List<RelatedWord>,
    previews: Map<Rating, String>,
    pending: Rating?,
    markedAs: CardState?,
    wordIndex: Map<String, Long>,
    decks: List<String>,
    userTags: List<String>,
    onEditTags: () -> Unit,
    speaker: WordSpeaker,
    onRate: (Rating) -> Unit,
    onMarkTooEasy: () -> Unit,
    onMarkTooHard: () -> Unit,
    onUndoMark: () -> Unit,
    onNextAfterMark: () -> Unit,
    onOpenWord: (Long) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        WordHeader(
            word = card.word,
            lastReviewedAt = card.state.lastReviewedAt,
            onSpeak = { speaker.speak(card.word.headword) },
            // 音标让给下一行：它要和「太简单 / 太难」并排
            showPhonetic = false
        )
        PhoneticActionRow(
            phonetic = card.word.phonetic,
            markedAs = markedAs,
            isPracticeMode = isPracticeMode,
            onMarkTooEasy = onMarkTooEasy,
            onMarkTooHard = onMarkTooHard,
            onUndoMark = onUndoMark
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // 长按选择复制；只包内容、不参与外层布局约束，
            // 布局职责全留给上面那个普通 Column
            SelectionContainer {
                WordCardView(
                    word = card.word,
                    fields = fields,
                    relations = relations,
                    wordIndex = wordIndex,
                    decks = decks,
                    userTags = userTags,
                    onEditTags = onEditTags,
                    onOpenWord = onOpenWord
                )
            }
        }
        if (isPracticeMode) {
            PracticeRatingBar(pending = pending, onRate = onRate)
        } else if (markedAs == null) {
            RatingBar(previews = previews, pending = pending, onRate = onRate)
        } else {
            NextAfterMarkBar(markedAs = markedAs, onNext = onNextAfterMark)
        }
    }
}

/**
 * 音标行：左边音标，右边两个「移出学习」动作。
 *
 * 从底部评分栏里拆出来，是因为这两者不是一类东西 —— 评分是记忆质量自评、由 SM-2
 * 算出下次间隔；「太简单 / 太难」是移出学习，不参与调度且不可逆。并排放在一起
 * 误按的代价太高，所以挪到单词正下方，和音标同一行。
 *
 * 点过之后整行换成单个「撤销」：这时队列还没推进（卡片停在原地），
 * 撤销就是把刚写进 review_states 的那一行还原成标记前的样子。
 */
@Composable
private fun PhoneticActionRow(
    phonetic: String?,
    markedAs: CardState?,
    isPracticeMode: Boolean = false,
    onMarkTooEasy: () -> Unit,
    onMarkTooHard: () -> Unit,
    onUndoMark: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val text = phonetic?.takeIf { it.isNotBlank() }
        if (text != null) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        } else {
            // 没音标的词也要把按钮顶到右边去
            Spacer(Modifier.weight(1f))
        }

        if (!isPracticeMode) {
            if (markedAs == null) {
                OutlinedButton(
                    onClick = onMarkTooEasy,
                    contentPadding = ACTION_BUTTON_PADDING
                ) {
                    Text("太简单", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onMarkTooHard,
                    contentPadding = ACTION_BUTTON_PADDING,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("太难", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                OutlinedButton(
                    onClick = onUndoMark,
                    contentPadding = ACTION_BUTTON_PADDING
                ) {
                    Text("撤销", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

private val ACTION_BUTTON_PADDING = PaddingValues(horizontal = 14.dp, vertical = 4.dp)

/**
 * 标记之后底部的唯一出口：推进到下一张。
 *
 * 那一行小字不是装饰 —— 标记生效后行上的「撤销」和这里的「下一个」都看不出
 * 刚才到底标了什么，得有一句话告诉用户「已移出学习」。
 */
@Composable
private fun NextAfterMarkBar(markedAs: CardState, onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            when (markedAs) {
                CardState.TOO_EASY -> "已移出学习（太简单），点「下一个」继续"
                else -> "已移出学习（太难），点「下一个」继续"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("下一个", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---- 顶部统计条 ----

/**
 * 单词卡界面顶部统计条：
 * - 常规学习：今日复习（今天已复习的单词 / 今天可达的复习目标）| 今日新词（已学 / 上限）| 学习时间
 * - 单纯复习：今日复习（单纯复习已复习池的单词 / 复习池的单词）| 今日新词（已学 / 上限）| 学习时间
 *
 * 「已复习」含今天被移出学习的旧词 —— 见 [com.alcohol.englishstudying.data.local.ReviewDao.observeReviewDoneCount]。
 * 分母用 `maxOf` 托底：两者都是查出来的，「已复习」不会超过「到期」的量，
 * 但重建队列、跨零点等情况下短暂不同步时，宁可显示 `N/N` 也不要出现 `5/3`。
 */
@Composable
private fun StatsStrip(
    progress: DailyProgress?,
    settings: SrsSettings,
    extraNewLimit: Int,
    isPracticeMode: Boolean,
    practicePoolReviewedCount: Int,
    practicePoolTotal: Int
) {
    val reviewDone = if (isPracticeMode) {
        practicePoolReviewedCount
    } else {
        progress?.reviewDoneToday ?: 0
    }

    val reviewTotal = if (isPracticeMode) {
        practicePoolTotal
    } else {
        val done = progress?.reviewDoneToday ?: 0
        // reviewTargetToday 已经把每日复习上限算进去：额度用完时它就是
        // max(已完成, 已过卡)，显示 X/X，不再拿全天到期数（326）画一个走不到的目标。
        maxOf(done, progress?.reviewTargetToday ?: 0)
    }

    val newRecognized = progress?.startedToday ?: 0
    val newGraduated = progress?.masteredToday ?: 0
    // 上限只算新词（重学词是额外贴上来的一项）。今天标「太难」的新词从上限里扣掉：
    // 它们不进初学 / 掌握，但名额照扣 —— 不扣的话会显示「初学 1 / 上限 20」却只剩 17 个可发。
    // 扣减放在 maxOf **外面**，否则那些词会从 learnedToday 一侧把扣掉的名额顶回来。
    val tooHardNew = progress?.tooHardNewToday ?: 0
    val newLimit = (
        maxOf(settings.dailyNewLimit, progress?.learnedToday ?: 0, extraNewLimit) - tooHardNew
    ).coerceAtLeast(0)
    // relearnPending 里已经含了「复习中每忘一个 +1」的那一段，见 StudyRepository.relearnPending
    val relearned = progress?.relearnPending ?: 0
    val elapsed = formatElapsed(progress?.elapsedTodayMs ?: 0L)

    val segments = if (isPracticeMode) null else progress?.let { p ->
        TodayProgress.segments(
            // 分母就是这一行里能看到的两个数：今日复习 93/361 的 361 + 上限 50
            reviewTarget = p.reviewTargetToday,
            newLimit = newLimit,
            reviewDone = p.reviewDoneToday,
            masteredNew = p.masteredToday,
            hazy = p.hazyToday,
            startedNew = p.startedToday
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatCell("今日复习", "$reviewDone/$reviewTotal")
            StatCell(
                "初学/掌握/上限",
                // 上限只算新词，重学词是额外贴上来的一项 —— 所以是 50+17 而不是 67
                if (relearned > 0) "$newRecognized/$newGraduated/$newLimit+$relearned"
                else "$newRecognized/$newGraduated/$newLimit"
            )
            StatCell("学习时间", elapsed)
        }
        if (segments != null && segments.total > 0) {
            // 深绿 = 今天已了结的（已复习 + 已掌握），浅绿 = 今天动过还没了结的
            // （模糊待重考 + 初学未掌握），其余就是今天还没做的。
            TodayProgressBar(
                doneFraction = segments.doneFraction,
                inProgressFraction = segments.inProgressFraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )
        }
    }
}

/** 今日进度条本体：一条 6dp 的细线，左深绿、接着浅绿，其余是底色。 */
@Composable
private fun TodayProgressBar(
    doneFraction: Float,
    inProgressFraction: Float,
    modifier: Modifier = Modifier
) {
    val doneColor = Color(0xFF07C187)
    val inProgressColor = Color(0xFFB5E7D9)
    val trackColor = MaterialTheme.colorScheme.surface
    Canvas(
        modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(trackColor)
    ) {
        val doneWidth = size.width * doneFraction.coerceIn(0f, 1f)
        // 两段都是「占总量的比例」，不是占剩余部分的比例
        val progressWidth = (size.width * inProgressFraction.coerceIn(0f, 1f))
            .coerceAtMost((size.width - doneWidth).coerceAtLeast(0f))
        if (doneWidth > 0f) {
            drawRect(color = doneColor, size = Size(doneWidth, size.height))
        }
        if (progressWidth > 0f) {
            drawRect(
                color = inProgressColor,
                topLeft = Offset(doneWidth, 0f),
                size = Size(progressWidth, size.height)
            )
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** 学习时长只到分钟粒度，不足一分钟不显示「0min」那种泄气话。 */
private fun formatElapsed(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes <= 0L) "<1min" else "${minutes}min"
}

// ---- 其余状态 ----

@Composable
private fun LoadingBox() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SessionFinished(
    reviewed: Int,
    unlearnedInLibrary: Int,
    coolingCount: Int = 0,
    coolingWaitMinutes: Int = 0,
    onRestart: () -> Unit,
    onStartEarlyReview: () -> Unit,
    onStartExtraNewWords: () -> Unit,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    val isCooling = coolingCount > 0

    Column(Modifier.fillMaxSize()) {
        // weight 把按钮推到下方，空状态本身占满上方
        Column(Modifier.weight(1f)) {
            EmptyState(
                icon = if (isCooling) Icons.Filled.Refresh else Icons.Filled.CheckCircle,
                title = when {
                    isCooling -> "还有 $coolingCount 个单词在冷却中"
                    reviewed > 0 -> "今日完成"
                    else -> "暂时没有要学的卡"
                },
                message = when {
                    isCooling -> "当前队列已学完，还有 $coolingCount 个单词正在冷却中（约 $coolingWaitMinutes 分钟后到期）。你可以稍后回来复习，也可以提前复习完成今日目标。"
                    reviewed > 0 -> "本次学了 $reviewed 张卡。到期的复习卡会在该出现的时候自动回到队列。"
                    else -> "今天的到期卡都清完了，也没有剩余的新词。" +
                        "可以去设置里调高每日新词上限，或者点击下方再背一组新词。"
                },
                actionLabel = if (isCooling) "提前复习冷却词" else "再查一次",
                onAction = if (isCooling) onStartEarlyReview else onRestart
            )
        }
        if (isCooling) {
            Button(
                onClick = onStartEarlyReview,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .height(52.dp)
            ) { Text("提前复习冷却词") }
        } else if (unlearnedInLibrary > 0) {
            Button(
                onClick = onStartExtraNewWords,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .height(52.dp)
            ) { Text("再背一组新词") }
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .height(52.dp)
        ) { Text("返回首页") }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = onOpenLibrary) { Text("去词库看看") }
        }
    }
}

@Composable
private fun RatingBar(
    previews: Map<Rating, String>,
    pending: Rating?,
    onRate: (Rating) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RATING_ORDER.forEach { rating ->
            Button(
                onClick = { onRate(rating) },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colorOf(rating)),
                contentPadding = PaddingValues(2.dp),
                // 正面「认识/不认识」给出的预选：描一圈边提示「按你刚才的选择」，
                // 再点一下即确认；想改判点别的档位就行
                border = if (rating == pending) {
                    BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                } else {
                    null
                }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        labelOf(rating),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center
                    )
                    previews[rating]?.let { preview ->
                        Text(
                            preview,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PracticeRatingBar(
    pending: Rating?,
    onRate: (Rating) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { onRate(Rating.AGAIN) },
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colorOf(Rating.AGAIN)),
            border = if (pending == Rating.AGAIN) {
                BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
            } else {
                null
            }
        ) {
            Text("没记住", style = MaterialTheme.typography.titleMedium)
        }
        Button(
            onClick = { onRate(Rating.GOOD) },
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colorOf(Rating.GOOD)),
            border = if (pending == Rating.GOOD) {
                BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
            } else {
                null
            }
        ) {
            Text("记住了", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PracticeSessionFinished(
    reviewed: Int,
    onPracticeAgain: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            EmptyState(
                icon = Icons.Filled.CheckCircle,
                title = "练习完成",
                message = if (reviewed > 0) {
                    "本次练习了 $reviewed 张卡片。本次练习未修改任何记忆算法参数与排期。"
                } else {
                    "当前暂无待复习的单词。"
                },
                actionLabel = "再练一组",
                onAction = onPracticeAgain
            )
        }
        Button(
            onClick = onPracticeAgain,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .height(52.dp)
        ) {
            Text("再练一组", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .height(52.dp)
        ) {
            Text("返回首页", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))
    }
}

