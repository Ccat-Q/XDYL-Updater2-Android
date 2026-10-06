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
import com.ccatq.xdylupdater2.core.*
import kotlinx.serialization.json.*

data class Feature(val key: String, val title: String, val path: String, val service: Boolean = true)
val features = listOf(
    Feature("notifications", "通知", "/notifications"), Feature("tasks", "任务", "/tasks"), Feature("duel", "决斗场", "/pvp/me"),
    Feature("shop", "商城", "/shop/items"), Feature("polls", "投票", "/polls"), Feature("suggestions", "意见箱", "/suggestions"),
    Feature("seasons", "周目", "/seasons"), Feature("items", "我的物品", "/user/items"), Feature("skins", "YSM 皮肤", "/user/items"),
    Feature("players", "在线玩家", "/server/players"), Feature("coins", "喵币排行", "/rank/coins"), Feature("playtime", "在线排行", "/rank/playtime"),
    Feature("rewards", "游戏奖励", "/playtime/rewards"), Feature("catalog", "称号目录", "/titles/catalog"), Feature("titles", "我的称号", "/titles/mine"),
    Feature("memorials", "纪念堂", "/memorials"), Feature("announcements", "公告", "/announcements", false)
)
@Composable fun ServicesScreen(nav: NavHostController) {
    Page { features.filter { it.service }.forEach { feature ->
        OutlinedButton({ nav.navigate(if (feature.key == "duel") "duel" else "feature/${feature.key}") }, modifier = Modifier.fillMaxWidth()) { Text(feature.title) }
    } }
}
@Composable fun HomeScreen(model: AppViewModel, nav: NavHostController) {
    val profile by model.profile.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val state = rememberLoad("home") {
        val announcements = model.api.request("/announcements")
        val players = model.api.request("/server/players")
        model.refreshUnread()
        announcements to players
    }
    Page {
        Text("你好，${profile?.nickname ?: "玩家"}", style = MaterialTheme.typography.headlineSmall)
        Text("浏览社区、获取资源，与游戏服务保持连接")
        LoadStatus(state)
        Section("服务器状态") {
            val players = state.data?.second?.let(RemoteItem::list).orEmpty()
            if (players.isEmpty()) Text("暂无状态数据")
            players.forEach { item -> ItemSummary(item) { nav.navigate("detail/players/${pathPart(item.id)}") } }
        }
        Section("公告") {
            val rows = state.data?.first?.let(RemoteItem::list).orEmpty()
            if (rows.isEmpty()) Text("暂无公告")
            rows.forEach { item -> ItemSummary(item) { nav.navigate("detail/announcements/${pathPart(item.id)}") } }
        }
        if (settings.updates) OutlinedButton({ nav.navigate("updates") }) { Text("检查应用更新") }
    }
}
@Composable fun DetailScreen(model: AppViewModel, key: String, id: String) {
    val feature = features.find { it.key == key }
    val state = rememberLoad("detail-$key-$id") {
        val source = model.api.request(feature?.path ?: error("内容入口不存在"))
        val items = if (key == "tasks") taskGroups(source).flatMap { it.second } else RemoteItem.list(source)
        items.find { it.id == id } ?: error("内容已变更，请返回列表刷新")
    }
    Page { LoadStatus(state); state.data?.let { RemoteContent(it) } }
}

