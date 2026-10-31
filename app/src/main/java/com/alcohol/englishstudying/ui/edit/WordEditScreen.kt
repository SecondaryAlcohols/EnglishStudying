package com.alcohol.englishstudying.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.data.local.RelatedWord
import com.alcohol.englishstudying.data.local.WordListRow
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.model.WordRelationType
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
                label = "近义词",
                value = state.draft.synonyms,
                singleLine = false,
                hint = "多个用顿号或分号分隔，例：effect；influence。只有库中存在的词才会变成可点击的关联"
            ) { v -> viewModel.update { it.copy(synonyms = v) } }
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
                label = "COCA 排名",
                value = state.draft.frequency,
                hint = "数字越小越常用，抽新词时优先；0 表示没有排名，排在最后",
                keyboardType = KeyboardType.Number
            ) { v -> viewModel.update { it.copy(frequency = v) } }

            if (!state.isNew) {
                SectionHeader("关联词")
                Text(
                    "同根词和变形由「重建词语关联」自动推导，近义词来自上面的近义词列。" +
                        "这里可以手工补充 —— 手工加的不受重建影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                WordRelationType.entries.forEach { type ->
                    RelationSection(
                        title = type.displayName,
                        peers = state.relations.filter { it.type == type },
                        onRemove = { viewModel.removeRelation(it, type) }
                    )
                }
                OutlinedButton(
                    onClick = viewModel::openAddRelation,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("添加关联")
                }
            }

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

    if (state.addingRelation) {
        AddRelationDialog(
            query = state.searchQuery,
            type = state.newRelationType,
            results = state.searchResults,
            onQueryChange = viewModel::setSearchQuery,
            onTypeChange = viewModel::setNewRelationType,
            onPick = viewModel::addRelation,
            onDismiss = viewModel::closeAddRelation
        )
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

/** 一类关系的一行：标题 + 可删除的 chip，没有关联时显示一句占位。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelationSection(
    title: String,
    peers: List<RelatedWord>,
    onRemove: (Long) -> Unit
) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        if (peers.isEmpty()) {
            Text(
                "（暂无）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                peers.forEach { peer ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(peer.word.id) },
                        label = { Text(peer.word.headword) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "删除与 ${peer.word.headword} 的关联",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * 添加关联：先选关系类型，再搜库里的词点一下。
 *
 * 类型放在上面而不是「每个词配三个按钮」：一次只想加一条，先定类型能让结果行
 * 保持单一点击目标，手机上更好点。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddRelationDialog(
    query: String,
    type: WordRelationType,
    results: List<WordListRow>,
    onQueryChange: (String) -> Unit,
    onTypeChange: (WordRelationType) -> Unit,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加关联") },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WordRelationType.entries.forEach { option ->
                        FilterChip(
                            selected = option == type,
                            onClick = { onTypeChange(option) },
                            label = { Text(option.displayName) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索词库") }
                )
                Spacer(Modifier.height(8.dp))
                if (results.isEmpty()) {
                    Text(
                        if (query.isBlank()) "输入关键词开始搜索" else "没有匹配的词",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(results, key = { it.word.id }) { row ->
                            ListItem(
                                modifier = Modifier.clickable { onPick(row.word.id) },
                                headlineContent = { Text(row.word.headword) },
                                supportingContent = row.word.meaningList.firstOrNull()?.let {
                                    { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        }
    )
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
