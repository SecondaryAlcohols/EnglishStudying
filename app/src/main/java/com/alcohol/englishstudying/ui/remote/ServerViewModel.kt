package com.alcohol.englishstudying.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alcohol.englishstudying.data.prefs.SettingsRepository
import com.alcohol.englishstudying.data.remote.HealthDto
import com.alcohol.englishstudying.data.remote.RemoteRepository
import com.alcohol.englishstudying.data.remote.ServerException
import com.alcohol.englishstudying.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerUiState(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val health: HealthDto? = null,
    val loggedIn: Boolean = false,
    val loggedInUser: String = "",
    val message: String? = null,
    val error: String? = null
)

class ServerViewModel(
    private val remote: RemoteRepository,
    settings: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ServerUiState())
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val savedUrl = settings.serverUrl.first()
            if (savedUrl.isNotBlank()) _state.update { it.copy(url = savedUrl) }

            remote.session.collect { session ->
                _state.update {
                    it.copy(
                        loggedIn = session.loggedIn,
                        loggedInUser = session.userName,
                        // 只在用户还没输入时回填已保存的地址，别把正在编辑的内容冲掉
                        url = if (it.url.isBlank() && session.url.isNotBlank()) session.url else it.url
                    )
                }
            }
        }
    }

    fun setUrl(value: String) = _state.update { it.copy(url = value, error = null, message = null) }

    fun setUsername(value: String) = _state.update { it.copy(username = value) }

    fun setPassword(value: String) = _state.update { it.copy(password = value) }

    fun testConnection() {
        val url = _state.value.url
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            runCatching { remote.testConnection(url) }
                .onSuccess { health ->
                    _state.update {
                        it.copy(
                            busy = false,
                            health = health,
                            message = "连接成功：${health.deckCount} 张词表 / ${health.wordCount} 个词"
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, health = null, error = error.friendly()) }
                }
        }
    }

    fun login() {
        val current = _state.value
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            runCatching { remote.login(current.url, current.username, current.password) }
                .onSuccess { user ->
                    _state.update {
                        it.copy(
                            busy = false,
                            password = "",
                            message = "已登录：${user.username}",
                            loggedIn = true,
                            loggedInUser = user.username
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, error = error.friendly()) }
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            remote.logout()
            _state.update {
                it.copy(loggedIn = false, loggedInUser = "", message = "已退出登录", error = null)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ServerViewModel(remote = container.remote, settings = container.settings)
            }
        }
    }
}

/** 网络异常翻成人话。Ktor 的异常类名不稳定，按类型 + 关键字兜底。 */
internal fun Throwable.friendly(): String {
    if (this is ServerException) return message ?: "服务器返回错误"
    val name = this::class.java.simpleName
    return when {
        name.contains("UnknownHost") -> "找不到这台服务器，检查 IP 是否写对"
        name.contains("ConnectTimeout") || name.contains("Timeout") -> "连接超时，确认手机和电脑在同一个 Wi-Fi"
        name.contains("Connect") -> "连不上服务器，检查端口和防火墙是否放行"
        else -> message ?: "网络异常，请重试"
    }
}
