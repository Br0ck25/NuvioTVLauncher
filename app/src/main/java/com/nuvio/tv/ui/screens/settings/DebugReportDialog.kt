package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun DebugReportDialog(
    onDismiss: () -> Unit,
    viewModel: DebugReportViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val closeFocusRequester = remember { FocusRequester() }
    val actionFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        viewModel.reset()
    }
    LaunchedEffect(state) {
        when (state) {
            is DebugReportUiState.Idle, is DebugReportUiState.Failed ->
                actionFocusRequester.requestFocus()
            is DebugReportUiState.Sent -> closeFocusRequester.requestFocus()
            is DebugReportUiState.Sending -> Unit
        }
    }

    NuvioDialog(
        onDismiss = {
            if (state !is DebugReportUiState.Sending) {
                viewModel.reset()
                onDismiss()
            }
        },
        title = when (state) {
            is DebugReportUiState.Sent -> stringResource(R.string.debug_report_sent_title)
            is DebugReportUiState.Failed -> stringResource(R.string.debug_report_failed_title)
            is DebugReportUiState.Sending -> stringResource(R.string.debug_report_sending_title)
            is DebugReportUiState.Idle -> stringResource(R.string.debug_report_dialog_title)
        },
        subtitle = when (state) {
            is DebugReportUiState.Sent -> stringResource(R.string.debug_report_sent_subtitle)
            is DebugReportUiState.Failed -> stringResource(R.string.debug_report_failed_subtitle)
            is DebugReportUiState.Sending -> stringResource(R.string.debug_report_sending_subtitle)
            is DebugReportUiState.Idle -> stringResource(R.string.debug_report_dialog_subtitle)
        },
        width = 680.dp,
        suppressFirstKeyUp = false
    ) {
        when (val current = state) {
            is DebugReportUiState.Idle -> {
                Text(
                    text = stringResource(R.string.debug_report_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }

            is DebugReportUiState.Sending -> {
                Text(
                    text = stringResource(R.string.debug_report_sending_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }

            is DebugReportUiState.Sent -> {
                Text(
                    text = stringResource(R.string.debug_report_sent_body, current.reportId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
            }

            is DebugReportUiState.Failed -> {
                Text(
                    text = stringResource(R.string.debug_report_failed_body, current.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            when (state) {
                is DebugReportUiState.Sent -> {
                    DialogButton(
                        text = stringResource(R.string.debug_report_action_close),
                        onClick = {
                            viewModel.reset()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(closeFocusRequester)
                    )
                }

                is DebugReportUiState.Failed -> {
                    DialogButton(
                        text = stringResource(R.string.debug_report_action_close),
                        onClick = {
                            viewModel.reset()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(closeFocusRequester)
                    )
                    DialogButton(
                        text = stringResource(R.string.debug_report_action_try_again),
                        onClick = viewModel::send,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(actionFocusRequester)
                    )
                }

                is DebugReportUiState.Idle -> {
                    DialogButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = {
                            viewModel.reset()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(closeFocusRequester)
                    )
                    DialogButton(
                        text = stringResource(R.string.debug_report_action_send),
                        onClick = viewModel::send,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(actionFocusRequester)
                    )
                }

                is DebugReportUiState.Sending -> Unit
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary
        )
    ) {
        Text(text = text)
    }
}
