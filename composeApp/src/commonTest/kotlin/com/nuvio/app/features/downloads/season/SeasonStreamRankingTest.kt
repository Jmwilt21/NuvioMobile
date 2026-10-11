package com.nuvio.app.features.downloads.season

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonStreamRankingTest {
    private val gb = 1024L * 1024L * 1024L

    @Test
    fun `addon order keeps the addon ranking and drops undownloadable streams`() {
        val streams = listOf(
            stream("torrent", url = null),
            stream("4K 2160p", size = 20 * gb),
            stream("1080p", size = 2 * gb),
        )
        assertEquals(
            listOf("4K 2160p", "1080p"),
            rank(streams, SeasonDownloadPreference.AddonOrder),
        )
    }

    @Test
    fun `smaller prefers the smallest 1080p then 720p then addon order`() {
        val streams = listOf(
            stream("2160p REMUX", size = 40 * gb),
            stream("1080p WEB big", size = 6 * gb),
            stream("720p WEB", size = 1 * gb),
            stream("1080p WEB small", size = 2 * gb),
            stream("no details"),
        )
        assertEquals(
            listOf("1080p WEB small", "1080p WEB big", "2160p REMUX", "720p WEB", "no details"),
            rank(streams, SeasonDownloadPreference.Smaller),
        )
    }

    @Test
    fun `higher quality respects the resolution cap`() {
        val streams = listOf(
            stream("720p", size = 1 * gb),
            stream("2160p", size = 30 * gb),
            stream("1080p small", size = 2 * gb),
            stream("1080p large", size = 8 * gb),
        )
        assertEquals(
            listOf("1080p large", "1080p small", "720p", "2160p"),
            rank(streams, SeasonDownloadPreference.HigherQuality, maxResolution = 1080),
        )
        assertEquals("2160p", rank(streams, SeasonDownloadPreference.HigherQuality, maxResolution = 2160).first())
    }

    @Test
    fun `uncached results are never preferred by the smart modes`() {
        val streams = listOf(
            stream("1080p cached", size = 5 * gb),
            stream("1080p ⏳ tiny", size = 1 * gb),
        )
        assertEquals(
            listOf("1080p cached", "1080p ⏳ tiny"),
            rank(streams, SeasonDownloadPreference.Smaller),
        )
    }

    @Test
    fun `size falls back to the description text`() {
        val streams = listOf(
            stream("1080p", description = "💾 4.5 GB"),
            stream("1080p", description = "💾 900 MB", title = "second"),
        )
        assertEquals("second", SeasonStreamRanking.rank(streams, SeasonDownloadPreference.Smaller, 1080) { true }.first().title)
    }

    @Test
    fun `file key needs a filename or size`() {
        assertNull(SeasonStreamRanking.fileKey(stream("1080p")))
        assertEquals(
            SeasonStreamRanking.fileKey(stream("a", size = 5, fileName = "show.s01e01.mkv")),
            SeasonStreamRanking.fileKey(stream("b", size = 5, fileName = "show.s01e01.mkv")),
        )
    }

    @Test
    fun `tiny or far too small downloads count as placeholders`() {
        val mb = 1024L * 1024L
        assertTrue(SeasonStreamRanking.isPlaceholderDownload(20 * 1024L, expectedBytes = 270 * mb))
        assertTrue(SeasonStreamRanking.isPlaceholderDownload(20 * 1024L, expectedBytes = null))
        assertTrue(SeasonStreamRanking.isPlaceholderDownload(8 * mb, expectedBytes = 1 * gb))
        assertFalse(SeasonStreamRanking.isPlaceholderDownload(268 * mb, expectedBytes = 270 * mb))
        // A size hint that describes a whole season pack must not reject a normal episode.
        assertFalse(SeasonStreamRanking.isPlaceholderDownload(300 * mb, expectedBytes = 20 * gb))
        assertFalse(SeasonStreamRanking.isPlaceholderDownload(8 * mb, expectedBytes = null))
    }

    private fun rank(
        streams: List<StreamItem>,
        preference: SeasonDownloadPreference,
        maxResolution: Int = 1080,
    ): List<String?> = SeasonStreamRanking.rank(streams, preference, maxResolution) { it.url != null }.map { it.name }

    private fun stream(
        name: String,
        url: String? = "https://example.com/$name",
        size: Long? = null,
        fileName: String? = null,
        description: String? = null,
        title: String? = null,
    ) = StreamItem(
        name = name,
        title = title,
        description = description,
        url = url,
        addonName = "Addon",
        addonId = "addon:test",
        behaviorHints = StreamBehaviorHints(videoSize = size, filename = fileName),
    )
}
