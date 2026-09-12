package com.alcohol.englishstudying.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.data.local.DeckWithCount
import com.alcohol.englishstudying.data.local.WordListRow
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.srs.CardState
import com.alcohol.englishstudying.ui.components.EmptyState
import com.alcohol.englishstudying.ui.components.StateBadge

private val STATE_FILTERS = listOf(
    null to "全部",
    CardState.NEW to "新词",
    CardState.LEARNING to "学习中",
    CardState.REVIEW to "复习中",
    CardState.SUSPENDED to "已挂起"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenWord: (Long) -> Unit,
    onOpenImport: () -> Unit,
    onOpenDecks: () -> Unit,
    onOpenStats: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("词库") },
                actions = {
                    IconButton(onClick = onOpenStats) {
                        Icon(Icons.Filled.BarChart, contentDescription = "统计")
                    }
                    IconButton(onClick = onOpenDecks) {
                        Icon(Icons.Filled.Layers, contentDescription = "词表")
                    }
                    IconButton(onClick = onOpenImport) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "导入")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenWord(0L) }) {
                Icon(Icons.Filled.Add, contentDescription = "新增词条")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isEmptyLibrary -> Column(Modifier.weight(1f)) {
                    EmptyState(
                        icon = Icons.Filled.MenuBook,
                        title = "词库是空的",
                        message = "导入一份 CSV 或 JSON 词库开始，也可以先手动加几个词试试效果。",
                        actionLabel = "去导入词库",
                        onAction = onOpenImport
                    )
                }

                else -> {
                    SearchAndFilters(
                        query = state.filters.query,
                        onQueryChange = viewModel::setQuery,
                        stateFilter = state.filters.stateFilter,
                        onStateFilterChange = viewModel::setStateFilter,
                        deckId = state.filters.deckId,
                        decks = state.decks,
                        onDeckChange = viewModel::setDeckFilter
                    )

                    Text(
                        text = "共 ${state.totalCount} 词，当前显示 ${state.rows.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    if (state.noMatches) {
                        Column(Modifier.weight(1f)) {
                            EmptyState(
                                icon = Icons.Filled.Search,
                                title = "没有匹配的词条",
                                message = "换个关键词，或者把筛选条件清掉。",
                                actionLabel = "清除筛选",
                                onAction = viewModel::clearFilters
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 88.dp)
                        ) {
                            items(state.rows, key = { it.word.id }) { row ->
                                Column {
                                    WordRow(row = row, onClick = { onOpenWord(row.word.id) })
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
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchAndFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    stateFilter: CardState?,
    onStateFilterChange: (CardState?) -> Unit,
    deckId: Long?,
    decks: List<DeckWithCount>,
    onDeckChange: (Long?) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜单词、释义、巧记、词根") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空")
                    }
                }
            }
        )

        Spacer(Modifier.height(8.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STATE_FILTERS.forEach { (value, label) ->
                FilterChip(
                    selected = stateFilter == value,
                    onClick = { onStateFilterChange(value) },
                    label = { Text(label) }
                )
            }
            DeckFilterChip(
                label = decks.firstOrNull { it.id == deckId }?.name ?: "全部词表",
                decks = decks,
                selectedId = deckId,
                onSelect = onDeckChange
            )
        }
    }
}

@Composable
private fun DeckFilterChip(
    label: String,
    decks: List<DeckWithCount>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        FilterChip(
            selected = selectedId != null,
            onClick = { expanded = true },
            label = { Text(label) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("全部词表") },
                onClick = { onSelect(null); expanded = false }
            )
            decks.forEach { deck ->
                DropdownMenuItem(
                    text = { Text("${deck.name}（${deck.wordCount}）") },
                    onClick = { onSelect(deck.id); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun WordRow(row: WordListRow, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.word.headword, style = MaterialTheme.typography.titleMedium)
                row.word.phonetic?.takeIf { it.isNotBlank() }?.let { phonetic ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        phonetic,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        supportingContent = {
            val meaning = row.word.meaningList.firstOrNull()
            if (meaning != null) {
                Text(meaning, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = { StateBadge(row.state) }
    )
}
