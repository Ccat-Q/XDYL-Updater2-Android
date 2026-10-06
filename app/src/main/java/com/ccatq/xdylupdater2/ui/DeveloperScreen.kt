package com.ccatq.xdylupdater2.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ccatq.xdylupdater2.core.*
import com.ccatq.xdylupdater2.developer.*
import kotlinx.serialization.encodeToString

@Composable fun DeveloperScreen(model: AppViewModel) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val sessions by model.graph.developer.sessions.collectAsStateWithLifecycle()
    val environments by model.graph.developer.environments.collectAsStateWithLifecycle()
    val samples by model.graph.developer.performance.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf("目录") }
    var draft by remember { mutableStateOf(DeveloperDraft()) }
    var search by rememberSaveable { mutableStateOf("") }
    var environmentName by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<PickedFile?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var deleteWord by remember { mutableStateOf("") }
    var selectedSession by remember { mutableStateOf<DeveloperSession?>(null) }
    var rawPreview by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.action { file?.file?.delete(); file = pickFile(context, uri) }
    }
    DisposableEffect(tab, settings.developer) {
        model.graph.performanceVisible.value = settings.developer && tab == "性能"
        onDispose { model.graph.performanceVisible.value = false }
    }
    DisposableEffect(file) { val selected = file; onDispose { selected?.file?.delete() } }
    if (!settings.developer) { Page { Text("开发者功能已关闭") }; return }
    fun execute() {
        val snapshot = draft; val attachment = file
        model.action {
            selectedSession = model.graph.developer.execute(snapshot, attachment?.file, attachment?.name ?: "upload.bin")
            rawPreview = false; tab = "会话"
        }
    }
    Page {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("目录", "请求", "会话", "性能", "本地数据").forEach { name -> FilterChip(tab == name, { tab = name }, label = { Text(name) }) }
        }
        when (tab) {
            "目录" -> {
                OutlinedTextField(search, { search = it }, label = { Text("搜索接口") }, modifier = Modifier.fillMaxWidth())
                knownRoutes.filter { search.isBlank() || "${it.group} ${it.title} ${it.path}".contains(search, true) }.forEach { route ->
                    Section("${route.group} · ${route.title}") {
                        Text("${route.method} ${route.path}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({
                                file = null; draft = DeveloperDraft(base = route.base, path = route.path, query = route.query, method = route.method)
                                tab = "请求"
                            }) { Text("编辑请求") }
                            if (route.method == "GET" && !route.path.contains('{') && !route.query.endsWith('=')) TextButton({
                                val request = DeveloperDraft(base = route.base, path = route.path, query = route.query, authenticated = route.authenticated)
                                model.action { selectedSession = model.graph.developer.execute(request); rawPreview = false; tab = "会话" }
                            }, enabled = !busy) { Text("请求 GET") }
                        }
                    }
                }
            }
            "请求" -> {
                Text("自定义 HTTP/HTTPS 请求与普通业务网络分开。令牌只在明确开启时发送，重定向不会自动跟随。")
                environments.forEach { env ->
                    Row { TextButton({ draft = draft.copy(base = env.baseURL, authenticated = false) }) { Text(env.name) }; TextButton({ model.action { model.graph.developer.deleteEnvironment(env.name) } }) { Text("移除") } }
                }
                OutlinedTextField(draft.base, { draft = draft.copy(base = it, authenticated = false) }, label = { Text("服务地址") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.path, { draft = draft.copy(path = it) }, label = { Text("接口路径（替换 {id} 等字段）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.query, { draft = draft.copy(query = it) }, label = { Text("查询参数（key=value&…）") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("GET", "POST", "PUT", "DELETE").forEach { method -> FilterChip(draft.method == method, { draft = draft.copy(method = method) }, label = { Text(method) }) } }
                if (draft.method != "GET") OutlinedTextField(draft.body, { draft = draft.copy(body = it) }, label = { Text("JSON 正文") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                SettingSwitch("携带当前登录令牌", draft.authenticated) { enabled ->
                    if (enabled) confirmation = "token" else draft = draft.copy(authenticated = false)
                }
                if (draft.authenticated) Text("令牌发送目标：${draft.base}", color = MaterialTheme.colorScheme.error)
                if (draft.method != "GET") {
                    OutlinedTextField(draft.uploadField, { draft = draft.copy(uploadField = it) }, label = { Text("上传文件字段") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton({ filePicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text(file?.name ?: "选择上传文件") }
                    if (file != null) TextButton({ file = null }) { Text("移除上传文件") }
                }
                Button({ if (draft.method != "GET") { deleteWord = ""; confirmation = "request" } else execute() }, enabled = !busy && draft.base.isNotBlank() && !draft.path.contains('{') && draft.uploadField.isNotBlank()) { Text(if (busy) "请求中…" else "发送请求") }
                OutlinedTextField(environmentName, { environmentName = it }, label = { Text("环境名称") }, modifier = Modifier.fillMaxWidth())
                TextButton({ model.action { model.graph.developer.saveEnvironment(environmentName, draft.base); environmentName = "" } }, enabled = environmentName.isNotBlank() && !busy) { Text("收藏当前环境") }
            }
            "会话" -> {
                Text("最近 ${sessions.size} 条完整会话；默认展示与导出已脱敏。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ model.action { share(context, model.graph.developer.export(false), "application/json") } }, enabled = !busy) { Text("脱敏导出") }
                    TextButton({ confirmation = "raw-export" }, enabled = !busy) { Text("原文导出") }
                }
                sessions.asReversed().forEach { session -> TextButton({ selectedSession = session; rawPreview = false }) { Text("${session.method} ${Redactor.url(session.url)} → ${session.status ?: "失败"}") } }
                selectedSession?.let { session ->
                    Section("会话详情") {
                        TextButton({ confirmation = "raw-preview" }) { Text(if (rawPreview) "正在显示原文" else "查看原文") }
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(wireJson.encodeToString(if (rawPreview) session else session.redacted()), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            "性能" -> {
                SettingSwitch("应用前台持续采样", settings.continuous) { model.setPreference("continuous", it) }
                Text("每 2 秒采样；离开页面停止，持续采样开启后在应用前台继续。")
                Text("已保留 ${samples.size} 条快照")
                samples.lastOrNull()?.let { sample -> Section("当前状态") {
                    Text("电池：${sample.battery}% · ${if (sample.charging) "充电中" else "未充电"}")
                    Text("省电：${sample.powerSave} · 温控：${sample.thermal ?: "系统不提供"}")
                    Text("可用存储：${sample.freeDisk / 1_048_576} MB")
                    Text("物理内存：${sample.physicalMemory / 1_048_576} MB · CPU 核数：${sample.processors}")
                } }
            }
            "本地数据" -> {
                listOf("sessions" to "清除完整会话", "performance" to "清除性能记录", "environments" to "清除环境收藏", "logs" to "清除普通日志", "downloads" to "清除全部下载", "cache" to "清除图片与上传缓存").forEach { (kind, label) -> OutlinedButton({ confirmation = "clear-$kind" }, enabled = !busy) { Text(label) } }
                Text("清理下载会取消进行中的任务，并删除应用保存的文件；已导出文件不受影响。")
            }
        }
    }
    confirmation?.let { pending ->
        val message = when (pending) {
            "token" -> "将登录令牌发送到 ${draft.base}。仅对本次控制台有效，不会保存开关。"
            "request" -> "${draft.method} ${draft.base}${draft.path}\n写操作可能修改目标服务的数据。"
            "raw-export", "raw-preview" -> "原文可能包含令牌、密码、Cookie 和个人信息。确认继续？"
            else -> "将移除对应本地数据。"
        }
        Confirm("确认操作", message, { confirmation = null }, {
            confirmation = null
            when (pending) {
                "token" -> draft = draft.copy(authenticated = true)
                "request" -> execute()
                "raw-preview" -> rawPreview = true
                "raw-export" -> model.action { share(context, model.graph.developer.export(true), "application/json") }
                "clear-downloads" -> model.action { model.graph.downloads.clear() }
                "clear-cache" -> model.action {
                    file = null
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.cacheDir.listFiles()?.filter { it.name != "exports" }?.forEach { it.deleteRecursively() }
                        coil.Coil.imageLoader(context).memoryCache?.clear(); coil.Coil.imageLoader(context).diskCache?.clear()
                    }
                }
                else -> model.action { model.graph.developer.clear(pending.removePrefix("clear-")); if (pending == "clear-sessions") selectedSession = null }
            }
        }, enabled = !busy && (pending != "request" || draft.method != "DELETE" || deleteWord == "DELETE"), content = {
            if (pending == "request" && draft.method == "DELETE") OutlinedTextField(deleteWord, { deleteWord = it }, label = { Text("输入 DELETE") })
        })
    }
}
