package com.nuvio.app.features.downloads.season

import co.touchlab.kermit.Logger
import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsClock
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Queue that feeds episodes one after another into the existing [DownloadsRepository].
 *
 * An episode only becomes a real download when its turn comes: the stream link is fetched at that
 * moment, so links never go stale while waiting. A download that is paused keeps its slot, which
 * means the queue never leaves more partial files behind than the configured concurrency.
 */
object SeasonDownloadQueue {
    private const val MAX_ATTEMPT = 2
    private const val MAX_FETCH_RETRIES = 6
    private const val FETCH_RETRY_BASE_DELAY_MS = 15_000L
    private const val FETCH_RETRY_MAX_DELAY_MS = 5 * 60_000L
    // Messages the platform downloader reports when Android will not start a transfer from the background.
    private val backgroundStartRefusedMarkers = listOf("could not start the download", "user-initiated", "user initiated")

    private val log = Logger.withTag("SeasonDownloadQueue")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _entries = MutableStateFlow<List<SeasonQueueEntry>>(emptyList())
    val entries: StateFlow<List<SeasonQueueEntry>> = _entries.asStateFlow()

    private val resolveJobs = mutableMapOf<String, Job>()
    private var hasStarted = false
    private var loadedProfileId: Int? = null
    private var nextOrdinal = 0L

    fun ensureStarted() {
        if (hasStarted) {
            reloadIfProfileChanged()
            return
        }
        hasStarted = true
        SeasonDownloadSettingsRepository.ensureLoaded()
        DownloadsRepository.ensureLoaded()
        loadFromDisk()
        scope.launch {
            DownloadsRepository.uiState.collect { state -> onDownloadsChanged(state.items) }
        }
    }

    /** Adds episodes to the queue. Returns how many were added (already downloaded or queued ones are skipped). */
    fun enqueue(
        show: SeasonDownloadShow,
        episodes: List<SeasonDownloadEpisode>,
        preference: SeasonDownloadPreference,
        addonId: String?,
    ): Int {
        ensureStarted()
        val taken = takenContentKeys()
        val now = DownloadsClock.nowEpochMs()
        val additions = episodes
            .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            .mapNotNull { episode ->
                val entry = SeasonQueueEntry(
                    id = nextEntryId(now),
                    contentType = show.contentType,
                    videoId = episode.videoId,
                    parentMetaId = show.parentMetaId,
                    parentMetaType = show.parentMetaType,
                    title = show.title,
                    logo = show.logo,
                    poster = show.poster,
                    background = show.background,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    episodeTitle = episode.title,
                    episodeThumbnail = episode.thumbnail,
                    addonId = addonId,
                    preference = preference,
                    createdAtEpochMs = now,
                )
                entry.takeIf { taken.add(it.logicalContentKey) }
            }
        if (additions.isEmpty()) return 0
        publish(_entries.value + additions)
        pump()
        return additions.size
    }

    /** Content keys (`metaId|season|episode`) that are already downloaded, downloading or queued. */
    fun takenContentKeys(): MutableSet<String> {
        ensureStarted()
        val keys = _entries.value.mapTo(mutableSetOf()) { it.logicalContentKey }
        DownloadsRepository.uiState.value.items
            .filter { it.status != DownloadStatus.Failed }
            .mapTo(keys) { it.logicalContentKey }
        return keys
    }

    fun cancel(entryId: String) {
        val entry = _entries.value.firstOrNull { it.id == entryId } ?: return
        remove(listOf(entry))
    }

    fun cancelSeason(parentMetaId: String, seasonNumber: Int) {
        remove(_entries.value.filter { it.parentMetaId == parentMetaId && it.seasonNumber == seasonNumber })
    }

    fun retry(entryId: String) {
        mutate(entryId) { it.copy(status = SeasonQueueStatus.Queued, attempt = 0, failure = null, errorMessage = null) }
        pump()
    }

    /** Called when the app returns to the foreground: Android allows starting transfers again. */
    fun onAppForeground() {
        if (!hasStarted) return
        reloadIfProfileChanged()
        // Also give episodes another go that gave up because the addon was unreachable (e.g. offline).
        val resumed = _entries.value.map { entry ->
            val unreachable = entry.status == SeasonQueueStatus.Failed && entry.failure == SeasonQueueFailure.Network
            if (entry.status == SeasonQueueStatus.WaitingForApp || unreachable) {
                entry.copy(status = SeasonQueueStatus.Queued, failure = null, errorMessage = null)
            } else {
                entry
            }
        }
        if (resumed != _entries.value) publish(resumed)
        pump()
    }

