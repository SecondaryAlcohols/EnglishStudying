package com.alcohol.englishstudying.ui.enhance

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.local.AppDatabase
import com.alcohol.englishstudying.data.local.TodayStudiedWordRow
import com.alcohol.englishstudying.di.AppContainer
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EnhanceUiState(
    /** 今天作答过的词，按作答时间正序。 */
    val todayWords: List<TodayStudiedWordRow> = emptyList(),
    val loading: Boolean = false,
    val exporting: Boolean = false,
    /** 一次性的结果提示（导出成功 / 失败原因），展示后由下一次操作覆盖。 */
    val message: String? = null
)

class EnhanceViewModel(
    private val database: AppDatabase,
    private val appContext: Context,
    private val today: () -> LocalDate = { LocalDate.now() }
) : ViewModel() {

    private val _state = MutableStateFlow(EnhanceUiState())
    val state: StateFlow<EnhanceUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 页面每次回到前台都会调一次，保证「今天学过的词」是最新的。 */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val rows = withContext(Dispatchers.IO) {
                val (start, end) = todayRange()
                database.reviewDao().todayStudiedWords(start, end)
            }
            _state.update { it.copy(loading = false, todayWords = rows) }
        }
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(exporting = true, message = null) }
            try {
                val count = withContext(Dispatchers.IO) {
                    val rows = loadTodayWords()
                    appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(buildCsv(rows, bom = true).toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("无法打开写入目标")
                    rows.size
                }
                _state.update { it.copy(exporting = false, message = "已导出 $count 个单词") }
            } catch (e: Exception) {
                _state.update { it.copy(exporting = false, message = "导出失败：${e.message}") }
            }
        }
    }

    /** 把今日单词的 CSV 内容直接放进系统剪贴板，方便粘到笔记 / Anki 输入框里。 */
    fun copyToClipboard() {
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val rows = loadTodayWords()
                    val csv = buildCsv(rows, bom = false)
                    withContext(Dispatchers.Main) { writeClipboard(csv) }
                    rows.size
                }
                _state.update { it.copy(message = "已复制 $count 个单词到剪贴板") }
            } catch (e: Exception) {
                _state.update { it.copy(message = "复制失败：${e.message}") }
            }
        }
    }

    private suspend fun loadTodayWords(): List<TodayStudiedWordRow> {
        val (start, end) = todayRange()
        return database.reviewDao().todayStudiedWords(start, end)
    }

    private fun writeClipboard(text: String) {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw IOException("剪贴板不可用")
        cm.setPrimaryClip(ClipData.newPlainText("今日学习单词", text))
    }

    fun suggestedFileName(): String = "今日学习_${today()}.csv"

    private fun todayRange(): Pair<Long, Long> {
        val start = today().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return start to start + MILLIS_PER_DAY
    }

    /**
     * 列名沿用导入格式（word/phonetic/…），导出的文件可以直接再导入回来；
     * 后两列是今天的作答摘要，导入时会被当作认不出的列忽略。
     * 文件导出带 BOM（Excel 双击不乱码），剪贴板内容不带。
     */
    private fun buildCsv(rows: List<TodayStudiedWordRow>, bom: Boolean): String {
        val header = "word,phonetic,pos,meaning,mnemonic,root,derivation,example,reviews,forgets"
        val lines = rows.map { r ->
            listOf(
                r.headword,
                r.phonetic.orEmpty(),
                r.partOfSpeech.orEmpty(),
                r.meanings.orEmpty(),
                r.mnemonic.orEmpty(),
                r.roots.orEmpty(),
                r.derivations.orEmpty(),
                r.examples.orEmpty(),
                r.answerCount.toString(),
                r.againCount.toString()
            ).joinToString(",") { csvEscape(it) }
        }
        return (if (bom) "\uFEFF" else "") + (listOf(header) + lines).joinToString("\r\n")
    }

    private fun csvEscape(field: String): String =
        if (field.contains(',') || field.contains('"') || field.contains('\n') || field.contains('\r')) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    companion object {
        private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EnhanceViewModel(
                    database = container.database,
                    appContext = container.appContext
                )
            }
        }
    }
}
