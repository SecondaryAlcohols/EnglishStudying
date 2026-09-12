package com.alcohol.englishstudying.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 参数输入框。
 *
 * 输入过程中就把合法值写回去（用户能立刻在学习页看到效果），
 * 但文本状态留在本地，这样「1.」这种输到一半的内容不会被强行补全成「1.0」。
 * 外部值变化（比如点了「恢复默认」）时再同步回来。
 */
@Composable
fun ParamField(
    label: String,
    value: Double,
    description: String,
    isDecimal: Boolean,
    onCommit: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(value.toPlainString()) }

    LaunchedEffect(value) {
        // 只在外部值与当前文本确实不一致时才回写，避免打字时光标跳动
        if (text.toDoubleOrNull() != value) text = value.toPlainString()
    }

    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                input.toDoubleOrNull()?.let(onCommit)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isDecimal) KeyboardType.Decimal else KeyboardType.Number
            )
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    }
}

/** 学习步这种「一串整数」的参数。输入非法时不写回，保留用户正在敲的内容。 */
@Composable
fun StepsField(
    steps: List<Int>,
    onCommit: (List<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    val canonical = steps.joinToString(",")
    var text by remember { mutableStateOf(canonical) }

    LaunchedEffect(canonical) {
        if (text.split(',').mapNotNull { it.trim().toIntOrNull() } != steps) text = canonical
    }

    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                // 所有非空片段都解析成正整数时才写回，否则用户删到一半就被强行改掉
                val segments = input.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val parsed = segments.mapNotNull { it.toIntOrNull() }
                if (parsed.isNotEmpty() && parsed.size == segments.size && parsed.all { it > 0 }) {
                    onCommit(parsed)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("学习步（分钟）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Text(
            "新词在学习阶段的重考间隔，走完最后一步才毕业到天级间隔。例：1,10 表示 1 分钟后、10 分钟后各考一次。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    }
}

/** 去掉 Double 结尾多余的 .0，让「2.5」显示成 2.5 而不是 2.500000。 */
internal fun Double.toPlainString(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()
