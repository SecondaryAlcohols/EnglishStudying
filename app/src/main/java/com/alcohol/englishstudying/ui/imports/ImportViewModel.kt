package com.alcohol.englishstudying.ui.imports

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.imports.ImportField
import com.alcohol.englishstudying.data.imports.ImportFormat
import com.alcohol.englishstudying.data.imports.ImportParser
import com.alcohol.englishstudying.data.imports.ImportPreview
import com.alcohol.englishstudying.data.imports.ImportResult
import com.alcohol.englishstudying.data.imports.ParseOutcome
import com.alcohol.englishstudying.data.imports.WordFileReader
import com.alcohol.englishstudying.data.imports.WordImporter
import com.alcohol.englishstudying.data.repo.DeckRepository
import com.alcohol.englishstudying.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImportStep { PICK_FILE, MAP_COLUMNS, DONE }

data class ImportUiState(
    val step: ImportStep = ImportStep.PICK_FILE,
    val busy: Boolean = false,
    val fileName: String? = null,
    val format: ImportFormat? = null,
    val deckName: String = "",
    val hasHeaderRow: Boolean = true,
    val preview: ImportPreview? = null,
    val result: ImportResult? = null,
    val error: String? = null
) {
    val canImport: Boolean get() = preview?.headwordMapped == true && !busy
}

class ImportViewModel(
    private val fileReader: WordFileReader,
    private val importer: WordImporter,
    private val deckRepository: DeckRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** 原始文本留着，用户切换「首行是表头」时可以重新解析，不必再读一次文件。 */
    private var rawText: String? = null

    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val text = fileReader.readText(uri)
                rawText = text
                val fileName = fileReader.displayName(uri)

                val parsed = ImportParser.parse(text, hasHeaderRow = _state.value.hasHeaderRow)
                when (parsed) {
                    is ParseOutcome.Failure -> _state.update {
                        it.copy(busy = false, error = parsed.message)
                    }

                    is ParseOutcome.Success -> {
                        val deckName = _state.value.deckName.ifBlank { fileName.toDeckName() }
                        _state.update {
                            it.copy(
                                busy = false,
                                fileName = fileName,
                                format = parsed.preview.format,
                                preview = parsed.preview,
                                deckName = deckName,
                                step = ImportStep.MAP_COLUMNS,
                                error = null
                            )
                        }
                        if (deckName.isNotBlank()) ensureDeck(deckName)
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, error = e.message ?: "读取文件失败")
                }
            }
        }
    }

    /** 提前把词表建出来，用户在向导里就能从下拉里选到它。 */
    private suspend fun ensureDeck(name: String) {
        runCatching { deckRepository.createOrGet(name) }
    }

    fun setDeckName(value: String) = _state.update { it.copy(deckName = value) }

    fun setHasHeaderRow(value: Boolean) {
        val text = rawText ?: return
        val parsed = ImportParser.parse(text, hasHeaderRow = value)
        _state.update { current ->
            when (parsed) {
                is ParseOutcome.Success -> current.copy(
                    hasHeaderRow = value,
                    preview = parsed.preview,
                    error = null
                )

                is ParseOutcome.Failure -> current.copy(hasHeaderRow = value, error = parsed.message)
            }
        }
    }

    fun setMapping(columnIndex: Int, field: ImportField?) {
        val preview = _state.value.preview ?: return
        val next = preview.mapping.toMutableList()
        if (columnIndex !in next.indices) return

        // 一个字段只能被一列占用，否则后面的列会静默覆盖前面的，用户看不出为什么值不对
        if (field != null) {
            next.indices.forEach { index ->
                if (index != columnIndex && next[index] == field) next[index] = null
            }
        }
        next[columnIndex] = field

        _state.update { it.copy(preview = preview.withMapping(next), error = null) }
    }

    fun startImport() {
        val preview = _state.value.preview ?: return
        if (!preview.headwordMapped) {
            _state.update { it.copy(error = "必须指定哪一列是单词，否则无法导入。") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                val deckName = _state.value.deckName.trim().ifEmpty { null }
                importer.import(preview.rows(), defaultDeckName = deckName)
            }.onSuccess { result ->
                _state.update {
                    it.copy(busy = false, result = result, step = ImportStep.DONE, error = null)
                }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "导入失败") }
            }
        }
    }

    /** 回到第一步重选文件。 */
    fun reset() {
        rawText = null
        _state.value = ImportUiState()
    }

    private fun String.toDeckName(): String =
        substringBeforeLast('.').trim().take(60).ifBlank { "导入的词库" }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ImportViewModel(
                    fileReader = container.fileReader,
                    importer = container.importer,
                    deckRepository = container.decks
                )
            }
        }
    }
}