@Composable fun FeatureScreen(model: AppViewModel, nav: NavHostController, feature: Feature) {
    val state = rememberLoad(feature.key) {
        val value = model.api.request(feature.path)
        if (feature.key == "notifications") model.markRead()
        value
    }
    val context = LocalContext.current
    val busy by model.busy.collectAsStateWithLifecycle()
    var confirmation by remember { mutableStateOf<Pair<String, JsonObject>?>(null) }
    val skinPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.action {
            val selected = pickFile(context, uri)
            try { model.api.upload("/upload_v2", selected.file, selected.name, "skin_file"); state.refresh() } finally { selected.file.delete() }
        }
    }
    fun write(path: String, fields: JsonObject) { confirmation = path to fields }
    Page {
        LoadStatus(state)
        when (feature.key) {
            "suggestions" -> Button({ nav.navigate("form/suggestion") }, enabled = !busy) { Text("提交意见") }
            "skins" -> Button({ skinPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("上传 YSM 皮肤") }
            "catalog" -> Button({ nav.navigate("titles") }, enabled = !busy) { Text("自定义称号") }
        }
        val source = state.data
        if (feature.key in setOf("coins", "playtime")) {
            val ranks = source?.let(LeaderboardEntry::list).orEmpty()
            if (ranks.isEmpty() && !state.loading) Text("暂无排行数据")
            ranks.forEach { rank -> Section("#${rank.rank} ${rank.name}") {
                rank.avatar?.let { AsyncImage(it, "玩家头像", modifier = Modifier.size(40.dp)) }
                if (feature.key == "coins") Text("${rank.coins ?: 0} 喵币")
                else Text("${(rank.seconds ?: 0) / 3600} 小时 ${((rank.seconds ?: 0) % 3600) / 60} 分")
            } }
        } else {
            val groups = if (feature.key == "tasks" && source != null) taskGroups(source) else listOf("" to (source?.let(RemoteItem::list).orEmpty()))
            if (groups.all { it.second.isEmpty() } && !state.loading) Text("暂无${feature.title}")
            groups.forEach { (group, rows) ->
                if (group.isNotEmpty()) Text(group, style = MaterialTheme.typography.titleLarge)
                rows.forEach { item -> Section(item.title) {
                    if (item.subtitle.isNotEmpty()) Text(item.subtitle)
                    TextButton({ nav.navigate("detail/${feature.key}/${pathPart(item.id)}") }) { Text("查看详情") }
                    when (feature.key) {
                        "tasks" -> {
                            val task = TaskState.parse(item.raw)
                            Text(task.label)
                            Text("奖励：${item.raw.str("reward", "reward_name", "reward_text") ?: item.raw.str("reward_coins", "coins")?.let { "$it 喵币" } ?: "以服务器结算为准"}")
                            Button({ write("/tasks/${pathPart(item.id)}/claim", obj()) }, enabled = task == TaskState.COMPLETE && !busy) { Text(if (task == TaskState.CLAIMED) "已领取" else "领取奖励") }
                        }
                        "shop" -> Button({ write("/shop/buy", obj("item_id" to item.id)) }, enabled = !busy) { Text("购买") }
                        "polls" -> Button({ write("/vote", obj("poll_id" to item.id)) }, enabled = !busy) { Text("投票") }
                        "rewards" -> Button({ write("/playtime/rewards/claim", obj("hours" to rewardHours(item.raw))) }, enabled = rewardHours(item.raw) != null && !busy) { Text("领取奖励") }
                        "catalog" -> Button({ write("/titles/buy", obj("title_id" to item.id)) }, enabled = !busy) { Text("购买称号") }
                        "titles" -> Button({ write("/titles/wear", obj("title_id" to item.id)) }, enabled = !busy) { Text("佩戴") }
                    }
                } }
            }
        }
    }
    confirmation?.let { (path, fields) -> Confirm("确认操作", "该操作会提交到服务器；涉及奖励或喵币的结果以服务端为准。", { confirmation = null }, {
        confirmation = null
        model.action { model.api.request(path, "POST", fields); if (feature.key in setOf("shop", "catalog", "rewards", "tasks")) model.refreshProfile(); state.refresh() }
    }, enabled = !busy) }
}
fun taskGroups(j: JsonElement): List<Pair<String, List<RemoteItem>>> {
    val source = j.payload()
    fun first(vararg keys: String) = keys.firstNotNullOfOrNull { source.field(it)?.let(RemoteItem::list)?.takeIf { it.isNotEmpty() } }.orEmpty()
    val daily = first("daily", "daily_tasks", "dailies")
    val achievements = first("achievements", "achievement_tasks", "achievement")
    if (daily.isNotEmpty() || achievements.isNotEmpty()) return listOf("每日任务" to daily, "成就" to achievements)
    val all = RemoteItem.list(source)
    val dailyRows = all.filter { it.raw.str("type", "task_type")?.contains("daily", true) == true }
    return if (dailyRows.isEmpty()) listOf("任务" to all) else listOf("每日任务" to dailyRows, "成就" to all.filterNot { it in dailyRows })
}

@Composable fun CustomTitleScreen(model: AppViewModel, onDone: () -> Unit) {
    val rules = rememberLoad("title-rules") { TitleRules.parse(model.api.request("/titles/catalog")) }
    var title by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf("55AAFF") }
    var confirm by remember { mutableStateOf(false) }
    val busy by model.busy.collectAsStateWithLifecycle()
    val length = TitleColors.length(title.trim())
    val estimate = rules.data?.price?.let { it * length }
    val valid = TitleColors.visible(title).isNotBlank() && (rules.data?.maxLength?.let { length <= it } ?: true)
    Page {
        LoadStatus(rules)
        OutlinedTextField(title, { title = it }, label = { Text("称号内容") }, modifier = Modifier.fillMaxWidth())
        Text("可见字符：$length${rules.data?.maxLength?.let { " / $it" } ?: ""}")
        OutlinedTextField(color, { color = it.take(6).uppercase() }, label = { Text("颜色（六位 HEX）") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ title += "&#$color" }, enabled = Regex("[0-9A-F]{6}").matches(color)) { Text("插入颜色") }
            TextButton({ title = TitleColors.visible(title) }) { Text("清除颜色") }
        }
        Section("预览") { StyledTitle(title) }
        rules.data?.price?.let { Text("单价：$it 喵币 / 字") }
        Text(estimate?.let { "预计扣费：$it 喵币" } ?: "价格和扣费以服务器最终校验为准")
        Button({ confirm = true }, enabled = valid && !busy) { Text("继续购买") }
    }
    if (confirm) Confirm("购买自定义称号？", "称号：${TitleColors.visible(title)}\n${estimate?.let { "预计扣除 $it 喵币。" } ?: ""}服务器会校验长度、价格和余额。", { confirm = false }, {
        confirm = false; model.action { model.api.request("/titles/buy", "POST", obj("title" to title.trim())); model.refreshProfile(); onDone() }
    }, enabled = !busy)
}

@Composable fun UpdateScreen(model: AppViewModel) {
    val context = LocalContext.current
    val state = rememberLoad("android-release") { model.api.latestRelease() }
    Page {
        Text("当前版本：${com.ccatq.xdylupdater2.BuildConfig.VERSION_NAME}")
        LoadStatus(state)
        state.data?.let { release ->
            Text(if (release.newerThan(com.ccatq.xdylupdater2.BuildConfig.VERSION_NAME)) "发现新版本" else "当前没有更新的正式版本", style = MaterialTheme.typography.titleLarge)
            Text(release.name ?: release.tag_name)
            release.body?.takeIf { it.isNotBlank() }?.let { Text(it) }
            Button({ model.action { openLink(context, release.apk ?: release.html_url) } }) { Text("查看并下载 APK") }
        }
        OutlinedButton({ model.action { openLink(context, Environment.releasePage) } }) { Text("打开 GitHub Releases") }
    }
}
