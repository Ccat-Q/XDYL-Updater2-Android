package com.ccatq.xdylupdater2.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.ccatq.xdylupdater2.core.*

@Composable fun StarWaveRoot(model: AppViewModel) {
    val tokens by model.tokens.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            if (tokens == null) AuthScreen(model) else MainTabs(model)
            error?.let { AlertDialog(onDismissRequest = { model.error.value = null }, title = { Text("提示") }, text = { Text(it) }, confirmButton = { TextButton({ model.error.value = null }) { Text("好") } }) }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MainTabs(model: AppViewModel) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val controllers = List(5) { rememberNavController() }
    val tab = selected
    val tabState = rememberSaveableStateHolder()
    val controller = controllers[tab]
    val entry by controller.currentBackStackEntryAsState()
    val titles = listOf("首页", "社区", "资源", "服务", "我的")
    val icons = listOf(Icons.Default.Home, Icons.Default.Forum, Icons.Default.Download, Icons.Default.Apps, Icons.Default.Person)
    val unread by model.unread.collectAsStateWithLifecycle()
    val route = entry?.destination?.route.orEmpty()
    val title = when {
        route == "root" || route.isEmpty() -> titles[selected]
        route.startsWith("feature") -> features.find { it.key == entry?.arguments?.getString("key") }?.title ?: "服务"
        route.startsWith("post") -> "帖子"
        route.startsWith("form") -> formTitle(entry?.arguments?.getString("kind").orEmpty())
        route == "settings" -> "设置"
        route == "developer" -> "开发者工具"
        route == "logs" -> "网络日志"
        route == "duel" -> "决斗场"
        route == "titles" -> "自定义称号"
        route == "qq" -> "绑定 QQ"
        route == "updates" -> "应用更新"
        else -> "详情"
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            if (route != "root" && route.isNotEmpty()) IconButton({ controller.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }, bottomBar = {
        NavigationBar {
            titles.forEachIndexed { index, label ->
                NavigationBarItem(selected = selected == index, onClick = { selected = index }, icon = {
                    BadgedBox(badge = { if (index == 4 && unread > 0) Badge { Text(unread.toString()) } }) { Icon(icons[index], label) }
                }, label = { Text(label) })
            }
        }
    }) { padding ->
        tabState.SaveableStateProvider(tab) {
        key(tab) {
        NavHost(controller, startDestination = "root", modifier = Modifier.padding(padding)) {
            composable("root") {
                when (tab) {
                    0 -> HomeScreen(model, controller)
                    1 -> CommunityScreen(model, controller)
                    2 -> DownloadsScreen(model)
                    3 -> ServicesScreen(controller)
                    4 -> ProfileScreen(model, controller)
                }
            }
            composable("feature/{key}") { back -> FeatureScreen(model, controller, features.first { it.key == back.arguments?.getString("key") }) }
            composable("detail/{key}/{id}") { back -> DetailScreen(model, back.arguments?.getString("key").orEmpty(), back.arguments?.getString("id").orEmpty()) }
            composable("post/{id}") { back -> PostScreen(model, back.arguments?.getString("id").orEmpty()) }
            composable("form/{kind}") { back -> FormScreen(model, back.arguments?.getString("kind").orEmpty(), onDone = { controller.popBackStack() }) }
            composable("settings") { SettingsScreen(model, controller) }
            composable("logs") { LogsScreen(model) }
            composable("developer") { DeveloperScreen(model) }
            composable("duel") { DuelScreen(model) }
            composable("titles") { CustomTitleScreen(model, onDone = { controller.popBackStack() }) }
            composable("qq") { QQScreen(model, bind = true, onDone = { controller.popBackStack() }) }
            composable("updates") { UpdateScreen(model) }
        }
        }
        }
    }
}
