package com.alcohol.englishstudying.data.imports

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.Charset

/**
 * 从 SAF 选中的文件里读出文本。
 *
 * 编码是这里唯一值得较真的地方：中文用户用 Excel 另存的 CSV 默认是 GBK，
 * 直接按 UTF-8 读会得到满屏替换字符 —— 而且不会报错，只是静默地把词库读坏。
 * 所以先按 UTF-8 解，发现替换字符再退回 GBK 重试。
 */
class WordFileReader(private val context: Context) {

    suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("无法打开这个文件，请换一个再试。")
        if (bytes.isEmpty()) throw IOException("文件是空的。")
        decode(bytes)
    }

    /** 显示在导入向导里的文件名。 */
    fun displayName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains(REPLACEMENT_CHAR)) return utf8

        // UTF-8 解出了替换字符，多半是 GBK。GB18030 是 GBK 的超集，优先试它。
        listOf("GB18030", "GBK", "UTF-16LE", "UTF-16BE").forEach { name ->
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: return@forEach
            val decoded = runCatching { String(bytes, charset) }.getOrNull() ?: return@forEach
            if (!decoded.contains(REPLACEMENT_CHAR)) return decoded
        }
        return utf8
    }

    private companion object {
        val REPLACEMENT_CHAR = 0xFFFD.toChar()
    }
}
