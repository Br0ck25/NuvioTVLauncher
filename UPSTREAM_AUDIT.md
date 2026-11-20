> **Historical.** This audit was written against the previous `dev` (the line that had Nuvio One's unreleased `livetv-green` merged in), which is now saved as `claude/backup-dev-before-replace` (tag `1.2.4-beta.13`). Where it says `dev`, read that branch. The current `dev` is Nuvio One's `hotfix/17.2.1` plus the launcher layer and the optional branches (see OPTIONAL_BRANCHES.md).

# Launcher vs Nuvio One audit

Generated read-only. Nothing in the repo or any branch was changed.

## Bases

- **Pristine release base:** tag `1.2.0-beta.1.17.2.1` (`5780885`, branch `hotfix/17.2.1`, Release Nuvio One update #11): the last build Nuvio One published.
- **Middle layer:** `nuvioone/fix/livetv-green` (`ab5e959`, Nov 19) holds 418 files of unreleased work on top of that tag, and `dev` already contains it.
- **Our layer:** `dev` vs `livetv-green` = 309 files (+19510/-577). Against the release tag the whole of `dev` is 692 files (+50627/-5382).

Layers matter: bugs can come from (1) the release tag, (2) unreleased Nuvio One work in livetv-green, (3) our changes, or (4) NuvioTV/NuvioPlusIPTV commits merged straight into `dev`.

## Summary of our layer (dev vs livetv-green)

| Bucket | Files | Meaning |
|---|---|---|
| Added | 92 | New files. Safe. Launcher code, site pages, workflows. |
| Deleted | 1 | Review. |
| Modified, only our commits | 131 | Our edits inside Nuvio's files. Each needs a keep, revert or shrink decision. |
| Modified, other authors too | 85 | Upstream NuvioTV/Nuvio One commits mixed with ours. Needs a three-way review. |

**Watch-out:** `merge-nuvio-release.yml` merges with `-X ours`, which silently keeps our side of every conflict. Some "ours" edits may be old Nuvio code that never got upstream fixes.

### Our-only modified files by category
> **Correction:** an earlier version counted app icon files (`ic_launcher*.png`) as launcher code. They are now under resources.


| Category | Files | Default action |
|---|---|---|
| resources | 79 | REVIEW (branding/icons) |
| nuvioone-feature | 38 | REVIEW: likely REVERT to Nuvio One |
| app-core | 7 | REVIEW: shrink to a hook |
| infra/docs | 2 | REVIEW (build/docs) |
| strings | 2 | KEEP launcher keys only; revert rest |
| tests | 2 | follow the code |
| launcher | 1 | KEEP (launcher) |

## Our-only modified files: decide keep / revert / shrink (131)

| File | +/- vs livetv-green | Category | Suggested | Your decision |
|---|---|---|---|---|
| `…/ui/screens/settings/LayoutCardSettings.kt` | +9/-0 | app-core | REVIEW: shrink to a hook | |
| `…/ui/screens/settings/SideMenuSettingsContent.kt` | +8/-1 | app-core | REVIEW: shrink to a hook | |
| `…/ui/screens/settings/LayoutHomeSettings.kt` | +7/-0 | app-core | REVIEW: shrink to a hook | |
| `app/src/full/AndroidManifest.xml` | +6/-0 | app-core | REVIEW: shrink to a hook | |
| `…/ui/screens/settings/LayoutSettingsModel.kt` | +2/-1 | app-core | REVIEW: shrink to a hook | |
| `…/ui/screens/settings/SettingsCatalog.kt` | +2/-0 | app-core | REVIEW: shrink to a hook | |
| `…/ui/screens/settings/SettingsSearch.kt` | +2/-0 | app-core | REVIEW: shrink to a hook | |
| `PRIVACY.md` | +1/-1 | infra/docs | REVIEW (build/docs) | |
| `spotify-link/README.md` | +1/-1 | infra/docs | REVIEW (build/docs) | |
| `…/livetv/home/LiveTvLauncherRows.kt` | +1/-1 | launcher | KEEP (launcher) | |
| `app/src/full/res/mipmap-hdpi/iptv_ic_launcher.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-hdpi/iptv_ic_launcher_arctic_blue.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-hdpi/iptv_ic_launcher_copper.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-hdpi/iptv_ic_launcher_emerald.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-hdpi/iptv_ic_launcher_graphite.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-hdpi/iptv_ic_launcher_rose_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-mdpi/iptv_ic_launcher.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-mdpi/iptv_ic_launcher_arctic_blue.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-mdpi/iptv_ic_launcher_copper.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-mdpi/iptv_ic_launcher_emerald.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-mdpi/iptv_ic_launcher_graphite.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-mdpi/iptv_ic_launcher_rose_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xhdpi/iptv_ic_launcher.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xhdpi/iptv_ic_launcher_arctic_blue.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xhdpi/iptv_ic_launcher_copper.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xhdpi/iptv_ic_launcher_emerald.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xhdpi/iptv_ic_launcher_graphite.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xhdpi/iptv_ic_launcher_rose_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xxhdpi/iptv_ic_launcher.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xxhdpi/iptv_ic_launcher_arctic_blue.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xxhdpi/iptv_ic_launcher_copper.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xxhdpi/iptv_ic_launcher_emerald.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xxhdpi/iptv_ic_launcher_graphite.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xxhdpi/iptv_ic_launcher_rose_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xxxhdpi/iptv_ic_launcher.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xxxhdpi/iptv_ic_launcher_arctic_blue.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xxxhdpi/iptv_ic_launcher_copper.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xxxhdpi/iptv_ic_launcher_emerald.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xxxhdpi/iptv_ic_launcher_graphite.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xxxhdpi/iptv_ic_launcher_rose_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-hdpi/iptv_ic_launcher.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-hdpi/iptv_ic_launcher_arctic_blue.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-hdpi/iptv_ic_launcher_copper.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-hdpi/iptv_ic_launcher_emerald.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-hdpi/iptv_ic_launcher_graphite.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-hdpi/iptv_ic_launcher_rose_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-mdpi/iptv_ic_launcher.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-mdpi/iptv_ic_launcher_arctic_blue.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-mdpi/iptv_ic_launcher_copper.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-mdpi/iptv_ic_launcher_emerald.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-mdpi/iptv_ic_launcher_graphite.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-mdpi/iptv_ic_launcher_rose_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xhdpi/iptv_ic_launcher.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xhdpi/iptv_ic_launcher_arctic_blue.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xhdpi/iptv_ic_launcher_copper.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xhdpi/iptv_ic_launcher_emerald.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xhdpi/iptv_ic_launcher_graphite.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xhdpi/iptv_ic_launcher_rose_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xxhdpi/iptv_ic_launcher.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xxhdpi/iptv_ic_launcher_arctic_blue.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xxhdpi/iptv_ic_launcher_copper.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xxhdpi/iptv_ic_launcher_emerald.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xxhdpi/iptv_ic_launcher_graphite.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xxhdpi/iptv_ic_launcher_rose_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xxxhdpi/iptv_ic_launcher.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xxxhdpi/iptv_ic_launcher_arctic_blue.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xxxhdpi/iptv_ic_launcher_copper.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xxxhdpi/iptv_ic_launcher_emerald.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xxxhdpi/iptv_ic_launcher_graphite.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xxxhdpi/iptv_ic_launcher_rose_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `…/livetv/home/LiveTvHomeRow.kt` | +201/-61 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/data/LiveTvPreferences.kt` | +172/-47 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/player/LiveTvPlaybackController.kt` | +85/-0 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/youtube/YouTubeAccountScopes.kt` | +55/-23 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/ui/LiveTvPlayerScreen.kt` | +65/-3 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicSecurePreferences.kt` | +44/-0 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/record/LiveTvRecordingScheduler.kt` | +23/-8 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/calendar/CalendarHomeFeed.kt` | +25/-3 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/data/LiveTvRepository.kt` | +12/-3 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/record/LiveTvRecorder.kt` | +12/-2 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/calendar/CalendarRepository.kt` | +11/-0 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/ui/LiveTvPlayerPanel.kt` | +9/-2 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicSettings.kt` | +7/-3 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicListeningStore.kt` | +7/-2 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/branding/LiveTvBranding.kt` | +3/-5 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/calendar/CalendarModels.kt` | +6/-2 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/multiview/MultiviewPicker.kt` | +4/-4 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/record/LiveTvRecording.kt` | +6/-2 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/ondemand/OnDemandDatabase.kt` | +7/-0 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicNavidrome.kt` | +3/-4 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicLibrary.kt` | +4/-2 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicQueueStore.kt` | +3/-3 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicWebDav.kt` | +3/-3 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/home/LiveTvHomeRowViewModel.kt` | +4/-1 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/startup/LiveTvStartup.kt` | +2/-2 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/thumbs/SeekThumbSettings.kt` | +2/-2 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/ui/LiveTvViewModel.kt` | +2/-2 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicAddons.kt` | +3/-1 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicSpotifyLink.kt` | +3/-1 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicSpotifyWebSession.kt` | +3/-1 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/youtube/YouTubeProgress.kt` | +2/-2 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/youtube/YouTubeSettings.kt` | +2/-1 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/model/LiveTvModels.kt` | +1/-1 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/livetv/sync/SyncParts.kt` | +1/-1 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicDriveUploader.kt` | +1/-1 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicMoodCovers.kt` | +1/-1 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicSpotifyImport.kt` | +1/-1 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `…/music/MusicSpotifyWebSync.kt` | +1/-1 | nuvioone-feature | REVIEW: likely REVERT to Nuvio One | |
| `app/src/full/res/mipmap-xhdpi/iptv_banner.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xhdpi/iptv_banner_arctic_blue.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xhdpi/iptv_banner_copper.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xhdpi/iptv_banner_emerald.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xhdpi/iptv_banner_graphite.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/mipmap-xhdpi/iptv_banner_rose_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xhdpi/iptv_banner.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xhdpi/iptv_banner_arctic_blue.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xhdpi/iptv_banner_copper.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xhdpi/iptv_banner_emerald.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xhdpi/iptv_banner_graphite.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/livetvTest/res/mipmap-xhdpi/iptv_banner_rose_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/main/res/drawable-nodpi/app_logo_mark.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/main/res/drawable-nodpi/app_logo_wordmark.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/main/res/drawable-nodpi/app_logo_wordmark_arctic_blue.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/main/res/drawable-nodpi/app_logo_wordmark_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/main/res/drawable-nodpi/app_logo_wordmark_graphite.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/main/res/drawable-nodpi/app_logo_wordmark_jade.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/main/res/drawable-nodpi/app_logo_wordmark_rose_gold.png` | +-/-- | resources | REVIEW (branding/icons) | |
| `app/src/full/res/values/livetv_app_name.xml` | +1/-1 | strings | KEEP launcher keys only; revert rest | |
| `app/src/main/res/values/strings_iptv_status.xml` | +1/-0 | strings | KEEP launcher keys only; revert rest | |
| `app/src/test/java/com/nuvio/tv/ui/screens/settings/SettingsCatalogTest.kt` | +3/-1 | tests | follow the code | |
| `app/src/test/java/com/nuvio/tv/ui/screens/settings/SettingsStructureTest.kt` | +2/-2 | tests | follow the code | |

## Mixed-author modified files: three-way review (85)

| File | +/- vs livetv-green | Category | Suggested | Your decision |
|---|---|---|---|---|
| `…/MainActivity.kt` | +454/-50 | app-core | THREE-WAY | |
| `…/ui/components/ContinueWatchingSection.kt` | +283/-52 | app-core | THREE-WAY | |
| `app/src/main/AndroidManifest.xml` | +132/-31 | app-core | THREE-WAY | |
| `…/ui/screens/home/ModernHomeRows.kt` | +140/-8 | app-core | THREE-WAY | |
| `…/ui/screens/home/HomeScreen.kt` | +44/-10 | app-core | THREE-WAY | |
| `…/ui/screens/home/ClassicHomeContent.kt` | +32/-8 | app-core | THREE-WAY | |
| `…/data/repository/TraktAuthService.kt` | +32/-7 | app-core | THREE-WAY | |
| `…/NuvioApplication.kt` | +26/-10 | app-core | THREE-WAY | |
| `…/ui/screens/settings/ThemeSettingsScreen.kt` | +35/-0 | app-core | THREE-WAY | |
| `…/data/local/LayoutPreferenceDataStore.kt` | +34/-0 | app-core | THREE-WAY | |
| `…/ui/screens/settings/ThemeSettingsViewModel.kt` | +28/-2 | app-core | THREE-WAY | |
| `app/build.gradle.kts` | +20/-7 | app-core | THREE-WAY | |
| `…/ui/screens/home/ModernHomePresentation.kt` | +26/-0 | app-core | THREE-WAY | |
| `…/ui/screens/addon/CatalogOrderViewModel.kt` | +19/-5 | app-core | THREE-WAY | |
| `…/ui/screens/home/GridHomeContent.kt` | +20/-3 | app-core | THREE-WAY | |
| `…/ui/screens/settings/SettingsScreen.kt` | +22/-1 | app-core | THREE-WAY | |
| `…/ui/components/GridContinueWatchingSection.kt` | +13/-9 | app-core | THREE-WAY | |
| `…/ui/screens/settings/LayoutSettingsViewModel.kt` | +21/-0 | app-core | THREE-WAY | |
| `…/ui/screens/home/HomeViewModelCatalogPipeline.kt` | +19/-0 | app-core | THREE-WAY | |
| `…/ui/screens/home/ModernHomeModels.kt` | +18/-1 | app-core | THREE-WAY | |
| `…/ui/components/CollectionRowSection.kt` | +15/-3 | app-core | THREE-WAY | |
| `…/ui/screens/home/ModernHomeContent.kt` | +14/-3 | app-core | THREE-WAY | |
| `…/data/remote/dto/trakt/TraktAuthDtos.kt` | +8/-4 | app-core | THREE-WAY | |
| `…/core/profile/ProfileManager.kt` | +11/-0 | app-core | THREE-WAY | |
| `…/ui/screens/settings/NetworkSettingsScreen.kt` | +0/-11 | app-core | THREE-WAY | |
| `…/ui/screens/settings/LicensesAttributionsScreen.kt` | +9/-0 | app-core | THREE-WAY | |
| `…/ui/screens/home/HomeViewModelCatalogUtils.kt` | +7/-1 | app-core | THREE-WAY | |
| `…/ui/screens/home/HomeUiState.kt` | +7/-0 | app-core | THREE-WAY | |
| `…/ui/navigation/NuvioNavHost.kt` | +6/-0 | app-core | THREE-WAY | |
| `app/src/full/java/com/nuvio/tv/updater/ApkInstaller.kt` | +5/-0 | app-core | THREE-WAY | |
| `…/data/remote/dto/TorboxDto.kt` | +4/-1 | app-core | THREE-WAY | |
| `…/ui/screens/home/HeroBackdropState.kt` | +5/-0 | app-core | THREE-WAY | |
| `…/ui/screens/home/ModernHomeRowsList.kt` | +5/-0 | app-core | THREE-WAY | |
| `…/ui/screens/addon/AddonManagerScreen.kt` | +3/-1 | app-core | THREE-WAY | |
| `…/ui/screens/home/HomeViewModel.kt` | +3/-0 | app-core | THREE-WAY | |
| `…/ui/screens/settings/LiveTvSettingsContent.kt` | +3/-0 | app-core | THREE-WAY | |
| `…/ui/navigation/Screen.kt` | +2/-0 | app-core | THREE-WAY | |
| `…/ui/screens/addon/AddonManagementAccess.kt` | +0/-1 | app-core | THREE-WAY | |
| `README.md` | +488/-112 | infra/docs | THREE-WAY | |
| `.github/ISSUE_TEMPLATE/config.yml` | +2/-2 | infra/docs | THREE-WAY | |
| `assets/brand/app_logo_mark.png` | +-/-- | infra/docs | THREE-WAY | |
| `assets/brand/app_logo_wordmark.png` | +-/-- | infra/docs | THREE-WAY | |
| `app/src/main/res/drawable/ic_launcher.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-hdpi/ic_launcher.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-hdpi/ic_launcher_arctic_blue.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-hdpi/ic_launcher_copper.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-hdpi/ic_launcher_emerald.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-hdpi/ic_launcher_graphite.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-hdpi/ic_launcher_rose_gold.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-mdpi/ic_launcher.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-mdpi/ic_launcher_arctic_blue.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-mdpi/ic_launcher_copper.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-mdpi/ic_launcher_emerald.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-mdpi/ic_launcher_graphite.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-mdpi/ic_launcher_rose_gold.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xhdpi/ic_launcher.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xhdpi/ic_launcher_arctic_blue.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xhdpi/ic_launcher_copper.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xhdpi/ic_launcher_emerald.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xhdpi/ic_launcher_graphite.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xhdpi/ic_launcher_rose_gold.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher_arctic_blue.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher_copper.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher_emerald.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher_graphite.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher_rose_gold.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher_arctic_blue.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher_copper.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher_emerald.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher_graphite.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher_rose_gold.png` | +-/-- | resources | THREE-WAY | |
| `…/livetv/sync/LiveTvDriveSync.kt` | +28/-2 | nuvioone-feature | THREE-WAY | |
| `app/src/main/res/drawable-nodpi/tv_banner.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/drawable/tv_banner.png` | +-/-- | resources | THREE-WAY | |
| `app/src/main/res/values/strings.xml` | +147/-3 | strings | THREE-WAY | |
| `app/src/main/res/values-ar/strings.xml` | +1/-7 | strings | THREE-WAY | |
| `app/src/main/res/values-sk/strings.xml` | +0/-7 | strings | THREE-WAY | |
| `app/src/main/res/values-pt-rPT/strings.xml` | +3/-0 | strings | THREE-WAY | |
| `app/src/debug/res/values/strings.xml` | +1/-1 | strings | THREE-WAY | |
| `app/src/main/res/values-fr/strings.xml` | +1/-0 | strings | THREE-WAY | |
| `app/src/main/res/values-vi/strings.xml` | +1/-0 | strings | THREE-WAY | |
| `app/src/test/java/com/nuvio/tv/ui/screens/settings/ThemeSettingsViewModelTest.kt` | +5/-1 | tests | THREE-WAY | |
| `app/src/test/java/com/nuvio/tv/ui/screens/addon/AddonManagementAccessTest.kt` | +2/-2 | tests | THREE-WAY | |

## Added files (92) by area

- `app/src`: 25
- `core/launcher`: 25
- `ui/screens`: 12
- `ui/components`: 8
- `core/apps`: 4
- `livetv/lists`: 4
- `.github/workflows`: 3
- `site/spotify-link`: 2
- `MONET_LAUNCHER_COMPARISON.md/`: 1
- `PERFORMANCE_PLAN.md/`: 1
- `core/notification`: 1
- `core/profile`: 1
- `assets/brand`: 1
- `site/googlee97a1fa68d2248c4.html`: 1
- `site/index.html`: 1
- `site/nuviotv.png`: 1
- `site/privacy.html`: 1

Deleted: `.github/FUNDING.yml`
