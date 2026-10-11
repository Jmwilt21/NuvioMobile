package com.nuvio.app.features.downloads.season

import com.nuvio.app.features.streams.StreamItem

/**
 * Orders an addon's streams for unattended downloading. The first element is the stream to try,
 * the rest are fallbacks. Streams that are not downloadable are dropped.
 */
internal object SeasonStreamRanking {
    private const val MIN_DECENT_RESOLUTION = 1080
    private const val FALLBACK_RESOLUTION = 720

    fun rank(
        streams: List<StreamItem>,
        preference: SeasonDownloadPreference,
        maxResolution: Int,
        isDownloadable: (StreamItem) -> Boolean,
    ): List<StreamItem> {
        val candidates = streams.filter(isDownloadable)
        if (candidates.isEmpty() || preference == SeasonDownloadPreference.AddonOrder) return candidates

        val described = candidates.map { stream ->
            DescribedStream(
                stream = stream,
                resolution = stream.detectedResolution(),
                sizeBytes = stream.detectedSizeBytes(),
                uncached = stream.looksUncached(),
            )
        }
        val comparable = described.filter { !it.uncached && it.resolution != null && it.sizeBytes != null }
        val preferred = when (preference) {
            SeasonDownloadPreference.Smaller -> {
                val decent = comparable.filter { it.resolution!! >= MIN_DECENT_RESOLUTION }
                    .sortedWith(compareBy<DescribedStream> { it.sizeBytes }.thenBy { it.resolution })
                val fallback = comparable.filter { it.resolution!! in FALLBACK_RESOLUTION until MIN_DECENT_RESOLUTION }
                    .sortedBy { it.sizeBytes }
                decent + fallback
            }
            SeasonDownloadPreference.HigherQuality ->
                comparable.filter { it.resolution!! <= maxResolution }
                    .sortedWith(
                        compareByDescending<DescribedStream> { it.resolution }.thenByDescending { it.sizeBytes },
                    )
            SeasonDownloadPreference.AddonOrder -> emptyList()
        }.map { it.stream }

        // Anything the smart ordering could not judge keeps the addon's own order as a fallback.
        return preferred + candidates.filterNot { it in preferred }
    }

    /** Stable identity of the file behind a stream, when the addon exposes one. */
    fun fileKey(stream: StreamItem): String? {
        val fileName = stream.behaviorHints.filename?.trim()?.takeIf { it.isNotEmpty() }
        val size = stream.behaviorHints.videoSize?.takeIf { it > 0L }
        if (fileName == null && size == null) return null
        return "${fileName.orEmpty()}|${size ?: ""}"
    }

    internal fun StreamItem.detectedResolution(): Int? {
        val text = searchableText()
        return when {
            resolution2160.containsMatchIn(text) -> 2160
            resolution1440.containsMatchIn(text) -> 1440
            resolution1080.containsMatchIn(text) -> 1080
            resolution720.containsMatchIn(text) -> 720
            resolution480.containsMatchIn(text) -> 480
            else -> null
        }
    }

    internal fun StreamItem.detectedSizeBytes(): Long? {
        behaviorHints.videoSize?.takeIf { it > 0L }?.let { return it }
        val match = sizePattern.find(searchableText()) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase().first()) {
            't' -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            'g' -> 1024.0 * 1024.0 * 1024.0
            'm' -> 1024.0 * 1024.0
            else -> return null
        }
        return (value * multiplier).toLong().takeIf { it > 0L }
    }

    internal fun StreamItem.looksUncached(): Boolean {
        val text = searchableText()
        return '⏳' in text || uncachedPattern.containsMatchIn(text)
    }

    private fun StreamItem.searchableText(): String =
        listOfNotNull(name, title, description, behaviorHints.filename).joinToString("\n")

    private data class DescribedStream(
        val stream: StreamItem,
        val resolution: Int?,
        val sizeBytes: Long?,
        val uncached: Boolean,
    )

    private val resolution2160 = Regex("(?i)(?<![a-z0-9])(2160p?|4k|uhd)(?![a-z0-9])")
    private val resolution1440 = Regex("(?i)(?<![a-z0-9])(1440p?|2k|qhd)(?![a-z0-9])")
    private val resolution1080 = Regex("(?i)(?<![a-z0-9])(1080[pi]?|fhd)(?![a-z0-9])")
    private val resolution720 = Regex("(?i)(?<![a-z0-9])720p?(?![a-z0-9])")
    private val resolution480 = Regex("(?i)(?<![a-z0-9])(480p?|sd)(?![a-z0-9])")
    private val sizePattern = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(tb|tib|gb|gib|mb|mib)(?![a-z])")
    private val uncachedPattern = Regex("(?i)(?<![a-z])(uncached|not cached)(?![a-z])")
}
