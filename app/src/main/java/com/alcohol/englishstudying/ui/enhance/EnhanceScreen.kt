package com.alcohol.englishstudying.ui.enhance

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Outbound
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.data.local.TodayStudiedWordRow
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.ui.components.EmptyState
import com.alcohol.englishstudying.ui.components.SectionHeader

/**
 * 「增强」页签。放各种围绕学习主流程的附加能力，
 * 第一项是导出今天学过的单词（CSV，可直接再导入回来）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhanceScreen(
    container: AppContainer,
    viewModel: EnhanceViewModel = viewModel(factory = EnhanceViewModel.factory(container))
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 页签用 saveState/restoreState 保活，切回来时组合重建，
    // 用 LaunchedEffect(Unit) 重新拉一次今天的作答记录
    LaunchedEffect(Unit) { viewModel.refresh() }

    val exportCsv = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let(viewModel::exportTo) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("增强") }) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.todayWords.isEmpty() && !state.loading) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.Outbound,
                    title = "今天还没有学习记录",
                    message = "去「学习」页签刷几个词，它们会出现在这里。"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    item {
                        SectionHeader("今日学习")
                        ExportCard(
                            count = state.todayWords.size,
                            exporting = state.exporting,
                            message = state.message,
                            fileName = viewModel.suggestedFileName(),
                            onExport = { exportCsv.launch(viewModel.suggestedFileName()) },
                            onCopy = viewModel::copyToClipboard
                        )
                    }

                    item { SectionHeader("今天学过的词（${state.todayWords.size}）") }

                    items(state.todayWords, key = { it.wordId }) { row ->
                        TodayWordRow(row)
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }

            if (state.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun ExportCard(
    count: Int,
    exporting: Boolean,
    message: String?,
    fileName: String,
    onExport: () -> Unit,
    onCopy: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("导出今日单词", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "把今天作答过的 $count 个词导出成 CSV（含巧记、词根、变形、例句和作答摘要）。" +
                    "文件可直接再导入本应用，也能给 Anki 等工具用；也可以直接复制到剪贴板粘贴走。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onExport, enabled = count > 0 && !exporting) {
                    Text(if (exporting) "导出中…" else "导出 CSV")
                }
                OutlinedButton(onClick = onCopy, enabled = count > 0 && !exporting) {
                    Text("复制到剪贴板")
                }
            }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TodayWordRow(row: TodayStudiedWordRow) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    row.headword,
                    style = MaterialTheme.typography.titleMedium
                )
                row.partOfSpeech?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (row.againCount > 0) "答${row.answerCount}次·忘${row.againCount}次"
                    else "答${row.answerCount}次",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.againCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            row.meanings?.let { meanings ->
                Text(
                    meanings.lines().first(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
