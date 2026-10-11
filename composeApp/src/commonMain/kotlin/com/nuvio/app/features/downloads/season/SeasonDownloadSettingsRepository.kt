package com.nuvio.app.features.downloads.season

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SeasonDownloadSettingsRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(SeasonDownloadSettings())
    val uiState: StateFlow<SeasonDownloadSettings> = _uiState.asStateFlow()
    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        val payload = SeasonDownloadStorage.loadSettings().orEmpty().trim()
        if (payload.isEmpty()) return
        runCatching { json.decodeFromString<SeasonDownloadSettings>(payload) }
            .getOrNull()
            ?.let { _uiState.value = it }
    }

    fun snapshot(): SeasonDownloadSettings {
        ensureLoaded()
        return _uiState.value
    }

    fun setAddonId(addonId: String?) = update { it.copy(addonId = addonId) }

    fun setMaxConcurrent(value: Int) = update {
        it.copy(maxConcurrent = value.coerceIn(SeasonDownloadSettings.MIN_CONCURRENT, SeasonDownloadSettings.MAX_CONCURRENT))
    }

    fun setPreference(preference: SeasonDownloadPreference) = update { it.copy(preference = preference) }

    fun setMaxResolution(value: Int) = update { it.copy(maxResolution = value) }

    private fun update(transform: (SeasonDownloadSettings) -> SeasonDownloadSettings) {
        ensureLoaded()
        val updated = transform(_uiState.value)
        if (updated == _uiState.value) return
        _uiState.value = updated
        SeasonDownloadStorage.saveSettings(json.encodeToString(updated))
        SeasonDownloadQueue.onSettingsChanged()
    }
}
