package com.nuvio.app.features.downloads.location

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.nuvio.app.features.downloads.DownloadItem
import java.io.File

internal actual object DownloadLocationPlatform {
    private const val preferencesName = "nuvio_download_location"
    private const val rootPathKey = "root_path"
    private const val stagingDirectoryName = ".nuvio-staging"
    private const val moviesDirectoryName = "Movies"
    private const val showsDirectoryName = "TV"

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 30

    actual fun currentPath(): String? = appContext?.let(::storedPath)

    actual fun hasStorageAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    actual fun requestStorageAccess() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < 30) return
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        intents.any { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.isSuccess
        }
    }

    actual fun clear() {
        val context = appContext ?: return
        preferences(context).edit().remove(rootPathKey).apply()
    }

    internal fun store(context: Context, path: String) {
        initialize(context)
        preferences(context).edit().putString(rootPathKey, path).apply()
    }

    /** Folder that holds in-progress `.part` files. Same volume as the final folder so finishing is a rename. */
    fun stagingDirectory(context: Context): File {
        val root = usableRoot(context) ?: return privateDirectory(context)
        val staging = File(root, stagingDirectoryName)
        if (staging.isDirectory || staging.mkdirs()) {
            runCatching { File(staging, ".nomedia").createNewFile() }
            return staging
        }
        return privateDirectory(context)
    }

    /** Final file for [item]: `<root>/TV/<show>/Season 01/<file>` or `<root>/Movies/<title>/<file>`. */
    fun destinationFile(context: Context, item: DownloadItem): File {
        val root = usableRoot(context) ?: return File(privateDirectory(context), item.fileName)
        val titleDirectory = item.title.toFolderName()
        val season = item.seasonNumber
        val directory = if (item.isEpisode && season != null) {
            val seasonDirectory = if (season <= 0) "Specials" else "Season ${season.toString().padStart(2, '0')}"
            File(File(File(root, showsDirectoryName), titleDirectory), seasonDirectory)
        } else {
            File(File(root, moviesDirectoryName), titleDirectory)
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            return File(privateDirectory(context), item.fileName)
        }
        return File(directory, item.fileName.replace("_${item.id}.", "."))
    }

    private fun usableRoot(context: Context): File? {
        // No custom folder: behave exactly like upstream without touching shared storage.
        val root = storedPath(context)?.let(::File) ?: return null
        if (!hasStorageAccess()) return null
        return root.takeIf { (it.isDirectory || it.mkdirs()) && it.canWrite() }
    }

    private fun storedPath(context: Context): String? =
        preferences(context).getString(rootPathKey, null)?.takeIf { it.isNotBlank() }

    private fun privateDirectory(context: Context): File = File(context.filesDir, "downloads")

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
}

@Composable
internal actual fun rememberDownloadFolderPicker(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.toTreeFilePath()
        if (path != null) DownloadLocationPlatform.store(context, path)
        onResult(path != null)
    }
    return { runCatching { launcher.launch(null) }.onFailure { onResult(false) } }
}

/** Maps a picker result on local storage (`primary:Movies/Nuvio`) to a plain file path. */
private fun Uri.toTreeFilePath(): String? {
    if (authority != "com.android.externalstorage.documents") return null
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull() ?: return null
    val volume = documentId.substringBefore(':')
    val relativePath = documentId.substringAfter(':', "")
    val volumeRoot = if (volume.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory()
    } else {
        File("/storage/$volume")
    }
    return (if (relativePath.isBlank()) volumeRoot else File(volumeRoot, relativePath)).absolutePath
}

private fun String.toFolderName(): String =
    replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.')
        .take(120)
        .ifBlank { "Unknown" }
