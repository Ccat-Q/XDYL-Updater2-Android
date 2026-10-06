package com.ccatq.xdylupdater2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ccatq.xdylupdater2.core.*
import com.ccatq.xdylupdater2.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Collections

class FixtureApi : RemoteApi {
    val writes = Collections.synchronizedList(mutableListOf<Pair<String, JsonObject?>>())
    override suspend fun request(path: String, method: String, body: JsonObject?, query: Map<String, String>, authenticated: Boolean, base: String): JsonElement {
        if (method != "GET") writes += path to body
        val response = when (path) {
            "/login" -> """{"data":{"access_token":"test-access","refresh_token":"test-refresh","username":"cat"}}"""
            "/user/profile" -> """{"data":{"username":"cat","nickname":"测试玩家","coins":12}}"""
            "/notifications/unread" -> """{"data":{"count":2}}"""
            "/announcements" -> """{"data":[{"id":"a","title":"测试公告","content":"完整正文"}]}"""
            "/forum/categories" -> """{"items":[{"id":"c","name":"测试分类"}]}"""
            "/forum/posts" -> """{"posts":[{"id":"p","title":"测试帖子","content":"内容"}]}"""
            "/forum/post/p" -> """{"data":{"post":{"id":"p","title":"测试帖子","content":"完整正文"},"replies":[]}}"""
            "/mods/mods.json" -> """{"data":{"files":[{"name":"测试模组.jar","url":"https://example.com/mod.jar","size":1024,"kind":"mod"}]}}"""
            "/pvp/me" -> """{"data":{"name":"cat","tier":"青铜","rating":1000}}"""
            "/pvp/accounts" -> """{"data":{"accounts":["cat"]}}"""
            "/pvp/match/status" -> """{"data":{"state":"idle"}}"""
            else -> """{"data":[]}"""
        }
        return wireJson.parseToJsonElement(response)
    }
    override suspend fun upload(path: String, file: File, filename: String, field: String): JsonElement = obj("url" to "/image.png")
    override suspend fun latestRelease() = GitHubRelease("v2.2.0", body = "测试更新", html_url = Environment.releasePage)
}
class NavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var graph: AppGraph
    private lateinit var fake: FixtureApi
    private lateinit var model: AppViewModel
    @Before fun setup() {
        fake = FixtureApi()
        graph = AppGraph(InstrumentationRegistry.getInstrumentation().targetContext, fake)
        graph.session.clear()
        runBlocking { graph.preferences.set("developer", false); graph.preferences.set("continuous", false) }
        model = AppViewModel(graph)
    }
    @After fun cleanSession() {
        graph.session.clear(); runBlocking { graph.preferences.set("developer", false); graph.preferences.set("continuous", false) }
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "upgrade-marker").writeText("persistent")
    }
    private fun start(authenticated: Boolean = true) {
        if (authenticated) graph.session.save(AuthTokens("test-access", "test-refresh", "cat"))
        compose.setContent { StarWaveRoot(model) }
        compose.waitForIdle()
    }
    private fun click(text: String) { compose.onNode(hasText(text) and hasClickAction()).performClick() }
    @Test fun loginUsesTheAccountContractAndStoresNoPlaintextToken() {
        start(false)
        compose.onNodeWithText("邮箱或用户名").performTextInput("cat")
        compose.onNodeWithText("密码").performTextInput("password")
        click("登录")
        compose.waitUntil(10_000) { graph.session.tokens.value != null }
        compose.onNodeWithText("你好，测试玩家").assertIsDisplayed()
        assertEquals("cat", fake.writes.first { it.first == "/login" }.second?.str("account"))
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "session.enc")
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("test-access"))
    }
    @Test fun tabsPreserveCommunityDetailAndResourceNavigation() {
        start()
        click("社区"); compose.onNodeWithText("测试帖子").performClick()
        compose.onNodeWithText("完整正文").assertIsDisplayed()
        click("资源"); compose.onNodeWithText("测试模组.jar").assertIsDisplayed()
        click("社区"); compose.onNodeWithText("完整正文").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("测试帖子").assertIsDisplayed()
        click("服务"); compose.onNodeWithText("决斗场").assertIsDisplayed()
        click("我的"); compose.onNodeWithText("测试玩家").assertIsDisplayed()
    }
    @Test fun duelMutationRequiresConfirmationAndCancelSendsNothing() {
        start(); click("服务"); click("决斗场")
        compose.onNodeWithText("开始匹配").performScrollTo().performClick()
        compose.onNodeWithText("开始排位匹配？").assertIsDisplayed()
        click("取消")
        assertFalse(fake.writes.any { it.first.startsWith("/pvp/") })
    }
    @Test fun sevenVersionTapsEnableDeveloperTools() {
        start(); click("我的")
        val version = compose.onNode(hasText("版本 ${BuildConfig.VERSION_NAME}") and hasClickAction())
        version.performScrollTo(); repeat(7) { version.performClick() }
        compose.waitUntil(10_000) { model.settings.value.developer }
        compose.onNodeWithText("开发者功能已启用").assertIsDisplayed()
        compose.onNode(hasText("设置") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("开发者工具").performScrollTo().assertIsDisplayed()
    }
}
