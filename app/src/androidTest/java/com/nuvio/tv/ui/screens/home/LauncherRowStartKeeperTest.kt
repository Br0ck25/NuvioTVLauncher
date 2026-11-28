package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LauncherRowStartKeeperTest {
    @get:Rule
    val rule = createComposeRule()

    private val items = mutableStateOf((10..40).map { "m$it" })
    private lateinit var state: LazyListState

    private fun show(keep: Boolean, active: Boolean = false) {
        rule.setContent {
            state = rememberLazyListState()
            if (keep) LauncherKeepRowAtStart(state, items.value.firstOrNull()) { active }
            LazyRow(state = state, modifier = Modifier.size(width = 300.dp, height = 100.dp)) {
                items(items.value, key = { it }) { Box(Modifier.size(100.dp)) { Text(it) } }
            }
        }
    }

    private fun addInFront() {
        rule.runOnIdle { items.value = listOf("m1", "m2", "m3") + items.value }
        rule.waitForIdle()
    }

    @Test
    fun withoutTheHelperARowAtItsStartIsPushedPastNewItems() {
        show(keep = false)
        addInFront()
        // The control: Compose holds the old first card in place, so the row no longer starts at its beginning.
        rule.runOnIdle { assertEquals(3, state.firstVisibleItemIndex) }
    }

    @Test
    fun aRowAtItsStartStaysAtItsStartWhenItemsAreAddedInFront() {
        show(keep = true)
        addInFront()
        rule.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun aRowTheUserScrolledIntoKeepsItsPlace() {
        show(keep = true)
        rule.runOnIdle { kotlinx.coroutines.runBlocking { state.scrollToItem(5) } }
        rule.waitForIdle()
        addInFront()
        rule.runOnIdle { assertEquals(8, state.firstVisibleItemIndex) }
    }

    @Test
    fun theRowTheRemoteIsOnIsLeftAlone() {
        show(keep = true, active = true)
        addInFront()
        rule.runOnIdle { assertEquals(3, state.firstVisibleItemIndex) }
    }
}
