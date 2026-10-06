package com.ccatq.xdylupdater2.developer

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.ccatq.xdylupdater2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable data class DeveloperSession(val id: String = UUID.randomUUID().toString(), val time: Long = System.currentTimeMillis(), val method: String, val url: String, val authenticated: Boolean, val requestBody: String?, val status: Int?, val responseHeaders: Map<String, String>, val responseBody: String, val durationMs: Long, val error: String? = null) {
    fun redacted() = copy(url = Redactor.url(url), requestBody = requestBody?.let(Redactor::text), responseHeaders = Redactor.headers(responseHeaders), responseBody = Redactor.text(responseBody), error = error?.let { "请求失败；详细错误仅见原文会话" })
}
@Serializable data class DeveloperEnvironment(val name: String, val baseURL: String)
@Serializable data class PerformanceSnapshot(val time: Long, val battery: Int, val charging: Boolean, val thermal: Int?, val powerSave: Boolean, val freeDisk: Long, val physicalMemory: Long, val processors: Int)
data class DeveloperDraft(val base: String = Environment.api, val path: String = "/user/profile", val query: String = "", val method: String = "GET", val body: String = "{}", val authenticated: Boolean = false, val uploadField: String = "file")
data class KnownRoute(val group: String, val title: String, val path: String, val method: String, val authenticated: Boolean, val base: String, val query: String = "")

class DeveloperRepository(private val context: Context, private val secrets: SecretFiles, private val tokens: TokenStore) {
    private val lock = Mutex()
    private val mutableSessions = MutableStateFlow(load<List<DeveloperSession>>("developer-sessions") ?: emptyList())
    val sessions = mutableSessions.asStateFlow()
    private val mutableEnvironments = MutableStateFlow(load<List<DeveloperEnvironment>>("developer-environments") ?: emptyList())
    val environments = mutableEnvironments.asStateFlow()
    private val mutablePerformance = MutableStateFlow(load<List<PerformanceSnapshot>>("performance") ?: emptyList())
    val performance = mutablePerformance.asStateFlow()
    private val mutableLogs = MutableStateFlow<List<String>>(emptyList())
    val logs = mutableLogs.asStateFlow()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    private inline fun <reified T> load(name: String): T? = secrets.read(name)?.let { runCatching { wireJson.decodeFromString<T>(it) }.getOrNull() }
    @Synchronized fun record(method: String, url: String, status: Int, duration: Long, bytes: Long) {
        mutableLogs.value = (mutableLogs.value + "${System.currentTimeMillis()} $method ${Redactor.url(url)} → $status · ${duration}ms · ${bytes}B").takeLast(300)
    }
    suspend fun execute(draft: DeveloperDraft, file: File? = null, filename: String = "upload.bin"): DeveloperSession {
        val base = draft.base.trim().toHttpUrl()
        require(base.username.isEmpty() && base.password.isEmpty()) { "地址不能包含用户名或密码" }
        val url = (base.toString().trimEnd('/') + "/" + draft.path.trimStart('/')).toHttpUrl().newBuilder().apply {
            if (draft.query.isNotBlank()) encodedQuery(draft.query.trim().removePrefix("?"))
        }.build()
        require(url.username.isEmpty() && url.password.isEmpty())
        require(draft.method in setOf("GET", "POST", "PUT", "DELETE"))
        val body = if (draft.method == "GET") null else if (file != null) {
            val rb = file.asRequestBody("application/octet-stream".toMediaType())
            MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart(draft.uploadField, filename, rb).build()
        } else {
            wireJson.parseToJsonElement(draft.body)
            draft.body.toRequestBody("application/json; charset=utf-8".toMediaType())
        }
        val request = Request.Builder().url(url).method(draft.method, body).header("User-Agent", "StarWave-Android/2.1.9")
        if (draft.authenticated) request.header("Authorization", "Bearer ${tokens.tokens.value?.accessToken ?: error("请先登录")}")
        val started = System.nanoTime()
        val result = try {
            client.newCall(request.build()).await().use { r ->
                DeveloperSession(method = draft.method, url = url.toString(), authenticated = draft.authenticated, requestBody = if (file != null) "[文件：$filename；${file.length()} 字节]" else body?.let { draft.body }, status = r.code, responseHeaders = r.headers.toMultimap().mapValues { it.value.joinToString("\n") }, responseBody = r.body?.string().orEmpty(), durationMs = (System.nanoTime() - started) / 1_000_000)
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            DeveloperSession(method = draft.method, url = url.toString(), authenticated = draft.authenticated, requestBody = body?.let { draft.body }, status = null, responseHeaders = emptyMap(), responseBody = "", durationMs = (System.nanoTime() - started) / 1_000_000, error = e.message)
        }
        lock.withLock {
            val list = (mutableSessions.value + result).takeLast(20)
            withContext(Dispatchers.IO) { secrets.write("developer-sessions", wireJson.encodeToString(list)) }
            mutableSessions.value = list
        }
        return result
    }
    suspend fun saveEnvironment(name: String, base: String) {
        require(name.isNotBlank()); base.toHttpUrl()
        lock.withLock {
            val list = mutableEnvironments.value.filterNot { it.name == name } + DeveloperEnvironment(name.trim(), base.trim())
            withContext(Dispatchers.IO) { secrets.write("developer-environments", wireJson.encodeToString(list)) }
            mutableEnvironments.value = list
        }
    }
    suspend fun deleteEnvironment(name: String) = lock.withLock {
        val list = mutableEnvironments.value.filterNot { it.name == name }
        withContext(Dispatchers.IO) { secrets.write("developer-environments", wireJson.encodeToString(list)) }
        mutableEnvironments.value = list
    }
    suspend fun sample() = withContext(Dispatchers.IO) {
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val snapshot = PerformanceSnapshot(System.currentTimeMillis(), battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), battery.isCharging, if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else null, power.isPowerSaveMode, StatFs(context.filesDir.path).availableBytes, memory.totalMem, Runtime.getRuntime().availableProcessors())
        lock.withLock {
            val list = (mutablePerformance.value + snapshot).takeLast(300)
            secrets.write("performance", wireJson.encodeToString(list)); mutablePerformance.value = list
        }
    }
    suspend fun export(raw: Boolean): File = withContext(Dispatchers.IO) {
        val rows = if (raw) sessions.value else sessions.value.map { it.redacted() }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Keep raw and redacted exports separate; remove earlier raw exports.
        dir.listFiles()?.filter { it.name.startsWith("developer-raw-") && System.currentTimeMillis() - it.lastModified() > 300_000 }?.forEach { it.delete() }
        File(dir, "developer-${if (raw) "raw" else "redacted"}-${UUID.randomUUID()}.json").apply { writeText(wireJson.encodeToString(rows)) }
    }
    suspend fun exportLogs(): File = withContext(Dispatchers.IO) {
        File(File(context.cacheDir, "exports").apply { mkdirs() }, "network-log.txt").apply { writeText(logs.value.joinToString("\n")) }
    }
    suspend fun clear(kind: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            when (kind) {
                "sessions" -> { secrets.delete("developer-sessions"); mutableSessions.value = emptyList(); File(context.cacheDir, "exports").deleteRecursively() }
                "performance" -> { secrets.delete("performance"); mutablePerformance.value = emptyList() }
                "environments" -> { secrets.delete("developer-environments"); mutableEnvironments.value = emptyList() }
                "logs" -> mutableLogs.value = emptyList()
            }
        }
    }
}
