package com.ccatq.xdylupdater2.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.SubcomposeAsyncImage
import com.ccatq.xdylupdater2.core.*
import kotlinx.coroutines.*
import java.io.File

class LoadState<T> {
    var data by mutableStateOf<T?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var refresh: () -> Unit = {}
    suspend fun fetch(block: suspend () -> T) {
        loading = true; error = null
        try { data = block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "加载失败" }
        finally { loading = false }
    }
}
@Composable fun <T> rememberLoad(key: Any, fetch: suspend () -> T): LoadState<T> {
    val state = remember(key) { LoadState<T>() }
    val scope = rememberCoroutineScope()
    val block by rememberUpdatedState(fetch)
    state.refresh = { if (!state.loading) scope.launch { state.fetch(block) } }
    LaunchedEffect(key) { state.fetch(block) }
    return state
}
@Composable fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
@Composable fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
@Composable fun <T> LoadStatus(state: LoadState<T>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.loading) CircularProgressIndicator(Modifier.size(24.dp))
        OutlinedButton(state.refresh, enabled = !state.loading) { Text("刷新") }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
@Composable fun ItemSummary(item: RemoteItem, onClick: (() -> Unit)? = null) {
    if (onClick != null) OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { ItemText(item) }
    } else ItemText(item)
}
@Composable private fun ItemText(item: RemoteItem) {
    Text(item.title, style = MaterialTheme.typography.titleMedium)
    if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall)
}
@Composable fun RemoteContent(item: RemoteItem) {
    Text(item.title, style = MaterialTheme.typography.headlineSmall)
    if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall)
    if (item.content.isNotBlank()) Text(android.text.Html.fromHtml(item.content.replace(Regex("!\\[[^]]*]\\([^)]+\\)"), ""), android.text.Html.FROM_HTML_MODE_COMPACT).toString())
    ContentURLs.images(item.raw).forEach { url ->
        SubcomposeAsyncImage(model = url, contentDescription = "内容图片", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), loading = { Text("正在加载图片…") }, error = { Text("图片无法加载") })
    }
}
@Composable fun StyledTitle(value: String) {
    val default = MaterialTheme.colorScheme.onSurface
    val styled = remember(value, default) {
        buildAnnotatedString {
            var start = 0; var color = default
            for (match in TitleColors.pattern.findAll(value)) {
                pushStyle(SpanStyle(color)); append(value.substring(start, match.range.first)); pop()
                color = Color(android.graphics.Color.parseColor("#${match.groupValues[1]}")); start = match.range.last + 1
            }
            pushStyle(SpanStyle(color)); append(value.substring(start)); pop()
        }
    }
    Text(styled, style = MaterialTheme.typography.titleLarge)
}
@Composable fun Confirm(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit, enabled: Boolean = true, content: @Composable (() -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(message); content?.invoke() } }, confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text("确认") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
fun openLink(context: Context, url: String) {
    val uri = Uri.parse(url)
    require(uri.scheme in setOf("https", "http")) { "链接无效" }
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
fun share(context: Context, file: File, mime: String = "application/octet-stream") {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).apply {
        clipData = ClipData.newRawUri("星灯云浪文件", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
data class PickedFile(val file: File, val name: String)
suspend fun pickFile(context: Context, uri: Uri): PickedFile = withContext(Dispatchers.IO) {
    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "upload.bin"
    val file = File.createTempFile("upload-", ".tmp", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: error("无法读取所选文件")
        PickedFile(file, name)
    } catch (e: Exception) { file.delete(); throw e }
}
