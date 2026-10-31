package com.alcohol.englishstudying.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.srs.NewWordOrder
import com.alcohol.englishstudying.ui.components.SectionHeader

private val ORDER_LABELS = listOf(
    NewWordOrder.FREQUENCY to "按词频排名",
    NewWordOrder.SEQUENCE to "按入库顺序",
    NewWordOrder.RANDOM to "随机",
    NewWordOrder.MIXED to "按难度混合",
    NewWordOrder.DECK_WEIGHTED to "按词表比例"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenCardFields: () -> Unit,
    onOpenDecks: () -> Unit,
    onOpenStats: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hideHints by viewModel.hideHints.collectAsStateWithLifecycle()
    val groupRelated by viewModel.groupRelated.collectAsStateWithLifecycle()
    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ---- 卡片显示：另一个自定义维度 ----
            SectionHeader("卡片显示")
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    modifier = Modifier.clickableRow(onOpenCardFields),
                    headlineContent = { Text("卡片字段与顺序") },
                    supportingContent = {
                        Text("决定学习时显示哪些字段、按什么顺序、默认展开还是折叠")
                    },
                    leadingContent = { Icon(Icons.Filled.Style, contentDescription = null) },
                    trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) }
                )
            }
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("作答前只显示单词和音标") },
                    supportingContent = {
                        Text("开启后，巧记、词根、释义等要点「显示答案」才出现，先逼自己回忆一遍")
                    },
                    trailingContent = {
                        Switch(checked = hideHints, onCheckedChange = viewModel::setHideHints)
                    }
                )
            }

            // ---- 学习范围 ----
            SectionHeader("学习范围")
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    modifier = Modifier.clickableRow(onOpenDecks),
                    headlineContent = { Text("词表与学习范围") },
                    supportingContent = {
                        Text(
                            settings.studyDeckId?.let { "当前只学选中的词表" } ?: "当前学全部词库"
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.Layers, contentDescription = null) },
                    trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) }
                )
            }
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("同一组变形的词放在一起背") },
                    supportingContent = {
                        Text(
                            "开启后，队列里互为变形的词（如 answer / answers / answered）" +
                                "会连续出现，其余顺序尽量保持不变。只在「已学过的卡」和「新词」" +
                                "各自内部连排，新词不会被提前拉进复习里。改完下次进入学习时生效。"
                        )
                    },
                    trailingContent = {
                        Switch(checked = groupRelated, onCheckedChange = viewModel::setGroupRelated)
                    }
                )
            }

            // ---- 每日计划 ----
            SectionHeader("每日计划")
            ParamField(
                label = "每日新词上限",
                value = settings.dailyNewLimit.toDouble(),
                description = "每天最多引入多少个没学过的词。标为「太简单 / 太难」移出学习的词也占一个名额，设 0 表示只复习不学新词。",
                isDecimal = false,
                onCommit = { viewModel.setDailyNewLimit(it.toInt()) }
            )
            ParamField(
                label = "每日复习上限",
                value = settings.dailyReviewLimit.toDouble(),
                description = "每天最多复习多少张到期卡。到期的词不会消失，没做完的会留到明天。",
                isDecimal = false,
                onCommit = { viewModel.setDailyReviewLimit(it.toInt()) }
            )
            Text(
                "新词抽取顺序",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ORDER_LABELS.forEach { (order, label) ->
                    FilterChip(
                        selected = settings.newWordOrder == order,
                        onClick = { viewModel.setNewWordOrder(order) },
                        label = { Text(label) }
                    )
                }
            }
            if (settings.newWordOrder == NewWordOrder.MIXED) {
                MixRatioField(
                    ratio = settings.newWordMix,
                    onCommit = viewModel::setNewWordMix
                )
            }
            if (settings.newWordOrder == NewWordOrder.DECK_WEIGHTED) {
                WeightedDeckField(
                    decks = decks,
                    selected = settings.weightedDeckIds,
                    onCommit = viewModel::setWeightedDeckIds
                )
            }
            ParamField(
                label = "每组词数",
                value = settings.groupSize.toDouble(),
                description = "连续作答这么多张卡后弹出一次「单词小结」，" +
                    "把这组词连词性带释义过一遍再继续。只影响提示节奏，不影响记忆算法。",
                isDecimal = false,
                onCommit = { viewModel.setGroupSize(it.toInt()) }
            )

            // ---- 学习步与毕业 ----
            SectionHeader("学习步与毕业")
            StepsField(
                steps = settings.learningStepsMinutes,
                onCommit = viewModel::setLearningSteps
            )
            ParamField(
                label = "毕业间隔（天）",
                value = settings.graduatingIntervalDays,
                description = "学习步走完后，第一次进入天级复习的间隔。",
                isDecimal = true,
                onCommit = viewModel::setGraduatingInterval
            )

            // ---- 算法参数 ----
            SectionHeader("算法参数")
            ParamField(
                label = "初始难度系数",
                value = settings.startingEase,
                description = "每个新词的起始 ease，越大后续间隔涨得越快。常用 2.5。",
                isDecimal = true,
                onCommit = viewModel::setStartingEase
            )
            ParamField(
                label = "「模糊」间隔倍数",
                value = settings.hardMultiplier,
                description = "按「模糊」时间隔乘以这个数，涨幅小于正常复习。",
                isDecimal = true,
                onCommit = viewModel::setHardMultiplier
            )
            ParamField(
                label = "全局间隔修正",
                value = settings.intervalModifier,
                description = "所有天级间隔最后统一乘这个数。觉得复习太频繁就调小，比如 0.8。",
                isDecimal = true,
                onCommit = viewModel::setIntervalModifier
            )
            ParamField(
                label = "最小间隔（天）",
                value = settings.minIntervalDays,
                description = "任何一张卡两次复习之间至少隔这么多天。",
                isDecimal = true,
                onCommit = viewModel::setMinInterval
            )
            ParamField(
                label = "最大间隔（天）",
                value = settings.maxIntervalDays,
                description = "间隔的上限，防止一个词被排到几年后。",
                isDecimal = true,
                onCommit = viewModel::setMaxInterval
            )
            ParamField(
                label = "挂起阈值（遗忘次数）",
                value = settings.leechThreshold.toDouble(),
                description = "同一个词累计「忘记」这么多次后自动挂起，不再出现在每日队列里，" +
                    "需要到词条编辑页手动重置。",
                isDecimal = false,
                onCommit = { viewModel.setLeechThreshold(it.toInt()) }
            )

            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = viewModel::restoreDefaults,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Undo, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("恢复默认参数")
            }

            // ---- 数据 ----
            SectionHeader("数据")
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    modifier = Modifier.clickableRow(onOpenStats),
                    headlineContent = { Text("学习统计") },
                    supportingContent = { Text("词库规模、各状态词数、今日完成情况") },
                    leadingContent = { Icon(Icons.Filled.BarChart, contentDescription = null) },
                    trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) }
                )
            }
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("重建词语关联") },
                    supportingContent = {
                        Text(
                            "重新推导同根词、变形、近义词。改了词根或近义词列重新导入后，" +
                                "点一下让关联跟上。你手工添加的关联不受影响。"
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.Hub, contentDescription = null) },
                    trailingContent = {
                        OutlinedButton(onClick = viewModel::rebuildRelations) { Text("重建") }
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { confirmReset = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) { Text("清空所有学习进度") }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("清空所有学习进度？") },
            text = {
                Text(
                    "所有词条都会回到「新词」状态，复习记录一并删除。" +
                        "词条本身、巧记、词根、变形这些内容不受影响。此操作无法撤销。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmReset = false
                    viewModel.resetAllProgress()
                }) { Text("清空") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmReset = false }) { Text("取消") }
            }
        )
    }
}

/** ListItem 自己不带点击，补一个可点击修饰符。 */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)
