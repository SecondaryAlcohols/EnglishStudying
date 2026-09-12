package com.alcohol.englishstudying.ui.imports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.data.imports.ImportField
import com.alcohol.englishstudying.data.imports.ImportFormat
import com.alcohol.englishstudying.data.imports.ImportPreview
import com.alcohol.englishstudying.data.imports.ImportResult
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.ui.components.EmptyState
import com.alcohol.englishstudying.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onImported: () -> Unit,
    viewModel: ImportViewModel = viewModel(factory = ImportViewModel.factory(container))
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val pickFile = rememberLauncherForActivityResult(
        // 用 */* 而不是 text/csv：各家文件管理器给 .csv/.json 报的 MIME 五花八门，
        // 限定类型会让用户根本选不中自己的文件
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onFilePicked) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入词库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (state.step) {
                ImportStep.PICK_FILE -> PickFileStep(
                    busy = state.busy,
                    error = state.error,
                    onPick = { pickFile.launch(arrayOf("*/*")) }
                )

                ImportStep.MAP_COLUMNS -> state.preview?.let { preview ->
                    MapColumnsStep(
                        preview = preview,
                        fileName = state.fileName,
                        deckName = state.deckName,
                        onDeckNameChange = viewModel::setDeckName,
                        hasHeaderRow = state.hasHeaderRow,
                        onHasHeaderRowChange = viewModel::setHasHeaderRow,
                        onMappingChange = viewModel::setMapping,
                        busy = state.busy,
                        error = state.error,
                        onImport = viewModel::startImport,
                        onReselect = viewModel::reset
                    )
                }

                ImportStep.DONE -> state.result?.let { result ->
                    ResultStep(
                        result = result,
                        onViewLibrary = onImported,
                        onImportAnother = viewModel::reset
                    )
                }
            }

            if (state.busy) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun PickFileStep(
    busy: Boolean,
    error: String?,
    onPick: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        EmptyState(
            icon = Icons.Filled.FileOpen,
            title = "选择词库文件",
            message = "支持 CSV 和 JSON。CSV 的首行可以是表头，列名用 word / 单词 / " +
                "mnemonic / 巧记 这类常见写法会自动识别；识别不出的下一步手动指定。",
            actionLabel = "选择文件",
            onAction = onPick
        )
        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapColumnsStep(
    preview: ImportPreview,
    fileName: String?,
    deckName: String,
    onDeckNameChange: (String) -> Unit,
    hasHeaderRow: Boolean,
    onHasHeaderRowChange: (Boolean) -> Unit,
    onMappingChange: (Int, ImportField?) -> Unit,
    busy: Boolean,
    error: String?,
    onImport: () -> Unit,
    onReselect: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text(
                "已读取 ${preview.dataRowCount} 行 · ${preview.format.name} · ${fileName.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (preview.format == ImportFormat.CSV) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("首行是表头", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "如果你的文件第一行就是数据，关掉它",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = hasHeaderRow, onCheckedChange = onHasHeaderRowChange)
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = deckName,
                onValueChange = onDeckNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("导入到哪个词表") },
                supportingText = { Text("同一个词表可以累积多次导入") }
            )

            SectionHeader("列映射")
            Text(
                "指定源文件的每一列对应应用的哪个字段。已有内容的词条会更新，" +
                    "复习进度不会丢。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        itemsIndexed(preview.headers) { index, header ->
            MappingRow(
                header = header,
                sample = preview.rawRows.firstOrNull()?.cells?.getOrNull(index),
                selected = preview.mapping.getOrNull(index),
                onSelect = { field -> onMappingChange(index, field) }
            )
        }

        item {
            SectionHeader("预览（前 ${minOf(5, preview.dataRowCount)} 行）")
            preview.previewRows(limit = 5).forEach { row ->
                PreviewRowCard(row.values)
            }

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReselect, modifier = Modifier.weight(1f)) {
                    Text("重选文件")
                }
                Button(
                    onClick = onImport,
                    modifier = Modifier.weight(1f),
                    enabled = preview.headwordMapped && !busy
                ) {
                    Text("开始导入")
                }
            }
            if (!preview.headwordMapped) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "还没指定「单词」列，无法导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun MappingRow(
    header: String,
    sample: String?,
    selected: ImportField?,
    onSelect: (ImportField?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                header.ifBlank { "(空列名)" },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            if (!sample.isNullOrBlank()) {
                Text(
                    sample.take(40),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selected?.displayName ?: "忽略")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                DropdownMenuItem(
                    text = { Text("忽略此列") },
                    onClick = { onSelect(null); expanded = false }
                )
                ImportField.entries.forEach { field ->
                    DropdownMenuItem(
                        // 带上常见列名，用户能一眼确认自己那列该选哪个
                        text = { Text("${field.displayName}　（${field.aliases.take(3).joinToString(" / ")}）") },
                        onClick = { onSelect(field); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewRowCard(values: Map<ImportField, String>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(10.dp)) {
            ImportField.entries.forEach { field ->
                values[field]?.let { value ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(
                            field.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(64.dp)
                        )
                        Text(
                            value.take(120),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultStep(
    result: ImportResult,
    onViewLibrary: () -> Unit,
    onImportAnother: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Text("导入完成", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    result.deckName?.let { "已写入词表「$it」" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    StatLine("新增", result.inserted)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    StatLine("更新", result.updated)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    StatLine("跳过", result.skipped)
                }
            }
        }

        if (result.issues.isNotEmpty()) {
            item {
                SectionHeader("跳过的行")
                Text(
                    "这些行没有写进词库。修好源文件后可以再导一次，已导入的部分不会重复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            items(result.issues.take(MAX_ISSUES_SHOWN)) { issue ->
                Text(
                    issue.display,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            if (result.issues.size > MAX_ISSUES_SHOWN) {
                item {
                    Text(
                        "……还有 ${result.issues.size - MAX_ISSUES_SHOWN} 条，未全部列出。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onImportAnother, modifier = Modifier.weight(1f)) {
                    Text("再导一个")
                }
                Button(onClick = onViewLibrary, modifier = Modifier.weight(1f)) {
                    Text("去词库看看")
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatLine(label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
    }
}

private const val MAX_ISSUES_SHOWN = 100
