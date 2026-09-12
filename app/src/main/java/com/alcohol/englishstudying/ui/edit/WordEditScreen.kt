package com.alcohol.englishstudying.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WordEditScreen(
    container: AppContainer,
    wordId: Long?,
    onBack: () -> Unit,
    viewModel: WordEditViewModel = viewModel(
        // key 带上 wordId：从词库点不同词进来时不会复用上一个词的 ViewModel
        key = "word_edit_${wordId ?: 0L}",
        factory = WordEditViewModel.factory(container, wordId)
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "新增词条" else "编辑词条") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (state.loading) return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            DraftField("单词", state.draft.headword) { v ->
                viewModel.update { it.copy(headword = v) }
            }

            state.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            DraftField("音标", state.draft.phonetic) { v ->
                viewModel.update { it.copy(phonetic = v) }
            }
            DraftField("词性", state.draft.partOfSpeech) { v ->
                viewModel.update { it.copy(partOfSpeech = v) }
            }
            DraftField(
                label = "释义",
                value = state.draft.meanings,
                singleLine = false,
                hint = "多条释义用换行分隔，卡片上会按 1. 2. 3. 编号显示"
            ) { v -> viewModel.update { it.copy(meanings = v) } }

            SectionHeader("三个特色字段")
            DraftField(
                label = "巧记",
                value = state.draft.mnemonic,
                singleLine = false,
                hint = "联想、谐音、词源故事都行"
            ) { v -> viewModel.update { it.copy(mnemonic = v) } }
            DraftField(
                label = "词根词缀",
                value = state.draft.roots,
                singleLine = false,
                hint = "例：ab-(离开) + sent(存在) → 不在场"
            ) { v -> viewModel.update { it.copy(roots = v) } }
            DraftField(
                label = "变形",
                value = state.draft.derivations,
                singleLine = false,
                hint = "例：absent-minded adj. 心不在焉的；absence n. 缺席"
            ) { v -> viewModel.update { it.copy(derivations = v) } }

            SectionHeader("其他")
            DraftField(
                label = "例句",
                value = state.draft.examples,
                singleLine = false,
                hint = "多条例句用换行分隔"
            ) { v -> viewModel.update { it.copy(examples = v) } }
            DraftField(
                label = "标签",
                value = state.draft.tags,
                hint = "英文逗号分隔，例：高频,熟词僻义"
            ) { v -> viewModel.update { it.copy(tags = v) } }
            DraftField(
                label = "重要度",
                value = state.draft.frequency,
                hint = "数字越大越靠前，用于「按重要度优先」抽新词",
                keyboardType = KeyboardType.Number
            ) { v -> viewModel.update { it.copy(frequency = v) } }

            if (state.decks.isNotEmpty()) {
                SectionHeader("所属词表")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.decks.forEach { deck ->
                        FilterChip(
                            selected = deck.id in state.selectedDeckIds,
                            onClick = { viewModel.toggleDeck(deck.id) },
                            label = { Text(deck.name) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }

                if (!state.isNew) {
                    OutlinedButton(onClick = viewModel::resetProgress) {
                        Text("重置进度")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个词条？") },
            text = { Text("它的复习进度和作答记录会一并删除，无法恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DraftField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    Column(Modifier.padding(top = 10.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}
