package com.alcohol.englishstudying.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.ui.components.EmptyState
import kotlinx.coroutines.launch

/**
 * 学习首页。
 *
 * 「学习」页签的落地页：当前词表的进度、今日新词与复习的计数，以及进入卡片界面的入口。
 * 首页本身不消费学习队列 —— 队列在真正点进卡片界面时才由 StudyViewModel 构建。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyHomeScreen(
    container: AppContainer,
    studyViewModel: StudyViewModel,
    onStartStudy: () -> Unit,
    onStartEarlyReview: () -> Unit,
    onStartExtraNewStudy: () -> Unit,
    onStartPractice: () -> Unit,
    onOpenImport: () -> Unit,
    viewModel: StudyHomeViewModel = viewModel(factory = StudyHomeViewModel.factory(container))
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val studyState by studyViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("学习") },
                actions = {
                    IconButton(onClick = onOpenImport) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "导入词库")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            state.loading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }

            state.libraryEmpty -> Column(Modifier.padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.FileUpload,
                    title = "词库还是空的",
                    message = "这个应用不内置词条。准备好你的考研词库后导入进来，" +
                        "巧记、词根词缀、变形这三块会在卡片上显示出来。",
                    actionLabel = "去导入词库",
                    onAction = onOpenImport
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                DeckProgressCard(state)

                Spacer(Modifier.height(28.dp))
                TodayCounts(state)

                Spacer(Modifier.height(32.dp))
                StartButton(
                    state = state,
                    activeQueueSize = studyState.regularQueueSize,
                    onStartStudy = onStartStudy,
                    onStartEarlyReview = onStartEarlyReview,
                    onStartExtraNewStudy = onStartExtraNewStudy
                )

                Spacer(Modifier.height(16.dp))
                PracticeButton(
                    dueCount = state.reviewDueCount,
                    onStartPractice = onStartPractice,
                    onNoDueWords = {
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar("当前暂无待复习单词，到期后再来复习吧！")
                        }
                    }
                )

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** 当前学习词表 + 已学比例。词表跟随设置里的「词表与学习范围」。 */
@Composable
private fun DeckProgressCard(state: StudyHomeUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                state.deckName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "已学 ${formatPercent(state.learnedFraction)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${state.learnedWords}/${state.totalWords}词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.learnedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
        }
    }
}

/**
 * 今日两行计数：「新学词 · 今天已学 36 / 上限 20」。
 *
 * 特意不做成裸分式。两个分母的语义完全不同 —— 新词那边是「每日上限」（一个目标），
 * 复习那边是「待复习的卡数」（一个会随作答减少、随新卡到期增大的动态值），
 * 光看 `36/20`、`0/4` 根本猜不出分子分母各是什么。写成短语之后，
 * 「今天已学 36」大于「上限 20」这种（把上限调小之后造成的）反常也一眼看得懂。
 *
 * 「待复习」这个叫法与统计页一致（StatsScreen 里同一个 dueCount 也叫这个名）。
 */
@Composable
private fun TodayCounts(state: StudyHomeUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CountRow3(
            label = "今日新词",
            firstHint = "初学",
            first = state.startedToday,
            secondHint = "掌握",
            second = state.masteredToday,
            thirdHint = "上限",
            third = state.newLimit,
            // 上限只算新词（dailyNewLimit），重学词不占额度 —— 所以是 `50+17` 不是 `67`
            thirdPlus = state.relearnPending
        )
        CountRow3(
            label = "复习词",
            firstHint = "已复习",
            first = state.reviewDoneToday,
            secondHint = "需巩固",
            second = state.learningCount,
            thirdHint = "待复习",
            third = state.regularDueCount
        )
    }
}

@Composable
private fun CountRow3(
    label: String,
    firstHint: String,
    first: Int,
    secondHint: String,
    second: Int,
    thirdHint: String,
    third: Int,
    /** 第三格后面额外跟的「+N」，见 [CountPhrase] 的说明。 */
    thirdPlus: Int? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(10.dp))
        CountPhrase(firstHint, first)
        Text(
            "/",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        CountPhrase(secondHint, second)
        Text(
            "/",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        CountPhrase(thirdHint, third, thirdPlus)
    }
}

