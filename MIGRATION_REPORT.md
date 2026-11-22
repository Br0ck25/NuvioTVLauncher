# Migration report: Nuvio One `main` @ 1.2.0-beta.3.2 → Nuvio Launcher

## What was merged

| | |
|---|---|
| Destination | `arena/164ee7eb-nuviotvlauncher`, branched from `dev` @ `4ded3ceb0` |
| Upstream | `zoga2o3-ops/Nuvio-One` branch `main` @ `3dfa4b0b6` ("Release notes: Nuvio One 1.2.0-beta.3.2") |
| Base the fork was last synced from | Nuvio One `hotfix/17.2.1` @ `5780885f9` (1.2.0-beta.1.17.2.1) |
| Upstream commits brought in | 414 |
| Files changed | 798 (377 added, 416 modified, 1 deleted) |
| Diff size | +80,310 / −8,755 |
| Conflicts | 25, all resolved by hand |
| Merge strategy | plain `git merge`, **no `-X ours` / `-X theirs`**, no conflict markers committed |

Upstream stopped publishing from `hotfix/17.2.1` and moved its release line to `main`, so this merge
also moves the sync target (see *Repository plumbing*).

## Conflict resolutions

Resolutions follow one rule: **take upstream's structure** where upstream has been maintaining the
code, and **keep the fork's semantics** where the fork is the only one maintaining them. Neither side
was taken wholesale for any file.

### Behaviour-bearing files

| File | Resolution |
|---|---|
| `HomeViewModel.kt` | Constructor union. Upstream's new required `trailerPlayerPool` and `backgroundTrailerController` sit **before** the fork's defaulted `installedAppsRepository`, so the three unit tests that construct the ViewModel by name and omit it still compile. |
| `CatalogOrderViewModel.kt` | Adopted upstream's pin-aware pipeline (`computeFollowAddonsOrderKeys`, `normalizeCollectionPositions`, `unpinnedEffectiveOrder`); dropped the fork's earlier ad-hoc follow-addons block, which computed the same thing less correctly. Pinned collections occupy `0 until firstUnpinnedIndex`; the Apps-row hoist may only push that boundary by one when the user has not placed Apps; in follow mode addon rows are immovable and collections may move anywhere below the pinned boundary. The fork's `lastManifestAddonKeys` is kept for the manifest-reorder action. On-disk keys (`order`, `disabled`, `disableKeys`) are unchanged. |
| `ModernHomeRows.kt` | Kept the fork's `isUpcomingRow`-aware Continue Watching artwork height (the helper in `ContinueWatchingSection.kt` takes `isUpcomingCard`), while taking upstream's prefetch cache key type (`Pair<String, MemoryCache.Key>`) and `cardImageMemoryCacheKey(...)`. The fork's skip of `InstalledApp` rows is retained. |
| `NuvioApplication.kt` | Upstream's image-loader/trailer wiring plus the fork's launcher interceptors; the direct field references that upstream's lazy Hilt bindings broke were replaced with lazy lookups. |
| `MainActivity.kt` | Upstream's `BackgroundTrailerController` composition local, tracking-addon consent dialog and sidebar focus-recovery signature (`sidebarHasFocus`); the fork's launcher setup wizard, double-Home profile switcher, Apps page, button-mapper plumbing and Live TV sidebar gating are untouched. |
| `ProfileManager.kt` | Fork's per-profile VPN re-keying kept; upstream's new profile-flow guards taken. |
| `LiveTvPreferences.kt` | Per-profile key mapping (`my`/`own`, `MatchGroups`) retained. **Rejected** upstream's `own(Keys.sideMenuHidden, me)` write: it mis-keys that preference and would stomp the shared profile-1 key. The fork's first-profile-only write stays. |
| `LiveTvRecording.kt` / `LiveTvRecordingScheduler.kt` | Upstream's direct-to-SMB recording/`ownerProfile`/`prepare(owner)` plumbing merged with the fork's `allRecordings()` semantics. **Rejected** upstream's `recorder.recordings.value` for the already-recorded check (the fork needs the merged list); upstream's `setNow` dedup is kept. |
| `LiveTvSettingsViewModel.kt` | Union of upstream's settings keys and the fork's SMB/VPN sections. |
| `MusicSpotifyLink.kt` | Upstream's `loadedProfile`/`appContext` lifetime, fork's per-profile Spotify prefs. |
| `YouTubeProgress.kt` | Upstream's `store()`/`parse()` shape over the fork's per-profile store: `ActiveProfile.prefs(context.applicationContext, "youtube_progress")` replaces upstream's global `getSharedPreferences`. |
| `SettingsScreen.kt` | Union: upstream's `SettingsRemote`/`Home`/`Shield` imports and the fork's `DEFAULT_LAUNCHER`, `REMOTE_BUTTONS` and `VPN` sections (all `SettingsSectionEntry.Inline`). |
| `ApkDownloader.kt` | Upstream's download loop, fork's single trailing flush. |
| `README.md`, `RELEASE_NOTES.md` | Rewritten around the new upstream base; see below. |
| 11 × `values*/strings_iptv_settings.xml` | Upstream string-union, fork's own keys preserved. |

