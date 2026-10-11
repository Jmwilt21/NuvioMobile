package com.nuvio.app.features.downloads.season

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object SeasonDownloadStorage {
    private const val queueKey = "season_queue_payload"
    private const val settingsKey = "season_download_settings"

    actual fun loadQueue(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(queueKey))

    actual fun saveQueue(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(queueKey))
    }

    actual fun loadSettings(): String? = NSUserDefaults.standardUserDefaults.stringForKey(settingsKey)

    actual fun saveSettings(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = settingsKey)
    }
}

internal actual object SeasonQueuePlatform {
    actual fun keepAlive(pendingEpisodes: Int) = Unit

    actual fun release() = Unit

    actual fun takeOver(downloadId: String): Boolean = false
}
