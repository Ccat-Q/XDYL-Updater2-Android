package com.ccatq.xdylupdater2.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

object Environment {
    const val api = "https://login.lanternwaves.fun"
    const val manifest = "http://api.lanternwaves.fun:5551/mods/mods.json"
    const val releases = "https://api.github.com/repos/Ccat-Q/XDYL-Updater2-Android/releases/latest"
    const val releasePage = "https://github.com/Ccat-Q/XDYL-Updater2-Android/releases"
}
object NetworkPolicy {
    fun business(url: HttpUrl, authenticated: Boolean): Boolean {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (authenticated) return url.scheme == "https" && url.host == "login.lanternwaves.fun" && url.port == 443
        return (url.scheme == "https" && url.port == 443 && url.host in setOf("login.lanternwaves.fun", "api.github.com", "github.com")) ||
            (url.scheme == "http" && url.host == "api.lanternwaves.fun" && url.port in setOf(8080, 5551))
    }
    fun download(raw: String): Boolean = runCatching { val u = raw.toHttpUrl(); u.username.isEmpty() && u.password.isEmpty() && (u.scheme == "https" || (u.scheme == "http" && u.host == "api.lanternwaves.fun" && u.port in setOf(8080, 5551))) }.getOrDefault(false)
}
class ApiException(val status: Int, message: String) : IOException(message)
interface TokenStore {
    val tokens: StateFlow<AuthTokens?>
    fun save(tokens: AuthTokens)
    fun clear()
}
interface RemoteApi {
    suspend fun request(path: String, method: String = "GET", body: JsonObject? = null, query: Map<String, String> = emptyMap(), authenticated: Boolean = true, base: String = Environment.api): JsonElement
    suspend fun upload(path: String, file: File, filename: String, field: String = "file"): JsonElement
    suspend fun latestRelease(): GitHubRelease
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

class ApiClient(
    private val session: TokenStore,
    private val baseURL: String = Environment.api,
    private val policy: (HttpUrl, Boolean) -> Boolean = NetworkPolicy::business,
    private val log: (String, String, Int, Long, Long) -> Unit = { _, _, _, _, _ -> },
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(120, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
) : RemoteApi {
    private val refreshMutex = Mutex()
    override suspend fun request(path: String, method: String, body: JsonObject?, query: Map<String, String>, authenticated: Boolean, base: String): JsonElement {
        val origin = if (base == Environment.api) baseURL else base
        val url = origin.toHttpUrl().resolve(path) ?: error("请求地址无效")
        val target = url.newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val rb = if (method == "GET" || method == "HEAD") null else (body ?: obj()).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        return send(target, method, rb, authenticated)
    }
    override suspend fun upload(path: String, file: File, filename: String, field: String): JsonElement {
        val url = baseURL.toHttpUrl().resolve(path) ?: error("上传地址无效")
        val rb = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: okio.BufferedSink) { file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; sink.write(buffer, 0, n) } } }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart(field, filename, rb).build()
        return send(url, "POST", multipart, true)
    }
    private suspend fun send(url: HttpUrl, method: String, body: RequestBody?, authenticated: Boolean, allowRefresh: Boolean = true): JsonElement {
        check(policy(url, authenticated)) { "请求地址不在允许的服务列表中" }
        val token = session.tokens.value?.accessToken
        if (authenticated && token == null) throw ApiException(401, "请先登录")
        val builder = Request.Builder().url(url).method(method, body).header("Accept", "application/json").header("User-Agent", "StarWave-Android/2.1.9").header("Accept-Language", "zh-CN,zh;q=0.9")
        if (authenticated) builder.header("Authorization", "Bearer $token")
        val started = System.nanoTime()
        val result = client.newCall(builder.build()).await().use { response ->
            val data = response.body?.string().orEmpty()
            log(method, Redactor.url(url.toString()), response.code, (System.nanoTime() - started) / 1_000_000, data.toByteArray().size.toLong())
            Triple(response.code, data, response.header("Location"))
        }
        val (status, data, redirect) = result
        if (status in 300..399) {
            val next = redirect?.let(url::resolve) ?: throw ApiException(status, "重定向缺少有效地址")
            check(policy(next, authenticated)) { "重定向目标不在允许的服务列表中" }
            // Mutations are never replayed across redirects, even for 307/308.
            throw ApiException(status, "服务器地址发生重定向，请稍后重试")
        }
        if (status == 401 && authenticated && allowRefresh) {
            refresh(token!!)
            return send(url, method, body, true, false)
        }
        val value = if (data.isBlank()) obj() else runCatching { wireJson.parseToJsonElement(data) }.getOrElse {
            throw ApiException(status, if (status in 200..299) "服务器返回了无效 JSON" else "服务器错误（$status）")
        }
        if (status !in 200..299) throw ApiException(status, value.str("message", "detail", "error") ?: "服务器错误（$status）")
        return value
    }
    private suspend fun refresh(rejected: String) = refreshMutex.withLock {
        val current = session.tokens.value ?: throw ApiException(401, "登录状态已失效")
        if (current.accessToken != rejected) return@withLock
        val refresh = current.refreshToken ?: run { session.clear(); throw ApiException(401, "登录状态已失效，请重新登录") }
        try {
            val value = send(baseURL.toHttpUrl().resolve("/refresh")!!, "POST", obj("refresh_token" to refresh).toString().toRequestBody("application/json".toMediaType()), false, false)
            val tokens = AuthTokens.parse(value) ?: throw IOException("刷新响应缺少令牌")
            // A logout or another login during the request must not be undone.
            if (session.tokens.value == current) session.save(tokens)
            else if (session.tokens.value == null) throw ApiException(401, "登录已结束")
        } catch (e: ApiException) {
            if (e.status in setOf(400, 401, 403) && session.tokens.value == current) session.clear()
            throw e
        }
    }
    override suspend fun latestRelease(): GitHubRelease {
        val value = request("/repos/Ccat-Q/XDYL-Updater2-Android/releases/latest", authenticated = false, base = "https://api.github.com")
        return wireJson.decodeFromJsonElement(value)
    }
}
