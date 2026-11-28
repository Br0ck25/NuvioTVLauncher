# Launcher touchpoints

How to add code without growing this list: see `LAUNCHER_RULES.md`.

This branch is Nuvio One `1.2.0-beta.4.2.1` (commit `8ec6830`, the last build Nuvio One published)
plus the launcher layer. The edits listed below are the only changes to Nuvio One's files.

To check whether a bug is ours or Nuvio One's, build that tag. If the bug is there, it is theirs.

## New files (ours, no conflicts with Nuvio One)

| Feature | Files |
|---|---|
| Home button, accessibility takeover, phone setup, ADB setup, Button Mapper | `core/launcher/*`, `app/src/full` and `app/src/playstore` `LocalAdbSetupFactory`, `LoopbackAdbSetup` |
| Installed apps, hiding, renaming, icon packs | `core/apps/*`, `InstalledApp*` and `AppIconChoiceDialog`, `AppMoveMode` in `ui/components` |
| Apps page | `ui/screens/apps/AppsScreen.kt` |
| Notifications | `core/notification/*`, `ui/screens/home/NotificationsDrawer.kt` |
| Top-right clock, bell and settings gear | `HomeTopBarHeader.kt`, `HomeSettingsDrawer.kt` |
| Setup wizard | `ui/screens/setup/LauncherSetupWizard.kt` |
| Settings pages | `ButtonMapper*`, `ButtonCaptureDialog`, `DefaultLauncherSettingsContent`, `IconPackPickerDialog`, `LocalAdbSetupDialog` |
| Launcher strings | `res/values/strings_launcher.xml` (Nuvio One's `strings.xml` is untouched) |
| Activity hooks (Home/Apps keys, Button Mapper, navigation requests) | `core/launcher/LauncherHost.kt`, `ui/screens/setup/LauncherHostCompose.kt` |
| Apps row in Modern Home, Home top-right panels, side-menu profile picture | `ui/screens/home/ModernAppsRow.kt`, `ui/screens/home/LauncherHomeOverlays.kt`, `ui/components/LauncherSidebarProfile.kt` |
| Icon pack picker (Settings → Appearance) | `ui/screens/settings/IconPackSettingsSection.kt`, `IconPackPickerDialog.kt` |
| Apps row catalog pipeline | `ui/screens/home/HomeViewModelInstalledApps.kt` |
| Live TV manual zoom (per channel; reuses the movie player's zoom dialog) | `livetv/player/LiveVideoZoomViewModel.kt`; hooks in `LivePictureShape`, `LivePlayerSurface`, the Live TV player screen, the guide and the gear panel |
| Media-server items in an external player (follow the Player setting; report where it stopped) | `data/mediaserver/ServerExternalPlayback.kt`; hooks: two lines in `StreamScreen` (`routePlayback`, `routeAutoPlay`), one in `StreamScreenViewModel.launchExternalPlayer`, one in `MainActivity` (the external-player result) |
| Home rows stay at their start when items are added in front | `ui/screens/home/LauncherRowStartKeeper.kt` |
| Merge-driver setup for files that are wholly ours | `.gitattributes`, `scripts/setup-merge-drivers.sh` |
| Manifest entries (HOME/ALL_APPS filters, services, boot receiver, queries) and Gradle settings | `app/src/launcher/AndroidManifest.xml`, `app/launcher.gradle` |
| Tests | `core/apps/*Test`, `core/launcher/*Test`, `core/sync/LauncherHomeCatalogSyncTest` |

## Nuvio One files we edit (keep each edit small; re-check on every Nuvio One update)

| File | What the hook does |
|---|---|
| `MainActivity.kt` | One `LauncherHost` property and about 10 one-line calls into it (`onCreate`, `onResume`, `onPause`, `onDestroy`, `onNewIntent`, `onWindowFocusChanged`, `onDispatchKeyEvent`, `onMappedKeyEvent`, `suppressAppExit`), plus `LauncherSetupGate`, `LauncherNavigationEffects`, `LauncherProfileSwitchEffect`, `attachListChannelsToProfile`, the Apps and Settings menu entries, sidebar `launcherMode`, the floating-pill profile and `hideSidebarRail` (two lines, `Fork:`: only Music hides the side menu rail / floating pill; Nuvio One 1.2.0-beta.4 hides them in every section, which left "Collapse sidebar", "Hide floating pill" and "Floating Pill Shows Profile" with nothing to control). The logic is in `core/launcher/LauncherHost.kt` and `ui/screens/setup/LauncherHostCompose.kt` |
| `NuvioApplication.kt` | Heavy services made lazy and started off the main thread so the accessibility service can bind early |
| `AndroidManifest.xml` | `MainActivity` launch mode `singleTask`, and the app-icon aliases (Nuvio One's icon activities became `activity-alias`). Everything else of ours is in `app/src/launcher/AndroidManifest.xml` |
| `full/AndroidManifest.xml` | `WRITE_SECURE_SETTINGS` permission (full flavor only) |
| `app/build.gradle.kts` | One `apply(from = "launcher.gradle")` at the end. App id, update repo, default Nuvio account server and key, ADB libraries and test deps are all in `app/launcher.gradle` |
| `HomeScreen.kt` | One `LauncherHomeOverlays(...)` call (top bar, settings and notifications drawers; the code is in `LauncherHomeOverlays.kt`; Nuvio One's Downloads button is the first button in our bar and keeps its `downloadsFocusRequester`), the `onNavigateToSettings` parameter, and `hasInstalledAppsContent` in the loading and empty-state checks |
| `ModernHomeRows` | Four calls into `ModernAppsRow.kt` (`rememberModernAppsRow`, `ModernAppsRowEffects`, `moveKeysModifier`, `ModernAppsRowTile`), one `LauncherKeepRowAtStart` call (`Fork:`) and the `InstalledApp` branches of exhaustive `when`s |
| `ClassicHomeContent`, `GridHomeContent`, `ModernHomeContent`, `ModernHomeRowsList`, `ModernHomeModels`, `ModernHomePresentation` | The Apps row as a row type (`when` branches, `InstalledAppsHomeRow` call) and "Home key / second Back goes to the top" (`focusTopOfHome`, still inline: it uses these files' local state) |
| `HomeViewModel`, `HomeViewModelCatalogPipeline`, `HomeViewModelCatalogUtils`, `HomeUiState`, `CatalogOrderViewModel` | The Apps row is a catalog entry, so it can be reordered and turned off in Reorder Home Catalogs |
| `NuvioNavHost.kt`, `Screen.kt` | Apps route, settings navigation |
| `SettingsScreen`, `SettingsCatalog`, `SettingsSearch`, `SideMenuSettingsContent`, `CalendarModels` (`SideMenuPages`) | Default Launcher and Remote Buttons settings, Apps and Settings menu toggles |
| `ThemeSettingsScreen` | One `IconPackSettingsSection()` call (the card, picker and ViewModel are in `IconPackSettingsSection.kt`) |
| `LayoutPreferenceDataStore`, `LayoutSettingsViewModel`, `LayoutCardSettings`, `LayoutHomeSettings` | Tile size and "pill shows profile" settings |

## Our sign-in code (kept as on `dev`)

| What | Where |
|---|---|
| Trakt sign-in with PKCE, no client secret needed (secret optional) | `TraktAuthService.kt`, `TraktAuthDtos.kt` |
| Current profile for the Apps row's own per-profile order and hidden apps (not accounts) | `core/profile/ActiveProfile.kt` (new), `ProfileManager.kt` (sets it), `HeroBackdropState.kt` (reset hook) |
| Default Nuvio account server and public key | `app/build.gradle.kts` |
| Where each service gets its credentials from CI secrets: Trakt, Simkl, MDBList, Google Drive, Last.fm, Spotify, TMDB, IntroDB default | `.github/workflows/release.yml` |
| Spotify phone-link pages, Google OAuth branding pages | `site/`, `.github/workflows/pages.yml` |

MDBList, Simkl, Premiumize and the Nuvio account sign-in code are identical to Nuvio One's; only the
credentials differ, and those come from `release.yml`.

Branding: the app icons (all colour variants), logo, wordmark, TV banner and the app name "Nuvio TV Launcher" are ours.

## Left out on purpose (still only on `dev`)

- Anything from unreleased Nuvio One work (`fix/livetv-green`) and edits we made to Nuvio One's Live TV, Music and YouTube code.
- Continue Watching and Upcoming card resizing, and collection-folder scaling (the Tile size setting now only sizes the apps row).
- Essential-mode override in `SettingsScreen` (Nuvio One's behavior kept).
- Live TV list channels (made from Home rows, collection folders and library lists; no mylistsaddon.com sync).
- Per-profile YouTube, Music and Spotify sign-ins, and the Google Drive sign-in changes and logging (Nuvio One's versions kept).
- Docs, and `sync-upstream.yml` (still follows Nuvio One's `main`; to be repointed at release tags).
