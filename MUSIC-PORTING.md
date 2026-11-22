# Porting the Music section

The Music section is merged into the `livetv-addons-work` branch. The original work was
developed in the `music-work` worktree at `H:\NuvioYouTubeFork\nuvio-music`, isolated from
the rest of Nuvio: almost every new file is under `com.nuvio.tv.music` or
`com.music.bitchord`, so the other branch mainly needed a small set of integration edits.

## What to bring over

Copy these paths unchanged:

| Path | Purpose |
|---|---|
| `app/src/main/java/com/music/bitchord/` | Ported BitChord YouTube Music catalogue client, parsers, models and lyrics. GPL-3.0; keep the README credit. |
| `app/src/main/java/com/nuvio/tv/music/` | Music UI, player, account bridge, downloads, Party Mode, WebDAV, Navidrome, Last.fm and Spotify import. |
| `app/src/main/assets/party/index.html` | The phone-facing Party Mode page. |
| `app/src/main/assets/navidrome-setup.html` | The phone-facing Navidrome linking page. |
| `app/src/main/assets/phone-setup.html` | The shared phone-linking page for WebDAV, Last.fm and Spotify. |
| `app/src/main/assets/licenses/` | Schibsted Grotesk OFL notice. |
| `app/src/main/res/font/schibsted_grotesk_*.ttf` | Music's typeface. |
| `app/src/main/res/drawable/ic_music_notification.xml` | Playback/download/party notification icon. |
| `design/music-ui/` | The Aurora design reference (not compiled). |
| `docs/MUSIC.md` | Feature, limits and manual test notes. |

## Shared-file edits

These are the only edits outside the copied folders. Apply them to the target branch, or
resolve them during a merge:

1. **`app/src/main/java/com/nuvio/tv/ui/navigation/Screen.kt`**
   Add `data object Music : Screen("music")`.

2. **`app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt`**
   Add a `composable(Screen.Music.route) { com.nuvio.tv.music.MusicScreen() }` entry.

3. **`app/src/main/java/com/nuvio/tv/MainActivity.kt`**
   - Import `androidx.compose.material.icons.filled.LibraryMusic`.
   - Add `Screen.Music.route` to `rootRoutes`.
   - Add `DrawerItem(route = Screen.Music.route, label = "Music", icon = Icons.Default.LibraryMusic)`
     after the YouTube item.
   - In `onStop()`, pause the Music player if it is playing/buffering.
   - In `LegacySidebarScaffold`, hide the host rail when the current route is Music and the
     drawer is closed, remove the content start padding on Music, and only draw the modern
     sidebar for Music when it is explicitly expanded.
   - Keep the existing `ModalNavigationDrawer` so Left on Music's own rail opens the host menu.

4. **`app/src/main/AndroidManifest.xml`**
   - `POST_NOTIFICATIONS` (playback notification).
   - `READ_EXTERNAL_STORAGE` (maxSdkVersion 32) and `READ_MEDIA_AUDIO` (local music).
   - Services:
     - `.music.MusicPlaybackService` (`mediaPlayback`)
     - `.music.MusicDownloadService` (`dataSync`)
     - `.music.MusicPartyService` (`dataSync`)
   - `FOREGROUND_SERVICE_DATA_SYNC` is already declared for Live TV; the two Music services
     use the same permission.

5. **`app/src/main/res/values/strings.xml`**
   Add `music_downloads_channel_name` and `music_party_channel_name`.

6. **`app/build.gradle.kts`** and **`gradle/libs.versions.toml`**
   The Music work depends on the existing Media3 and ZXing entries, plus:
   - `ktor-client-content-negotiation`
   - `ktor-serialization-kotlinx-json`
   (The BitChord catalogue client uses them.)

7. **`README.md`**
   Keep the BitChord GPL-3.0 credit in the Credits section.

## Merge options

Preferred: merge or cherry-pick the `music-work` branch into the target branch. The copied
folders do not exist in the target, so they merge cleanly; only the seven shared files above
can conflict.

If the changes are not committed yet, commit them on `music-work` first. Keep a single author
(the repository user) and do not add tool or co-author credits. Do not push until the user
approves.

After merging, verify:

1. `./gradlew.bat :app:assembleFullDebug -PlivetvUpdateRepo=… -PlivetvDebugAppId=…`
2. Install the armv7 APK on the projector.
3. Open **Music** from the side menu; check Home, Now Playing, Library, Downloads, Party,
   WebDAV, Navidrome and Integrations, including the phone-link panels.
4. Run the manual test in `docs/MUSIC.md`.
