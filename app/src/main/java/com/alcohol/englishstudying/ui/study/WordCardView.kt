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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.alcohol.englishstudying.data.local.RelatedWord
import com.alcohol.englishstudying.data.local.WordEntity
import com.alcohol.englishstudying.domain.model.CardField
import com.alcohol.englishstudying.domain.model.CardFieldConfig
import com.alcohol.englishstudying.domain.model.MeaningParser
import com.alcohol.englishstudying.ui.theme.HeadwordStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 卡片主体。
 *
 * [fields] 已经由调用方按配置过滤和排序 —— 包括「揭晓前只留单词和音标」这一步。
 * 这里只负责画，以及处理单个字段的折叠/展开。
 *
 * [relations] 是当前这个词的关联词，供「同根词 / 近义词」两个字段渲染；
 * 「变形」字段的可点击词走 [wordIndex]。查不到关联时那些字段整个不显示，
 * 而不是显示一个空标题。
 */
@Composable
fun WordCardView(
    word: WordEntity,
    fields: List<CardFieldConfig>,
    relations: List<RelatedWord> = emptyList(),
    /** 「变形」文本里提到的词（小写）→ 词 id，命中的词渲染成可点击。 */
    wordIndex: Map<String, Long> = emptyMap(),
    /** 这个词所属的词表名，供「词表」字段渲染。空列表时该字段不显示。 */
    decks: List<String> = emptyList(),
    onOpenWord: (Long) -> Unit = {},
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
                relations = relations,
                wordIndex = wordIndex,
                decks = decks,
                expanded = expanded,
                onToggle = { overrides[config.field] = !expanded },
                onOpenWord = onOpenWord
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldBlock(
    field: CardField,
    word: WordEntity,
    relations: List<RelatedWord>,
    wordIndex: Map<String, Long>,
    decks: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenWord: (Long) -> Unit
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

        CardField.MEANINGS -> {
            val meanings = word.meaningList
            if (meanings.isNotEmpty()) {
                val parsed = MeaningParser.parseAll(meanings)
                // 词性逐条挂在释义前。第一条释义自己没写词性时，pos 列的值兜底补上
                // （but：pos 列 conj.，其余释义各自带 prep./adv./n.…… 首条也要有着落）
                // pos 列是 ECDICT 原样（`a.` / `ad.` 居多），要过一遍归一化，
                // 否则首条绿框写 `a.`、下面几条写 `adj.`，看着像两种词性。
                val globalPos = MeaningParser.normalizePos(word.partOfSpeech)
                Column {
                    parsed.forEachIndexed { index, item ->
                        if (index > 0) Spacer(Modifier.height(4.dp))
                        val pos = item.pos ?: if (index == 0) globalPos else null
                        Row {
                            if (pos != null) {
                                PosChip(pos)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
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

        // 词表：一个词可以同时属于多个。不属于任何词表时整个字段不显示 ——
        // 导入时若没指定词表也不在这里报「无」，那只是噪声
        CardField.DECKS -> {
            if (decks.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    decks.forEach { name ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }

        // 关联词：库中真实存在、可点击跳转的词。没有关联时整个字段不显示，
        // 不留一个空标题在那儿占地方
        CardField.ROOT_FAMILY,
        CardField.SYNONYMS -> {
            val type = field.relationType ?: return
            val peers = relations.filter { it.type == type }
            if (peers.isEmpty()) return

            Column {
                CollapsibleHeader(
                    label = field.displayName,
                    hint = field.hint.takeIf { !expanded },
                    expanded = expanded,
                    onToggle = onToggle
                )
                AnimatedVisibility(visible = expanded) {
                    RelationChips(peers = peers, onOpenWord = onOpenWord)
                }
            }
        }

        // 变形：自由文本，但其中提到的、库里真实存在的词渲染成可点击，直达那个词。
        // 曾经有一个单独的「变形链」chip 字段渲染这层关系，后来并入这里
        CardField.DERIVATIONS -> {
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
                        DerivationText(content = content, wordIndex = wordIndex, onOpenWord = onOpenWord)
                    }
                }
            }
        }

        // 以下是「提示类」字段，支持折叠 —— 默认折叠，先自己回忆再展开
        CardField.MNEMONIC,
        CardField.ROOTS,
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

/**
 * 关联词的 chip 流。
 *
 * 每个 chip 显示对方的词条，点一下跳到那个词。同根词额外把共享的词根印在
 * 名字后面 —— 光看「infer / refer / prefer 是一组」不如直接看到 `-fer-` 有用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelationChips(peers: List<RelatedWord>, onOpenWord: (Long) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        peers.forEach { peer ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.clickable { onOpenWord(peer.word.id) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        peer.word.headword,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    peer.label?.let { label ->
                        Spacer(Modifier.width(6.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单词页头：词条 + 音标 + 发音喇叭。
 *
 * 与作答正面的区别：这里靠左、字号小一档，因为下面还有大段释义要占地方。
 * 学习揭晓页和单词详情页共用；给 [lastReviewedAt] 就在词条上方带一枚
 * 「多久前学过」徽章（新词或查不到复习状态时不显示）。
 *
 * [showPhonetic] 给学习揭晓页用：那一页的音标要挪到下一行、和「太简单 / 太难」
 * 并排，所以由页头让出去。单词详情页不传，保持原样。
 */
@Composable
fun WordHeader(
    word: WordEntity,
    lastReviewedAt: Long? = null,
    onSpeak: () -> Unit,
    showPhonetic: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            lastReviewedAt?.let { lastSeenBadge(it) }
            Text(word.headword, style = MaterialTheme.typography.titleLarge)
            if (showPhonetic) {
                word.phonetic?.takeIf { it.isNotBlank() }?.let { phonetic ->
                    Text(
                        phonetic,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        IconButton(onClick = onSpeak) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "朗读 ${word.headword}",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 词性小胶囊。固定最小宽度让正文左缘对齐，不随 n./pron. 的长短参差。 */
@Composable
private fun PosChip(pos: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            pos,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 52.dp)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * 「变形」的正文：把库里存在的英文词渲染成可点击，点击直达那个词的详情页。
 *
 * 词形与词 id 的对应关系由调用方预先查好（[wordIndex]），这里只做纯渲染 ——
 * 渲染期间不做任何数据库访问。没命中索引的片段按普通文本对待。
 */
@Composable
private fun DerivationText(
    content: String,
    wordIndex: Map<String, Long>,
    onOpenWord: (Long) -> Unit
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val normalColor = MaterialTheme.colorScheme.onSurface

    val annotated = remember(content, wordIndex, linkColor, normalColor) {
        buildAnnotatedString {
            var cursor = 0
            for (match in WORD_TOKEN_REGEX.findAll(content)) {
                append(content.substring(cursor, match.range.first))
                val token = match.value
                val id = wordIndex[token.lowercase()]
                if (id != null) {
                    pushStringAnnotation(WORD_TAG, id.toString())
                    withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)) {
                        append(token)
                    }
                    pop()
                } else {
                    append(token)
                }
                cursor = match.range.last + 1
            }
            append(content.substring(cursor))
        }
    }

    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = normalColor),
        onClick = { offset ->
            annotated.getStringAnnotations(WORD_TAG, offset, offset)
                .firstOrNull()
                ?.item
                ?.toLongOrNull()
                ?.let(onOpenWord)
        }
    )
}

private const val WORD_TAG = "word"
private val WORD_TOKEN_REGEX = Regex("[A-Za-z][A-Za-z'’\\-]*")

/**
 * 「多久以前学过」徽章。
 *
 * 用复习状态里的毫秒时间戳按天取整反推；没有复习记录的词（新词）整个不显示。
 */
@Composable
fun lastSeenBadge(lastReviewedAt: Long) {
    val days = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(lastReviewedAt).atZone(ZoneId.systemDefault()).toLocalDate(),
        LocalDate.now()
    )
    val label = when {
        days <= 0L -> "今天学过"
        days == 1L -> "昨天学过"
        days < 30L -> "$days 天前学过"
        days < 365L -> "${days / 30} 个月前学过"
        else -> "${days / 365} 年前学过"
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
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