    internal fun onSettingsChanged() {
        if (hasStarted) pump()
    }

    private fun remove(targets: List<SeasonQueueEntry>) {
        if (targets.isEmpty()) return
        val ids = targets.mapTo(mutableSetOf()) { it.id }
        ids.forEach { resolveJobs.remove(it)?.cancel() }
        publish(_entries.value.filterNot { it.id in ids })
        targets.mapNotNull { it.downloadId }.forEach(DownloadsRepository::cancelDownload)
        pump()
    }

    private fun onDownloadsChanged(items: List<DownloadItem>) {
        if (reloadIfProfileChanged()) return
        val itemsById = items.associateBy { it.id }
        var changed = false
        val updated = _entries.value.mapNotNull { entry ->
            val downloadId = entry.downloadId ?: return@mapNotNull entry
            val item = itemsById[downloadId]
            val next = when {
                // Removed from the downloads screen while it was ours: the user cancelled it.
                item == null -> if (entry.status == SeasonQueueStatus.Resolving) entry else null
                item.status == DownloadStatus.Completed -> null
                item.status == DownloadStatus.Failed && entry.status == SeasonQueueStatus.Downloading ->
                    entry.afterTransferFailure(item)
                // Retried by hand from the downloads screen.
                item.status != DownloadStatus.Failed &&
                    (entry.status == SeasonQueueStatus.Failed || entry.status == SeasonQueueStatus.WaitingForApp) ->
                    entry.copy(status = SeasonQueueStatus.Downloading, failure = null, errorMessage = null)
                else -> entry
            }
            if (next != entry) changed = true
            next
        }
        if (changed) publish(updated)
        // Episodes that ran out of fallbacks are shown once, in the queue section, with a retry button.
        updated.filter { it.status == SeasonQueueStatus.Failed && it.downloadId != null }.forEach { entry ->
            val downloadId = entry.downloadId ?: return@forEach
            mutate(entry.id) { it.copy(downloadId = null) }
            DownloadsRepository.cancelDownload(downloadId)
        }
        pump()
    }

    private fun SeasonQueueEntry.afterTransferFailure(item: DownloadItem): SeasonQueueEntry {
        val message = item.errorMessage
        if (message != null && backgroundStartRefusedMarkers.any { message.contains(it, ignoreCase = true) }) {
            return copy(status = SeasonQueueStatus.WaitingForApp, errorMessage = null)
        }
        val nextAttempt = attempt + 1
        return if (nextAttempt > MAX_ATTEMPT) {
            copy(status = SeasonQueueStatus.Failed, failure = SeasonQueueFailure.DownloadFailed, errorMessage = message)
        } else {
            log.i { "Retrying $logicalContentKey (attempt $nextAttempt): $message" }
            copy(status = SeasonQueueStatus.Queued, attempt = nextAttempt, errorMessage = message)
        }
    }

    private fun pump() {
        if (!hasStarted) return
        // Starting more transfers is pointless while Android is refusing them in the background.
        if (_entries.value.any { it.status == SeasonQueueStatus.WaitingForApp }) return
        val limit = SeasonDownloadSettingsRepository.snapshot().effectiveMaxConcurrent
        var active = _entries.value.count { it.holdsSlot }
        for (entry in _entries.value) {
            if (active >= limit) break
            if (entry.status != SeasonQueueStatus.Queued) continue
            active += 1
            start(entry)
        }
    }

    private fun start(entry: SeasonQueueEntry) {
        mutate(entry.id) { it.copy(status = SeasonQueueStatus.Resolving) }
        resolveJobs.remove(entry.id)?.cancel()
        resolveJobs[entry.id] = scope.launch {
            try {
                resolveAndStart(entry.id)
            } finally {
                if (resolveJobs[entry.id] === coroutineContext[Job]) resolveJobs.remove(entry.id)
            }
            onDownloadsChanged(DownloadsRepository.uiState.value.items)
        }
    }

