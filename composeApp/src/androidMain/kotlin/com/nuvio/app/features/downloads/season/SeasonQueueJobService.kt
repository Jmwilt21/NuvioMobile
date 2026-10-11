package com.nuvio.app.features.downloads.season

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.nuvio.app.core.deeplink.buildDownloadsDeepLinkUrl
import com.nuvio.app.features.downloads.AndroidDownloadTransfer
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsLiveStatusPlatform
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.settings.AppIconPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.season_queue_notification_text
import nuvio.composeapp.generated.resources.season_queue_notification_title
import org.jetbrains.compose.resources.getString

internal actual object SeasonQueuePlatform {
    private const val jobNamespace = "nuvio-season-queue"
    private const val jobId = 1
    internal const val channelId = "season_download_queue"
    internal const val notificationId = 0x5EA50

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun keepAlive(pendingEpisodes: Int) {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < 34) return
        SeasonQueueJobService.pendingEpisodes = pendingEpisodes
        SeasonQueueJobService.active?.let {
            it.refreshIdleNotification()
            return
        }
        val scheduler = jobScheduler(context)
        if (scheduler.getPendingJob(jobId) != null) return
        val job = JobInfo.Builder(jobId, ComponentName(context, SeasonQueueJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        // Only succeeds while the app is visible; from the background the queue waits for the app instead.
        runCatching { scheduler.schedule(job) }
    }

    actual fun release() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < 34) return
        SeasonQueueJobService.active?.finish() ?: runCatching { jobScheduler(context).cancel(jobId) }
    }

    actual fun takeOver(downloadId: String): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return SeasonQueueJobService.active?.takeOver(downloadId) == true
    }

    @RequiresApi(34)
    private fun jobScheduler(context: Context): JobScheduler =
        context.getSystemService(JobScheduler::class.java).forNamespace(jobNamespace)
}

/**
 * One long-running user-initiated job that represents the whole season queue.
 *
 * Android only lets an app start a transfer job while it is visible. This job is started at that
 * moment and then stays alive, so when a later episode's turn comes while the app is in the
 * background the transfer is carried out here, with the regular download engine, instead of in a
 * new job that Android would refuse.
 */
