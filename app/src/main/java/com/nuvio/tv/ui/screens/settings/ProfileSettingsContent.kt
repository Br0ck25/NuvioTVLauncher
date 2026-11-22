@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R

@Composable
internal fun ProfileSettingsContent(
    onManageProfiles: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
    insightsViewModel: ProfileInsightsViewModel = hiltViewModel()
) {
    val insights by insightsViewModel.insights.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        item {
            SettingsDetailHeader(
                title = stringResource(R.string.profile_title),
                subtitle = stringResource(R.string.profile_subtitle)
            )
        }
        item {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsActionRow(
                    title = stringResource(R.string.profile_manage_button),
                    subtitle = null,
                    onClick = onManageProfiles,
                    modifier = if (initialFocusRequester != null) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    }
                )
            }
        }
        if (insights.loaded) {
            item {
                SettingsDetailHeader(
                    title = stringResource(R.string.profile_insights_title),
                    subtitle = stringResource(R.string.profile_insights_subtitle)
                )
            }
            item {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    InsightRow(stringResource(R.string.profile_insights_continue), insights.continueWatching.toString())
                    InsightRow(stringResource(R.string.profile_insights_started), insights.inProgress.toString())
                    InsightRow(stringResource(R.string.profile_insights_movies), insights.moviesWatched.toString())
                    InsightRow(stringResource(R.string.profile_insights_episodes), insights.episodesWatched.toString())
                    InsightRow(stringResource(R.string.profile_insights_series), insights.seriesWatched.toString())
                    InsightRow(stringResource(R.string.profile_insights_library), insights.libraryItems.toString())
                }
            }
            if (insights.topSeries.isNotEmpty()) {
                item {
                    SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                        insights.topSeries.forEachIndexed { index, (title, count) ->
                            InsightRow(
                                title = if (index == 0) stringResource(R.string.profile_insights_top_series, title) else title,
                                value = stringResource(R.string.profile_insights_episode_count, count)
                            )
                        }
                    }
                }
            }
            if (insights.recent.isNotEmpty()) {
                item {
                    SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                        insights.recent.forEachIndexed { index, title ->
                            InsightRow(
                                title = title,
                                value = if (index == 0) stringResource(R.string.profile_insights_latest) else ""
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A read-only line of numbers. It takes focus only so the list can be scrolled with the remote. */
@Composable
private fun InsightRow(title: String, value: String) {
    SettingsActionRow(
        title = title,
        subtitle = null,
        value = value.takeIf { it.isNotBlank() },
        onClick = {},
        trailingIcon = Icons.Filled.Remove
    )
}
