package com.alcohol.englishstudying.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.data.local.WordEntity
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.model.CardField
import com.alcohol.englishstudying.domain.model.CardFieldConfig
import com.alcohol.englishstudying.ui.components.SectionHeader
import com.alcohol.englishstudying.ui.study.WordCardView

/** 预览用的样例词条，把九个字段都填满，这样任何配置的效果都能一眼看到。 */
private val SAMPLE_WORD = WordEntity(
    id = -1L,
    headword = "absent",
    phonetic = "/ˈæbsənt/",
    partOfSpeech = "adj.",
    meanings = "缺席的；不在场的\n心不在焉的",
    mnemonic = "谐音「爱不 Sent」——爱不发送消息，人就不在场了",
    roots = "ab-（离开）+ -sent-（存在）→ 不在场",
    derivations = "absence n. 缺席\nabsent-minded adj. 心不在焉的",
    examples = "He was absent from class again. 他又缺课了。",
    tags = "高频,熟词僻义",
    frequency = 90
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardFieldSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    viewModel: CardFieldViewModel = viewModel(factory = CardFieldViewModel.factory(container))
) {
    val fields by viewModel.fields.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("卡片字段与顺序") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Text(
                    "从上到下就是卡片上的显示顺序。关掉的字段不会出现；" +
                        "「默认展开」决定这个字段刚翻到这张卡时是否已经展开 —— " +
                        "巧记、词根、变形默认是折叠的，先自己回忆再看提示效果更好。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                SectionHeader("实时预览")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        WordCardView(
                            word = SAMPLE_WORD,
                            fields = fields.filter { it.enabled }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            itemsIndexed(fields, key = { _, config -> config.field.name }) { index, config ->
                FieldConfigRow(
                    config = config,
                    isFirst = index == 0,
                    isLast = index == fields.lastIndex,
                    onMoveUp = { viewModel.moveUp(index) },
                    onMoveDown = { viewModel.moveDown(index) },
                    onToggleEnabled = { viewModel.toggleEnabled(config.field) },
                    onToggleExpanded = { viewModel.toggleExpanded(config.field) }
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = viewModel::restoreDefaults,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Undo, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("恢复默认字段配置")
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** 这几个字段是「答案」本身，展开与否没有意义，所以不给展开开关。 */
private val ALWAYS_EXPANDED = setOf(
    CardField.HEADWORD,
    CardField.PHONETIC,
    CardField.PART_OF_SPEECH,
    CardField.MEANINGS,
    CardField.TAGS
)

@Composable
private fun FieldConfigRow(
    config: CardFieldConfig,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleEnabled: () -> Unit,
    onToggleExpanded: () -> Unit
) {
    val collapsible = config.field !in ALWAYS_EXPANDED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(config.field.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    config.field.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onMoveUp, enabled = !isFirst) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
            }
            IconButton(onClick = onMoveDown, enabled = !isLast) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
            }
        }

        Row(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToggleCell(
                label = "显示",
                checked = config.enabled,
                enabled = true,
                onToggle = onToggleEnabled,
                modifier = Modifier.weight(1f)
            )
            ToggleCell(
                label = "默认展开",
                checked = if (collapsible) config.expanded else true,
                enabled = collapsible && config.enabled,
                onToggle = onToggleExpanded,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ToggleCell(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline
        )
    }
}
