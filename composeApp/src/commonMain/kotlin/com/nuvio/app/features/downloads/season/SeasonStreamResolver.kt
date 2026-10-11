package com.nuvio.app.features.downloads.season

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.streams.InstalledStreamAddonTarget
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream

/** Thrown when streams could not be fetched for a reason that is worth retrying later. */
internal class SeasonStreamUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Fetches streams for one episode from a single installed addon, right when they are needed. */
internal object SeasonStreamResolver {
    /** Installed, enabled addons that declare a stream resource. */
    fun streamAddonOptions(): List<SeasonStreamAddonOption> =
        AddonRepository.uiState.value.addons.enabledAddons().mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            if (manifest.resources.none { it.name == "stream" }) return@mapNotNull null
            SeasonStreamAddonOption(
                addonId = addon.streamAddonInstanceId(manifest.id),
                name = addon.displayTitle.ifBlank { manifest.name },
            )
        }

    suspend fun rankedStreams(entry: SeasonQueueEntry, settings: SeasonDownloadSettings): List<StreamItem> {
        val target = targetAddon(entry)
            ?: throw SeasonStreamUnavailableException("No stream addon is available for ${entry.videoId}")
        val url = buildAddonResourceUrl(
            manifestUrl = target.manifest.transportUrl,
            resource = "stream",
            type = entry.contentType,
            id = entry.videoId,
        )
        val streams = try {
            StreamParser.parse(
                payload = fetchAddonResponseText(url = url, forceRefresh = true),
                addonName = target.addonName,
                addonId = target.addonId,
                addonLogo = target.manifest.logoUrl,
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw SeasonStreamUnavailableException(error.message ?: "Stream request failed", error)
        }
        return SeasonStreamRanking.rank(
            streams = streams,
            preference = entry.preference,
            maxResolution = settings.maxResolution,
            isDownloadable = ::isDownloadable,
        )
    }

    /** Turns a ranked stream into one with a direct http(s) link, or null when that is not possible. */
    suspend fun toPlayable(stream: StreamItem, entry: SeasonQueueEntry): StreamItem? {
        if (!DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
            return stream.takeIf { it.playableDirectUrl.isHttpFileUrl() }
        }
        val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
            stream = stream,
            season = entry.seasonNumber,
            episode = entry.episodeNumber,
        )
        return (resolved as? DirectDebridPlayableResult.Success)?.stream
            ?.takeIf { it.playableDirectUrl.isHttpFileUrl() }
    }

    private fun isDownloadable(stream: StreamItem): Boolean =
        stream.playableDirectUrl.isHttpFileUrl() ||
            DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)

    private fun targetAddon(entry: SeasonQueueEntry): InstalledStreamAddonTarget? {
        val targets = AddonRepository.uiState.value.addons.enabledAddons().mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            if (!manifest.supportsStream(entry.contentType, entry.videoId)) return@mapNotNull null
            InstalledStreamAddonTarget(
                addonName = addon.displayTitle.ifBlank { manifest.name },
                addonId = addon.streamAddonInstanceId(manifest.id),
                manifest = manifest,
            )
        }
        return targets.firstOrNull { it.addonId == entry.addonId } ?: targets.firstOrNull()
    }
}

private fun String?.isHttpFileUrl(): Boolean {
    val normalized = this?.trim()?.lowercase() ?: return false
    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false
    val path = normalized.substringBefore('?').substringBefore('#')
    return !path.endsWith(".m3u8") && !path.endsWith(".mpd") && !path.endsWith(".torrent")
}
