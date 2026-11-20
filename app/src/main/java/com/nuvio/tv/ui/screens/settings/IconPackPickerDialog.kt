@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.IconPackInfo
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun IconPackPickerDialog(
    installedPacks: List<IconPackInfo>,
    selectedPackage: String?,
    isLoading: Boolean,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        initialFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.appearance_icon_pack_dialog_title),
        subtitle = stringResource(R.string.appearance_icon_pack_dialog_subtitle),
        width = 520.dp,
        suppressFirstKeyUp = false
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs)
                ) {
                    // 1. System Default Option
                    item(key = "__system_default__") {
                        val isSelected = selectedPackage == null
                        IconPackOptionRow(
                            name = stringResource(R.string.appearance_icon_pack_system_default),
                            packageName = stringResource(R.string.appearance_icon_pack_system_default_subtitle),
                            icon = null,
                            isSelected = isSelected,
                            onClick = { onSelect(null) },
                            modifier = if (isSelected) Modifier.focusRequester(initialFocusRequester) else Modifier
                        )
                    }

                    // 2. Installed Icon Packs
                    items(installedPacks, key = { it.packageName }) { pack ->
                        val isSelected = selectedPackage == pack.packageName
                        IconPackOptionRow(
                            name = pack.name,
                            packageName = pack.packageName,
                            icon = pack.icon,
                            isSelected = isSelected,
                            onClick = { onSelect(pack.packageName) },
                            modifier = if (isSelected) Modifier.focusRequester(initialFocusRequester) else Modifier
                        )
                    }

                    if (installedPacks.isEmpty()) {
                        item(key = "__empty_notice__") {
                            Text(
                                text = stringResource(R.string.appearance_icon_pack_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconPackOptionRow(
    name: String,
    packageName: String,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                role = Role.RadioButton
                selected = isSelected
            },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NuvioTheme.colors.Background.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = NuvioTheme.colors.Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
