package com.nuvio.tv.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.youtube.YouTubeContentRegion
import com.nuvio.tv.youtube.YouTubeSettings
import com.nuvio.tv.youtube.YtTab
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.youtube.YouTubeAccountViewModel

/** Settings → YouTube: sign in with a code from youtube.com/activate, or sign out. */
@Composable
internal fun YouTubeSettingsContent(
    viewModel: YouTubeAccountViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    val context = LocalContext.current
    var sponsorOn by remember { mutableStateOf(YouTubeSettings.sponsorBlockEnabled(context)) }
    var categories by remember { mutableStateOf(YouTubeSettings.sponsorCategoriesOn(context)) }
    var quality by remember { mutableStateOf(YouTubeSettings.preferredHeight(context)) }
    var volumeOffset by remember { mutableStateOf(YouTubeSettings.volumeOffsetDb(context)) }
    var hiddenTabs by remember { mutableStateOf(YouTubeSettings.hiddenTabs(context)) }
    var autoplay by remember { mutableStateOf(YouTubeSettings.autoplayNext(context)) }
    var autoSubtitles by remember { mutableStateOf(YouTubeSettings.autoSubtitles(context)) }
    var videoPreviews by remember { mutableStateOf(YouTubeSettings.videoPreviews(context)) }
    var sponsorAsk by remember { mutableStateOf(YouTubeSettings.sponsorAsk(context)) }
    var sponsorNotice by remember { mutableStateOf(YouTubeSettings.sponsorNotice(context)) }
    var deTitles by remember { mutableStateOf(YouTubeSettings.deArrowTitles(context)) }
    var tabOrder by remember { mutableStateOf(YouTubeSettings.tabOrder(context)) }
    var reordering by remember { mutableStateOf(false) }
    var rememberTab by remember { mutableStateOf(YouTubeSettings.rememberLastTab(context)) }
    var deThumbs by remember { mutableStateOf(YouTubeSettings.deArrowThumbnails(context)) }
    var hiddenWords by remember { mutableStateOf(YouTubeSettings.hiddenKeywords(context)) }
    var hiddenChannels by remember { mutableStateOf(YouTubeSettings.hiddenChannels(context)) }
    var newWord by remember { mutableStateOf("") }
    var addingWord by remember { mutableStateOf(false) }
    var hideShorts by remember { mutableStateOf(YouTubeSettings.hideShorts(context)) }
    var hideWatched by remember { mutableStateOf(YouTubeSettings.hideWatched(context)) }
    var hideUpcoming by remember { mutableStateOf(YouTubeSettings.hideUpcoming(context)) }
    var codec by remember { mutableStateOf(YouTubeSettings.codecPreference(context)) }
    var compatibility by remember { mutableStateOf(YouTubeSettings.compatibilityMode(context)) }
    var contentRegion by remember { mutableStateOf(YouTubeSettings.contentRegion(context)) }
    var showContentRegion by remember { mutableStateOf(false) }
    var posterColumns by remember { mutableStateOf(YouTubeSettings.posterColumns(context)) }
    var clockOn by remember { mutableStateOf(YouTubeSettings.showClock(context)) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = "OneTube",
            subtitle = "Sign in to see your subscriptions and watch history in the OneTube section"
        )

        SettingsGroupCard(title = "Account") {
            val first = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
            when {
                state.code != null -> {
                    Text(
                        text = "On your phone or computer, go to ${state.url} and enter:",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(vertical = NuvioTheme.spacing.sm)
                    )
                    Text(
                        text = state.code.orEmpty(),
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTheme.spacing.sm)
                    )
                    SettingsActionRow(
                        title = "Cancel",
                        subtitle = "Waiting for you to approve on your other device",
                        onClick = viewModel::cancelSignIn,
                        modifier = first
                    )
                }
                state.signedIn -> {
                    SettingsActionRow(
                        title = "Signed in",
                        subtitle = state.accountName ?: "Your OneTube account is connected",
                        onClick = {},
                        enabled = false
                    )
                    SettingsActionRow(
                        title = "Sign out",
                        subtitle = "Removes the account from this device",
                        onClick = viewModel::signOut,
                        leadingIcon = Icons.Default.Logout,
                        modifier = first
                    )
                }
                else -> {
                    SettingsActionRow(
                        title = "Sign in",
                        subtitle = state.error ?: "Get a code to enter at youtube.com/activate",
                        onClick = viewModel::startSignIn,
                        leadingIcon = Icons.Default.Login,
                        modifier = first
                    )
                }
            }
        }

        SettingsGroupCard(title = "Header") {
            SettingsToggleRow(
                title = "Clock",
                subtitle = "Show the time in the OneTube header",
                checked = clockOn,
                onToggle = {
                    clockOn = !clockOn
                    YouTubeSettings.setShowClock(context, clockOn)
                }
            )
        }

        SettingsGroupCard(title = "Playback") {
            SettingsToggleRow(
                title = "Thumbnail previews with sound",
                subtitle = "Play a preview when you pause on a OneTube video",
                checked = videoPreviews,
                onToggle = {
                    videoPreviews = !videoPreviews
                    YouTubeSettings.setVideoPreviews(context, videoPreviews)
                }
            )
            SettingsToggleRow(
                title = "Automatically select subtitles",
                subtitle = "Start OneTube videos with your preferred subtitles when available",
                checked = autoSubtitles,
                onToggle = {
                    autoSubtitles = !autoSubtitles
                    YouTubeSettings.setAutoSubtitles(context, autoSubtitles)
                }
            )
            SettingsActionRow(
                title = "Content region",
                subtitle = "The country whose videos show in News and Live. Automatic follows this device.",
                value = contentRegionLabel(contentRegion),
                onClick = { showContentRegion = true }
            )
            SettingsActionRow(
                title = "Posters per row",
                subtitle = "How many videos fit across OneTube rows and grids",
                value = "$posterColumns",
                onClick = {
                    posterColumns = if (posterColumns >= 5) 3 else posterColumns + 1
                    YouTubeSettings.setPosterColumns(context, posterColumns)
                }
            )
            SettingsToggleRow(
                title = "Autoplay next video",
                subtitle = "When a video ends, play a related video, or the next one in the list",
                checked = autoplay,
                onToggle = {
                    autoplay = !autoplay
                    YouTubeSettings.setAutoplayNext(context, autoplay)
                }
            )
            SettingsActionRow(
                title = "Device compatibility",
                subtitle = YouTubeSettings.compatibilityDescription(context, compatibility),
                value = YouTubeSettings.compatibilityLabel(context, compatibility),
                onClick = {
                    val next = when (compatibility) {
                        "auto" -> "on"
                        "on" -> "off"
                        else -> "auto"
                    }
                    YouTubeSettings.setCompatibilityMode(context, next)
                    compatibility = next
                }
            )
            SettingsActionRow(
                title = "Video format",
                subtitle = if (YouTubeSettings.compatibilityActive(context))
                    "Auto uses H.264 on this device for smoother playback. Pick H.264 if video stutters."
                else "Auto prefers VP9, then H.264, and leaves AV1 for last. Pick H.264 if video stutters.",
                value = YouTubeSettings.codecLabel(codec),
                onClick = {
                    val before = compatibility
                    val choices = YouTubeSettings.codecChoices
                    codec = choices[(choices.indexOf(codec).coerceAtLeast(0) + 1) % choices.size]
                    YouTubeSettings.setCodecPreference(context, codec)
                    YouTubeSettings.onUserSelectedCodec(context, codec)
                    compatibility = YouTubeSettings.compatibilityMode(context)
                    if (before != "off" && compatibility == "off") {
                        Toast.makeText(
                            context,
                            "Compatibility mode off for full codec quality",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            )
            SettingsActionRow(
                title = "Default quality",
                subtitle = if (quality <= 0 && YouTubeSettings.compatibilityActive(context))
                    "Auto currently starts at ${YouTubeSettings.compatibilityMaxHeight(context)}p on this device. You can still change it in the player."
                else "The video size to start at. You can still change it in the player.",
                value = YouTubeSettings.qualityLabel(quality),
                onClick = {
                    val before = compatibility
                    val choices = YouTubeSettings.qualityCycle(context)
                    quality = choices[(choices.indexOf(quality).coerceAtLeast(0) + 1) % choices.size]
                    YouTubeSettings.setPreferredHeight(context, quality)
                    YouTubeSettings.onUserSelectedQuality(context, quality)
                    compatibility = YouTubeSettings.compatibilityMode(context)
                    when {
                        before != "off" && compatibility == "off" ->
                            Toast.makeText(
                                context,
                                "Compatibility mode off for higher quality",
                                Toast.LENGTH_SHORT,
                            ).show()
                        before == "off" && compatibility == "auto" && quality <= 0 ->
                            Toast.makeText(
                                context,
                                "Compatibility mode back on Automatic",
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                }
            )
            SettingsActionRow(
                title = "Volume offset",
                subtitle = "Makes OneTube quieter or louder than the other sections. Negative is quieter.",
                value = YouTubeSettings.volumeOffsetLabel(volumeOffset),
                onClick = {
                    val choices = YouTubeSettings.volumeOffsetChoices
                    volumeOffset = choices[(choices.indexOf(volumeOffset).coerceAtLeast(0) + 1) % choices.size]
                    YouTubeSettings.setVolumeOffsetDb(context, volumeOffset)
                }
            )
        }

        SettingsGroupCard(
            title = "DeArrow",
            subtitle = "Community-written titles and thumbnails in place of clickbait. Takes effect the next time a page loads."
        ) {
            SettingsToggleRow(
                title = "Better titles",
                subtitle = "Plain titles where the community has written one",
                checked = deTitles,
                onToggle = {
                    deTitles = !deTitles
                    YouTubeSettings.setDeArrowTitles(context, deTitles)
                }
            )
            SettingsToggleRow(
                title = "Better thumbnails",
                subtitle = "Thumbnails picked from the video itself",
                checked = deThumbs,
                onToggle = {
                    deThumbs = !deThumbs
                    YouTubeSettings.setDeArrowThumbnails(context, deThumbs)
                }
            )
        }

        SettingsGroupCard(
            title = "Feeds",
            subtitle = "Leave things out of the OneTube page. History always shows everything you've watched."
        ) {
            SettingsToggleRow(
                title = "Hide Shorts",
                subtitle = "Short vertical videos",
                checked = hideShorts,
                onToggle = {
                    hideShorts = !hideShorts
                    YouTubeSettings.setHideShorts(context, hideShorts)
                }
            )
            SettingsToggleRow(
                title = "Hide watched videos",
                subtitle = "Videos you've mostly watched, on this device or on your account",
                checked = hideWatched,
                onToggle = {
                    hideWatched = !hideWatched
                    YouTubeSettings.setHideWatched(context, hideWatched)
                }
            )
            SettingsToggleRow(
                title = "Hide upcoming videos",
                subtitle = "Premieres and streams that haven't started, which can't be played yet",
                checked = hideUpcoming,
                onToggle = {
                    hideUpcoming = !hideUpcoming
                    YouTubeSettings.setHideUpcoming(context, hideUpcoming)
                }
            )
        }

        SettingsGroupCard(
            title = "Hidden words and channels",
            subtitle = "Videos with these words in the title or channel name, and these channels, stay off the OneTube page"
        ) {
            // The keyboard only opens once you choose to type, not when you scroll past the field.
            if (!addingWord) {
                SettingsActionRow(
                    title = "Add a word or phrase",
                    subtitle = "Press OK to type one",
                    onClick = { addingWord = true }
                )
            } else {
                val wordFocus = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) {
                    runCatching { wordFocus.requestFocus() }
                    keyboard?.show()
                }
                // Back puts the box away without adding anything.
                BackHandler {
                    newWord = ""
                    addingWord = false
                }
                OutlinedTextField(
                    value = newWord,
                    onValueChange = { newWord = it },
                    singleLine = true,
                    label = { Text("Type a word or phrase, then press Done") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        YouTubeSettings.addHiddenKeyword(context, newWord)
                        hiddenWords = YouTubeSettings.hiddenKeywords(context)
                        newWord = ""
                        addingWord = false
                        keyboard?.hide()
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTheme.spacing.xs).focusRequester(wordFocus)
                )
            }
            hiddenWords.forEach { word ->
                SettingsActionRow(
                    title = word,
                    subtitle = "Press OK to stop hiding this word",
                    onClick = {
                        YouTubeSettings.removeHiddenKeyword(context, word)
                        hiddenWords = YouTubeSettings.hiddenKeywords(context)
                    }
                )
            }
            hiddenChannels.forEach { (id, name) ->
                SettingsActionRow(
                    title = name,
                    subtitle = "Hidden channel. Press OK to show it again",
                    onClick = {
                        YouTubeSettings.unhideChannel(context, id)
                        hiddenChannels = YouTubeSettings.hiddenChannels(context)
                    }
                )
            }
            if (hiddenWords.isEmpty() && hiddenChannels.isEmpty()) {
                Text(
                    text = "Nothing hidden yet. Hide a channel from the OneTube panel in the player.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(vertical = NuvioTheme.spacing.sm)
                )
            }
        }

        SettingsGroupCard(
            title = "Tabs",
            subtitle = "Choose which tabs show along the top of the OneTube page"
        ) {
            SettingsToggleRow(
                title = "Open on the last tab used",
                subtitle = "Off: the page always opens on the first tab",
                checked = rememberTab,
                onToggle = {
                    rememberTab = !rememberTab
                    YouTubeSettings.setRememberLastTab(context, rememberTab)
                }
            )
            SettingsActionRow(
                title = "Change the order",
                subtitle = "Pick a tab, then move it up or down",
                onClick = { reordering = true }
            )
            if (reordering) {
                TabOrderEditor(
                    order = tabOrder,
                    hidden = hiddenTabs,
                    onChange = {
                        tabOrder = it
                        YouTubeSettings.setTabOrder(context, it)
                    },
                    onDone = { reordering = false }
                )
            }
            // The switches show in your order, except while you're moving tabs around.
            tabOrder.filter { !reordering }.forEach { tab ->
                val shown = tab.name !in hiddenTabs
                val onlyOneLeft = shown && YtTab.entries.count { it.name !in hiddenTabs } <= 1
                SettingsToggleRow(
                    title = tab.title,
                    subtitle = when {
                        onlyOneLeft -> "At least one tab has to stay on"
                        tab.needsSignIn -> "Needs a signed-in account"
                        else -> null
                    },
                    checked = shown,
                    enabled = !onlyOneLeft,
                    onToggle = {
                        YouTubeSettings.setTabVisible(context, tab, !shown)
                        hiddenTabs = YouTubeSettings.hiddenTabs(context)
                    }
                )
            }
        }

        SettingsGroupCard(
            title = "SponsorBlock",
            subtitle = "Skips parts of videos that viewers have marked, such as sponsor reads"
        ) {
            SettingsToggleRow(
                title = "Skip marked segments",
                subtitle = "Jumps past them and shows what was skipped",
                checked = sponsorOn,
                onToggle = {
                    sponsorOn = !sponsorOn
                    YouTubeSettings.setSponsorBlockEnabled(context, sponsorOn)
                }
            )
            if (sponsorOn) {
                SettingsToggleRow(
                    title = "Ask before skipping",
                    subtitle = "Shows a card when a marked part starts; press OK to skip it",
                    checked = sponsorAsk,
                    onToggle = {
                        sponsorAsk = !sponsorAsk
                        YouTubeSettings.setSponsorAsk(context, sponsorAsk)
                    }
                )
                SettingsToggleRow(
                    title = "Show a notice when skipping",
                    subtitle = "A short message such as \"Skipped: Sponsor\"",
                    checked = sponsorNotice,
                    onToggle = {
                        sponsorNotice = !sponsorNotice
                        YouTubeSettings.setSponsorNotice(context, sponsorNotice)
                    }
                )
            }
            if (sponsorOn) {
                YouTubeSettings.sponsorCategories.forEach { (key, label) ->
                    SettingsToggleRow(
                        title = label,
                        subtitle = null,
                        checked = key in categories,
                        onToggle = {
                            val on = key !in categories
                            YouTubeSettings.setSponsorCategory(context, key, on)
                            categories = YouTubeSettings.sponsorCategoriesOn(context)
                        }
                    )
                }
            }
        }
    }

    if (showContentRegion) {
        SettingsSingleChoiceDialog(
            title = "Content region",
            subtitle = "Which country's videos appear in News and Live",
            options = contentRegionChoices(),
            selectedValue = contentRegion,
            onOptionSelected = { region ->
                contentRegion = region
                YouTubeSettings.setContentRegion(context, region)
                YouTubeContentRegion.apply(context)
                showContentRegion = false
            },
            onDismiss = { showContentRegion = false }
        )
    }
}

private fun contentRegionChoices(): List<SettingsPickerOption<String>> =
    (listOf(SettingsPickerOption("", "Automatic (device)")) +
        Locale.getISOCountries()
            .map { code ->
                SettingsPickerOption(code, runCatching {
                    Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault())
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: code)
            })
        .sortedBy { it.title }

private fun contentRegionLabel(region: String): String =
    if (region.isBlank()) "Automatic"
    else runCatching {
        Locale.Builder().setRegion(region).build().getDisplayCountry(Locale.getDefault())
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: region
