package com.nuvio.app.features.downloads.season

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.downloads.location.DownloadLocationPlatform

internal actual object SeasonDownloadStorage {
    private const val preferencesName = "nuvio_season_downloads"
    private const val queueKey = "season_queue_payload"
    private const val settingsKey = "season_download_settings"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadQueue(): String? = preferences?.getString(ProfileScopedKey.of(queueKey), null)

    actual fun saveQueue(payload: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of(queueKey), payload)?.apply()
    }

    actual fun loadSettings(): String? = preferences?.getString(settingsKey, null)

    actual fun saveSettings(payload: String) {
        preferences?.edit()?.putString(settingsKey, payload)?.apply()
    }
}

/** Single entry point wired from MainActivity for everything the season download feature needs on Android. */
object SeasonDownloadsAndroid {
    private var registered = false

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        SeasonDownloadStorage.initialize(appContext)
        SeasonQueuePlatform.initialize(appContext)
        DownloadLocationPlatform.initialize(appContext)
        if (registered) return
        registered = true
        (appContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    SeasonDownloadQueue.ensureStarted()
                    SeasonDownloadQueue.onAppForeground()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
