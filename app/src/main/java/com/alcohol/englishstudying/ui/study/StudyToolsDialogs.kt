package com.alcohol.englishstudying.ui.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alcohol.englishstudying.data.local.TodayLogRow
import com.alcohol.englishstudying.data.local.UserTagWithCount
import com.alcohol.englishstudying.data.local.WordEntity
import com.alcohol.englishstudying.domain.model.MeaningParser
import com.alcohol.englishstudying.domain.srs.Rating
import com.alcohol.englishstudying.ui.theme.RatingAgain
import com.alcohol.englishstudying.ui.theme.RatingEasy
import com.alcohol.englishstudying.ui.theme.RatingGood
import com.alcohol.englishstudying.ui.theme.RatingHard
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 卡片页右上角三个工具弹窗中的一个。 */
enum class StudyTool { SEARCH, TAGS, LOGS }

/**
 * 搜索单词。
 *
 * 边打边查：每次输入停 180ms 再查库，免得一个字一次 LIKE 把键盘敲卡。
 * 点结果直接跳词条详情 —— 卡片页收起后回来还是当前这张卡。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WordSearchDialog(
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    search: suspend (String) -> List<WordEntity>
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WordEntity>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }

    // 点开就聚焦、直接弹键盘：这个弹窗的唯一用途就是打字，
    // 还要再点一下输入框等于让用户多点一次。延迟一帧等对话框挂上去再请求焦点，
    // 太早请求会被对话框自己的初始焦点抢走。
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(120)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            searched = false
            return@LaunchedEffect
        }
        delay(180)
        results = search(query)
        searched = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索单词") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("单词或释义片段") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
                Spacer(Modifier.height(8.dp))
                when {
                    query.isBlank() -> Text(
                        "输入几个字母就能查，不用退出学习。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    searched && results.isEmpty() -> Text(
                        "词库里没有匹配「$query」的词",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    else -> LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(results, key = { it.id }) { word ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(word.id) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        word.headword,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    MeaningParser.normalizePos(word.partOfSpeech)?.let { pos ->
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            pos,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                val firstMeaning = word.meaningList.firstOrNull().orEmpty()
                                if (firstMeaning.isNotEmpty()) {
                                    Text(
                                        firstMeaning,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/**
 * 给当前这张卡的词打**用户标签**（易混 / 写作 / 听力错……）。
 *
 * 候选只有用户自己建的那些 —— 系统标签（L0–L3、真题高频、考研…）不在这里，它们属于
 * 词库本身，改了就丢了层级信息。点一下切换选中，输入框里敲的新名字点「添加」或回车即进
 * 选中集合，保存时由 `UserTagRepository` 负责「没有这个标签就顺手建一个」。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagsDialog(
    headword: String,
    wordId: Long,
    loadAllTags: suspend () -> List<UserTagWithCount>,
    loadCurrentTags: suspend (Long) -> List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var selected by remember(wordId) { mutableStateOf<Set<String>>(emptySet()) }
    var allTags by remember { mutableStateOf<List<UserTagWithCount>>(emptyList()) }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(wordId) {
        allTags = loadAllTags()
        selected = loadCurrentTags(wordId).toSet()
    }

    fun addTyped() {
        val tag = input.trim()
        if (tag.isEmpty()) return
        selected = selected + tag
        input = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标签 · $headword") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "只显示你自己建的标签；系统标签（L0/L1、词表）不在这里出现。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 选中的排前面（可能是刚输入、库里还没有的名字），再按使用次数排
                    val names = (selected.toList() + allTags.map { it.name }).distinct()
                    names.forEach { name ->
                        val count = allTags.firstOrNull { it.name == name }?.wordCount
                        FilterChip(
                            selected = name in selected,
                            onClick = {
                                selected = if (name in selected) selected - name else selected + name
                            },
                            label = {
                                Text(if (count != null) "$name $count" else name)
                            }
                        )
                    }
                }
                if (allTags.isEmpty() && selected.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "还没有任何标签，直接在下面输入就能建第一个。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("新标签") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addTyped() })
                    )
                    IconButton(onClick = { addTyped() }, enabled = input.isNotBlank()) {
                        Icon(Icons.Filled.Add, contentDescription = "添加标签")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected.toList()) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 今天的答题流水（最新在上）。
 *
 * 和「单词小结」不同：那边按组回顾词义，这边是操作记录 —— 什么时候答的、给了哪一档、
 * 答完排到几天后，用来回看「这个词怎么突然跑到 16 天后了」。
 */
@Composable
fun TodayLogDialog(
    onDismiss: () -> Unit,
    load: suspend () -> List<TodayLogRow>,
    onOpenWord: (Long) -> Unit
) {
    var rows by remember { mutableStateOf<List<TodayLogRow>?>(null) }
    LaunchedEffect(Unit) { rows = load() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("今天的答题日志") },
        text = {
            val list = rows
            when {
                list == null -> Text(
                    "读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                list.isEmpty() -> Text(
                    "今天还没有作答记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> Column {
                    Text(
                        "共 ${list.size} 次作答，最新的在最上面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list, key = { it.id }) { row ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenWord(row.wordId) }
                                    .padding(vertical = 7.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    TIME_FORMAT.format(
                                        Instant.ofEpochMilli(row.reviewedAt).atZone(ZoneId.systemDefault())
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    row.headword,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                RatingTag(row.rating)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    formatInterval(row.intervalDaysAfter),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun RatingTag(rating: Rating) {
    val (label, color) = when (rating) {
        Rating.AGAIN -> "忘记" to RatingAgain
        Rating.HARD -> "模糊" to RatingHard
        Rating.GOOD -> "认识" to RatingGood
        Rating.EASY -> "太简单" to RatingEasy
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.16f)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** `6.25 天` / `3 天` / `10 分钟`：学习步里的卡间隔小于一天，按分钟显示更好读。 */
private fun formatInterval(days: Double): String = when {
    days <= 0.0 -> "—"
    days >= 1.0 -> {
        val rounded = Math.round(days * 100) / 100.0
        if (rounded == Math.floor(rounded)) "${rounded.toInt()} 天" else "$rounded 天"
    }
    else -> "${Math.round(days * 24 * 60)} 分钟"
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
