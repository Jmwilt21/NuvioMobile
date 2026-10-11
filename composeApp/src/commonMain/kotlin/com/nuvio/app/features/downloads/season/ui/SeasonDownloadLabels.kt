package com.nuvio.app.features.downloads.season.ui

import androidx.compose.runtime.Composable
import com.nuvio.app.features.downloads.season.SeasonDownloadPreference
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun seasonDownloadSeasonLabel(seasonNumber: Int): String =
    if (seasonNumber <= 0) {
        stringResource(Res.string.episodes_specials)
    } else {
        stringResource(Res.string.episodes_season, seasonNumber)
    }

@Composable
internal fun seasonDownloadPreferenceLabel(preference: SeasonDownloadPreference): String = stringResource(
    when (preference) {
        SeasonDownloadPreference.AddonOrder -> Res.string.season_download_preference_addon_order
        SeasonDownloadPreference.Smaller -> Res.string.season_download_preference_smaller
        SeasonDownloadPreference.HigherQuality -> Res.string.season_download_preference_higher
    },
)

@Composable
internal fun seasonDownloadPreferenceDescription(preference: SeasonDownloadPreference, maxResolution: Int): String =
    when (preference) {
        SeasonDownloadPreference.AddonOrder ->
            stringResource(Res.string.season_download_preference_addon_order_description)
        SeasonDownloadPreference.Smaller ->
            stringResource(Res.string.season_download_preference_smaller_description)
        SeasonDownloadPreference.HigherQuality -> stringResource(
            Res.string.season_download_preference_higher_description,
            stringResource(Res.string.season_download_settings_resolution_value, maxResolution),
        )
    }
