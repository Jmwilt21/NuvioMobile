package com.nuvio.app.features.downloads.season.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.downloads.season.SeasonDownloadEpisode
import com.nuvio.app.features.downloads.season.SeasonDownloadPreference
import com.nuvio.app.features.downloads.season.SeasonDownloadQueue
import com.nuvio.app.features.downloads.season.SeasonDownloadSettingsRepository
import com.nuvio.app.features.downloads.season.SeasonDownloadShow
import com.nuvio.app.features.downloads.season.SeasonStreamResolver
import com.nuvio.app.features.watched.releasedEpisodesForSeason
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** "Download season" / "Select episodes" actions shown above a season's episode list. */
@Composable
internal fun SeasonDownloadEntryRow(
    meta: MetaDetails,
    seasonNumber: Int,
    modifier: Modifier = Modifier,
) {
    val episodes = remember(meta, seasonNumber) { meta.seasonDownloadEpisodes(seasonNumber) }
    if (episodes.isEmpty()) return

    var preselectAll by remember(meta.id, seasonNumber) { mutableStateOf<Boolean?>(null) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = { preselectAll = true },
            label = { Text(stringResource(Res.string.season_download_action_season)) },
            leadingIcon = { Icon(imageVector = Icons.Rounded.Download, contentDescription = null) },
        )
        AssistChip(
            onClick = { preselectAll = false },
            label = { Text(stringResource(Res.string.season_download_action_select)) },
            leadingIcon = { Icon(imageVector = Icons.Rounded.Checklist, contentDescription = null) },
        )
    }

    preselectAll?.let { selectAll ->
        SeasonDownloadSheet(
            show = SeasonDownloadShow(
                contentType = meta.type,
                parentMetaId = meta.id,
                parentMetaType = meta.type,
                title = meta.name,
                logo = meta.logo,
                poster = meta.poster,
                background = meta.background,
            ),
            seasonNumber = seasonNumber,
            episodes = episodes,
            preselectAll = selectAll,
            onDismiss = { preselectAll = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SeasonDownloadSheet(
    show: SeasonDownloadShow,
    seasonNumber: Int,
    episodes: List<SeasonDownloadEpisode>,
    preselectAll: Boolean,
    onDismiss: () -> Unit,
) {
    val settings by remember {
        SeasonDownloadSettingsRepository.ensureLoaded()
        SeasonDownloadSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val queueEntries by remember {
        SeasonDownloadQueue.ensureStarted()
        SeasonDownloadQueue.entries
    }.collectAsStateWithLifecycle()
    val addons = remember { SeasonStreamResolver.streamAddonOptions() }
    val takenKeys = remember(queueEntries, episodes) { SeasonDownloadQueue.takenContentKeys() }
    val availableEpisodes = remember(episodes, takenKeys) {
        episodes.filterNot { show.contentKey(it) in takenKeys }
    }

    var preference by remember { mutableStateOf(settings.preference) }
    var addonId by remember { mutableStateOf(addons.firstOrNull { it.addonId == settings.addonId }?.addonId ?: addons.firstOrNull()?.addonId) }
    var selectedVideoIds by remember {
        mutableStateOf(if (preselectAll) availableEpisodes.mapTo(mutableSetOf()) { it.videoId } else emptySet<String>())
    }
    val selectedEpisodes = availableEpisodes.filter { it.videoId in selectedVideoIds }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        coroutineScope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }
    val addedTemplate = stringResource(Res.string.season_download_added)
    val nothingAddedText = stringResource(Res.string.season_download_nothing_added)

    NuvioModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(
                        Res.string.season_download_sheet_title,
                        seasonDownloadSeasonLabel(seasonNumber),
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = show.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NuvioBottomSheetDivider()

            SheetLabel(stringResource(Res.string.season_download_preference_title))
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeasonDownloadPreference.entries.forEach { option ->
                    FilterChip(
                        selected = option == preference,
                        onClick = { preference = option },
                        label = { Text(seasonDownloadPreferenceLabel(option)) },
                    )
                }
            }
            Text(
                text = seasonDownloadPreferenceDescription(preference, settings.maxResolution),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (addons.size > 1) {
                SheetLabel(stringResource(Res.string.season_download_addon_title))
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    addons.forEach { addon ->
                        FilterChip(
                            selected = addon.addonId == addonId,
                            onClick = { addonId = addon.addonId },
                            label = { Text(text = addon.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.season_download_episodes_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                val allSelected = availableEpisodes.isNotEmpty() && selectedEpisodes.size == availableEpisodes.size
                TextButton(
                    enabled = availableEpisodes.isNotEmpty(),
                    onClick = {
                        selectedVideoIds = if (allSelected) {
                            emptySet()
                        } else {
                            availableEpisodes.mapTo(mutableSetOf()) { it.videoId }
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (allSelected) Res.string.season_download_select_none else Res.string.season_download_select_all,
                        ),
                    )
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items(items = episodes, key = { it.videoId }) { episode ->
                    val taken = show.contentKey(episode) in takenKeys
                    val checked = episode.videoId in selectedVideoIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !taken) {
                                selectedVideoIds = if (checked) {
                                    selectedVideoIds - episode.videoId
                                } else {
                                    selectedVideoIds + episode.videoId
                                }
                            }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked || taken, onCheckedChange = null, enabled = !taken, modifier = Modifier.padding(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = episode.displayLabel(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (taken) {
                                Text(
                                    text = stringResource(Res.string.season_download_episode_taken),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (addons.isEmpty()) {
                Text(
                    text = stringResource(Res.string.season_download_no_addon),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                enabled = selectedEpisodes.isNotEmpty() && addons.isNotEmpty(),
                onClick = {
                    SeasonDownloadSettingsRepository.setPreference(preference)
                    val added = SeasonDownloadQueue.enqueue(
                        show = show,
                        episodes = selectedEpisodes,
                        preference = preference,
                        addonId = addonId,
                    )
                    NuvioToastController.show(
                        if (added > 0) addedTemplate.replace("%1\$d", added.toString()) else nothingAddedText,
                    )
                    dismiss()
                },
            ) {
                Text(stringResource(Res.string.season_download_confirm, selectedEpisodes.size))
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun SeasonDownloadShow.contentKey(episode: SeasonDownloadEpisode): String =
    "${parentMetaId.trim()}|${episode.seasonNumber}|${episode.episodeNumber}"

private fun SeasonDownloadEpisode.displayLabel(): String =
    title?.takeIf { it.isNotBlank() }?.let { "$episodeNumber. $it" } ?: episodeNumber.toString()

private fun MetaDetails.seasonDownloadEpisodes(seasonNumber: Int): List<SeasonDownloadEpisode> =
    releasedEpisodesForSeason(
        seasonNumber = seasonNumber,
        todayIsoDate = CurrentDateProvider.todayIsoDate(),
    ).mapNotNull { video ->
        val episodeNumber = video.episode ?: return@mapNotNull null
        val season = video.season ?: seasonNumber
        val playbackVideoId = buildPlaybackVideoId(
            parentMetaId = id,
            seasonNumber = season,
            episodeNumber = episodeNumber,
            fallbackVideoId = video.id,
        )
        SeasonDownloadEpisode(
            videoId = video.id.takeIf { it.isNotBlank() } ?: playbackVideoId,
            seasonNumber = season,
            episodeNumber = episodeNumber,
            title = video.title.takeIf { it.isNotBlank() },
            thumbnail = video.thumbnail,
        )
    }.distinctBy { it.videoId }
