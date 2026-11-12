@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.setup

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.launcher.DeviceAction
import com.nuvio.tv.core.launcher.LauncherSetup
import com.nuvio.tv.core.launcher.LocalAdbSetupFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nuvio Launcher takes over Home while the TV's own launcher is still enabled. If that launcher has
 * been turned off, Google TV can get stuck in a recovery loop, so this warns and offers a one-press fix.
 * Draws nothing while the stock launcher is on.
 */
@Composable
fun StockLauncherWarning(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var disabled by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            disabled = withContext(Dispatchers.IO) { LauncherSetup.disabledStockLaunchers(context) }
            delay(3_000)
        }
    }
    if (disabled.isEmpty()) return
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .padding(24.dp)
            .background(Color(0xFF3A1D1D), RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stringResource(R.string.stock_launcher_off_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text(stringResource(R.string.stock_launcher_off_body), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE0E0E0))
        Button(
            enabled = !working,
            onClick = {
                working = true
                scope.launch {
                    // With the phone setup paired, one command turns it back on. Otherwise open its page in
                    // Settings, where it has an Enable button.
                    val result = withContext(Dispatchers.IO) {
                        runCatching { LocalAdbSetupFactory.create(context)?.run(DeviceAction.ENABLE_STOCK, null)?.getOrThrow() }
                    }
                    working = false
                    disabled = withContext(Dispatchers.IO) { LauncherSetup.disabledStockLaunchers(context) }
                    if (result.getOrNull() == null || disabled.isNotEmpty()) {
                        Toast.makeText(context, R.string.stock_launcher_off_open_settings, Toast.LENGTH_LONG).show()
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${disabled.firstOrNull() ?: return@runCatching}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            }
        ) {
            Text(stringResource(R.string.stock_launcher_off_action))
        }
    }
}
