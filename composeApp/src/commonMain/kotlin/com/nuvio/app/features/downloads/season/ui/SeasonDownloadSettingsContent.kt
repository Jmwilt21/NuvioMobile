package com.nuvio.app.features.downloads.season.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.location.DownloadLocationPlatform
import com.nuvio.app.features.downloads.location.rememberDownloadFolderPicker
import com.nuvio.app.features.downloads.season.SeasonBackgroundAccess
import com.nuvio.app.features.downloads.season.SeasonDownloadPreference
import com.nuvio.app.features.downloads.season.SeasonDownloadSettings
import com.nuvio.app.features.downloads.season.SeasonDownloadSettingsRepository
import com.nuvio.app.features.downloads.season.SeasonStreamResolver
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private enum class SeasonSettingsDialog { Addon, Preference, Resolution, Concurrency }

internal fun LazyListScope.seasonDownloadSettingsContent(isTablet: Boolean) {
    item {
        val settings by remember {
            SeasonDownloadSettingsRepository.ensureLoaded()
            SeasonDownloadSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val addons = remember { SeasonStreamResolver.streamAddonOptions() }
        var openDialog by remember { mutableStateOf<SeasonSettingsDialog?>(null) }

        // Permission and folder state change outside the app (system settings, folder picker).
        var refreshTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000L)
                refreshTick += 1
            }
        }
        val folderPath = remember(refreshTick) { DownloadLocationPlatform.currentPath() }
        val hasStorageAccess = remember(refreshTick) { DownloadLocationPlatform.hasStorageAccess() }
        val backgroundGranted = remember(refreshTick) { SeasonBackgroundAccess.isGranted() }
        val accessNeededText = stringResource(Res.string.season_download_settings_folder_access_needed)
        val folderUnsupportedText = stringResource(Res.string.season_download_settings_folder_unsupported)
        val pickFolder = rememberDownloadFolderPicker { stored ->
            if (!stored) NuvioToastController.show(folderUnsupportedText)
            refreshTick += 1
        }

        Spacer(
            modifier = Modifier.height(
                if (isTablet) NuvioTokens.Space.s18 else MaterialTheme.nuvio.spacing.listGap,
            ),
        )
        SettingsSection(
            title = stringResource(Res.string.season_download_settings_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.season_download_addon_title),
                    description = addons.firstOrNull { it.addonId == settings.addonId }?.name
                        ?: stringResource(Res.string.season_download_settings_addon_default),
                    isTablet = isTablet,
                    onClick = { openDialog = SeasonSettingsDialog.Addon },
                )
                SettingsNavigationRow(
                    title = stringResource(Res.string.season_download_preference_title),
                    description = seasonDownloadPreferenceLabel(settings.preference),
                    isTablet = isTablet,
                    onClick = { openDialog = SeasonSettingsDialog.Preference },
                )
                SettingsNavigationRow(
                    title = stringResource(Res.string.season_download_settings_resolution_title),
                    description = stringResource(Res.string.season_download_settings_resolution_value, settings.maxResolution),
                    isTablet = isTablet,
                    onClick = { openDialog = SeasonSettingsDialog.Resolution },
                )
                SettingsNavigationRow(
                    title = stringResource(Res.string.season_download_settings_concurrency_title),
                    description = stringResource(
                        Res.string.season_download_settings_concurrency_value,
                        settings.effectiveMaxConcurrent,
                    ),
                    isTablet = isTablet,
                    onClick = { openDialog = SeasonSettingsDialog.Concurrency },
                )
                if (DownloadLocationPlatform.isSupported) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.season_download_settings_folder_title),
                        description = when {
                            folderPath == null -> stringResource(Res.string.season_download_settings_folder_default)
                            hasStorageAccess -> folderPath
                            else -> stringResource(Res.string.season_download_settings_folder_no_access, folderPath)
                        },
                        isTablet = isTablet,
                        onClick = {
                            if (hasStorageAccess) {
                                pickFolder()
                            } else {
                                NuvioToastController.show(accessNeededText, durationMillis = 5_000L)
                                DownloadLocationPlatform.requestStorageAccess()
                            }
                        },
                    )
                    if (folderPath != null) {
                        SettingsNavigationRow(
                            title = stringResource(Res.string.season_download_settings_folder_reset),
                            description = stringResource(Res.string.season_download_settings_folder_reset_description),
                            isTablet = isTablet,
                            onClick = {
                                DownloadLocationPlatform.clear()
                                refreshTick += 1
                            },
                        )
                    }
                }
                if (SeasonBackgroundAccess.isSupported) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.season_download_settings_background_title),
                        description = if (backgroundGranted) {
                            stringResource(Res.string.season_download_settings_background_granted)
                        } else {
                            stringResource(Res.string.season_download_settings_background_description)
                        },
                        isTablet = isTablet,
                        onClick = { SeasonBackgroundAccess.request() },
                    )
                }
            }
        }

        when (openDialog) {
            SeasonSettingsDialog.Addon -> SeasonChoiceDialog(
                title = stringResource(Res.string.season_download_addon_title),
                options = listOf<Pair<String?, String>>(
                    null to stringResource(Res.string.season_download_settings_addon_default),
                ) + addons.map { it.addonId to it.name },
                selected = settings.addonId.takeIf { id -> addons.any { it.addonId == id } },
                onSelected = SeasonDownloadSettingsRepository::setAddonId,
                onDismiss = { openDialog = null },
            )
            SeasonSettingsDialog.Preference -> SeasonChoiceDialog(
                title = stringResource(Res.string.season_download_preference_title),
                options = SeasonDownloadPreference.entries.map { it to seasonDownloadPreferenceLabel(it) },
                selected = settings.preference,
                onSelected = SeasonDownloadSettingsRepository::setPreference,
                onDismiss = { openDialog = null },
            )
            SeasonSettingsDialog.Resolution -> SeasonChoiceDialog(
                title = stringResource(Res.string.season_download_settings_resolution_title),
                options = SeasonDownloadSettings.RESOLUTION_CAPS.map {
                    it to stringResource(Res.string.season_download_settings_resolution_value, it)
                },
                selected = settings.maxResolution,
                onSelected = SeasonDownloadSettingsRepository::setMaxResolution,
                onDismiss = { openDialog = null },
            )
            SeasonSettingsDialog.Concurrency -> SeasonChoiceDialog(
                title = stringResource(Res.string.season_download_settings_concurrency_title),
                options = (SeasonDownloadSettings.MIN_CONCURRENT..SeasonDownloadSettings.MAX_CONCURRENT).map {
                    it to stringResource(Res.string.season_download_settings_concurrency_value, it)
                },
                selected = settings.effectiveMaxConcurrent,
                onSelected = SeasonDownloadSettingsRepository::setMaxConcurrent,
                onDismiss = { openDialog = null },
            )
            null -> Unit
        }
    }
}

@Composable
private fun <T> SeasonChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
    ) {
        options.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = value == selected,
                        role = Role.RadioButton,
                        onClick = {
                            onSelected(value)
                            onDismiss()
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                )
            }
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
        }
    }
}
