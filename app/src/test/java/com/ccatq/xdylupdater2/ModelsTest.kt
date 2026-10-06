package com.ccatq.xdylupdater2

import com.ccatq.xdylupdater2.core.*
import com.ccatq.xdylupdater2.developer.knownRoutes
import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class ModelsTest {
    private fun json(text: String) = wireJson.parseToJsonElement(text)
    @Test fun tokensAcceptEnvelopesAndRejectEmptyCredentials() {
        val token = AuthTokens.parse(json("""{"data":{"access_token":"a","refresh_token":"r","user":{"username":"cat"}}}"""))!!
        assertEquals(AuthTokens("a", "r", "cat"), token)
        assertNull(AuthTokens.parse(json("""{"data":{"access_token":""}}""")))
        assertEquals("a", AuthTokens.parse(json("""{"token":"a"}"""))?.accessToken)
    }
    @Test fun listFindsFeatureSpecificContainersAndSkipsErrorEnvelopes() {
        val rows = RemoteItem.list(json("""{"data":{"daily_rewards":[{"reward_id":8,"reward_name":"奖励","description":"说明"}]}}"""))
        assertEquals("8", rows.single().id); assertEquals("奖励", rows.single().title)
        assertTrue(RemoteItem.list(json("""{"code":401,"message":"error"}""" )).isEmpty())
    }
    @Test fun postDetailDoesNotMistakeOuterEnvelopeForAPost() {
        val detail = ForumDetail.parse(json("""{"data":{"post":{"id":2,"title":"帖子","content":"正文"},"replies":[{"id":3,"nickname":"回复者","content":"回复正文"}]}}"""))
        assertEquals("正文", detail.post.content); assertEquals("回复正文", detail.replies.single().content)
    }
    @Test fun relativeAvatarsAndMarkdownAttachmentsResolveSecurely() {
        assertEquals("https://login.lanternwaves.fun/user/avatar/cat.jpg", ContentURLs.avatar("cat.jpg"))
        val images = ContentURLs.images(obj("content" to "![图](/uploads/a.png) <img src=\"https://cdn.example/b.png\">", "images" to json("""["/uploads/c.png",{"url":"https://cdn.example/d.png"}]""")))
        assertEquals(4, images.size)
        assertTrue(images.all { it.startsWith("https://") }); assertNull(ContentURLs.resolve("javascript:alert(1)"))
    }
    @Test fun profileRecognizesAdminWithoutOverwritingLeaderboardBalance() {
        val profile = Profile.parse(json("""{"username":"owner","role":"super_admin","coins":12}"""))
        assertTrue(profile.administrator); assertEquals("12", profile.balance)
    }
    @Test fun taskCompletionHandlesFlagsStatusesAndProgress() {
        listOf("""{"status":"finished"}""", """{"progress":"3 / 3"}""", """{"progress":"100%"}""", """{"current_progress":100,"target":100}""").forEach { assertEquals(TaskState.COMPLETE, TaskState.parse(json(it))) }
        assertEquals(TaskState.CLAIMED, TaskState.parse(obj("is_claimed" to "1", "completed" to true)))
        assertEquals(TaskState.IN_PROGRESS, TaskState.parse(obj("progress" to "1/2")))
    }
    @Test fun rewardClaimsUseHoursNotRowIdentifiers() {
        assertEquals(12.0, rewardHours(obj("id" to "row-1", "hours" to 12))!!, 0.0)
        assertEquals(2.0, rewardHours(obj("required_seconds" to 7200))!!, 0.0)
        assertNull(rewardHours(obj("id" to 12))); assertNull(rewardHours(obj("hours" to -1)))
    }
    @Test fun manifestUsesFilesOnlyAndPreservesMetadata() {
        val rows = ResourceFile.list(json("""{"data":{"files":[{"name":"mod.jar","url":"http://api.lanternwaves.fun:5551/mods/a.jar","sha256":"abc","size":1024,"kind":"mod"}],"groups":[{"name":"wrong"}]}}"""))
        assertEquals(1, rows.size); assertEquals(1024L, rows.single().size); assertEquals("abc", rows.single().sha256)
        assertTrue(ResourceFile.list(json("""{"groups":[{"name":"group"}]}""")).isEmpty())
    }
    @Test fun leaderboardUsesServerRanksAndFeatureNames() {
        val ranks = LeaderboardEntry.list(json("""{"data":[{"rank":2,"nickname":"猫","coins":12},{"rank":1,"player_name":"玩家","seconds":3600}]}"""))
        assertEquals(listOf(1, 2), ranks.map { it.rank }); assertEquals("玩家", ranks.first().name)
    }
    @Test fun titleColorsAreExcludedFromVisibleGraphemeCount() {
        assertEquals("赤青", TitleColors.visible("&#FF0000赤&#00FF00青"))
        assertEquals(2, TitleColors.length("&#FF0000赤&#00FF00青"))
        assertEquals(1, TitleColors.length("👨‍👩‍👧‍👦"))
        val rules = TitleRules.parse(json("""{"data":{"custom":{"price_per_char":12,"max_len":8}}}"""))
        assertEquals(TitleRules(12, 8), rules)
    }
    @Test fun duelKeepsServerStatesAndInvitationDirections() {
        val match = DuelMatch.parse(json("""{"data":{"state":"found","account":"cat","waiting_seconds":8,"me_confirmed":true,"opponent":{"name":"wolf","rating":1017}}}"""))
        assertTrue(match.state.active); assertEquals("wolf", match.opponent?.name); assertTrue(match.meConfirmed)
        val invites = DuelPayload.invites(json("""{"data":{"incoming":[{"invite_id":"in","player":{"name":"a"}}],"outgoing":[{"invite_id":"out","player":{"name":"b"}}]}}"""))
        assertTrue(invites.first().incoming); assertFalse(invites.last().incoming)
        assertEquals(listOf("cat", "wolf"), DuelPayload.accounts(json("""{"data":{"accounts":["cat",{"account":"wolf"}]}}""")))
    }
    @Test fun releaseComparesNumbersAndRejectsPreviewVersions() {
        fun release(tag: String, preview: Boolean = false) = GitHubRelease(tag, html_url = Environment.releasePage, prerelease = preview)
        assertTrue(release("v2.10.0").newerThan("2.9.99"))
        assertFalse(release("v2.1.9").newerThan("2.1.9-debug"))
        assertFalse(release("v3.0.0", true).newerThan("2.1.9"))
        assertFalse(release("garbage").newerThan("2.1.9"))
    }
    @Test fun redactionCoversNestedBodiesHeadersAndQueries() {
        val redacted = Redactor.text("""{"nested":{"access_token":"secret","old_password":"pw","ok":"visible"}}""")
        assertFalse(redacted.contains("secret")); assertFalse(redacted.contains("pw")); assertTrue(redacted.contains("visible"))
        assertEquals("[REDACTED]", Redactor.headers(mapOf("Set-Cookie" to "SID=secret")).getValue("Set-Cookie"))
        assertFalse(Redactor.url("https://example.com/?session_id=secret&password=pw&normal=yes").contains("secret"))
        assertFalse(Redactor.text("Authorization: Bearer secret").contains("secret"))
    }
    @Test fun businessTokensCannotReachForeignHostsOrUnexpectedPorts() {
        assertTrue(NetworkPolicy.business(Environment.api.toHttpUrl(), true))
        listOf("https://github.com", "https://login.lanternwaves.fun:444", "http://api.lanternwaves.fun:5551", "https://login.lanternwaves.fun.evil.example").forEach { assertFalse(NetworkPolicy.business(it.toHttpUrl(), true)) }
        assertTrue(NetworkPolicy.download("https://pan.example/a.zip"))
        assertTrue(NetworkPolicy.download(Environment.manifest))
        assertFalse(NetworkPolicy.download("http://other.example/a.zip"))
        assertFalse(NetworkPolicy.download("http://api.lanternwaves.fun/a.zip"))
        assertFalse(NetworkPolicy.download("https://user:password@example.com/a.zip"))
    }
    @Test fun knownRoutesAreCompleteAndUnique() {
        assertEquals(67, knownRoutes.size)
        assertEquals(knownRoutes.size, knownRoutes.map { "${it.method} ${it.base} ${it.path}" }.toSet().size)
        assertTrue(knownRoutes.any { it.path == "/pvp/challenge/respond" && it.method == "POST" })
        assertTrue(knownRoutes.any { it.path == "/mods.json" && !it.authenticated })
    }
}
