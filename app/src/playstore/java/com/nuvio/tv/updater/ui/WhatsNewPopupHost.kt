package com.nuvio.tv.updater.ui

import androidx.compose.runtime.Composable

/** The Play Store build updates through the store, so it has no release notes popup. */
@Composable
fun WhatsNewPopupHost(suppress: Boolean = false) = Unit
