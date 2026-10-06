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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.ccatq.xdylupdater2.BuildConfig
import com.ccatq.xdylupdater2.core.*

@Composable fun ProfileScreen(model: AppViewModel, nav: NavHostController) {
    val profile by model.profile.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var taps by rememberSaveable { mutableIntStateOf(0) }
    var unbind by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    val avatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) model.action {
            val selected = pickFile(context, uri)
            try { model.api.upload("/user/avatar", selected.file, selected.name, "avatar"); model.refreshProfile() } finally { selected.file.delete() }
        }
    }
    Page {
        Section(profile?.nickname ?: "用户") {
            profile?.avatar?.let { AsyncImage(it, "我的头像", modifier = Modifier.size(64.dp)) }
            Text("@${profile?.username ?: model.tokens.value?.username.orEmpty()}")
            Text("喵币：${profile?.balance ?: "0"}")
            OutlinedButton({ model.action { model.refreshProfile(); model.refreshUnread() } }, enabled = !busy) { Text("刷新资料") }
        }
        Section("账户") {
            TextButton({ avatar.launch("image/*") }, enabled = !busy) { Text("更换头像") }
            TextButton({ nav.navigate("form/profile") }) { Text("编辑资料") }
            TextButton({ nav.navigate("form/password") }) { Text("修改密码") }
            if (profile?.qq == null) TextButton({ nav.navigate("qq") }) { Text("绑定 QQ") }
            else TextButton({ unbind = true }, enabled = !busy) { Text("解绑 QQ · ${profile?.qq}") }
        }
        Section("应用") {
            TextButton({ nav.navigate("feature/notifications") }) { Text("通知") }
            TextButton({ nav.navigate("updates") }) { Text("检查应用更新") }
            TextButton({ nav.navigate("settings") }) { Text("设置") }
            if (profile?.administrator == true) TextButton({ model.action { openLink(context, "${Environment.api}/webadmin") } }) { Text("管理后台") }
            TextButton({ if (!settings.developer) { taps++; if (taps >= 7) { model.setPreference("developer", true); taps = 0 } } }) { Text("版本 ${BuildConfig.VERSION_NAME}") }
            if (settings.developer) Text("开发者功能已启用")
        }
        OutlinedButton({ logout = true }, enabled = !busy) { Text("退出登录") }
    }
    if (unbind) Confirm("解绑 QQ？", "将解除当前社区账号与 QQ 的绑定。", { unbind = false }, {
        unbind = false; model.action { model.api.request("/user/unbind-qq", "POST", obj()); model.refreshProfile() }
    })
    if (logout) Confirm("退出登录？", "会清除本机保存的登录会话，下载文件仍保留。", { logout = false }, { logout = false; model.logout() })
}

@Composable fun SettingsScreen(model: AppViewModel, nav: NavHostController) {
    val settings by model.settings.collectAsStateWithLifecycle()
    Page {
        Section("下载") {
            SettingSwitch("允许蜂窝网络下载", settings.cellular) { model.setPreference("cellular", it) }
            Text("设置用于之后创建的任务。已入队任务保留原网络设置；重试时采用当前设置。")
        }
        Section("更新") {
            SettingSwitch("显示应用更新入口", settings.updates) { model.setPreference("updates", it) }
            TextButton({ nav.navigate("updates") }) { Text("检查 GitHub 正式版本") }
        }
        Section("诊断") {
            TextButton({ nav.navigate("logs") }) { Text("网络日志") }
            Text("普通日志仅记录脱敏地址、时间、方法、状态码和耗时。")
        }
        if (settings.developer) Section("开发者") {
            TextButton({ nav.navigate("developer") }) { Text("开发者工具") }
            TextButton({ model.setPreference("continuous", false); model.setPreference("developer", false) }) { Text("关闭开发者功能") }
        }
        Section("网络") { Text("账号与社区使用 HTTPS；旧资源服务使用官方 HTTP 端口。") }
    }
}
@Composable fun SettingSwitch(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, modifier = Modifier.weight(1f).padding(top = 12.dp, end = 12.dp))
        Switch(value, onChange)
    }
}
@Composable fun LogsScreen(model: AppViewModel) {
    val logs by model.graph.developer.logs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Page {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ model.action { share(context, model.graph.developer.exportLogs(), "text/plain") } }, enabled = logs.isNotEmpty()) { Text("分享日志") }
            TextButton({ model.action { model.graph.developer.clear("logs") } }) { Text("清除") }
        }
        if (logs.isEmpty()) Text("尚无网络请求记录")
        logs.asReversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
