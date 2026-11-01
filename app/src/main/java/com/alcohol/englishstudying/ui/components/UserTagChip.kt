package com.alcohol.englishstudying.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

/**
 * 用户标签的小色块。
 *
 * 颜色由**标签名**决定，不是随机分配的 —— 同一个标签在词库列表、标签管理、学习页弹窗里
 * 必须是同一个颜色，否则每次重进都换个色，看着像换了个标签。名字的 hashCode 是稳定的，
 * 取模落到下面这 5 组柔和色里。
 *
 * 注意和系统标签区分：系统标签（L0/L1、考研…）在卡片上还是原来的灰色块，
 * 这个彩色块只属于用户自己建的标签。
 */
private val TAG_COLORS: List<Pair<Color, Color>> = listOf(
    Color(0xFF8A5A2B) to Color(0xFFFBF0DF),
    Color(0xFF2F5E8C) to Color(0xFFE4EFFA),
    Color(0xFF8C3B3B) to Color(0xFFFBE7E7),
    Color(0xFF3A6B63) to Color(0xFFDFEFEB),
    Color(0xFF5B4A8C) to Color(0xFFECE7F8)
)

/** 标签名 → 调色板下标。纯函数，便于单测「同名同色」。 */
fun userTagColorIndex(name: String, paletteSize: Int = TAG_COLORS.size): Int {
    if (paletteSize <= 0) return 0
    return name.hashCode().absoluteValue % paletteSize
}

@Composable
fun UserTagChip(
    name: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    val (foreground, background) = TAG_COLORS[userTagColorIndex(name)]
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) foreground else background
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.White else foreground,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
