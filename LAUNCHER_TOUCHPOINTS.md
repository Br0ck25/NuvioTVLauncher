# Launcher touchpoints

This branch is Nuvio One `1.2.0-beta.1.17.2.1` (commit `5780885`, the last build Nuvio One published)
plus the launcher layer. Nothing else from Nuvio One is changed.

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
| Tests | `core/apps/*Test`, `core/launcher/*Test` |

## Nuvio One files we edit (keep each edit small; re-check on every Nuvio One update)

| File | What the hook does |
|---|---|
| `MainActivity.kt` | Home/Apps key handling, Button Mapper dispatch, setup wizard gate, Back never exits, sidebar `launcherMode`, Apps and Settings menu entries |
| `NuvioApplication.kt` | Heavy services made lazy and started off the main thread so the accessibility service can bind early |
| `AndroidManifest.xml`, `full/AndroidManifest.xml` | HOME and ALL_APPS filters, services, boot receiver, app-icon aliases |
| `app/build.gradle.kts` | App id `com.nuvio.tv.launcher`, update repo, ADB libraries, test deps |
| `HomeScreen.kt` | Top bar, settings drawer, notifications drawer |
| `ClassicHomeContent`, `GridHomeContent`, `ModernHomeContent`, `ModernHomeRows`, `ModernHomeRowsList`, `ModernHomeModels`, `ModernHomePresentation` | The Apps row (long-press menu, move mode) and `HomeTile` size publishing |
| `HomeViewModel`, `HomeViewModelCatalogPipeline`, `HomeViewModelCatalogUtils`, `HomeUiState`, `CatalogOrderViewModel` | The Apps row is a catalog entry, so it can be reordered and turned off in Reorder Home Catalogs |
| `NuvioNavHost.kt`, `Screen.kt` | Apps route, settings navigation |
| `SettingsScreen`, `SettingsCatalog`, `SettingsSearch`, `SideMenuSettingsContent`, `CalendarModels` (`SideMenuPages`) | Default Launcher and Remote Buttons settings, Apps and Settings menu toggles |
| `ThemeSettingsScreen`, `ThemeSettingsViewModel` | Icon pack picker |
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
- Live TV "My Lists" channels.
- Per-profile YouTube, Music and Spotify sign-ins, and the Google Drive sign-in changes and logging (Nuvio One's versions kept).
- Docs, and `sync-upstream.yml` (still follows Nuvio One's `main`; to be repointed at release tags).
