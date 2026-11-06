package com.alcohol.englishstudying.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.data.local.DeckWithCount
import com.alcohol.englishstudying.data.local.WordListRow
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.model.MeaningParser
import com.alcohol.englishstudying.domain.srs.CardState
import com.alcohol.englishstudying.domain.srs.ReviewSchedule
import com.alcohol.englishstudying.ui.components.EmptyState
import com.alcohol.englishstudying.ui.components.StateBadge
import com.alcohol.englishstudying.ui.components.UserTagChip

private val STATE_FILTERS = listOf(
    null to "全部",
    CardState.NEW to "未学",
    CardState.LEARNING to "学习中",
    CardState.RELEARNING to "重学中",
    CardState.REVIEW to "已掌握",
    CardState.TOO_EASY to "太简单",
    CardState.TOO_HARD to "太难",
    CardState.SUSPENDED to "已挂起"
)

/**
 * 词库。
 *
 * 版面自上而下：搜索 → 词表 → 学习状态 → 我的标签 → 列表。
 *
 * 两套标签在这里第一次分开：列表行下面那些彩色小块是**用户标签**（易混/写作/听力错…），
 * 系统标签（L0/L1、真题高频、考研…）只在词条详情里出现 —— 一页几十行、每行五六个灰标签，
 * 谁都看不清哪个是自己打的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenWord: (Long) -> Unit,
    onAddWord: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenDecks: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenCloudDecks: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("词库") },
                actions = {
                    // 进「云端词表」整页：下载、管理自己的词表、公开与分享码都在那边
                    IconButton(onClick = onOpenCloudDecks) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = "云端词表")
                    }
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
            FloatingActionButton(onClick = onAddWord) {
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
                        state = state,
                        onQueryChange = viewModel::setQuery,
                        onStateFilterChange = viewModel::setStateFilter,
                        onDeckChange = viewModel::setDeckFilter,
                        onPriorityChange = viewModel::setPriorityOnly,
                        onToggleTag = viewModel::toggleTag,
                        onOpenTags = onOpenTags
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
                                WordRow(
                                    row = row,
                                    onClick = { onOpenWord(row.word.id) },
                                    // 太简单/太难名单里的词：行内直接移出恢复学习
                                    onResume = if (row.state == CardState.TOO_EASY ||
                                        row.state == CardState.TOO_HARD
                                    ) {
                                        { viewModel.resumeExcluded(row.word.id) }
                                    } else {
                                        null
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
    }
}


@Composable
private fun SearchAndFilters(
    state: LibraryUiState,
    onQueryChange: (String) -> Unit,
    onStateFilterChange: (CardState?) -> Unit,
    onDeckChange: (Long?) -> Unit,
    onPriorityChange: (Boolean) -> Unit,
    onToggleTag: (Long) -> Unit,
    onOpenTags: () -> Unit
) {
    val filters = state.filters
    Column {
        OutlinedTextField(
            value = filters.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text("搜单词、释义、巧记、词根") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (filters.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空")
                    }
                }
            }
        )

        // 词表：横向滚动，一行放得下常用的几个
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = filters.deckId == null,
                    onClick = { onDeckChange(null) },
                    label = { Text("全部词表") }
                )
            }
            items(state.decks, key = { it.id }) { deck ->
                FilterChip(
                    selected = filters.deckId == deck.id,
                    onClick = { onDeckChange(deck.id) },
                    label = { Text("${deck.name} ${deck.wordCount}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }

        // 学习状态 + 优先学：**两行，各自横向滑动**。
        // 一行塞九个会把后面的词截成半个；两行既能一屏看全多数选项，
        // 又能左右滑到剩下的。样式保持原来的 FilterChip。
        val stateRows = listOf(STATE_FILTERS.take(5), STATE_FILTERS.drop(5))
        stateRows.forEachIndexed { rowIndex, row ->
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = if (rowIndex == 0) 0.dp else 2.dp)
            ) {
                items(row) { (value, label) ->
                    FilterChip(
                        selected = filters.stateFilter == value,
                        onClick = { onStateFilterChange(value) },
                        label = { Text(label) }
                    )
                }
                // 「优先学」不是学习状态，单独挂在第二行末尾
                if (rowIndex == stateRows.lastIndex) {
                    item {
                        FilterChip(
                            selected = filters.priorityOnly,
                            onClick = { onPriorityChange(!filters.priorityOnly) },
                            label = { Text("优先学") },
                            leadingIcon = if (filters.priorityOnly) {
                                {
                                    Icon(
                                        Icons.Filled.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }

        // 我的标签：用户自建的那一套，和上面的词表/状态取交集。
        //
        // 标签为空时入口文案是「新建」—— 空着也要有条路能建第一个标签，
        // 否则新装的应用在词库里永远看不到标签区。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "我的标签",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            // 一个标签都没有时也要能进得去：入口文案换成「新建」，
            // 点进去就是标签管理页，那里有新建输入框。
            TextButton(onClick = onOpenTags) {
                Text(if (state.tags.isEmpty()) "新建 ›" else "管理 ›")
            }
        }
        if (state.tags.isEmpty()) {
            Text(
                "还没有标签。点右上「新建」建一个，或者到卡片底部给词打标签。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.tags, key = { it.id }) { tag ->
                    FilterChip(
                        selected = tag.id in filters.tagIds,
                        onClick = { onToggleTag(tag.id) },
                        label = { Text("${tag.name} ${tag.wordCount}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun WordRow(
    row: WordListRow,
    onClick: () -> Unit,
    onResume: (() -> Unit)? = null
) {
    val meaning = row.word.meaningList.firstOrNull()
    val pos = MeaningParser.normalizePos(row.word.partOfSpeech)
    val tags = row.userTagList

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.word.headword,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (row.word.priority) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "优先学",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                pos?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (meaning != null) {
                Text(
                    meaning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            if (tags.isNotEmpty()) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 一行最多露三个，多了会挤掉词义
                    tags.take(3).forEach { tag -> UserTagChip(tag) }
                    if (tags.size > 3) {
                        Text(
                            "+${tags.size - 3}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(start = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateBadge(row.displayState)
                if (onResume != null) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onResume, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("移出")
                    }
                }
            }
            // 只给真正参与调度的状态显示倒计时：挂起 / 太简单 / 太难 留着的是移出前那一刻的
            // dueAtMinutes，照着它说「N 天后复习」是假话
            row.dueAtMinutes
                ?.takeIf {
                    row.displayState == CardState.LEARNING ||
                        row.displayState == CardState.RELEARNING ||
                        row.displayState == CardState.REVIEW
                }
                ?.let { due ->
                    Text(
                        ReviewSchedule.label(due),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
        }
    }
}
