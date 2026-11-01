package com.alcohol.englishstudying.ui.tags

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.data.local.UserTagWithCount
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.ui.components.userTagColorIndex

/**
 * 「我的标签」管理页。
 *
 * 这里是用户标签唯一的编辑入口：新建、改名、删除。**系统标签不在这里** ——
 * L0–L3、真题高频、考研这些来自词库本身，跟着词表走，删了会把词库的层级信息弄丢。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(
    container: AppContainer,
    onBack: () -> Unit,
    viewModel: TagViewModel = viewModel(factory = TagViewModel.factory(container))
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var input by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<UserTagWithCount?>(null) }
    var deleting by remember { mutableStateOf<UserTagWithCount?>(null) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的标签") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 一句话说清标签的边界：它只影响筛选和搜索，不动排期
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    "标签只影响筛选和搜索，不参与复习排期。系统标签（L0–L3、真题高频、考研…）" +
                        "来自词库本身，不在这里编辑。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("新建标签，如「翻译易错」") }
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            viewModel.create(input)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加标签")
                }
            }

            if (state.tags.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "还没有标签",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "在词库或学习卡片上给词打标签，这里会按使用次数排出来。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.tags, key = { it.id }) { tag ->
                        TagRow(
                            tag = tag,
                            onRename = { renaming = tag },
                            onDelete = { deleting = tag }
                        )
                    }
                }
            }
        }
    }

    renaming?.let { tag ->
        RenameDialog(
            tag = tag,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                viewModel.rename(tag.id, name)
            }
        )
    }

    deleting?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除标签「${tag.name}」？") },
            text = { Text("只会解除 ${tag.wordCount} 个词上的这个标签，词条本身不动。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(tag.id)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TagRow(
    tag: UserTagWithCount,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.tertiary
        )
        Box(
            Modifier
                .size(10.dp)
                .background(colors[userTagColorIndex(tag.name, colors.size)], CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            tag.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${tag.wordCount} 词",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onRename) { Text("编辑") }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

@Composable
private fun RenameDialog(
    tag: UserTagWithCount,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(tag.id) { mutableStateOf(tag.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名标签") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "改名会同步到所有挂着它的词。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
