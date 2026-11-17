# Nuvio One 1.2.0-beta.1.17

## Live TV

- Added **Filter channels by group tag** under **Settings → Live TV → Channels**. Scan your groups on demand, then choose tags like `US`, `UK`, `Arabic` or `Hindi` to hide matching groups. Tags match whole words and segments, so `AR` does not hide `Arena`. Favorites, custom-group channels and copied channels stay visible. The filter applies to the guide, All channels, search, home rows, Multiview, Sports and EPG listings, and is saved per profile.
- The scan is manual — nothing scans when playlists load. The tag list shows how many channels and groups each tag covers, and the settings row shows how many tags are selected.

## Playback

- Dolby Vision Profile 7 remuxes: official builds now include the native DV7 → DV8.1 bridge, so **Convert to DV8.1** works. If the bridge is unavailable, playback falls back to the HDR10 base layer instead of failing.
- Translated YouTube captions: the background now wraps the caption text instead of spanning the whole screen.

## Music

- Music videos now continue to the next track in the queue when they end. Repeat one replays the same video; repeat all wraps to the start; at the end of the queue the audio player's usual autoplay/stop behavior takes over.
