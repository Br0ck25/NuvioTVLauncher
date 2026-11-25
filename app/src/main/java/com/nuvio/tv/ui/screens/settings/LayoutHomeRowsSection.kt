package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.home.AddHomeRowDialog
import com.nuvio.tv.livetv.home.HomeLiveRow
import com.nuvio.tv.livetv.home.HomeRowOptionsDialog
import com.nuvio.tv.livetv.home.HomeRowsSetupViewModel
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun LayoutHomeRowsSection(
    viewModel: HomeRowsSetupViewModel = hiltViewModel()
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val names by viewModel.names.collectAsStateWithLifecycle()
    var addingRow by remember { mutableStateOf(false) }
    var editingRowId by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Text(
            text = stringResource(R.string.home_rows_settings_intro),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
        SettingsActionRow(
            title = stringResource(R.string.home_rows_add_button),
            subtitle = stringResource(R.string.home_rows_add_button_hint),
            onClick = { addingRow = true }
        )
        rows.forEach { row ->
            val title = names[row.id].orEmpty().ifBlank { row.title }.ifBlank {
                stringResource(row.typeTitleResource())
            }
            SettingsActionRow(
                title = title,
                subtitle = stringResource(row.typeSubtitleResource()),
                onClick = { editingRowId = row.id }
            )
        }
    }

    if (addingRow) {
        AddHomeRowDialog(viewModel) { addingRow = false }
    }
    editingRowId?.let { id ->
        rows.firstOrNull { it.id == id }?.let { row ->
            HomeRowOptionsDialog(row, viewModel) { editingRowId = null }
        }
    }
}

private fun HomeLiveRow.typeTitleResource(): Int = when (kind) {
    HomeLiveRow.KIND_CHANNELS -> R.string.home_rows_type_live
    HomeLiveRow.KIND_SPORTS -> R.string.home_rows_type_sports
    else -> R.string.home_rows_type_vod
}

private fun HomeLiveRow.typeSubtitleResource(): Int = when (kind) {
    HomeLiveRow.KIND_CHANNELS -> R.string.home_rows_options_channels_subtitle
    HomeLiveRow.KIND_SPORTS -> R.string.home_rows_options_sports_subtitle
    else -> R.string.home_rows_options_vod_subtitle
}
