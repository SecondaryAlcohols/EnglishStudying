package com.alcohol.englishstudying.ui.remote

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.data.remote.WordSearchHitDto
import com.alcohol.englishstudying.di.AppContainer
import kotlinx.coroutines.launch

/**
 * 服务器上的单张词表详情：搜词、加词/摘词、上传 CSV。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDeckDetailScreen(
    container: AppContainer,
    deckId: Long,
    onBack: () -> Unit,
    viewModel: MyDeckDetailViewModel = viewModel(
        factory = MyDeckDetailViewModel.factory(container, deckId)
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val name = container.fileReader.displayName(uri)
                val text = container.fileReader.readText(uri)
                viewModel.upload(name.ifBlank { "upload.csv" }, text)
            }.onFailure {
                snackbarHostState.showSnackbar(it.message ?: "读取文件失败")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.name.ifBlank { "词表" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.uploading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(20.dp)
                        )
                    } else {
                        IconButton(onClick = {
                            // csv / json 都给出去，剩下交给系统文件选择器
                            picker.launch(
                                arrayOf(
                                    "text/*",
                                    "application/json",
                                    "application/octet-stream"
                                )
                            )
                        }) {
                            Icon(Icons.Filled.UploadFile, contentDescription = "上传词表")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                "${state.wordCount} 词 · 上传的 CSV 会写进你的私有词库，不影响公共词库",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                singleLine = true,
                placeholder = { Text("搜单词或释义，点右侧按钮加入词表") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
            )

            state.error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.searching -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )

                    state.query.isBlank() -> Text(
                        "输入关键词开始搜索；也可以直接用右上角的按钮上传 CSV / JSON，\n" +
                            "上传的词会进你的私有词库并自动挂到这张表。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )

                    state.results.isEmpty() -> Text(
                        "没有匹配的词。可以换个关键词，或者搜不到就直接上传 CSV。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.results, key = { "${it.source}-${it.id}" }) { hit ->
                            SearchRow(
                                hit = hit,
                                enabled = !state.busy,
                                onToggle = { viewModel.toggle(hit) }
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
}

@Composable
private fun SearchRow(hit: WordSearchHitDto, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(hit.headword, style = MaterialTheme.typography.bodyLarge)
                if (hit.source == "private") {
                    Text(
                        "私有",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            hit.meaning?.takeIf { it.isNotBlank() }?.let { meaning ->
                Text(
                    meaning.replace('\n', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onToggle, enabled = enabled) {
            if (hit.inDeck) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已在词表中，点击移除",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(Icons.Filled.Add, contentDescription = "加入词表")
            }
        }
    }
}
