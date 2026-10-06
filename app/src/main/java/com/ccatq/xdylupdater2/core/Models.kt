package com.ccatq.xdylupdater2.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder

val wireJson = Json { ignoreUnknownKeys = true; isLenient = false }
fun JsonElement.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }
fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull
fun JsonElement.str(vararg keys: String): String? = keys.firstNotNullOfOrNull { field(it)?.text() }
fun JsonElement.int(vararg keys: String): Int? = str(*keys)?.toDoubleOrNull()?.toInt()
fun JsonElement.number(vararg keys: String): Double? = str(*keys)?.toDoubleOrNull()
fun JsonElement.flag(key: String): Boolean = str(key)?.lowercase() in setOf("true", "1", "yes", "done", "complete", "completed", "finished", "achieved", "已完成", "完成")
fun JsonElement.payload(): JsonElement = field("data") ?: this
fun JsonElement.array(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()
fun obj(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
    fields.forEach { (k, v) -> put(k, when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }) }
}
fun pathPart(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

@Serializable
data class AuthTokens(val accessToken: String, val refreshToken: String? = null, val username: String? = null) {
    companion object {
        fun parse(json: JsonElement): AuthTokens? {
            val s = json.payload()
            val access = s.str("access_token", "token")?.takeIf { it.isNotBlank() } ?: return null
            return AuthTokens(access, s.str("refresh_token"), s.str("username") ?: s.field("user")?.str("username"))
        }
    }
}

data class Profile(val username: String, val nickname: String, val email: String, val role: String, val balance: String, val qq: String?, val avatar: String?) {
    val administrator get() = role in setOf("admin", "super_admin")
    companion object {
        fun parse(json: JsonElement): Profile {
            val s = json.field("data") ?: json.field("profile") ?: json
            val name = s.str("username") ?: "用户"
            return Profile(name, s.str("nickname") ?: name, s.str("email") ?: "", s.str("role") ?: "user", s.str("balance", "coins") ?: "0", s.str("qq_nickname"), ContentURLs.avatar(s.str("avatar", "avatar_url")))
        }
    }
}

data class RemoteItem(val id: String, val title: String, val subtitle: String, val content: String, val raw: JsonElement) {
    companion object {
        fun parse(j: JsonElement, index: Int = 0): RemoteItem = RemoteItem(
            j.str("id", "post_id", "task_id", "item_id", "reward_id", "title_id", "season_id", "user_id") ?: "row-$index",
            j.str("title", "name", "task_name", "nickname", "player_name", "item_name", "reward_name", "season_name", "label", "type", "username", "content")
                ?: j.str("hours", "required_hours")?.let { "$it 小时奖励" } ?: "项目 ${index + 1}",
            j.str("description", "summary", "nickname", "player_name", "message", "progress", "rank", "author", "created_at", "status") ?: j.str("coins", "reward_coins")?.let { "$it 喵币" } ?: "",
            j.str("content", "note", "description", "message") ?: "", j)
        fun list(j: JsonElement): List<RemoteItem> = rows(j).mapIndexed { i, row -> parse(row, i) }
        fun rows(j: JsonElement): List<JsonElement> {
            if (j is JsonArray) return j.toList()
            if (j !is JsonObject) return emptyList()
            for (key in listOf("data", "result", "items", "list", "posts", "tasks", "notifications", "polls", "seasons", "records", "rows", "rewards", "players", "rankings", "titles", "presets")) {
                val rows = j[key]?.let(::rows).orEmpty()
                if (rows.isNotEmpty()) return rows
            }
            for (value in j.values.filterIsInstance<JsonArray>()) if (value.isNotEmpty()) return value.toList()
            for (value in j.values.filterIsInstance<JsonObject>()) { val rows = rows(value); if (rows.isNotEmpty()) return rows }
            return if (listOf("id", "title", "name", "username", "player_name", "reward_id").any { it in j }) listOf(j) else emptyList()
        }
    }
}

object ContentURLs {
    private val base = URI("https://login.lanternwaves.fun/")
    fun resolve(value: String?): String? = runCatching {
        if (value.isNullOrBlank()) return null
        base.resolve(value.trim()).takeIf { it.scheme == "https" && it.host != null && it.userInfo == null }?.toString()
    }.getOrNull()
    fun avatar(value: String?): String? = if (value.isNullOrBlank()) null else if (value.startsWith("http", true) || value.startsWith("/")) resolve(value) else resolve("user/avatar/${pathPart(value)}")
    fun images(raw: JsonElement): List<String> {
        val urls = mutableListOf<String>()
        fun add(v: String?) { resolve(v)?.let { urls += it } }
        listOf("image", "image_url", "cover").forEach { add(raw.str(it)) }
        raw.field("images")?.array()?.forEach { add(it.text() ?: it.str("url", "image_url", "path")) }
        val content = raw.str("content", "note", "description") ?: ""
        Regex("!\\[[^]]*]\\(([^)]+)\\)").findAll(content).forEach { add(it.groupValues[1]) }
        Regex("<img[^>]*src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(content).forEach { add(it.groupValues[1]) }
        return urls.distinct()
    }
    fun uploaded(j: JsonElement) = resolve(j.payload().str("url", "image_url", "path"))
}

data class ForumDetail(val post: RemoteItem, val replies: List<RemoteItem>) {
    companion object {
        fun parse(json: JsonElement): ForumDetail {
            val s = json.payload()
            return ForumDetail(RemoteItem.parse(s.field("post") ?: error("帖子详情缺少正文")), s.field("replies")?.array().orEmpty().mapIndexed { i, j -> RemoteItem.parse(j, i) })
        }
    }
}

data class ResourceFile(val name: String, val url: String?, val sha256: String?, val size: Long?, val kind: String) {
    companion object {
        fun list(j: JsonElement) = j.payload().field("files")?.array().orEmpty().mapNotNull {
            val name = it.str("name") ?: return@mapNotNull null
            ResourceFile(name, it.str("url"), it.str("sha256"), it.str("size")?.toLongOrNull(), it.str("kind") ?: "资源")
        }
    }
}

enum class TaskState(val label: String) { IN_PROGRESS("进行中"), COMPLETE("已完成"), CLAIMED("已领取");
    companion object {
        fun parse(j: JsonElement): TaskState {
            val statuses = listOf("status", "task_status", "completion_status", "state", "claim_status").mapNotNull { j.str(it)?.trim()?.lowercase() }
            if (listOf("claimed", "is_claimed", "received", "is_received", "collected", "is_collected").any { j.flag(it) } || statuses.any { it in setOf("claimed", "received", "collected", "已领取", "领取成功") }) return CLAIMED
            if (listOf("completed", "is_completed", "complete", "is_complete", "done", "is_done", "finished", "is_finished", "achieved", "is_achieved", "can_claim", "canClaim").any { j.flag(it) } || statuses.any { it in setOf("complete", "completed", "done", "finished", "achieved", "已完成", "完成") }) return COMPLETE
            for (key in listOf("progress", "task_progress", "completion_progress")) {
                val p = j.str(key)?.trim() ?: continue
                if (p.endsWith('%') && (p.dropLast(1).trim().toDoubleOrNull() ?: 0.0) >= 100) return COMPLETE
                val parts = p.split('/').map { it.trim().toDoubleOrNull() }
                if (parts.size == 2 && parts.all { it != null } && parts[1]!! > 0 && parts[0]!! >= parts[1]!!) return COMPLETE
            }
            val current = j.number("current_progress", "current", "completed_count", "count", "value", "progress")
            val target = j.number("target", "target_count", "required", "required_count", "goal", "total", "max_progress")
            return if (current != null && target != null && target > 0 && current >= target) COMPLETE else IN_PROGRESS
        }
    }
}
fun rewardHours(j: JsonElement): Double? = (j.number("hours", "hour", "required_hours", "requiredHours", "tier") ?: j.number("required_seconds", "seconds")?.div(3600))?.takeIf { it > 0 }

data class LeaderboardEntry(val rank: Int, val name: String, val avatar: String?, val coins: Long?, val seconds: Long?) {
    companion object {
        fun list(j: JsonElement) = RemoteItem.rows(j).mapIndexedNotNull { i, s ->
            val name = s.str("nickname", "player_name", "username") ?: return@mapIndexedNotNull null
            LeaderboardEntry(s.int("rank") ?: i + 1, name, ContentURLs.avatar(s.str("avatar")), s.str("coins")?.toLongOrNull(), s.str("seconds")?.toLongOrNull())
        }.sortedBy { it.rank }
    }
}
object TitleColors {
    val pattern = Regex("&#([0-9a-fA-F]{6})")
    fun visible(s: String) = pattern.replace(s, "")
    fun length(s: String) = Regex("\\X").findAll(visible(s)).count()
}
data class TitleRules(val price: Int?, val maxLength: Int?) {
    companion object { fun parse(j: JsonElement): TitleRules { val s = j.payload().field("custom") ?: j.payload(); return TitleRules(s.int("price_per_char", "pricePerChar"), s.int("max_len", "max_length", "maxLen")) } }
}

data class DuelPlayer(val id: String, val name: String, val rating: Int, val tier: String, val wins: Int, val losses: Int, val matches: Int, val rank: Int?, val peak: Int?, val avatar: String?) {
    companion object { fun parse(j: JsonElement, fallback: String = "player") = DuelPlayer(j.str("id", "username", "player", "name") ?: fallback, j.str("name", "nickname", "username", "player") ?: "未知玩家", j.int("rating") ?: 0, j.str("tier") ?: "未定级", j.int("wins") ?: 0, j.int("losses") ?: 0, j.int("matches") ?: 0, j.int("rank", "rank_position"), j.int("peak_rating"), ContentURLs.avatar(j.str("avatar", "avatar_url"))) }
}
enum class MatchState(val label: String) { idle("未在匹配"), searching("正在寻找对手"), found("已找到对手，等待确认"), starts("即将开始"), expired("匹配已超时"), declined("匹配已取消");
    val active get() = this == searching || this == found
}
data class DuelMatch(val state: MatchState = MatchState.idle, val account: String = "", val waiting: Int = 0, val expires: Int = 0, val meConfirmed: Boolean = false, val opponentConfirmed: Boolean = false, val opponent: DuelPlayer? = null) {
    companion object { fun parse(j: JsonElement): DuelMatch { val s = j.payload(); return DuelMatch(MatchState.entries.find { it.name == s.str("state") } ?: MatchState.idle, s.str("account") ?: "", s.int("waiting_seconds") ?: 0, s.int("expires_in") ?: 0, s.flag("me_confirmed"), s.flag("opponent_confirmed"), s.field("opponent")?.let { DuelPlayer.parse(it) }) } }
}
data class DuelInvite(val id: String, val player: DuelPlayer, val incoming: Boolean, val status: String, val expires: Int?)
object DuelPayload {
    fun rows(j: JsonElement, vararg keys: String): List<JsonElement> { val s = j.payload(); return keys.firstNotNullOfOrNull { s.field(it)?.array()?.takeIf { it.isNotEmpty() } } ?: s.array() }
    fun accounts(j: JsonElement) = rows(j, "accounts", "players", "data").mapNotNull { it.text() ?: it.str("account", "name", "player_name") }
    fun invites(j: JsonElement): List<DuelInvite> {
        val s = j.payload()
        val incoming = s.field("incoming") ?: s.field("invites") ?: if (s is JsonArray) s else JsonArray(emptyList())
        val outgoing = s.field("outgoing") ?: s.field("sent") ?: JsonArray(emptyList())
        return listOf(true to incoming, false to outgoing).flatMap { (isIncoming, list) -> list.array().mapIndexed { i, row ->
            DuelInvite(row.str("invite_id", "id") ?: "invite-$isIncoming-$i", DuelPlayer.parse(row.field("player") ?: row.field("from") ?: row.field("opponent") ?: row), isIncoming, row.str("status") ?: "pending", row.int("expires_in", "remaining_seconds"))
        } }
    }
}

@Serializable data class ReleaseAsset(val name: String, val browser_download_url: String)
@Serializable data class GitHubRelease(val tag_name: String, val name: String? = null, val body: String? = null, val html_url: String, val assets: List<ReleaseAsset> = emptyList(), val prerelease: Boolean = false, val draft: Boolean = false) {
    val apk get() = assets.firstOrNull { it.name.endsWith(".apk") }?.browser_download_url
    fun newerThan(current: String): Boolean {
        fun version(s: String): List<Int> = s.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: return emptyList<Int>() }
        val next = version(tag_name); val now = version(current)
        if (draft || prerelease || next.isEmpty() || now.isEmpty()) return false
        for (i in 0 until maxOf(next.size, now.size)) { val a = next.getOrElse(i) { 0 }; val b = now.getOrElse(i) { 0 }; if (a != b) return a > b }
        return false
    }
}
