package com.ccatq.xdylupdater2

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import com.ccatq.xdylupdater2.core.*
import com.ccatq.xdylupdater2.developer.DeveloperRepository
import com.ccatq.xdylupdater2.downloads.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AppGraph(context: Context, apiOverride: RemoteApi? = null) {
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val secrets = SecretFiles(application)
    val session = SessionStore(secrets)
    val preferences = Preferences(application)
    val developer = DeveloperRepository(application, secrets, session)
    val api: RemoteApi = apiOverride ?: ApiClient(session, log = developer::record)
    private val database = Room.databaseBuilder(application, DownloadDatabase::class.java, "downloads.db").build()
    val downloads = DownloadRepository(application, database.downloads())
    val foreground = MutableStateFlow(false)
    val performanceVisible = MutableStateFlow(false)
    init {
        downloads.scheduleReconciliation()
        scope.launch { runCatching { downloads.reconcile() } }
        scope.launch {
            combine(preferences.settings, foreground, performanceVisible) { settings, active, visible -> settings.developer && active && (settings.continuous || visible) }
                .distinctUntilChanged().collectLatest { enabled ->
                    if (enabled) while (currentCoroutineContext().isActive) { runCatching { developer.sample() }; delay(2_000) }
                }
        }
    }
}
class StarWaveApplication : Application(), DefaultLifecycleObserver {
    val graph by lazy { AppGraph(this) }
    override fun onCreate() {
        super.onCreate()
        graph
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }
    override fun onStart(owner: LifecycleOwner) { graph.foreground.value = true }
    override fun onStop(owner: LifecycleOwner) { graph.foreground.value = false }
}
