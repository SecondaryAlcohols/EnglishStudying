package com.alcohol.englishstudying.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.prefs.SettingsRepository
import com.alcohol.englishstudying.di.AppContainer
import com.alcohol.englishstudying.domain.model.CardField
import com.alcohol.englishstudying.domain.model.CardFieldConfig
import com.alcohol.englishstudying.domain.model.CardFieldConfigDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 卡片字段配置。
 *
 * 本地持有一份可变副本用于即时响应拖拽排序，同时写回 DataStore。
 * 直接对 DataStore 做「上移一位」需要先读再写，快速连点会丢更新。
 */
class CardFieldViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _fields = MutableStateFlow(CardFieldConfigDefaults.default())
    val fields: StateFlow<List<CardFieldConfig>> = _fields.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.cardFields.collect { _fields.value = it }
        }
    }

    fun moveUp(index: Int) = move(index, index - 1)

    fun moveDown(index: Int) = move(index, index + 1)

    private fun move(from: Int, to: Int) {
        val current = _fields.value
        if (from !in current.indices || to !in current.indices) return
        val next = current.toMutableList()
        next.add(to, next.removeAt(from))
        persist(next)
    }

    fun toggleEnabled(field: CardField) = update(field) { it.copy(enabled = !it.enabled) }

    fun toggleExpanded(field: CardField) = update(field) { it.copy(expanded = !it.expanded) }

    fun restoreDefaults() = persist(CardFieldConfigDefaults.default())

    private fun update(field: CardField, transform: (CardFieldConfig) -> CardFieldConfig) {
        persist(_fields.value.map { if (it.field == field) transform(it) else it })
    }

    private fun persist(next: List<CardFieldConfig>) {
        _fields.value = next
        viewModelScope.launch { settingsRepository.setCardFields(next) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { CardFieldViewModel(container.settings) }
        }
    }
}
