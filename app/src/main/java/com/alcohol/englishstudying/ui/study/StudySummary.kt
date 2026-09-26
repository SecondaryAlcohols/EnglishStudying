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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alcohol.englishstudying.domain.model.MeaningParser

/**
 * 一组的单词小结。
 *
 * 连刷若干张之后给一次停顿：把这组词的词形、词性、释义排在一起过一遍。
 * 作答时为了逼自己回忆，释义是被藏起来的；这里补上一次完整的回看。
 */
@Composable
fun StudySummary(
    items: List<GroupItem>,
    speaker: WordSpeaker,
    hasMore: Boolean,
    remainingCount: Int = 0,
    isRetryOnly: Boolean = false,
    onNextGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "单词小结",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${items.size} 个词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            val hintText = when {
                remainingCount == 0 -> "本组单词已全部掌握"
                isRetryOnly -> "本组已学完，后方还有 $remainingCount 个生词待巩固"
                else -> "本组已学完，后方剩余 $remainingCount 词"
            }
            Text(
                text = hintText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isRetryOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            // key 里带上下标：按「忘记」的卡会排回队尾，一组里同一个词可能出现两次，
            // 只用 word.id 当 key 会撞车，LazyColumn 会直接抛异常。
            itemsIndexed(items, key = { index, item -> "$index-${item.word.id}" }) { _, item ->
                SummaryRow(
                    item = item,
                    onSpeak = { speaker.speak(item.word.headword) }
                )
            }
        }

        val buttonText = when {
            !hasMore || remainingCount == 0 -> "完成"
            isRetryOnly -> "巩固未掌握单词（剩余 $remainingCount 词）"
            else -> "下一组（剩余 $remainingCount 词）"
        }
        Button(
            onClick = onNextGroup,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(56.dp)
        ) {
            Text(
                buttonText,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun SummaryRow(item: GroupItem, onSpeak: () -> Unit) {
    val word = item.word
    // 逐条解析出各自的词性；都没有词性时退回 pos 列那一个值
    val parsed = MeaningParser.parseAll(word.meaningList)
    val anyPos = parsed.any { it.pos != null }
    val globalPos = word.partOfSpeech?.takeIf { it.isNotBlank() && !anyPos }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                word.headword,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSpeak) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "朗读 ${word.headword}",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        if (parsed.isEmpty() && globalPos == null) {
            Spacer(Modifier.height(14.dp))
        } else {
            Column(Modifier.padding(top = 2.dp, bottom = 14.dp)) {
                globalPos?.let { pos ->
                    SummaryMeaningRow(pos, null, italic = true)
                }
                parsed.forEach { item ->
                    SummaryMeaningRow(item.pos, item.text, italic = false)
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    }
}

@Composable
private fun SummaryMeaningRow(pos: String?, meaning: String?, italic: Boolean) {
    Row(Modifier.padding(vertical = 1.dp)) {
        if (pos != null) {
            Text(
                pos,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(min = 44.dp)
            )
            Spacer(Modifier.size(10.dp))
        }
        if (meaning != null) {
            Text(meaning, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
