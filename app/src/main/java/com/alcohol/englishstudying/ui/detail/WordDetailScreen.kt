package com.alcohol.englishstudying.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alcohol.englishstudying.data.local.ReviewStateEntity
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.model.CardField
import com.alcohol.englishstudying.domain.relations.WordTokens
import com.alcohol.englishstudying.domain.srs.CardState
import com.alcohol.englishstudying.domain.srs.ReviewSchedule
import com.alcohol.englishstudying.ui.components.EmptyState
import com.alcohol.englishstudying.ui.study.WordCardView
import com.alcohol.englishstudying.ui.study.WordHeader
import com.alcohol.englishstudying.ui.study.rememberWordSpeaker
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 只读的单词详情页。
 *
 * 学习卡片上的关联词 chip、词库列表点进来都是这一页；要改内容走顶栏的编辑。
 * 从关联词 chip 再点进去是详情页套详情页，返回键沿链条逐层退回。
 *
 * 复习状态与词表归属不在订阅的流里，进页面时各取一次；设置完下次复习时间后
 * 用 [reloadTick] 触发重取。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordDetailScreen(
    container: AppContainer,
    wordId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenWord: (Long) -> Unit
) {
    val speaker = rememberWordSpeaker()
    val scope = rememberCoroutineScope()

    // 整行复习状态（不只要 lastReviewedAt）：下面要拿 dueAtMinutes 显示「N 天后复习」，
    // 还要靠「这行在不在」判断该词能不能被设置 —— 新词没有行，也就不能设置
    var reviewState by remember(wordId) { mutableStateOf<ReviewStateEntity?>(null) }
    var decks by remember(wordId) { mutableStateOf<List<String>>(emptyList()) }
    var showScheduleDialog by remember(wordId) { mutableStateOf(false) }
    // 改完下次复习时间要重取一次。用计数器当 key，比在回调里手写重复的取数逻辑干净
    var reloadTick by remember(wordId) { mutableIntStateOf(0) }

    LaunchedEffect(wordId, reloadTick) {
        reviewState = container.database.reviewDao().get(wordId)
        decks = container.decks.decksForWord(wordId).map { it.name }
    }

    // 词条、关联词、字段配置三个流合成一个快照。initialValue 的 null 表示
    // 「还没等到第一帧」，等到了之后 word 仍是 null 才是真不存在（刚被删掉）
    val snapshot by remember(wordId) {
        combine(
            container.words.observeById(wordId),
            container.relations.observeFor(wordId),
            container.settings.cardFields
        ) { word, relations, fields -> Triple(word, relations, fields) }
    }.collectAsStateWithLifecycle(initialValue = null)

    // 进入详情页自动播一次发音
    snapshot?.first?.let { word ->
        LaunchedEffect(word.id) { speaker.speak(word.headword) }
    }

    // 「变形」文本里提到的库内词 → id，供渲染成可点击；词条到达后算一次
    var derivationsIndex by remember(wordId) { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(snapshot?.first?.id) {
        val derivations = snapshot?.first?.derivations
        derivationsIndex = container.words.idsByHeadwords(WordTokens.extract(derivations))
    }

    if (showScheduleDialog) {
        reviewState?.let { row ->
            ScheduleDialog(
                currentDays = ReviewSchedule.daysUntil(row.dueAtMinutes),
                onDismiss = { showScheduleDialog = false },
                onConfirm = { days ->
                    showScheduleDialog = false
                    scope.launch {
                        container.study.setNextReview(wordId, days)
                        reloadTick++
                    }
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(snapshot?.first?.headword ?: "单词") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑")
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
            val data = snapshot
            when {
                data == null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) { CircularProgressIndicator() }

                data.first == null -> Column(Modifier.fillMaxSize()) {
                    EmptyState(
                        icon = Icons.Filled.MenuBook,
                        title = "词条不存在",
                        message = "这个词可能刚被删除。"
                    )
                }

                else -> {
                    val (word, relations, fields) = data
                    // word 的可空性在上一个分支已经排除，但编译器无法通过
                    // Triple 的解构看出来，所以用 let 收窄
                    word?.let { current ->
                        WordHeader(
                            word = current,
                            lastReviewedAt = reviewState?.lastReviewedAt,
                            onSpeak = { speaker.speak(current.headword) }
                        )
                        ScheduleBadge(
                            reviewState = reviewState,
                            onSetDays = { showScheduleDialog = true }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            // 长按选择复制；只包内容、不参与外层布局约束
                            SelectionContainer {
                                // 单词和音标由页头单独渲染，这里剔掉；详情页一律全展开，
                                // 理由与学习揭晓页相同：进详情就是来看内容的
                                WordCardView(
                                    word = current,
                                    fields = fields
                                        .filter {
                                            it.enabled &&
                                                it.field != CardField.HEADWORD &&
                                                it.field != CardField.PHONETIC
                                        }
                                        .map { it.copy(expanded = true) },
                                    relations = relations,
                                    wordIndex = derivationsIndex,
                                    decks = decks,
                                    onOpenWord = onOpenWord
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「N 天后复习」徽章，点它设置下次复习时间。
 *
 * 只对**参与调度**的词显示。没有 review_states 行的词（从没学过）不显示 —— 给它
 * 设到期日等于把它从新词队列里抹掉，见 [com.alcohol.englishstudying.data.repo.StudyRepository.setNextReview]。
 * 挂起 / 太简单 / 太难的词也不显示：它们根本没被排进队列，留着的是移出前那一刻的
 * 到期时刻，照着它说「N 天后复习」是假话。
 */
@Composable
private fun ScheduleBadge(reviewState: ReviewStateEntity?, onSetDays: () -> Unit) {
    val row = reviewState ?: return
    if (row.state != CardState.LEARNING &&
        row.state != CardState.RELEARNING &&
        row.state != CardState.REVIEW
    ) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.clickable(onClick = onSetDays)
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    ReviewSchedule.label(row.dueAtMinutes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "设置下次复习",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

/** 设置下次复习的天数。确认后由调用方写库并重取。 */
@Composable
private fun ScheduleDialog(
    currentDays: Long,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(currentDays.coerceAtLeast(0L).toString()) }
    val parsed = text.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置下次复习") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    // 只收数字，且限制到四位 —— 五位数天数是 27 年，没有意义
                    onValueChange = { input -> text = input.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("几天后") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "0 表示今天就复习。设完之后这个字按天级间隔排期，不再走学习步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
