@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SectionScale
import com.nuvio.tv.domain.model.SectionScales
import com.nuvio.tv.ui.scale.SectionScalingViewModel
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Live TV fork: Settings → Appearance → Section scaling.
 *
 * Pick a section, then give it its own text size and interface size. Text size changes text
 * only; interface size scales the whole page. Settings itself is never scaled, and Live TV
 * keeps its own Guide Size setting for the guide's rows.
 */
@Composable
internal fun SectionScalingSettingsCard(
    viewModel: SectionScalingViewModel = hiltViewModel()
) {
    val scales by viewModel.scales.collectAsStateWithLifecycle()
    var selectedSection by rememberSaveable { mutableStateOf(SectionScales.HOME) }
    var showSectionPicker by remember { mutableStateOf(false) }
    val scale = scales[selectedSection] ?: SectionScales.defaultFor(selectedSection)

    SettingsGroupCard(
        modifier = Modifier.fillMaxWidth(),
        title = stringResource(R.string.appearance_section_scaling),
        subtitle = stringResource(R.string.appearance_section_scaling_subtitle)
    ) {
        if (MemoryBudget.isLowRamTier) {
            LowRamScalingWarning()
            Spacer(Modifier.height(8.dp))
        }
        // Controls on the left, sample on the right: the preview stays in view while the
        // sliders are being adjusted, instead of sitting below them off screen.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SettingsActionRow(
                    title = stringResource(R.string.appearance_scaling_section),
                    subtitle = stringResource(R.string.appearance_scaling_section_subtitle),
                    value = sectionLabel(selectedSection),
                    onClick = { showSectionPicker = true }
                )
                SliderSettingsItem(
                    title = stringResource(R.string.appearance_scaling_text),
                    values = SectionScales.PERCENT_STEPS,
                    selected = scale.textPercent,
                    valueText = "${scale.textPercent}%",
                    onValueChange = { percent -> viewModel.setTextScale(selectedSection, percent) }
                )
                SliderSettingsItem(
                    title = stringResource(R.string.appearance_scaling_interface),
                    values = SectionScales.PERCENT_STEPS,
                    selected = scale.uiPercent,
                    valueText = "${scale.uiPercent}%",
                    onValueChange = { percent -> viewModel.setUiScale(selectedSection, percent) }
                )
                SettingsResetButton(
                    text = stringResource(R.string.appearance_scaling_reset),
                    onClick = { viewModel.resetAll() }
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SectionScalingPreview(selectedSection, scale)
            }
        }
    }

    if (showSectionPicker) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.appearance_scaling_section),
            subtitle = stringResource(R.string.appearance_scaling_section_subtitle),
            options = SectionScales.ALL.map { section ->
                SettingsPickerOption(section, sectionLabel(section))
            },
            selectedValue = selectedSection,
            onOptionSelected = { section ->
                selectedSection = section
                showSectionPicker = false
            },
            onDismiss = { showSectionPicker = false },
            width = 400.dp,
            maxHeight = 360.dp
        )
    }
}

@Composable
private fun LowRamScalingWarning() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NuvioTheme.colors.BackgroundCard.copy(alpha = 0.65f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = Color(0xFFF5A623),
            modifier = Modifier
                .width(18.dp)
                .height(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.appearance_scaling_low_ram_warning),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun sectionLabel(section: String): String = when (section) {
    SectionScales.HOME -> stringResource(R.string.nav_home)
    SectionScales.DISCOVER -> stringResource(R.string.nav_discover)
    SectionScales.SEARCH -> stringResource(R.string.nav_search)
    SectionScales.LIBRARY -> stringResource(R.string.nav_library)
    SectionScales.CALENDAR -> "Calendar"
    SectionScales.LIVE_TV -> "Live TV"
    SectionScales.SPORTS -> "Sports"
    SectionScales.ON_DEMAND -> "On Demand"
    SectionScales.RECORDINGS -> "Recordings"
    SectionScales.YOUTUBE -> "OneTube"
    SectionScales.MUSIC -> "Music"
    else -> section
}

/**
 * A small side-by-side sample so the size can be judged before leaving Settings. The left panel
 * is always the normal size; the right one uses the chosen section's text and interface size.
 * Both frames stay put while their contents grow or shrink, so the difference is easy to see.
 */
@Composable
private fun SectionScalingPreview(section: String, scale: SectionScale) {
    Text(
        text = stringResource(R.string.appearance_scaling_preview),
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.colors.TextSecondary
    )
    Spacer(Modifier.height(4.dp))
    ScalingPreviewPanel(
        title = stringResource(R.string.appearance_scaling_normal),
        detail = stringResource(R.string.appearance_scaling_normal_detail),
        scale = SectionScale(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    ScalingPreviewPanel(
        title = sectionLabel(section),
        detail = stringResource(
            R.string.appearance_scaling_preview_detail,
            scale.textPercent,
            scale.uiPercent
        ),
        scale = scale,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ScalingPreviewPanel(
    title: String,
    detail: String,
    scale: SectionScale,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))

        val baseDensity = LocalDensity.current
        val previewDensity = remember(baseDensity, scale) {
            Density(
                density = baseDensity.density * scale.uiFactor,
                fontScale = baseDensity.fontScale * scale.textFactor
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(116.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(NuvioTheme.colors.BackgroundCard)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(10.dp)
                )
        ) {
            CompositionLocalProvider(LocalDensity provides previewDensity) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        repeat(6) { index ->
                            Box(
                                modifier = Modifier
                                    .width(74.dp)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        if (index % 2 == 0) Color(0xFF34343B) else Color(0xFF26262B)
                                    )
                            )
                        }
                    }
                    Text(
                        text = "Your shows, your size",
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "S2E4 · The Long Way Home",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
