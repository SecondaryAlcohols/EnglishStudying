package com.alcohol.englishstudying.ui.decks

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.data.local.DeckWithCount
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.ui.components.EmptyState

/**
 * 词表管理。
 *
 * 交互定成一条规则：**点一行 = 把它设为学习范围**，改名和删除走右侧图标。
 * 学习范围只能有一个，所以用单选而不是开关 —— 开关表达不了「选哪一个」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckScreen(
    container: AppContainer,
    onBack: () -> Unit,
    viewModel: DeckViewModel = viewModel(factory = DeckViewModel.factory(container))
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<DeckWithCount?>(null) }
    var deleting by remember { mutableStateOf<DeckWithCount?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("词表与学习范围") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建词表")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ListItem(
                modifier = Modifier.clickable { viewModel.setStudyDeck(null) },
                headlineContent = { Text("全部词库") },
                supportingContent = { Text("共 ${state.totalWords} 个词条，不限词表") },
                leadingContent = {
                    RadioButton(
                        selected = state.studyDeckId == null,
                        onClick = { viewModel.setStudyDeck(null) }
                    )
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            if (state.decks.isEmpty()) {
                Column(Modifier.weight(1f)) {
                    EmptyState(
                        icon = Icons.Filled.Layers,
                        title = "还没有词表",
                        message = "导入词库时会按文件名自动建一个。" +
                            "也可以手动新建，用来把不同来源的词库分开背。",
                        actionLabel = "新建词表",
                        onAction = { showCreate = true }
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(state.decks, key = { it.id }) { deck ->
                        val selected = state.studyDeckId == deck.id
                        Column {
                            ListItem(
                                modifier = Modifier.clickable { viewModel.setStudyDeck(deck.id) },
                                headlineContent = { Text(deck.name) },
                                supportingContent = {
                                    Text(
                                        if (selected) "正在只学这个词表 · ${deck.wordCount} 个词条"
                                        else "${deck.wordCount} 个词条"
                                    )
                                },
                                leadingContent = {
                                    RadioButton(
                                        selected = selected,
                                        onClick = { viewModel.setStudyDeck(deck.id) }
                                    )
                                },
                                trailingContent = {
                                    Row {
                                        IconButton(onClick = { renaming = deck }) {
                                            Icon(Icons.Filled.Edit, contentDescription = "重命名")
                                        }
                                        IconButton(onClick = { deleting = deck }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                                        }
                                    }
                                }
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        NameDialog(
            title = "新建词表",
            initial = "",
            confirmLabel = "创建",
            onConfirm = { name -> viewModel.create(name); showCreate = false },
            onDismiss = { showCreate = false }
        )
    }

    renaming?.let { deck ->
        NameDialog(
            title = "重命名词表",
            initial = deck.name,
            confirmLabel = "保存",
            onConfirm = { name -> viewModel.rename(deck.id, name); renaming = null },
            onDismiss = { renaming = null }
        )
    }

    deleting?.let { deck ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除词表「${deck.name}」？") },
            text = {
                Text(
                    "只是解散这个分组，里面的 ${deck.wordCount} 个词条仍留在词库中，" +
                        "复习进度也不受影响。"
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.delete(deck.id); deleting = null }) { Text("删除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("词表名") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = { Button(onClick = { onConfirm(text) }) { Text(confirmLabel) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } }
    )
}
