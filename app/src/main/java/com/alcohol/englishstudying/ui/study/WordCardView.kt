package com.alcohol.englishstudying.ui.study

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.alcohol.englishstudying.data.local.WordEntity
import com.alcohol.englishstudying.domain.model.CardField
import com.alcohol.englishstudying.domain.model.CardFieldConfig
import com.alcohol.englishstudying.ui.theme.HeadwordStyle

/**
 * 卡片主体。
 *
 * [fields] 已经由调用方按配置过滤和排序 —— 包括「揭晓前只留单词和音标」这一步。
 * 这里只负责画，以及处理单个字段的折叠/展开。
 */
@Composable
fun WordCardView(
    word: WordEntity,
    fields: List<CardFieldConfig>,
    modifier: Modifier = Modifier
) {
    // key 用 word.id：换到下一张卡时所有字段回到配置里的默认折叠状态
    val overrides = remember(word.id) { mutableStateMapOf<CardField, Boolean>() }

    Column(modifier = modifier.fillMaxWidth()) {
        fields.forEachIndexed { index, config ->
            if (index > 0) Spacer(Modifier.height(14.dp))
            val expanded = overrides[config.field] ?: config.expanded
            FieldBlock(
                field = config.field,
                word = word,
                expanded = expanded,
                onToggle = { overrides[config.field] = !expanded }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldBlock(
    field: CardField,
    word: WordEntity,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    when (field) {
        CardField.HEADWORD -> {
            Text(word.headword, style = HeadwordStyle)
        }

        CardField.PHONETIC -> {
            word.phonetic?.takeIf { it.isNotBlank() }?.let { phonetic ->
                Text(
                    phonetic,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        CardField.PART_OF_SPEECH -> {
            word.partOfSpeech?.takeIf { it.isNotBlank() }?.let { pos ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        pos,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        CardField.MEANINGS -> {
            val meanings = word.meaningList
            if (meanings.isNotEmpty()) {
                Column {
                    meanings.forEachIndexed { index, meaning ->
                        if (index > 0) Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (meanings.size > 1) "${index + 1}. $meaning" else meaning,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        CardField.TAGS -> {
            val tags = word.tagList
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // 以下是「提示类」字段，支持折叠 —— 默认折叠，先自己回忆再展开
        CardField.MNEMONIC,
        CardField.ROOTS,
        CardField.DERIVATIONS,
        CardField.EXAMPLES -> {
            val content = field.contentOf(word)
            if (content.isNullOrBlank()) return

            Column {
                CollapsibleHeader(
                    label = field.displayName,
                    hint = field.hint.takeIf { !expanded },
                    expanded = expanded,
                    onToggle = onToggle
                )
                AnimatedVisibility(visible = expanded) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        when (field) {
                            CardField.EXAMPLES -> word.exampleList.forEach { example ->
                                Text(
                                    "· $example",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }

                            CardField.ROOTS -> Text(
                                content,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )

                            else -> Text(content, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleHeader(
    label: String,
    hint: String?,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (hint != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun CardField.contentOf(word: WordEntity): String? = when (this) {
    CardField.MNEMONIC -> word.mnemonic
    CardField.ROOTS -> word.roots
    CardField.DERIVATIONS -> word.derivations
    CardField.EXAMPLES -> word.examples
    else -> null
}