### Rejected upstream changes

Only two, both because upstream was writing to a shared key while the fork writes per profile:

1. `LiveTvPreferences`: `own(Keys.sideMenuHidden, me)` — mis-keyed and profile-1-only data loss.
2. `LiveTvRecordingScheduler`: `recorder.recordings.value` for the already-recorded check — silently
   sees only the active profile's list.

### Deleted by upstream, accepted

- `app/src/test/java/com/nuvio/tv/livetv/ondemand/OnDemandLookupTest.kt` — upstream's `onDemandLookup`
  helper no longer exists anywhere in the tree (`grep -rn "fun onDemandLookup"` is empty), so the test
  could not compile. Removing it follows upstream's own commit `decafeae9`, which states the same reason.
- 8 unused default string keys (`autosync_toast_no_fit`, `library_empty_local_*`, `library_type_items`,
  …). Verified unused: no `R.string.<key>` reference remains.

## Kept fork integrations

97 files that exist only in this fork were left intact. The ones that matter:

- **Launcher layer** — `core/launcher/*` (home-button override, accessibility service, boot receiver,
  button mapper, phone pairing/on-device ADB setup, self-setup, startup timing), `core/notification/`,
  `ui/screens/apps/*`, `ui/screens/setup/LauncherSetupWizard.kt`, `AppIconManager`, the
  `launcher.*` activities/aliases, and `full/AndroidManifest.xml`'s launcher receivers/services.
- **Live TV fork** — VPN (`vpn-core`, `vpn-android`, `core/vpn`, settings), SMB recording and playback,
  Google Drive sync, calendar home feed, sports matcher hooks, On Demand, multiview, the IPTV settings
  pages and the `iptv_*` strings.
- **Branding/plumbing** — `com.nuvio.tv.launcher` app ID for debug+release (`…launcher.test` for test
  builds), Nuvio Launcher name/banners/icons, the release workflow, and the fork's versioning.
- `LICENSE` (GPL-3.0) unchanged, as upstream.

## Versioning, app ID and signing

Deliberately unchanged from the fork:

- `applicationId` → `com.nuvio.tv.launcher` for `full` debug and release; `com.nuvio.tv.launcher.test`
  for `-PlivetvTestBuild=true`; playstore flavour keeps its own IDs.
- `versionCode = base × 100 + livetvBuildNumber`, `versionName` from `-PlivetvVersionName`.
- Test-build versioning (`200_000_000 + minutes since 2026`) unchanged, so the tester update track keeps
  ordering correctly.
- The release workflow's keystore handling and its check against the 1.1.x signing certificate SHA-256
  are untouched.
- `defaultConfig` version bumped exactly as upstream did: `1078` / `1.2.0-beta.1` → `1080` / `1.2.0-beta.3`.

## Repository plumbing

- `.github/workflows/sync-upstream.yml`: `UPSTREAM_BRANCH` default `hotfix/17.2.1` → **`main`**, with the
  explanatory comment updated. `vars.NUVIOONE_SYNC_BRANCH` still overrides it. Without this the scheduled
  sync would keep re-merging the stale `hotfix` branch.
- `README.md`: added the upstream base line, the direct-to-SMB recording note, and the corrected sync
  branch. `RELEASE_NOTES.md` rewritten for 1.2.0-beta.3.2 and no longer points at the retired
  `UPSTREAM_AUDIT.md`.
