package com.alcohol.englishstudying.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.local.UserTagWithCount
import com.alcohol.englishstudying.data.repo.UserTagRepository
import com.alcohol.englishstudying.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TagUiState(
    val tags: List<UserTagWithCount> = emptyList(),
    val loading: Boolean = true
)

class TagViewModel(
    private val repository: UserTagRepository
) : ViewModel() {

    /** 操作结果提示（重名、空名之类）；显示完由 UI 调 [clearMessage] 清掉。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val state: StateFlow<TagUiState> = repository.observeAll()
        .map { TagUiState(tags = it, loading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TagUiState())

    fun create(name: String) {
        viewModelScope.launch {
            if (!repository.create(name)) {
                _message.value = "标签为空或已经存在"
            }
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            if (!repository.rename(id, name)) {
                _message.value = "名字为空，或者已经有同名标签"
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { TagViewModel(container.userTags) }
        }
    }
}
