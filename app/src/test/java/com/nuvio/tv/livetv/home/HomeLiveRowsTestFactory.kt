package com.nuvio.tv.livetv.home

import com.nuvio.tv.domain.model.CatalogRow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

internal fun mockHomeLiveRows(): HomeLiveRows = mockk(relaxed = true) {
    every { rows } returns flowOf(emptyList())
    every { shownRows } returns flowOf(emptyList())
    every { names } returns MutableStateFlow(emptyMap())
    every { emptyRowIds } returns MutableStateFlow(emptySet())
    every { vodRows } returns MutableStateFlow<Map<String, CatalogRow>>(emptyMap())
}
