package com.ccatq.xdylupdater2.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ccatq.xdylupdater2.core.*
import com.ccatq.xdylupdater2.downloads.DownloadState
import kotlinx.coroutines.*

@Composable fun DownloadsScreen(model: AppViewModel) {
    val state = rememberLoad("resource-manifest") { ResourceFile.list(model.api.request("/mods/mods.json", authenticated = false, base = "http://api.lanternwaves.fun:5551")) }
    val records by model.downloads.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = exportId; exportId = null
        if (uri != null && id != null) model.action {
            val file = model.graph.downloads.shareable(id)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("无法写入导出位置")
            }
        }
    }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                try { model.graph.downloads.reconcile() } catch (e: CancellationException) { throw e } catch (e: Exception) { model.error.value = e.message; break }
                delay(1_500)
            }
        }
    }
    Page {
        LoadStatus(state)
        Section("资源清单") {
            if (state.data.isNullOrEmpty() && !state.loading) Text("暂无资源")
            state.data.orEmpty().forEach { file ->
                Text(file.name, style = MaterialTheme.typography.titleMedium)
                Text("${file.kind} · ${file.size?.let { "${it / 1024} KB" } ?: "大小未知"}")
                Button({ model.action { model.graph.downloads.enqueue(file.url ?: error("下载地址缺失"), file.name, file.sha256, settings.cellular) } }, enabled = !busy && file.url?.let(NetworkPolicy::download) == true) { Text("下载") }
                HorizontalDivider()
            }
        }
        Section("下载记录") {
            if (records.isEmpty()) Text("暂无下载记录")
            records.forEach { record ->
                Text(record.filename, style = MaterialTheme.typography.titleMedium)
                Text(DownloadState.entries.find { it.name == record.state }?.label ?: record.state)
                record.message?.let { Text(it) }
                if (record.state == DownloadState.downloading.name) {
                    if (record.total > 0) LinearProgressIndicator(progress = { (record.downloaded.toFloat() / record.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${record.downloaded / 1024} KB / ${if (record.total > 0) "${record.total / 1024} KB" else "未知"}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (record.state == DownloadState.completed.name) {
                        TextButton({ model.action { share(context, model.graph.downloads.shareable(record.id)) } }, enabled = !busy) { Text("分享") }
                        TextButton({ exportId = record.id; export.launch(record.filename) }, enabled = !busy) { Text("导出") }
                    }
                    if (record.state == DownloadState.failed.name) TextButton({ model.action { model.graph.downloads.retry(record, settings.cellular) } }, enabled = !busy) { Text("重试") }
                    TextButton({ deleteId = record.id }, enabled = !busy) { Text("删除") }
                }
                HorizontalDivider()
            }
        }
        Text("文件保存在应用专属空间，卸载时会移除；需要长期保存时请导出。")
    }
    deleteId?.let { id -> Confirm("删除下载记录？", "会取消未完成任务，并删除应用保存的文件；已导出的文件不受影响。", { deleteId = null }, {
        deleteId = null; model.action { model.graph.downloads.delete(id) }
    }) }
}
