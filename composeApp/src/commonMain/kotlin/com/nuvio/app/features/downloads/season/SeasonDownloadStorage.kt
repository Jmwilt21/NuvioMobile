package com.nuvio.app.features.downloads.season

internal expect object SeasonDownloadStorage {
    fun loadQueue(): String?
    fun saveQueue(payload: String)
    fun loadSettings(): String?
    fun saveSettings(payload: String)
}

/**
 * Optional OS permission that lets the queue start the next transfer while the app is in the
 * background. Without it the queue continues the next time the app is opened.
 */
internal expect object SeasonBackgroundAccess {
    val isSupported: Boolean
    fun isGranted(): Boolean
    fun request()
}
