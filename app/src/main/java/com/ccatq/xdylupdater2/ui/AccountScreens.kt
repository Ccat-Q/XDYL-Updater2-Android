package com.ccatq.xdylupdater2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ccatq.xdylupdater2.core.*
import kotlinx.coroutines.*
import java.util.UUID

@Composable fun AuthScreen(model: AppViewModel) {
    var screen by rememberSaveable { mutableStateOf("login") }
    var account by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busy by model.busy.collectAsStateWithLifecycle()
    Surface(Modifier.safeDrawingPadding()) {
        when (screen) {
            "register", "reset" -> Column { TextButton({ screen = "login" }) { Text("返回登录") }; FormScreen(model, screen) { screen = "login" } }
            "qq" -> Column { TextButton({ screen = "login" }) { Text("取消授权") }; QQScreen(model, false) { screen = "login" } }
            else -> Page {
                Spacer(Modifier.height(32.dp))
                Text("星灯云浪", style = MaterialTheme.typography.headlineLarge)
                Text("社区与游戏资源，随时连接")
                OutlinedTextField(account, { account = it }, label = { Text("邮箱或用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Button({ model.action { model.login(account.trim(), password); password = "" } }, enabled = account.isNotBlank() && password.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "登录中…" else "登录") }
                OutlinedButton({ screen = "qq" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("使用 QQ 登录") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton({ screen = "register" }) { Text("注册账号") }; TextButton({ screen = "reset" }) { Text("忘记密码") } }
            }
        }
    }
}

fun formTitle(kind: String) = when (kind) { "register" -> "注册账号"; "reset" -> "重置密码"; "profile" -> "编辑资料"; "password" -> "修改密码"; "suggestion" -> "提交意见"; "post" -> "发布帖子"; else -> "编辑" }
@Composable fun FormScreen(model: AppViewModel, kind: String, onDone: () -> Unit) {
    val profile by model.profile.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    var first by rememberSaveable(kind) { mutableStateOf(if (kind == "profile") profile?.nickname.orEmpty() else "") }
    var email by rememberSaveable(kind) { mutableStateOf(if (kind == "profile") profile?.email.orEmpty() else "") }
    var code by rememberSaveable(kind) { mutableStateOf("") }
    var password by remember(kind) { mutableStateOf("") }
    var oldPassword by remember(kind) { mutableStateOf("") }
    var content by rememberSaveable(kind) { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    if (kind == "post") { ComposePostScreen(model, onDone); return }
    Page {
        Text(formTitle(kind), style = MaterialTheme.typography.titleLarge)
        if (kind in setOf("register", "profile")) OutlinedTextField(first, { first = it }, label = { Text(if (kind == "register") "用户名" else "昵称") }, modifier = Modifier.fillMaxWidth())
        if (kind in setOf("register", "reset", "profile")) OutlinedTextField(email, { email = it }, label = { Text("邮箱") }, modifier = Modifier.fillMaxWidth())
        if (kind in setOf("register", "reset")) {
            OutlinedTextField(code, { code = it }, label = { Text("验证码") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton({ model.action { model.api.request("/send-verify-code", "POST", obj("email" to email.trim(), "purpose" to if (kind == "register") "register" else "reset"), authenticated = false); message = "验证码已发送" } }, enabled = email.isNotBlank() && !busy) { Text("发送验证码") }
        }
        if (kind == "password") OutlinedTextField(oldPassword, { oldPassword = it }, label = { Text("当前密码") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (kind in setOf("register", "reset", "password")) OutlinedTextField(password, { password = it }, label = { Text("新密码（至少 6 位）") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (kind == "suggestion") OutlinedTextField(content, { content = it }, label = { Text("意见内容") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        if (message.isNotEmpty()) Text(message)
        val valid = when (kind) {
            "register" -> first.isNotBlank() && email.isNotBlank() && code.isNotBlank() && password.length >= 6
            "reset" -> email.isNotBlank() && code.isNotBlank() && password.length >= 6
            "password" -> oldPassword.isNotBlank() && password.length >= 6
            "profile" -> first.isNotBlank() && email.isNotBlank()
            "suggestion" -> content.isNotBlank()
            else -> false
        }
        Button({ model.action {
            when (kind) {
                "register" -> model.api.request("/register", "POST", obj("username" to first.trim(), "email" to email.trim(), "password" to password, "code" to code.trim()), authenticated = false)
                "reset" -> model.api.request("/reset-password", "POST", obj("email" to email.trim(), "password" to password, "code" to code.trim()), authenticated = false)
                "profile" -> { model.api.request("/user/profile", "POST", obj("nickname" to first.trim(), "email" to email.trim())); model.refreshProfile() }
                "password" -> model.api.request("/user/password", "POST", obj("old_password" to oldPassword, "new_password" to password))
                "suggestion" -> model.api.request("/suggestions", "POST", obj("content" to content.trim()))
            }
            password = ""; oldPassword = ""; onDone()
        } }, enabled = valid && !busy) { Text(if (busy) "提交中…" else "提交") }
    }
}

@Composable fun QQScreen(model: AppViewModel, bind: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var session by rememberSaveable { mutableStateOf<String?>(null) }
    var expires by rememberSaveable { mutableLongStateOf(0L) }
    var waiting by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val busy by model.busy.collectAsStateWithLifecycle()
    val currentOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(session, waiting) {
        val id = session ?: return@LaunchedEffect
        if (!waiting) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (waiting && currentCoroutineContext().isActive) {
                if (System.currentTimeMillis() >= expires) { waiting = false; message = "授权已超时，请重试"; break }
                try {
                    val result = model.api.request("/check-qq-login", query = mapOf("session_id" to id), authenticated = false)
                    if (bind) {
                        val source = result.payload()
                        if (result.str("status") == "success" || source.field("qq_token") != null) {
                            model.api.request("/user/bind-qq", "POST", obj("session_id" to id, "qq_token" to source.field("qq_token")))
                            model.refreshProfile(); waiting = false; currentOnDone(); break
                        }
                    } else AuthTokens.parse(result)?.let { tokens ->
                        withContext(Dispatchers.IO) { model.graph.session.save(tokens) }; waiting = false; currentOnDone()
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { waiting = false; message = e.message ?: "授权失败" }
                if (waiting) delay(2_000)
            }
        }
    }
    Page {
        Text(if (bind) "绑定 QQ" else "QQ 登录", style = MaterialTheme.typography.titleLarge)
        Text("在浏览器完成 QQ 授权后返回应用，登录状态会自动刷新。")
        if (waiting) CircularProgressIndicator()
        if (message.isNotBlank()) Text(message)
        Button({ scope.launch {
            starting = true
            try {
            val id = UUID.randomUUID().toString()
            val query = mutableMapOf("session_id" to id).apply { if (bind) put("mode", "bind") }
            val value = model.api.request("/qq-login", query = query, authenticated = false)
            val url = value.payload().str("login_url") ?: error("授权地址缺失")
            val uri = java.net.URI(url)
            require(uri.scheme == "https" && uri.host == "graph.qq.com" && uri.userInfo == null) { "QQ 授权地址无效" }
            currentCoroutineContext().ensureActive()
            session = id; expires = System.currentTimeMillis() + 90_000; waiting = true; message = ""; openLink(context, url)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "无法启动授权" }
            finally { starting = false }
        } }, enabled = !waiting && !starting && !busy) { Text(if (starting) "请求授权中…" else "打开 QQ 授权页面") }
        if (waiting) TextButton({ waiting = false; message = "已取消等待" }) { Text("取消等待") }
    }
}
