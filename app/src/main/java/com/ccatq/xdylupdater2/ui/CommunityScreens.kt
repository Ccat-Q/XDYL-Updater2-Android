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
import androidx.navigation.NavHostController
import com.ccatq.xdylupdater2.core.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Composable fun CommunityScreen(model: AppViewModel, nav: NavHostController) {
    var category by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(1) }
    val state = rememberLoad("community-$category-$page") {
        coroutineScope {
            val categories = async { RemoteItem.list(model.api.request("/forum/categories")) }
            val query = mutableMapOf("page" to page.toString()).apply { if (category.isNotBlank()) put("category_id", category) }
            val posts = async { RemoteItem.list(model.api.request("/forum/posts", query = query)) }
            categories.await() to posts.await()
        }
    }
    Page {
        LoadStatus(state)
        Button({ nav.navigate("form/post") }) { Text("发布帖子") }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(category.isBlank(), { category = ""; page = 1 }, label = { Text("全部") })
            state.data?.first.orEmpty().forEach { item -> FilterChip(category == item.id, { category = item.id; page = 1 }, label = { Text(item.title) }) }
        }
        val posts = state.data?.second.orEmpty()
        if (posts.isEmpty() && !state.loading) Text("暂无帖子")
        posts.forEach { item -> ItemSummary(item) { nav.navigate("post/${pathPart(item.id)}") } }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ page-- }, enabled = page > 1 && !state.loading) { Text("上一页") }
            Text("第 $page 页", modifier = Modifier.padding(top = 14.dp))
            OutlinedButton({ page++ }, enabled = posts.isNotEmpty() && !state.loading) { Text("下一页") }
        }
    }
}
@Composable private fun ImageAttachment(model: AppViewModel, onUploaded: (String) -> Unit) {
    val context = LocalContext.current
    val busy by model.busy.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) model.action {
            val file = pickFile(context, uri)
            try {
                val result = model.api.upload("/upload/image", file.file, file.name)
                onUploaded(ContentURLs.uploaded(result) ?: error("上传响应缺少图片地址"))
            } finally { file.file.delete() }
        }
    }
    OutlinedButton({ picker.launch("image/*") }, enabled = !busy) { Text("添加图片") }
}
@Composable fun ComposePostScreen(model: AppViewModel, onDone: () -> Unit) {
    val categories = rememberLoad("post-categories") { RemoteItem.list(model.api.request("/forum/categories")) }
    var category by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    val busy by model.busy.collectAsStateWithLifecycle()
    LaunchedEffect(categories.data) { if (category.isBlank()) category = categories.data?.firstOrNull()?.id.orEmpty() }
    Page {
        LoadStatus(categories)
        Text("选择分类")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.data.orEmpty().forEach { item -> FilterChip(category == item.id, { category = item.id }, label = { Text(item.title) }) }
        }
        OutlinedTextField(title, { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(content, { content = it }, label = { Text("正文") }, minLines = 6, modifier = Modifier.fillMaxWidth())
        ImageAttachment(model) { url -> content += "\n![图片]($url)\n" }
        Button({ model.action {
            model.api.request("/forum/post", "POST", obj("category_id" to category, "title" to title.trim(), "content" to content.trim())); onDone()
        } }, enabled = title.isNotBlank() && content.isNotBlank() && category.isNotBlank() && !busy) { Text("发布") }
    }
}
@Composable fun PostScreen(model: AppViewModel, id: String) {
    val path = "/forum/post/${pathPart(id)}"
    val state = rememberLoad("post-$id") { ForumDetail.parse(model.api.request(path)) }
    var reply by rememberSaveable(id) { mutableStateOf("") }
    var tip by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val busy by model.busy.collectAsStateWithLifecycle()
    Page {
        LoadStatus(state)
        state.data?.let { detail ->
            Section("正文") { RemoteContent(detail.post) }
            Section("回复") {
                if (detail.replies.isEmpty()) Text("暂无回复")
                detail.replies.forEach { item -> RemoteContent(item); HorizontalDivider() }
            }
            OutlinedTextField(reply, { reply = it }, label = { Text("写下回复…") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            ImageAttachment(model) { reply += "\n![图片]($it)\n" }
            Button({ model.action { model.api.request("$path/reply", "POST", obj("content" to reply.trim(), "reply_to" to null)); reply = ""; state.refresh() } }, enabled = reply.isNotBlank() && !busy) { Text("发送回复") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ model.action {
                    val response = model.api.request("$path/like", "POST", obj()).payload()
                    message = if (response.str("liked", "is_liked") == "false") "已取消点赞" else "点赞成功"
                    state.refresh()
                } }, enabled = !busy) { Text("点赞 · ${detail.post.raw.str("likes") ?: "0"}") }
                OutlinedButton({ amount = ""; tip = true }, enabled = !busy) { Text("打赏") }
            }
            if (message.isNotBlank()) Text(message)
        }
    }
    if (tip) Confirm("确认打赏", "输入正整数喵币数量；服务端成功后刷新余额。", { tip = false }, {
        val value = amount.toLongOrNull() ?: return@Confirm
        tip = false
        model.action { model.api.request("$path/tip", "POST", obj("amount" to value)); model.refreshProfile(); state.refresh(); message = "打赏成功" }
    }, enabled = (amount.toLongOrNull() ?: 0) > 0 && !busy, content = { OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("喵币数量") }) })
}
