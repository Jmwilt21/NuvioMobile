package com.nuvio.app.features.downloads.season.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.downloads.season.SeasonDownloadQueue
import com.nuvio.app.features.downloads.season.SeasonQueueEntry
import com.nuvio.app.features.downloads.season.SeasonQueueFailure
import com.nuvio.app.features.downloads.season.SeasonQueueStatus
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Episodes that are waiting for their turn, grouped by show and season. Episodes that are
 * already transferring are shown by the regular downloads list instead.
 */
@Composable
internal fun SeasonQueueSection(modifier: Modifier = Modifier) {
    val entries by remember {
        SeasonDownloadQueue.ensureStarted()
        SeasonDownloadQueue.entries
    }.collectAsStateWithLifecycle()
    val pending = remember(entries) { entries.filter { it.status != SeasonQueueStatus.Downloading } }
    if (pending.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.season_queue_section),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        pending.groupBy { it.parentMetaId to it.seasonNumber }.forEach { (group, groupEntries) ->
            val (parentMetaId, seasonNumber) = group
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = groupEntries.first().title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = seasonDownloadSeasonLabel(seasonNumber),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { SeasonDownloadQueue.cancelSeason(parentMetaId, seasonNumber) }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = stringResource(Res.string.season_queue_cancel_season),
                            )
                        }
                    }
                    groupEntries.sortedBy { it.episodeNumber }.forEach { entry ->
                        SeasonQueueRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonQueueRow(entry: SeasonQueueEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.episodeTitle?.takeIf { it.isNotBlank() }
                    ?.let { "${entry.episodeNumber}. $it" }
                    ?: entry.episodeNumber.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = seasonQueueStatusText(entry),
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.status == SeasonQueueStatus.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.status == SeasonQueueStatus.Failed) {
            IconButton(onClick = { SeasonDownloadQueue.retry(entry.id) }) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(Res.string.action_retry),
                )
            }
        }
        IconButton(onClick = { SeasonDownloadQueue.cancel(entry.id) }) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.action_cancel),
            )
        }
    }
}

@Composable
private fun seasonQueueStatusText(entry: SeasonQueueEntry): String = when (entry.status) {
    SeasonQueueStatus.Queued -> stringResource(
        if (entry.attempt > 0) Res.string.season_queue_status_retrying else Res.string.season_queue_status_queued,
    )
    SeasonQueueStatus.Resolving -> stringResource(Res.string.season_queue_status_resolving)
    SeasonQueueStatus.Downloading -> ""
    SeasonQueueStatus.WaitingForApp -> stringResource(Res.string.season_queue_status_waiting_for_app)
    SeasonQueueStatus.Failed -> {
        val reason = stringResource(
            when (entry.failure) {
                SeasonQueueFailure.NoStreams -> Res.string.season_queue_status_failed_no_streams
                SeasonQueueFailure.Network -> Res.string.season_queue_status_failed_network
                SeasonQueueFailure.BadFile -> Res.string.season_queue_status_failed_bad_file
                SeasonQueueFailure.DownloadFailed, null -> Res.string.season_queue_status_failed_download
            },
        )
        entry.errorMessage?.takeIf { it.isNotBlank() }?.let { "$reason ($it)" } ?: reason
    }
}
