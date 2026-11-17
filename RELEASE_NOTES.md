# Nuvio One 1.2.0-beta.1.16

## Subtitles

- Fixed subtitles going blank when a background colour, custom font, or drop shadow was selected. The styled subtitle overlay is restored after an upstream merge dropped it.
- Fixed Croatian/Serbian translated captions not showing. Media3 reports `hbs-hrv`/`hbs-srp` while YouTube uses `hr`/`sr`; the player now matches those codes.
- Fixed duplicate subtitles when a translated YouTube track (marked `*`) is selected: the stale original cue is no longer drawn above the translated line.
- Translated captions no longer stop early: if YouTube's translated file ends before the video, the player falls back to the locally generated 60-second segments, retries failed segments, and keeps the caption loop alive after transient errors.
- Caption language filtering understands the same aliases, so Croatian/Serbian tracks are kept instead of filtered out.

## Music & Spotify

- Link Spotify from Music → Integrations with the desktop-style web login (mobile user agent, D-pad cursor, cursor hides with the keyboard).
- Spotify **Liked Songs** now go into the app's **Liked Songs**; Spotify playlists become their own local playlists. The old “Spotify Liked Songs” playlist is migrated into Liked Songs and removed.
- Songs appear in the library while syncing; Music Home and Explore are cached so they open quickly.
- Sync backs off when YouTube Music rate-limits: one match at a time, 600 ms pause, a 20 s wait on the first 403, then a clear message. Tracks already added are kept.
- Bottom-right sync status pill and friendly rate-limit errors across Music Home, Explore, Search and detail.
- Last.fm sign-in is configured in this build.

## Home / Live TV

- On Demand Continue Watching row on Home, with a toggle under **Settings → Live TV → On Demand**. It uses the existing On Demand progress and removes those items from the normal Continue Watching row while active.

## Playback

- Hardware video decoder failures retry once with the platform software decoder; `ERROR_CODE_DECODING_RESOURCES_RECLAIMED` is treated as retryable; decoder errors show a short message. Seek thumbnails default off again on low-end boxes to avoid stealing the only hardware decoder.