@Composable
private fun CountRow(
    label: String,
    firstHint: String,
    first: Int,
    secondHint: String,
    second: Int
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(10.dp))
        CountPhrase(firstHint, first)
        Text(
            "/",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        CountPhrase(secondHint, second)
    }
}

/** 「上限 20」这样的短语：说明文字压暗，数字加粗，凑近了读也不会串行。 */
@Composable
private fun CountPhrase(hint: String, value: Int, plus: Int? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        // 「上限 50+17」里的那个 +17：今天要重学的词数。它**不占**新词额度（额度仍是前面
        // 那个数字），所以不能并进去，必须让两者看得见地分开。为 0 时不画，免得多个 +0。
        if (plus != null && plus > 0) {
            Text(
                "+$plus",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun StartButton(
    state: StudyHomeUiState,
    activeQueueSize: Int,
    onStartStudy: () -> Unit,
    onStartEarlyReview: () -> Unit,
    onStartExtraNewStudy: () -> Unit
) {
    val isSessionActive = activeQueueSize > 0
    val isGoalAchieved = state.isDailyGoalAchieved
    val canStudyExtra = state.hasUnlearnedInLibrary
    val isCooling = state.regularDueCount == 0 && state.coolingCount > 0

    val (buttonText, buttonEnabled, onClick) = when {
        isSessionActive && isGoalAchieved -> Triple(
            "继续巩固（剩余 $activeQueueSize 词）",
            true,
            onStartStudy
        )
        isSessionActive -> Triple(
            "继续学习（剩余 $activeQueueSize 词）",
            true,
            onStartStudy
        )
        isCooling -> Triple(
            "提前复习（${state.coolingCount} 词冷却中，约 ${state.coolingWaitMinutes} 分钟后）",
            true,
            onStartEarlyReview
        )
        isGoalAchieved && canStudyExtra -> Triple(
            "再背一组新词",
            true,
            onStartExtraNewStudy
        )
        isGoalAchieved && !canStudyExtra -> Triple(
            "今日已完成",
            false,
            onStartStudy
        )
        else -> Triple(
            if (state.answeredToday > 0) "继续学习" else "开始学习",
            state.hasCards,
            onStartStudy
        )
    }

    Button(
        onClick = onClick,
        enabled = buttonEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(buttonText, style = MaterialTheme.typography.titleMedium)
    }

    if (isSessionActive && isGoalAchieved) {
        Spacer(Modifier.height(12.dp))
        Text(
            "今日常规目标已达成，当前剩余 $activeQueueSize 个生词待再次巩固。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    } else if (isCooling && !isSessionActive) {
        Spacer(Modifier.height(12.dp))
        Text(
            "今日常规计划尚未全部完成，还有 ${state.coolingCount} 个单词正在冷却中（约 ${state.coolingWaitMinutes} 分钟后到期）。可点击上方提前复习，也可稍候再来。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    } else if (!buttonEnabled || (isGoalAchieved && canStudyExtra && !isSessionActive)) {
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                isGoalAchieved && canStudyExtra && !isSessionActive ->
                    "今日常规计划已达成！点击上方按钮可超额再背一组新词。"
                isGoalAchieved && !canStudyExtra ->
                    "词库中的单词已全部学完，暂无到期复习。"
                state.newLimit == 0 ->
                    "今天的卡都清完了。当前设置是「只复习不学新词」，想学新词就去设置里把每日新词上限调高。"
                else ->
                    "今天的到期卡都清完了，新词也学完了。到期的卡会在该出现的时候自动回来。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 「单纯复习」按钮：
 * - 仅抽取待复习词，练习过程不修改任何 SRS 属性，不落库、不记日志；
 * - 待复习数为 0 时点击弹出明确提示。
 */
@Composable
private fun PracticeButton(
    dueCount: Int,
    onStartPractice: () -> Unit,
    onNoDueWords: () -> Unit
) {
    OutlinedButton(
        onClick = {
            if (dueCount > 0) {
                onStartPractice()
            } else {
                onNoDueWords()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (dueCount > 0) "单纯复习（待复习 $dueCount 词）"
            else "单纯复习（暂无待复习词）",
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/** 百分比保留一位小数，整数时不留「.0」。 */
private fun formatPercent(fraction: Float): String {
    val percent = fraction * 100
    val rounded = Math.round(percent * 10) / 10f
    return if (rounded == rounded.toLong().toFloat()) "${rounded.toLong()}%"
    else "$rounded%"
}
