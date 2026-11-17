# Nuvio One 1.2.0-beta.1.17

## Live TV

- Added **Filter channels by group tag** under **Settings → Live TV → Channels**. Scan your groups on demand, then choose tags like `US`, `UK`, `Arabic` or `Hindi` to hide matching groups. Tags match whole words and segments, so `AR` does not hide `Arena`. Favorites, custom-group channels and copied channels stay visible. The filter applies to the guide, All channels, search, home rows, Multiview, Sports and EPG listings, and is saved per profile.
- The scan is manual — nothing scans when playlists load. The tag list shows how many channels and groups each tag covers, and the settings row shows how many tags are selected.
- Back from Live TV search results returns to the group you were in before searching. Submitting or cancelling the search also closes the keyboard, so Back is never left going to the IME.
- In the group list, hold **Up** or **Down** for about two seconds to jump straight to the first or last group.

## Playback

- Dolby Vision Profile 7 remuxes: official builds now include the native DV7 → DV8.1 bridge, so **Convert to DV8.1** works. If the bridge is unavailable, playback falls back to the HDR10 base layer instead of failing.
- Translated YouTube captions: the background now wraps the caption text instead of spanning the whole screen.

## Music

- Music videos now continue to the next track in the queue when they end. Repeat one replays the same video; repeat all wraps to the start; at the end of the queue the audio player's usual autoplay/stop behavior takes over.
- Now Playing shows where the track is actually playing from: YouTube, a Music addon's own name (for example Nebula or Tidodido), a download, WebDAV, Navidrome or this device.
