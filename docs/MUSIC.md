# Music (YouTube Music)

The Music section is a port of [BitChord](https://github.com/kushagrasinghx/BitChord)'s
YouTube Music catalogue and its Apple-Music-inspired TV design into this app. BitChord is
GPL-3.0, as is Nuvio; the credit is in the README.

## Wave 10 (Navidrome / Subsonic)

- **Link with phone** — the Navidrome screen starts a short-lived LAN setup page and shows a QR
  code. Scanning it opens a phone form for the server URL, username and password. The TV tests
  the connection and stores only the derived Subsonic token and salt.
- **Type it on the TV** — the same screen keeps a full on-screen form for anyone who prefers the
  remote.
- **Token auth** — Subsonic's `u` / `t` / `s` / `v` / `c` / `f=json` parameters are generated
  locally. The password is never stored; the token is MD5(password + salt).
- **Browse** — the newest albums and the server's playlists, with cover art, artist names and
  track counts. Selecting an album or playlist opens its tracks.
- **Playback** — tracks stream from `/rest/stream?format=raw` through the normal Music player;
  cover art comes from `/rest/getCoverArt`. Queue persistence and Party Mode work with them.
- **Save playlists to Library** — a playlist detail page has **Save to Library**, which creates or
  replaces a local playlist with the same name.
- **Scrobbling and likes** — Subsonic `scrobble` calls are sent when a Navidrome track starts and
  when it passes the scrobble threshold. The heart calls `star` / `unstar` instead of trying to
  sync the track to YouTube.
- **Phone setup safety** — the setup page is LAN-only, carries a random one-shot token, expires
  after five minutes, and closes after a successful link.
- **WebDAV / Last.fm / Spotify phone linking** — the same QR panel is available on all three:
  WebDAV takes the URL and credentials, Last.fm takes the API key/secret/session key, and
  Spotify takes a public playlist URL and shows live import progress on the phone. The TV's
  own typing forms remain in place for anyone who prefers them.

## Wave 9 (playback durability and integrations)

- **Queue survives restarts** — the queue, current track, playhead, repeat mode and shuffle
  state are saved to a small local snapshot. Reopening Music shows the saved track paused;
  pressing Play resumes at the saved position instead of the start.
- **Hardware media buttons** — the MediaSession now routes Next/Previous from the TV remote,
  Bluetooth remotes and the system media controls into the Music queue.
- **Party Mode 2.0** — the host can require approval for every guest request, approve or decline
  pending songs on the TV, and guests can remove songs they added while those songs are still
  upcoming. Phones can also vote to skip the current track; two votes skip it. A foreground
  `MusicPartyService` keeps the LAN page reachable when the TV switches screens.
- **WebDAV** — connect a WebDAV collection from the Music rail, browse folders, stream audio
  with Basic auth, and queue a folder with **Play folder**. Streamed tracks remember their URL
  in the queue and use the configured credentials when played.
- **Spotify playlist import** — paste a public Spotify playlist link in Integrations. The
  importer reads the public embed page (no Spotify account or API key), matches each track on
  YouTube Music and creates a normal local playlist.
- **Last.fm scrobbling** — paste an API key, shared secret and session key in Integrations, then
  turn scrobbling on. Tracks are announced when playback starts and scrobbled after half their
  duration (or four minutes), with the standard Last.fm MD5 signature.
- **Integrations hub** — a single Music-rail page for Last.fm, Spotify import and WebDAV status,
  so setup is not buried in playback settings.

## Wave 8 (Party Mode)

- **Start a party** — the **Party** item in the Music rail opens the host screen. The TV
  generates a random session token and serves a mobile web page on the local network.
- **Scan to join** — the TV shows a QR code and the join URL. Guests use any phone browser;
  there is no app to install and no account to sign in.
- **Search and add** — the phone page searches YouTube Music through the TV's own catalogue
  session. Additions go to the end of the shared queue; the TV owns the lookup and the queue.
- **Host controls** — lock or unlock guest additions, skip the current track, remove individual
  upcoming tracks, clear the up-next list, and end the party. The TV shows the now-playing
  track, the upcoming queue, each track's contributor and the session addition count.
- **Guest identity** — an optional display name is stored on the phone and attached to that
  guest's additions. Only the first name is used; no account or persistent identity is created.
- **LAN only** — every page and API request carries the session token in the URL. There is no
  cloud relay and no internet exposure. The TV and phones must be on the same network.
- **Keeps playing** — party additions use the same queue as the Music section, so playback,
  downloads and Now Playing all stay in sync.

## Wave 7 (downloads and offline)

- **Download tracks and collections** — the download button in Now Playing saves the current
  track; detail pages add a **Download all** action for albums and playlists. The button fills
  with the overall progress and shows the percentage, and the Now Playing circle wears a
  matching progress ring.
- **Background queue** — Media3's `DownloadManager` and a foreground `MusicDownloadService`
  keep downloads moving while the viewer browses. Preparation is serialised, so a 100-track
  playlist does not fire 100 stream lookups at once.
- **Downloads manager** — Library → Downloads shows active, failed and completed downloads,
  per-track sizes, total storage and free space. Pause/resume all, cancel, retry, remove a
  single download and clear finished downloads are available.
- **Offline playback** — a completed download is played from the Media3 cache before the
  player asks the network for a stream. Cache-backed playback was verified after a cold app
  restart with the device's network blocked.
- **Downloaded library** — the Library entry shows the downloaded track count and total size;
  downloaded rows are marked **plays offline**.
- **Artwork** — the high-resolution cover used by Now Playing and the list thumbnail are
  prefetched into Coil's disk cache when a track is downloaded.
- **Download settings** — Music settings gains Download quality (Low/Medium/High) and
  Wi-Fi only. Quality applies to new downloads; Wi-Fi only makes Media3 wait for an
  unmetered network.

## Wave 6 (YouTube Music account)

- **TV sign-in** — the account button in the Music top bar opens a QR/device-code panel using
  the same Google sign-in as the YouTube section. Library also offers a sign-in card.
- **Personalised Home** — when signed in, Home loads the account's YouTube Music shelves from
  the app's YouTube engine. Signed out, or if the account home is empty, it falls back to the
  public YouTube Music home.
- **Account library** — Library shows the account's Liked Music and playlists above the local
  likes and playlists, so device-only and account-only collections stay distinct.
- **Likes sync** — the heart updates the local library immediately and syncs the like to
  YouTube Music in the background.
- **Account switching** — the panel lists every Google account and channel profile on the
  device, with add-account and sign-out actions.

## Wave 5 (playback and settings)

- **Music settings** — the gear in the Music top bar (and in Now Playing) opens a full-screen
  settings panel. Each row is one focus target: Left/Right changes the value and Up/Down moves
  between settings. Choices are stored locally in `nuvio_music_settings`.
- **Audio quality** — Low / Medium / High cap the selected audio bitrate at roughly 96 /
  192 / 320 kbps. The source still supplies the best stream under that ceiling.
- **Autoplay** — when the queue ends with repeat off, a radio mix starts from the last track.
  Local files stop instead, because they have no YouTube seed.
- **Sleep timer** — Off / 15 / 30 / 45 / 60 / 90 minutes; playback pauses when it expires and
  the settings panel shows the remaining time.
- **Skip silence** — ExoPlayer's silence skipping, for tracks with long quiet passages.
- **Playback speed** — 0.75x to 2x with pitch preserved. Lyrics and progress follow the media
  clock, so they stay in sync.
- **Track transition** — Gapless uses the prefetched next stream; Fade 2s / Fade 4s ramps the
  outgoing track down and the incoming track up. This is a fade, not an overlapping mix.
- **Equalizer** — Off / Flat / Bass / Vocal / Rock, mapped over the TV's own equalizer bands.
  The preset is remembered even on a device where the effect is unavailable.

## Wave 4 (lyrics)

- **Lyrics in Now Playing** — the **Lyrics** chip replaces the up-next queue with the
  current track's lyrics; the same chip becomes **Queue** to switch back.
- **Synced lines** — when the provider has timings, the current line is highlighted and the
  list scrolls to follow playback. Word-synced sources (Apple Music TTML) also light each
  word as it is sung.
- **Full-screen lyrics** — the expand icon in the lyrics header fills the Now Playing area
  with the words; the collapse icon or Back returns to the artwork/queue layout.
- **Plain fallback** — unsynced lyrics are shown as readable text without highlighting or
  auto-scroll.
- **Provider fallbacks** — BiniLyrics (Apple Music TTML through lrc.red), BetterLyrics,
  LRCLIB, YouTube's transcript, then YouTube Music's Lyrics tab. Providers are queried
  concurrently, with answers taken in that priority order, and the winning source is named
  in the panel header.

## Wave 2 and 3 (library, local music, sharing)

- **Library** — local Liked Songs, playlists and Recently Played, with a Play All action.
  This library lives on the device; YouTube-account sync comes later.
- **Likes** — the heart on Now Playing adds or removes the current track from Liked Songs.
- **Playlists** — create one from Now Playing's Playlist action, add the current track, and
  open it from Library. Deleting a playlist is available on its page.
- **Radio** — starts a YouTube Music radio queue from the current track.
- **Shuffle and repeat** — shuffle the rest of the queue, and cycle repeat off / all / one.
- **History** — every track that starts playing is added to Recently Played.
- **Local Music** — scans the device, common music folders and mounted USB drives
  (`/storage`, `/mnt/usb`, app-accessible volumes). Requires storage permission; press Scan after
  plugging in a drive. Local files play through the same player and queue.
- **Share over network** — a tokenised LAN page (`http://<tv-ip>:<port>/m/<token>/`) lists every
  local track with individual download links and a streamed ZIP. It shows a QR code and stops from
  the same panel.
- **Copy to Google Drive** — connects with Google's TV device-code flow and uploads local tracks to
  a “Nuvio Music” folder in the user's own Drive using the `drive.file` scope.
- **Keep awake** — while music is playing, the Music section holds the TV's screen on so the
  screensaver does not interrupt listening.

## Wave 1 (the foundation)

- **Home** — YouTube Music's signed-out home shelves, with the first shelf promoted to a
  large hero card. Pulls the next page as you reach the end.
- **Explore** — moods and genres from `FEmusic_moods_and_genres`; each category opens its
  own shelves.
- **Search** — YouTube Music's mixed search plus the Songs / Videos / Albums / Artists /
  Playlists filters, typeahead suggestions, and continuation pages.
- **Detail pages** — albums, playlists and artists, with a Play action and the full track
  list.
- **Now Playing** — the large album-art screen: transport, seek, queue, and the up-next list.
- **Mini player** — stays at the bottom of the Music section while browsing, and opens
  Now Playing.
- **Background playback** — `MusicPlaybackService` (a foreground media service with a MediaStyle
  notification) keeps audio playing when the music screens are not on screen.

## How it fits together

| Piece | Where | Notes |
|---|---|---|
| Catalogue client | `app/src/main/java/com/music/bitchord/` | Copied from BitChord's `shared` module: the WEB_REMIX InnerTube client, its parser and `YtMusicRepository`. Package names are unchanged so future BitChord syncs are easier. |
| Music UI | `app/src/main/java/com/nuvio/tv/music/` | Home, Explore, Search, Detail, Now Playing, components. |
| Stream lookup | `MusicStreamResolver.kt` | Uses the same MediaServiceCore engine as the YouTube tab, so there is one sign-in and one set of client fallbacks. |
| Playback | `MusicPlayer.kt`, `MusicPlaybackService.kt` | One audio-only ExoPlayer; video tracks are disabled in the track selector. The queue lives here, because stream URLs are resolved per track. |
| Lyrics | `MusicLyricsRepository.kt`, `MusicLyricsViewModel.kt`, `com/music/bitchord/data/lyrics/` | BitChord's TTML/LRC parsers and providers, raced with per-provider deadlines. |
| Settings | `MusicSettings.kt`, `MusicSettingsScreen.kt` | Persisted playback preferences and the TV settings panel. |
| Equalizer | `MusicEqualizer.kt` | Android audio effect attached to the player's audio session. |
| Account | `MusicAccountRepository.kt`, `MusicAccountScreen.kt` | Bridges the signed-in YouTube engine's Music home, liked songs, playlists and like actions into the Music UI. |
| Manifest route | `Screen.Music` + `MainActivity` drawer | A separate Music item in the side menu. |

BitChord's own player, Spotify, Last.fm, Discord, WebDAV, Automix, true overlapping
crossfade and its settings are **not** part of these waves yet.

## Known limits

- The local library is device-only. The signed-in account library is shown separately; local
  likes and playlists do not sync to YouTube Music (only the heart does).
- Account playlists are read-only in this wave; creating or editing them is not implemented.
- The heart's filled state follows the local library. Likes made on YouTube Music elsewhere
  appear in the account Library section, not as local likes.
- Account data comes from the app's YouTube engine (OAuth); BitChord's cookie-based account
  sync is not ported. The engine's TV Music layout can differ from the web layout.
- Sign-in is per device; the TV code must be approved on a phone or computer.
- Lyrics come from public providers; some tracks only have plain lyrics, and word-level timing is
  only available when the provider supplies it. Instrumental tracks can return no lyrics.
- Downloads live in the app-external `Music/NuvioDownloads` folder; choosing a different
  storage location (such as a USB drive) is not implemented. The streamed-content portion of
  that cache is capped at 512 MB and evicts least-recently-used entries; completed downloads
  are pinned.
- Download quality only affects new downloads, and a source that offers one bitrate cannot be
  lowered further. Some tracks fail to download (age, region or unavailable streams); they stay
  in the manager as retryable failures.
- Offline covers depend on Coil's disk cache being filled while online. A track downloaded
  before artwork prefetch existed may show the generated gradient until it has been online once.
- Party Mode has no cloud relay, so guest Wi-Fi, VPNs and router AP isolation can block the
  phone page. The TV stays awake while a party is active, and a foreground party service keeps
  the page alive when the viewer switches screens, but killing the app closes the session.
  Guest identity is a random per-browser id, not an account; there is no cloud authentication.
  Skip votes need two phones, and host approval can still be toggled off for open requests.
  A declined request disappears from the TV; the phone does not currently show a separate
  “declined” state.
- Queue persistence is local to the TV. It survives process death and reinstall-free restarts,
  but it is not synced across devices.
- True overlapping crossfade and BitChord-style Automix are still not implemented; the existing
  **Fade** transition is a fade-out followed by a fade-in, and gapless playback still depends on
  the next stream being prefetched before the current one ends.
- WebDAV supports Basic auth only (no OAuth, digest auth or moving/copying files). Audio is
  streamed, not downloaded into the offline cache.
- Spotify import reads up to 100 tracks from a public playlist and matches them by title and
  artist; private playlists, unmatched tracks and playlists behind a login are not supported.
- Last.fm needs credentials created outside the app. Scrobbling is best-effort: failures are
  logged and do not interrupt playback.
- Discord presence is not implemented; there is no Discord client on Android TV to attach to.
- Navidrome browsing currently covers the newest albums and playlists; artist browsing, server
  search and offline downloading of Navidrome tracks are not implemented. The album list is
  capped at the newest 60 albums.
- Navidrome needs a valid HTTPS certificate if the server is exposed to the internet; the app
  does not offer a self-signed-certificate bypass. The phone setup page is plain HTTP on the
  local network for the short linking window.
- Saving a Navidrome playlist replaces a local playlist with the same name; there is no
  two-way playlist editing, play-count import or library mirror.
- Crossfade is a fade-out/fade-in; tracks do not overlap. Gapless depends on the next stream being
  prefetched, so a slow lookup can still leave a gap.
- Equalizer availability depends on the TV's audio effects; some Fire OS builds do not expose one.
- Audio quality caps the selected track; a source that only offers one bitrate cannot be lowered
  further.
- Local scanning needs storage permission; on newer Android versions MediaStore and the common
  public folders are used, while older Fire TV builds also get a direct `/storage` and `/mnt/usb`
  walk.
- The LAN share page runs inside the app process; keep the TV on the Local Music screen while
  transferring. Google Drive uploads use `drive.file` (files the app creates), not a full
  two-way Drive sync.
- Media buttons can play/pause and move Next/Previous through the Music queue; a few TV
  remotes only map play/pause in their firmware.
- Artist pages show YouTube's top songs, not the complete catalogue.
- Radio is started manually; the queue stops at its end when repeat is off.

## Manual test

1. Build the full debug variant:
   `.\gradlew.bat :app:assembleFullDebug "-PlivetvDebugAppId=com.nuvio.music.test"`
2. Install it and open **Music** from the side menu.
3. Home should show real shelves (e.g. "Throwbacks", "The Hits: '80s").
4. Search for an artist, open a song and check that Now Playing appears and audio starts.
5. Close Now Playing; the top-right now-playing chip should keep the track going while you browse.
6. Open the queue in Now Playing and jump to another track.
7. With a track playing, choose **Lyrics**; a source should appear and the current line should
   highlight and scroll. Choose the expand icon to fill the screen, then the collapse icon or Back.
8. Try a track the providers are unlikely to have; the panel should say no lyrics were found
   instead of crashing.
9. Open the gear in the Music top bar; use Up/Down to move between rows and Left/Right to
   change a value. Set **Audio quality** to Low and confirm playback continues.
10. Set **Playback speed** to 1.25x; progress and lyrics should move faster.
11. Set a 15-minute **Sleep timer** and confirm the countdown appears.
12. Enable **Skip silence** and **Fade 2s**; skip between tracks and listen for the fade.
13. Pick an **Equalizer** preset; if the TV does not support effects, the panel says so.
14. Open the account button in the Music top bar; sign in with the TV code on a phone.
15. Home should switch to the account's personalised shelves.
16. Library should show YouTube Music liked songs and playlists above the local sections.
17. Like a song on Now Playing; it should appear locally and in YouTube Music's liked music.
18. Switch accounts or sign out; Home and Library should refresh or fall back to signed-out.
19. Open an album or playlist, choose **Download all** and watch the pill fill with the
    percentage; the Now Playing download circle should show the same progress.
20. Open Library → **Downloads**; check the track count, size and free space. Pause/resume,
    retry a failed track, remove one download and clear finished entries.
21. In Music settings, change **Download quality** and toggle **Wi-Fi only**; both should
    survive leaving and reopening the panel.
22. Force-stop the app, disconnect the TV from the network (or point the device proxy at a
    dead port), relaunch and play a completed download from Library → Downloads. It should
    start from cache.
23. Open **Party**, choose **Start party**, and scan the QR code from a phone on the same Wi-Fi.
24. On the phone page, search for a song and add it; the TV queue should update and playback
    should start if nothing was playing.
25. Lock additions on the TV; a new phone add should be rejected with “Locked”. Unlock again.
26. Remove an upcoming song, clear the up-next list, skip the current track, then end the party;
    the join page should no longer accept additions.
27. Play a track, let it reach the middle, force-stop the app and relaunch Music. The chip should
    show the same track paused; open Now Playing, confirm the saved duration/position, press Play
    and confirm it resumes there instead of restarting.
28. Press the remote/Bluetooth Next and Previous media keys; the queue should move instead of
    ignoring them.
29. In Party, turn **Approval On**; add a song from the phone. It should appear under Requests on
    the TV. Approve it and confirm it enters the queue; try a second one and decline it.
30. Add a song from the phone, then use the **Remove** action on that row on the phone; it should
    disappear from the TV queue.
31. With two phones (or two browser profiles), use **Vote to skip**; the second vote should skip
    the current track.
32. Close the Party overlay or switch screens; the phone page and server should stay responsive,
    and the “Party is live” notification should be present. End the party and confirm both stop.
33. Open **Integrations → WebDAV**, connect a collection URL (and credentials if needed), browse
    folders and play a file with **Play folder**.
34. Paste a public Spotify playlist link in **Integrations → Spotify playlists** and import it;
    the new playlist should appear in Library with matched tracks.
35. In **Integrations → Last.fm**, save an API key, shared secret and session key, turn scrobbling
    on and confirm a track is announced and then scrobbled after the threshold.
36. Open **Integrations → Navidrome → Link Navidrome** and scan the QR on a phone. Enter the
    server URL, username and password there; the TV should switch to the album list.
37. Repeat the link using **Or type it here** on the TV to confirm the manual path.
38. Open an album, play it and confirm the stream and cover art; open a playlist and choose
    **Save to Library**, then check Library for the imported playlist.
39. Like a Navidrome track and confirm the heart updates locally; the TV should send `star` to
    the server rather than syncing the track to YouTube.
40. Open **WebDAV → Server → Link with phone**, submit the URL/credentials on the phone and
    confirm the TV switches to the folder listing.
41. Open **Integrations → Last.fm → Link with phone**, submit the API key, secret and session
    key on the phone, then enable scrobbling on the TV.
42. Open **Integrations → Spotify → Link with phone**, submit a public playlist URL on the phone
    and confirm the progress lines and final imported count appear there.
