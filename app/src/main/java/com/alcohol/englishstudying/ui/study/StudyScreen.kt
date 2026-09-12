package com.alcohol.englishstudying.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.srs.Rating
import com.alcohol.englishstudying.ui.components.EmptyState
import com.alcohol.englishstudying.ui.theme.RatingAgain
import com.alcohol.englishstudying.ui.theme.RatingEasy
import com.alcohol.englishstudying.ui.theme.RatingGood
import com.alcohol.englishstudying.ui.theme.RatingHard

private val RATING_ORDER = listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY)

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
    onOpenLibrary: () -> Unit,
    onOpenImport: () -> Unit,
    viewModel: StudyViewModel = viewModel(factory = StudyViewModel.factory(container))
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val card = state.current

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

                state.finished -> Column(Modifier.weight(1f)) {
                    SessionFinished(
                        reviewed = state.reviewedThisSession,
                        onRestart = { viewModel.startSession() },
                        onOpenLibrary = onOpenLibrary
                    )
                }

                card != null -> {
                    ProgressHeader(
                        reviewed = state.reviewedThisSession,
                        remaining = state.queue.size
                    )

                    // weight 让卡片区占满剩余空间，评分按钮始终贴在底部
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        WordCardView(word = card.word, fields = state.visibleFields)
                    }

                    RatingBar(
                        revealed = state.revealed,
                        previews = state.previewIntervals,
                        onReveal = viewModel::reveal,
                        onRate = viewModel::rate
                    )
                }
            }
        }
    }
}

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
private fun ProgressHeader(reviewed: Int, remaining: Int) {
    val total = reviewed + remaining
    val fraction = if (total == 0) 0f else reviewed.toFloat() / total

    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("本次已学 $reviewed", style = MaterialTheme.typography.labelLarge)
            Text(
                "队列剩余 $remaining",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SessionFinished(
    reviewed: Int,
    onRestart: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // weight 把按钮推到下方，空状态本身占满上方
        Column(Modifier.weight(1f)) {
            EmptyState(
                icon = Icons.Filled.CheckCircle,
                title = if (reviewed > 0) "今日完成" else "暂时没有要学的卡",
                message = if (reviewed > 0) {
                    "本次学了 $reviewed 张卡。到期的复习卡会在该出现的时候自动回到队列。"
                } else {
                    "今天的到期卡都清完了，也没有剩余的新词。" +
                        "可以去设置里调高每日新词上限，或者稍后再来。"
                },
                actionLabel = "再查一次",
                onAction = onRestart
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            OutlinedButton(onClick = onOpenLibrary) { Text("去词库看看") }
        }
    }
}

@Composable
private fun RatingBar(
    revealed: Boolean,
    previews: Map<Rating, String>,
    onReveal: () -> Unit,
    onRate: (Rating) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (!revealed) {
            Button(
                onClick = onReveal,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("显示答案")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RATING_ORDER.forEach { rating ->
                    Button(
                        onClick = { onRate(rating) },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colorOf(rating)),
                        contentPadding = PaddingValues(2.dp)
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
    }
}
