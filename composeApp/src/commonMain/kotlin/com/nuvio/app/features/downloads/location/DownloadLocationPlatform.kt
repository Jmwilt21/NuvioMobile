package com.nuvio.app.features.downloads.location

import androidx.compose.runtime.Composable

/**
 * User-chosen download folder. When unset (or unsupported) downloads stay in the app's
 * private storage exactly as upstream does.
 */
internal expect object DownloadLocationPlatform {
    val isSupported: Boolean

    /** Absolute path of the chosen folder, or null when the private default is used. */
    fun currentPath(): String?

    fun hasStorageAccess(): Boolean

    fun requestStorageAccess()

    fun clear()
}

/** Returns a launcher for the system folder picker. [onResult] gets true when a folder was stored. */
@Composable
internal expect fun rememberDownloadFolderPicker(onResult: (Boolean) -> Unit): () -> Unit
