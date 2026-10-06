package com.ccatq.xdylupdater2.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ccatq.xdylupdater2.AppGraph
import com.ccatq.xdylupdater2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AppViewModel(val graph: AppGraph) : ViewModel() {
    val api get() = graph.api
    val tokens = graph.session.tokens
    val settings = graph.preferences.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val downloads = graph.downloads.records.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutableProfile = MutableStateFlow<Profile?>(null)
    val profile = mutableProfile.asStateFlow()
    private val mutableUnread = MutableStateFlow(0)
    val unread = mutableUnread.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    init {
        viewModelScope.launch {
            tokens.collectLatest { token ->
                if (token == null) { mutableProfile.value = null; mutableUnread.value = 0 }
                else {
                    try { refreshProfile(); refreshUnread() } catch (e: CancellationException) { throw e } catch (e: Exception) { error.value = e.message ?: "无法读取账号资料" }
                }
            }
        }
    }
    suspend fun refreshProfile() { mutableProfile.value = Profile.parse(api.request("/user/profile")) }
    suspend fun refreshUnread() {
        try { val j = api.request("/notifications/unread").payload(); mutableUnread.value = j.int("count", "unread") ?: j.text()?.toIntOrNull() ?: 0 }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* Badge failure must not hide notifications. */ }
    }
    suspend fun markRead() { api.request("/notifications/read", "POST", obj()); mutableUnread.value = 0 }
    fun logout() = graph.session.clear()
    fun setPreference(key: String, value: Boolean) { viewModelScope.launch { graph.preferences.set(key, value) } }
    fun action(work: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { work() } catch (e: CancellationException) { throw e } catch (e: Exception) { error.value = e.message ?: "操作失败，请重试" }
            finally { busy.value = false }
        }
    }
    suspend fun login(account: String, password: String) {
        val tokens = AuthTokens.parse(api.request("/login", "POST", obj("account" to account, "password" to password), authenticated = false)) ?: error("登录响应缺少令牌")
        withContext(Dispatchers.IO) { graph.session.save(tokens) }
    }
    companion object {
        fun factory(graph: AppGraph): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph) as T
        }
    }
}