    private suspend fun resolveAndStart(entryId: String) {
        var fetchFailures = 0
        while (true) {
            val entry = _entries.value.firstOrNull { it.id == entryId } ?: return
            val settings = SeasonDownloadSettingsRepository.snapshot()
            val ranked = try {
                SeasonStreamResolver.rankedStreams(entry, settings)
            } catch (error: CancellationException) {
                throw error
            } catch (error: SeasonStreamUnavailableException) {
                fetchFailures += 1
                log.w { "Stream fetch failed for ${entry.logicalContentKey} ($fetchFailures): ${error.message}" }
                if (fetchFailures >= MAX_FETCH_RETRIES) {
                    mutate(entryId) {
                        it.copy(status = SeasonQueueStatus.Failed, failure = SeasonQueueFailure.Network, errorMessage = error.message)
                    }
                    return
                }
                delay((FETCH_RETRY_BASE_DELAY_MS shl (fetchFailures - 1)).coerceAtMost(FETCH_RETRY_MAX_DELAY_MS))
                continue
            }

            // Attempts 0 and 1 use the top stream (1 = same stream, freshly fetched link); 2 moves on.
            val candidates = if (entry.attempt >= MAX_ATTEMPT) ranked.drop(1) else ranked
            for (candidate in candidates) {
                val playable = SeasonStreamResolver.toPlayable(candidate, entry) ?: continue
                if (startTransfer(entry, playable)) return
            }
            mutate(entryId) {
                it.copy(status = SeasonQueueStatus.Failed, failure = SeasonQueueFailure.NoStreams, errorMessage = null)
            }
            return
        }
    }

    private fun startTransfer(entry: SeasonQueueEntry, stream: StreamItem): Boolean {
        val fileKey = SeasonStreamRanking.fileKey(stream)
        val existingId = entry.downloadId
        val sameFile = fileKey != null && fileKey == entry.streamFileKey
        if (existingId != null && sameFile && DownloadsRepository.resumeWithFreshSource(existingId, stream)) {
            mutate(entry.id) { it.copy(status = SeasonQueueStatus.Downloading) }
            return true
        }

        val result = DownloadsRepository.enqueueFromStream(
            contentType = entry.contentType,
            videoId = entry.videoId,
            parentMetaId = entry.parentMetaId,
            parentMetaType = entry.parentMetaType,
            title = entry.title,
            logo = entry.logo,
            poster = entry.poster,
            background = entry.background,
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
            episodeTitle = entry.episodeTitle,
            episodeThumbnail = entry.episodeThumbnail,
            stream = stream,
        )
        if (result != DownloadEnqueueResult.Started && result != DownloadEnqueueResult.Replaced) return false
        val item = DownloadsRepository.uiState.value.items
            .firstOrNull { it.logicalContentKey == entry.logicalContentKey }
            ?: return false
        mutate(entry.id) {
            it.copy(status = SeasonQueueStatus.Downloading, downloadId = item.id, streamFileKey = fileKey)
        }
        return true
    }

    private fun mutate(entryId: String, transform: (SeasonQueueEntry) -> SeasonQueueEntry) {
        var changed = false
        val updated = _entries.value.map { entry ->
            if (entry.id != entryId) return@map entry
            transform(entry).also { if (it != entry) changed = true }
        }
        if (changed) publish(updated)
    }

    private fun publish(entries: List<SeasonQueueEntry>) {
        _entries.value = entries
        SeasonDownloadStorage.saveQueue(json.encodeToString(StoredSeasonQueue(entries)))
    }

    private fun reloadIfProfileChanged(): Boolean {
        if (loadedProfileId == ProfileRepository.activeProfileId) return false
        loadFromDisk()
        return true
    }

    private fun loadFromDisk() {
        resolveJobs.values.forEach(Job::cancel)
        resolveJobs.clear()
        loadedProfileId = ProfileRepository.activeProfileId
        val payload = SeasonDownloadStorage.loadQueue().orEmpty().trim()
        val stored = if (payload.isEmpty()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<StoredSeasonQueue>(payload).entries }.getOrDefault(emptyList())
        }
        // A link fetch that was interrupted by the app closing simply starts over.
        _entries.value = stored.map { entry ->
            if (entry.status == SeasonQueueStatus.Resolving) entry.copy(status = SeasonQueueStatus.Queued) else entry
        }
        onDownloadsChanged(DownloadsRepository.uiState.value.items)
    }

    private fun nextEntryId(nowEpochMs: Long): String {
        nextOrdinal += 1L
        return "sq_${nowEpochMs.toString(36)}_${nextOrdinal.toString(36)}"
    }
}

@Serializable
private data class StoredSeasonQueue(
    val entries: List<SeasonQueueEntry> = emptyList(),
)
