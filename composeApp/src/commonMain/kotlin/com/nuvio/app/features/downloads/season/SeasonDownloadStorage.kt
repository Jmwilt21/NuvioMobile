package com.nuvio.app.features.downloads.season

internal expect object SeasonDownloadStorage {
    fun loadQueue(): String?
    fun saveQueue(payload: String)
    fun loadSettings(): String?
    fun saveSettings(payload: String)
}

/**
 * Keeps the queue going while the app is in the background, where the OS may refuse to start
 * new transfers.
 */
internal expect object SeasonQueuePlatform {
    /** Called while there is queue work left; starts or refreshes whatever keeps the process alive. */
    fun keepAlive(pendingEpisodes: Int)

    /** Called once the queue has nothing left to do. */
    fun release()

    /** Runs a download the OS refused to start in the background. Returns false when that is not possible. */
    fun takeOver(downloadId: String): Boolean
}
