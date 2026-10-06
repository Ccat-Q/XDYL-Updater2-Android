package com.ccatq.xdylupdater2.downloads

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.work.*
import com.ccatq.xdylupdater2.core.NetworkPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class DownloadRepository(private val context: Context, private val dao: DownloadDao) {
    private val lock = Mutex()
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val root: File get() = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) { "下载存储不可用" }
    val records: Flow<List<DownloadRecord>> = dao.observe()
    private fun staging(id: String) = File(root, "staging/$id.partial")
    private fun completed(record: DownloadRecord) = File(root, "completed/${record.id}/${record.filename}")
    suspend fun enqueue(url: String, filename: String, expected: String?, cellular: Boolean): String = withContext(Dispatchers.IO) {
        require(NetworkPolicy.download(url)) { "仅支持 HTTPS 或官方旧 HTTP 资源端口" }
        if (!expected.isNullOrBlank()) require(Regex("[a-fA-F0-9]{64}").matches(expected)) { "清单 SHA-256 格式无效" }
        lock.withLock {
            val id = UUID.randomUUID().toString()
            val record = DownloadRecord(id, url, FileIntegrity.safeName(filename), expected?.lowercase(), System.currentTimeMillis(), cellular = cellular)
            dao.put(record)
            try {
                staging(id).parentFile!!.mkdirs()
                val request = DownloadManager.Request(Uri.parse(url)).setTitle(record.filename)
                    .setDescription("星灯云浪资源下载").setAllowedOverMetered(cellular).setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "staging/$id.partial")
                val systemId = manager.enqueue(request)
                dao.put(record.copy(systemId = systemId, state = DownloadState.downloading.name))
            } catch (e: Exception) { dao.put(record.copy(state = DownloadState.failed.name, message = e.message ?: "无法创建下载任务")) }
            id
        }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val record = dao.find(id) ?: return@withLock
            WorkManager.getInstance(context).cancelUniqueWork("verify-$id")
            record.systemId?.let { manager.remove(it) }
            staging(id).delete(); completed(record).parentFile?.deleteRecursively()
            dao.delete(id)
        }
    }
    suspend fun clear() { dao.all().forEach { delete(it.id) } }
    suspend fun retry(record: DownloadRecord, cellular: Boolean) = enqueue(record.remoteURL, record.filename, record.expectedSHA256, cellular)
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        lock.withLock {
            for (original in dao.all()) {
                if (original.state == DownloadState.completed.name) {
                    if (!completed(original).isFile) dao.put(original.copy(state = DownloadState.failed.name, localPath = null, message = "已下载文件不存在，请重试"))
                    continue
                }
                if (original.state == DownloadState.failed.name) continue
                var record = original
                if (record.systemId == null) {
                    manager.query(DownloadManager.Query())?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)).orEmpty()
                            if (uri.endsWith("/${record.id}.partial")) {
                                record = record.copy(systemId = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)))
                                dao.put(record); break
                            }
                        }
                    }
                }
                val systemId = record.systemId
                if (systemId == null) { dao.put(record.copy(state = DownloadState.failed.name, message = "上次任务未成功提交，请重试")); continue }
                manager.query(DownloadManager.Query().setFilterById(systemId))?.use { cursor ->
                    if (!cursor.moveToFirst()) { dao.put(record.copy(state = DownloadState.failed.name, message = "系统任务已移除，请重试")); return@use }
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            dao.put(record.copy(state = DownloadState.verifying.name, downloaded = bytes, total = total))
                            scheduleVerification(record.id)
                        }
                        DownloadManager.STATUS_FAILED -> dao.put(record.copy(state = DownloadState.failed.name, message = "系统下载失败（${cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))}）", downloaded = bytes, total = total))
                        else -> dao.put(record.copy(state = DownloadState.downloading.name, downloaded = bytes, total = total, message = if (status == DownloadManager.STATUS_PAUSED) "等待网络或系统调度" else null))
                    }
                }
            }
        }
    }
    suspend fun verify(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val record = dao.find(id) ?: return@withLock
            if (record.state != DownloadState.verifying.name) return@withLock
            val systemId = record.systemId ?: return@withLock
            val successful = manager.query(DownloadManager.Query().setFilterById(systemId))?.use {
                it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
            } ?: false
            if (!successful) return@withLock
            try {
                val target = completed(record)
                // Atomic move can have succeeded before a process death and DB commit.
                if (target.isFile) {
                    if (!record.expectedSHA256.isNullOrBlank()) require(FileIntegrity.sha256(target) { coroutineContext.ensureActive() }.equals(record.expectedSHA256, true)) { "文件校验失败" }
                } else FileIntegrity.commit(staging(id), target, record.expectedSHA256) { coroutineContext.ensureActive() }
                dao.put(record.copy(state = DownloadState.completed.name, localPath = target.path, downloaded = target.length(), total = target.length(), message = if (record.expectedSHA256.isNullOrBlank()) "已完成（清单未提供校验值）" else "SHA-256 校验通过"))
            } catch (e: CancellationException) {
                throw e // Keep a verified file for reconciliation if cancellation follows the atomic move.
            } catch (e: Exception) {
                completed(record).delete()
                dao.put(record.copy(state = DownloadState.failed.name, localPath = null, message = e.message ?: "文件无法保存"))
            }
        }
    }
    suspend fun shareable(id: String): File = withContext(Dispatchers.IO) {
        val record = dao.find(id) ?: error("下载记录不存在")
        check(record.state == DownloadState.completed.name) { "文件尚未校验完成" }
        completed(record).also { check(it.isFile) { "文件不存在" } }
    }
    private fun scheduleVerification(id: String) {
        val work = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf("record" to id)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("verify-$id", ExistingWorkPolicy.KEEP, work)
    }
    fun scheduleReconciliation() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("downloads-reconcile", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<DownloadWorker>(15, TimeUnit.MINUTES).build())
    }
}
