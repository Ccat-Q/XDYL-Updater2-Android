package com.ccatq.xdylupdater2.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ccatq.xdylupdater2.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class DuelData(val me: DuelPlayer, val recent: List<JsonElement>, val online: List<DuelPlayer>, val invites: List<DuelInvite>, val accounts: List<String>, val ranks: List<DuelPlayer>)
@Composable fun DuelScreen(model: AppViewModel) {
    var match by remember { mutableStateOf(DuelMatch()) }
    var account by rememberSaveable { mutableStateOf("") }
    var action by remember { mutableStateOf<Triple<String, String, JsonObject>?>(null) }
    val busy by model.busy.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = rememberLoad("duel") {
        val me = model.api.request("/pvp/me")
        val online = model.api.request("/pvp/online")
        val invites = model.api.request("/pvp/incoming")
        val accounts = DuelPayload.accounts(model.api.request("/pvp/accounts"))
        val ranks = model.api.request("/pvp/rank", query = mapOf("limit" to "50"))
        match = DuelMatch.parse(model.api.request("/pvp/match/status"))
        if (account !in accounts) account = accounts.firstOrNull().orEmpty()
        DuelData(DuelPlayer.parse(me.payload()), DuelPayload.rows(me, "recent", "recent_matches", "matches", "history"), DuelPayload.rows(online, "players", "online").mapIndexed { i, j -> DuelPlayer.parse(j, "online-$i") }, DuelPayload.invites(invites), accounts, DuelPayload.rows(ranks, "rankings", "players").mapIndexed { i, j -> DuelPlayer.parse(j, "rank-$i") }.sortedBy { it.rank ?: Int.MAX_VALUE })
    }
    LifecycleResumeEffect(Unit) {
        if (state.data != null) state.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(match.state.active) {
        if (!match.state.active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (match.state.active && currentCoroutineContext().isActive) {
                delay(2_000)
                try {
                    match = DuelMatch.parse(model.api.request("/pvp/match/status"))
                    val invites = DuelPayload.invites(model.api.request("/pvp/incoming"))
                    state.data = state.data?.copy(invites = invites)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { state.error = e.message }
            }
        }
    }
    fun confirm(title: String, path: String, fields: JsonObject = obj()) { action = Triple(title, path, fields) }
    Page {
        LoadStatus(state)
        state.data?.let { data ->
            Section("我的排位") { PlayerSummary(data.me); Text("${data.me.wins} 胜 · ${data.me.losses} 负 · ${data.me.matches} 场${data.me.peak?.let { " · 最高 $it 分" } ?: ""}") }
            Section("近期对局") {
                if (data.recent.isEmpty()) Text("暂无记录")
                data.recent.forEach { row ->
                    Text("对手：${row.field("opponent")?.str("name") ?: row.str("opponent_name", "opponent") ?: "未知对手"}")
                    Text("${row.str("result", "status") ?: ""} · ${row.str("rating_change", "delta") ?: "0"} 分 · ${row.str("created_at", "time") ?: ""}")
                }
            }
            Section("排位匹配") {
                if (data.accounts.isEmpty()) Text("请先在游戏内绑定游戏账号")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    data.accounts.forEach { name -> FilterChip(account == name, { account = name }, label = { Text(name) }, enabled = !match.state.active) }
                }
                Text("时间偏好：白天")
                Text("状态：${match.state.label}")
                if (match.state == MatchState.searching) Text("已等待 ${match.waiting} 秒")
                match.opponent?.let { PlayerSummary(it) }
                if (match.state == MatchState.found) {
                    Text("确认：我 ${if (match.meConfirmed) "已确认" else "未确认"} · 对方 ${if (match.opponentConfirmed) "已确认" else "未确认"} · 剩余 ${match.expires} 秒")
                    if (!match.meConfirmed) Button({ confirm("确认本场对局？", "/pvp/match/confirm", obj("action" to "accept")) }, enabled = !busy) { Text("确认对局") }
                }
                if (match.state in setOf(MatchState.idle, MatchState.expired, MatchState.declined)) Button({ confirm("开始排位匹配？", "/pvp/match/join", obj("account" to account, "time_pref" to "day")) }, enabled = account.isNotBlank() && !busy) { Text("开始匹配") }
                if (match.state.active) OutlinedButton({ confirm("取消当前匹配？", "/pvp/match/leave") }, enabled = !busy) { Text("取消匹配") }
                if (match.state == MatchState.starts) Text("请返回游戏，等待服务器安排对局。")
            }
            Section("发起挑战") {
                if (data.online.isEmpty()) Text("暂无可挑战的在线玩家")
                data.online.forEach { player -> PlayerSummary(player); OutlinedButton({ confirm("向 ${player.name} 发起挑战？", "/pvp/challenge", obj("target" to player.name)) }, enabled = !busy) { Text("挑战") } }
            }
            Section("收到的挑战") {
                val incoming = data.invites.filter { it.incoming }
                if (incoming.isEmpty()) Text("暂无挑战")
                incoming.forEach { invite ->
                    PlayerSummary(invite.player); invite.expires?.let { Text("剩余 $it 秒") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ confirm("接受挑战？", "/pvp/challenge/respond", obj("id" to invite.id, "action" to "accept")) }, enabled = !busy && invite.status == "pending") { Text("接受") }
                        TextButton({ confirm("拒绝挑战？", "/pvp/challenge/respond", obj("id" to invite.id, "action" to "decline")) }, enabled = !busy && invite.status == "pending") { Text("拒绝") }
                    }
                }
            }
            Section("已发出的挑战") { val sent = data.invites.filterNot { it.incoming }; if (sent.isEmpty()) Text("暂无挑战"); sent.forEach { Text("${it.player.name} · ${it.status}") } }
            Section("排行榜") { if (data.ranks.isEmpty()) Text("暂无排行"); data.ranks.forEach { PlayerSummary(it) } }
        }
    }
    action?.let { (title, path, fields) -> Confirm(title, "该操作会发送到游戏服务器，客户端不负责游戏内结算。", { action = null }, {
        action = null; model.action { model.api.request(path, "POST", fields); state.refresh() }
    }, enabled = !busy) }
}
@Composable private fun PlayerSummary(player: DuelPlayer) { Text("${player.rank?.let { "#$it " } ?: ""}${player.name} · ${player.tier} · ${player.rating} 分") }
