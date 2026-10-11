package com.nuvio.app.features.downloads.location

import androidx.compose.runtime.Composable

internal actual object DownloadLocationPlatform {
    actual val isSupported: Boolean = false

    actual fun currentPath(): String? = null

    actual fun hasStorageAccess(): Boolean = false

    actual fun requestStorageAccess() = Unit

    actual fun clear() = Unit
}

@Composable
internal actual fun rememberDownloadFolderPicker(onResult: (Boolean) -> Unit): () -> Unit = { onResult(false) }
