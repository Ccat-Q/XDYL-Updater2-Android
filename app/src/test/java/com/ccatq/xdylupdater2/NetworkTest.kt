package com.ccatq.xdylupdater2

import com.ccatq.xdylupdater2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MemoryTokens(initial: AuthTokens? = AuthTokens("old", "refresh")) : TokenStore {
    override val tokens = MutableStateFlow(initial)
    override fun save(tokens: AuthTokens) { this.tokens.value = tokens }
    override fun clear() { tokens.value = null }
}
class NetworkTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemoryTokens
    private lateinit var api: ApiClient
    @Before fun setup() {
        server = MockWebServer(); server.start(); store = MemoryTokens()
        api = ApiClient(store, server.url("/").toString(), policy = { url, _ -> url.host == server.hostName && url.port == server.port })
    }
    @After fun teardown() { server.shutdown() }
    private fun response(code: Int = 200, body: String = "{}") = MockResponse().setResponseCode(code).setBody(body)
    @Test fun loginUsesAccountAndSendsNoBearer() = runBlocking {
        server.enqueue(response(body = """{"access_token":"a"}"""))
        api.request("/login", "POST", obj("account" to "cat", "password" to "pw"), authenticated = false)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertNull(request.getHeader("Authorization")); assertEquals("cat", wireJson.parseToJsonElement(request.body.readUtf8()).str("account"))
    }
    @Test fun concurrentUnauthorizedRequestsShareOneRefresh() = runBlocking {
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/refresh" -> { refreshes.incrementAndGet(); response(body = """{"data":{"access_token":"new","refresh_token":"r2"}}""") }
                request.getHeader("Authorization") == "Bearer old" -> response(401)
                else -> response(body = """{"data":{"username":"cat"}}""")
            }
        }
        withTimeout(10_000) { List(4) { async { api.request("/user/profile") } }.awaitAll() }
        assertEquals(1, refreshes.get()); assertEquals("new", store.tokens.value?.accessToken)
    }
    @Test fun rejectedRefreshClearsSessionAndDoesNotReplayMutation() = runBlocking {
        server.enqueue(response(401)); server.enqueue(response(401))
        try { api.request("/shop/buy", "POST", obj("item_id" to "1")); fail("Expected authentication failure") } catch (_: ApiException) { }
        assertNull(store.tokens.value); assertEquals(2, server.requestCount)
        assertEquals("/shop/buy", server.takeRequest().path); assertEquals("/refresh", server.takeRequest().path)
    }
    @Test fun transientRefreshFailurePreservesSession() = runBlocking {
        server.enqueue(response(401)); server.enqueue(response(503))
        try { api.request("/user/profile"); fail("Expected service failure") } catch (_: ApiException) { }
        assertEquals("old", store.tokens.value?.accessToken)
    }
    @Test fun retryStopsAfterOneRefresh() = runBlocking {
        server.enqueue(response(401)); server.enqueue(response(body = """{"access_token":"new","refresh_token":"refresh"}""")); server.enqueue(response(401))
        try { api.request("/user/profile"); fail("Expected failure") } catch (_: ApiException) { }
        assertEquals(3, server.requestCount)
    }
    @Test fun redirectsCannotSendBearerToAnotherOrigin() = runBlocking {
        val foreign = MockWebServer(); foreign.start()
        try {
            server.enqueue(response(302).setHeader("Location", foreign.url("/steal")))
            try { api.request("/user/profile"); fail("Expected blocked redirect") } catch (_: IllegalStateException) { }
            assertEquals(0, foreign.requestCount)
        } finally { foreign.shutdown() }
    }
    @Test fun multipartUploadUsesTheSameRefreshAndRetainsField() = runBlocking {
        val file = File.createTempFile("avatar", ".png").apply { writeText("image-bytes") }
        try {
            server.enqueue(response(401)); server.enqueue(response(body = """{"access_token":"new","refresh_token":"r2"}""")); server.enqueue(response(body = """{"url":"/avatar.png"}"""))
            api.upload("/user/avatar", file, "avatar.png", "avatar")
            val first = server.takeRequest(); val refresh = server.takeRequest(); val retried = server.takeRequest()
            assertTrue(first.body.readUtf8().contains("name=\"avatar\""))
            assertEquals("/refresh", refresh.path); assertEquals("Bearer new", retried.getHeader("Authorization"))
            assertTrue(retried.body.readUtf8().contains("image-bytes"))
        } finally { file.delete() }
    }
    @Test fun errorsAndMalformedResponsesNeverLookSuccessful() = runBlocking {
        server.enqueue(response(409, """{"message":"余额不足"}"""))
        try { api.request("/shop/buy", "POST", obj("item_id" to "1")); fail("Expected failure") } catch (e: ApiException) { assertEquals("余额不足", e.message) }
        server.enqueue(response(200, "<html>broken</html>"))
        try { api.request("/user/profile"); fail("Expected decoding failure") } catch (e: ApiException) { assertTrue(e.message!!.contains("JSON")) }
    }
}