- `.github/FUNDING.yml` stays deleted (a destination decision, not part of this migration).

## What upstream brings

Highlights visible to users: hardware-bitmap image decoding option, season posters on details,
continue-trailer-in-details, Plex Media Server support, styled subtitles on mpv, subtitle/trailer
improvements, audio-sync (sherpa-onnx runtime fetched and SHA-256-verified at build time), updater
polish (cancel, relaunch, optimized-install warning), addon tracking consent, the TV drag-reorder for
Home catalogs, and a large block of localization work.

## Verification

**Performed in this environment**

- Zero conflict markers anywhere in the tree (only `=======` lines in vendored `commons-io`
  release notes, which are original text).
- All 345 XML files (manifests, resources) parse as well-formed XML.
- No duplicate resource names within a configuration (0 conflicts across all `values*` and
  `drawable*`/`mipmap*` folders).
- Every `R.<type>.<name>` reference in the tree resolves to a declared resource (0 unresolved; the
  seven apparent misses are `android.R`/media3 `R` references, verified by their imports).
- String resources reconcile exactly: 6,239 default keys, 0 keys that exist only in translations.
- Bracket balance across all 2,062 Kotlin/Java sources with a string/comment-aware scanner (0 failures).
- Kotlin **parser** sweep of `app/src/main` in 37 chunks — checks syntax only, since the Android SDK and
  Maven repositories are unreachable in this sandbox.
- Declaration cross-check: no fork declaration removed by the merge is still referenced.
- All 18 workflow YAML files parse.

**Not performed here, and why:** no Gradle build locally. This sandbox has no JDK, no Android SDK, and
no route to `dl.google.com`, `maven.google.com`, `repo.maven.apache.org` or `plugins.gradle.org` (only
GitHub, PyPI and npm are reachable), so dependencies cannot be resolved.

**Build and tests, run in CI on this branch:** the repository's own **PR Full Debug Build** workflow
(`:app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.*'` then `:app:assembleFullDebug`) passes,
and it produced `app-full-universal-debug.apk` (360.4 MB). That compiler run is what found the two
defects listed below, so those fixes are verified by a real build, not by inspection.

## Defects the build found, and their fixes

The sandbox could not compile, so CI compiled it. That found three things, all now fixed and verified
by a green build:

1. **`UpdatePreferences.kt` — duplicate key, property and setter.** Upstream added `optimizeDuringInstall`
   support while the fork already had its own copy. Git merged both cleanly because the hunks sat in
   different places in the file, so nothing conflicted; only the compiler could see it.
2. **`CalendarHomeFeed.kt` — `appContext` declared twice and assigned twice in `init()`.** Same cause:
   upstream and the fork each added the same field in a different spot.
3. **`checkFullDebugDuplicateClasses` / `mergeFullDebugJavaResource` — BouncyCastle.** Upstream's SMB
   support adds `eu.agno3.jcifs:jcifs-ng`, which depends on `org.bouncycastle:bcprov-jdk18on`. The fork
   pins `bcprov-jdk15to18` for the on-device ADB identity certificate, and both jars ship the same
   `org.bouncycastle.*` classes. Fixed by keeping the pinned provider and excluding jcifs's copy
   (`exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")`); jcifs only asks it for the JCE
   provider, which the pinned jar supplies, and upstream's 1.2.0-beta.3.2 MD4/SMB keep rules still apply.

Classes 1 and 2 are the failure mode this kind of sync produces most often: a real conflict is easy,
but two independent additions of the same member are invisible to `git merge` and surface only at
compile time. That is why the build gate, not the merge itself, is the real check.

## Known issue found, not introduced here

`app/src/full/AndroidManifest.xml` declares `com.nuvio.tv.launcher.AppIconDefault` as an `<activity>`
while `app/src/main/AndroidManifest.xml` declares the same component name as an `<activity-alias>`. This
mismatch already exists at `4ded3ceb0` (the full manifest is byte-identical before and after this
merge); it is **not** a merge regression, so it was left alone rather than folded into a migration PR.
It is worth a separate look, since `AppIconManager` toggles components by name.
