# Nuvio One 1.2.0-beta.1.17

## Live TV

- Added **Filter channels by group tag** under **Settings → Live TV → Channels**. Scan your groups on demand, then choose tags like `US`, `UK`, `Arabic` or `Hindi` to hide matching groups. Tags match whole words and segments, so `AR` does not hide `Arena`. Favorites, custom-group channels and copied channels stay visible. The filter applies to the guide, All channels, search, home rows, Multiview, Sports and EPG listings, and is saved per profile.
- The scan is manual — nothing scans when playlists load. The tag list shows how many channels and groups each tag covers, and the settings row shows how many tags are selected.
- Back from Live TV search results returns to the group you were in before searching. Submitting or cancelling the search also closes the keyboard, so Back is never left going to the IME.
- In the group list, hold **Up** or **Down** for about two seconds to jump straight to the first or last group.
- Each profile can now choose which playlists it shows under **Settings → Live TV → Playlists**. The playlist sources and logins stay shared, so nothing is stored twice; a hidden playlist is left out of the guide, All channels, search, Home rows, Sports, Multiview and EPG listings for that profile.

## Playback

- Dolby Vision Profile 7 remuxes: official builds now include the native DV7 → DV8.1 bridge, so **Convert to DV8.1** works. If the bridge is unavailable, playback falls back to the HDR10 base layer instead of failing.
- Translated YouTube captions: the background now wraps the caption text instead of spanning the whole screen.
- OneTube keeps its first poster clear of the TV's overscan and the side menu, so cards are no longer clipped on the left after switching sections on sets like Hisense.
- A stuck OneTube video lookup now gives up after about a minute and shows a retry message instead of leaving the spinner up for several minutes. Normal lookups, which take a few seconds, are unchanged.
- VLC now stops and releases its native player when you leave playback. Repeated VLC use no longer leaves a decoder running in the background, which could build up memory and crash the app.

## Music

- Music videos now continue to the next track in the queue when they end. Repeat one replays the same video; repeat all wraps to the start; at the end of the queue the audio player's usual autoplay/stop behavior takes over.
- Now Playing shows where the track is actually playing from: YouTube, a Music addon's own name (for example Nebula or Tidodido), a download, WebDAV, Navidrome or this device.
