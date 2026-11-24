package com.nuvio.tv.ui.scale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.nuvio.tv.domain.model.SectionScale

/**
 * Live TV fork: the active profile's section sizes, provided above the navigation host.
 * The key is one of [com.nuvio.tv.domain.model.SectionScales]'s section names.
 */
val LocalSectionScales = staticCompositionLocalOf<Map<String, SectionScale>> { emptyMap() }

/**
 * Draws [content] at the size chosen for [sectionKey].
 *
 * Text size is applied through the density's font scale (so it affects text only), and the
 * interface size through the density itself (so spacing, cards and text all follow). A section
 * left at 100%/100% adds no wrapper at all.
 */
@Composable
fun SectionScaleHost(
    sectionKey: String?,
    content: @Composable () -> Unit
) {
    val scale = sectionKey?.let { LocalSectionScales.current[it] }
    if (scale == null || (scale.textPercent == 100 && scale.uiPercent == 100)) {
        content()
        return
    }

    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, scale) {
        Density(
            density = baseDensity.density * scale.uiFactor,
            fontScale = baseDensity.fontScale * scale.textFactor
        )
    }
    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        content()
    }
}
