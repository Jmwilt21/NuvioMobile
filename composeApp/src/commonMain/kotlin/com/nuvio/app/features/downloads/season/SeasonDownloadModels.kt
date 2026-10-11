package com.nuvio.app.features.downloads.season

import kotlinx.serialization.Serializable

/** How the stream for each queued episode is picked from the addon's results. */
@Serializable
enum class SeasonDownloadPreference {
    /** Top result exactly as the addon ranks it. */
    AddonOrder,

    /** Smallest file that is still 1080p (then 720p). */
    Smaller,

    /** Largest file at the highest resolution up to the configured cap. */
    HigherQuality,
}

@Serializable
enum class SeasonQueueStatus {
    Queued,
    Resolving,
    Downloading,

    /** Android refused to start a background transfer; continues when the app is opened. */
    WaitingForApp,
    Failed,
}

@Serializable
enum class SeasonQueueFailure {
    NoStreams,
    DownloadFailed,
    Network,

    /** The link answered with a tiny placeholder (for example a debrid proxy's error clip) instead of the episode. */
    BadFile,
}

@Serializable
data class SeasonQueueEntry(
    val id: String,
    val contentType: String,
    val videoId: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val addonId: String? = null,
    val preference: SeasonDownloadPreference = SeasonDownloadPreference.AddonOrder,
    val status: SeasonQueueStatus = SeasonQueueStatus.Queued,
    /** Id of the [com.nuvio.app.features.downloads.DownloadItem] once the transfer exists. */
    val downloadId: String? = null,
    /** 0 = first try, 1 = same stream with a fresh link, 2 = next stream in the list. */
    val attempt: Int = 0,
    /** Identity of the file picked last time, used to decide if a partial file can be continued. */
    val streamFileKey: String? = null,
    /** File size the addon advertised for the picked stream, used to spot placeholder downloads. */
    val expectedSizeBytes: Long? = null,
    /** The transfer was cut off by the system (not paused by the user) and should continue by itself. */
    val interrupted: Boolean = false,
    val failure: SeasonQueueFailure? = null,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long = 0L,
) {
    val logicalContentKey: String
        get() = "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"

    val holdsSlot: Boolean
        get() = status == SeasonQueueStatus.Resolving || status == SeasonQueueStatus.Downloading
}

@Serializable
data class SeasonDownloadSettings(
    /** Stream addon instance id; null means the first installed stream addon. */
    val addonId: String? = null,
    val maxConcurrent: Int = 1,
    val preference: SeasonDownloadPreference = SeasonDownloadPreference.AddonOrder,
    /** Resolution cap (vertical pixels) for [SeasonDownloadPreference.HigherQuality]. */
    val maxResolution: Int = 1080,
) {
    val effectiveMaxConcurrent: Int
        get() = maxConcurrent.coerceIn(MIN_CONCURRENT, MAX_CONCURRENT)

    companion object {
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 3
        val RESOLUTION_CAPS = listOf(720, 1080, 1440, 2160)
    }
}

data class SeasonDownloadShow(
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String?,
    val poster: String?,
    val background: String?,
)

data class SeasonDownloadEpisode(
    val videoId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String?,
    val thumbnail: String?,
)

data class SeasonStreamAddonOption(
    val addonId: String,
    val name: String,
)