@RequiresApi(34)
class SeasonQueueJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var params: JobParameters? = null
    private var run: Job? = null
    private var current: AndroidDownloadTransfer? = null

    override fun onStartJob(params: JobParameters): Boolean {
        // In a process the system started only for this job the app is not initialised; wait for the app.
        if (!SeasonDownloadQueue.isStarted) return false
        this.params = params
        active = this
        ensureChannel()
        setNotification(params, SeasonQueuePlatform.notificationId, idleNotification(), JOB_END_NOTIFICATION_POLICY_REMOVE)
        SeasonDownloadQueue.onKeeperStarted()
        return true
    }

    override fun onNetworkChanged(params: JobParameters) {
        this.params = params
        current?.let(::execute)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val interrupted = current
        run?.cancel()
        run = null
        current = null
        this.params = null
        if (active === this) active = null
        if (interrupted != null) {
            // Leave a clean paused state; the queue resumes it when it can run again.
            SeasonDownloadQueue.onKeeperTransferInterrupted(
                downloadId = interrupted.item.id,
                byUser = params.stopReason == JobParameters.STOP_REASON_USER,
            )
            DownloadsPlatformDownloader.scheduler(this).pause(interrupted.item.fileName)
        }
        return params.stopReason != JobParameters.STOP_REASON_USER && SeasonDownloadQueue.hasPendingWork()
    }

    override fun onDestroy() {
        if (active === this) active = null
        scope.cancel()
        super.onDestroy()
    }

    internal fun finish() {
        val params = params ?: return
        if (current != null) return
        this.params = null
        if (active === this) active = null
        jobFinished(params, false)
    }

    internal fun refreshIdleNotification() {
        val params = params ?: return
        if (current == null) {
            setNotification(params, SeasonQueuePlatform.notificationId, idleNotification(), JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
    }

    /** Runs a download here that Android refused to start as its own background job. */
    internal fun takeOver(downloadId: String): Boolean {
        if (params == null || current != null) return false
        val item = DownloadsRepository.uiState.value.items.firstOrNull { it.id == downloadId } ?: return false
        if (item.status != DownloadStatus.Failed && item.status != DownloadStatus.Paused) return false
        val scheduler = DownloadsPlatformDownloader.scheduler(this)
        // Registering the transfer as already running makes the repository attach to it without
        // asking Android for another job.
        val transfer = scheduler.store.begin(item)
        DownloadsRepository.resumeDownload(downloadId)
        if (scheduler.store.get(item.fileName)?.generation != transfer.generation) return false
        execute(transfer)
        return true
    }

    private fun execute(transfer: AndroidDownloadTransfer) {
        val scheduler = DownloadsPlatformDownloader.scheduler(this)
        val fileName = transfer.item.fileName
        current = transfer
        run?.cancel()
        run = scope.launch {
            DownloadsLiveStatusPlatform.initialize(this@SeasonQueueJobService)
            var attempt = 0
            while (true) {
                val jobParams = params ?: return@launch
                val latest = scheduler.store.get(fileName)?.takeIf { it.generation == transfer.generation } ?: break
                if (!scheduler.isActive(latest)) break
                setNotification(
                    jobParams,
                    DownloadsLiveStatusPlatform.notificationId(latest.item.id),
                    DownloadsLiveStatusPlatform.buildNotification(this@SeasonQueueJobService, latest.item),
                    JOB_END_NOTIFICATION_POLICY_REMOVE,
                )
                val retry = coroutineScope {
                    val work = async {
                        scheduler.execute(latest, jobParams.network) { updated ->
                            updated.item.totalBytes?.let { updateEstimatedNetworkBytes(jobParams, it, 0L) }
                            updateTransferredNetworkBytes(jobParams, updated.item.downloadedBytes, 0L)
                            DownloadsLiveStatusPlatform.notifyTransfer(updated.item)
                        }
                    }
                    // Pausing or deleting from the app has no job of its own to cancel here, so watch for it.
                    val watcher = launch {
                        scheduler.store.transfers.first { transfers ->
                            val stored = transfers[fileName]
                            stored == null || stored.generation != transfer.generation ||
                                stored.item.status != DownloadStatus.Downloading
                        }
                        work.cancel()
                    }
                    val result = runCatching { work.await() }
                    watcher.cancel()
                    result.getOrElse { error ->
                        if (error !is CancellationException) throw error
                        ensureActive()
                        false
                    }
                }
                if (!retry) break
                attempt += 1
                delay((RETRY_BASE_DELAY_MS shl (attempt - 1).coerceAtMost(4)))
            }
            val finished = scheduler.store.get(fileName)?.takeIf { it.generation == transfer.generation }
            scheduler.notifyCurrent(fileName)
            current = null
            if (finished?.item?.status == DownloadStatus.Paused) {
                SeasonDownloadQueue.onKeeperTransferInterrupted(downloadId = transfer.item.id, byUser = true)
            }
            refreshIdleNotification()
            SeasonDownloadQueue.onKeeperIdle()
        }
    }

    private fun idleNotification(): Notification {
        val launchIntent = Intent().apply {
            component = AppIconPlatform.currentLauncherComponent(this@SeasonQueueJobService)
            action = Intent.ACTION_VIEW
            data = Uri.parse(buildDownloadsDeepLinkUrl())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return NotificationCompat.Builder(this, SeasonQueuePlatform.channelId)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(runBlocking { getString(Res.string.season_queue_notification_title) })
            .setContentText(runBlocking { getString(Res.string.season_queue_notification_text, pendingEpisodes) })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    SeasonQueuePlatform.notificationId,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(SeasonQueuePlatform.channelId) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                SeasonQueuePlatform.channelId,
                runBlocking { getString(Res.string.season_queue_notification_title) },
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val RETRY_BASE_DELAY_MS = 30_000L

        @Volatile
        internal var active: SeasonQueueJobService? = null

        @Volatile
        internal var pendingEpisodes: Int = 0
    }
}
