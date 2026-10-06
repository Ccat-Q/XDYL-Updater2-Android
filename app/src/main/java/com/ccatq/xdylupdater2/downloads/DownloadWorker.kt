package com.ccatq.xdylupdater2.downloads

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import com.ccatq.xdylupdater2.StarWaveApplication
import kotlinx.coroutines.CancellationException

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as StarWaveApplication).graph.downloads
        return try {
            inputData.getString("record")?.let { repository.verify(it) } ?: repository.reconcile()
            Result.success()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        // Never trust broadcast status or file paths; reconcile only owned DB IDs against DownloadManager.
        WorkManager.getInstance(context).enqueueUniqueWork("downloads-completion", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<DownloadWorker>().build())
    }
}
