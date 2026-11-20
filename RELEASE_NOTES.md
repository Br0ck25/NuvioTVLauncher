# Nuvio One 1.2.0-beta.3.1

## Live TV

- A guide for each playlist, On Demand from M3U playlists, a DNS setting (System, Cloudflare or Google), copy several channels to a group at once, series recordings that take new episodes only, repeating recordings, a 14-day past guide, and Hide Stray Entries.
- Multiview with up to four panes.
- Profiles: Copy Settings includes Live TV favorites, groups, channel order, hidden items, Sports and Calendar choices; Keep Matched keeps matched profiles in step.
- New: Restore from Phone. Scan a QR code, choose the Live TV backup on your phone, and send it to the TV.

## Recording

- Record a live channel or catch-up show straight to a NAS, router or server share.
- SMB2/3 only, with server, share, optional folder, username, password, port and domain, plus a connection test.
- Credentials are stored in encrypted preferences on the TV.
- Recording stages locally and mirrors to the share every couple of seconds. At the end, local media is removed only after every file has uploaded; if the share cannot be reached, the local copy is kept and the recording still plays.
- Playback serves the playlist locally and reads the pieces from the share. Delete removes both copies, and the low-space rule can delete the oldest SMB recordings.
- Moving existing recordings to or from an SMB share is not supported yet.

## OneTube

- New optional **Continue Watching - OneTube** row for videos you started on the OneTube page.
- Shows titles, thumbnails, channel names and time left.
- Off by default; enable it under Settings → OneTube → Continue watching on Home.
- Cards resume in Nuvio's player and keep their progress and SponsorBlock behaviour.

## Sports

- Finished games now show official highlights and condensed games from ESPN, NHL, MLB and YouTube, above the replays from your sports add-ons.
- YouTube results come from the OneTube engine, are checked against the teams, and are cached on the TV for six hours.
- Official videos open in the main player; NHL Brightcove links are resolved when picked, and Back returns to the game page.
- My Sports, All Events, Live Now and per-sport filters; AFCON qualifying with qualification status.

## Calendar

- F1, NASCAR and IndyCar sessions show on their own day and time.
- Multi-day golf and tennis events show on every day they run.

## VPN

- Built-in WireGuard VPN. Import a WireGuard `.conf` (including NordVPN's manual WireGuard files) from your phone by scanning the QR code under Settings → VPN.
- The profile is read and encrypted on the TV; the private key is never shown in the QR code.
- Choose **HTTP streams only** to protect HTTP playback while HTTPS stays direct, or **Full app** to protect all Nuvio traffic.

## Media servers

- Added Plex Media Server.
- Added Silo on its own API.
- Jellyfin and Emby sign-ins are kept separate per profile.
- Added French strings for Plex and Silo.

## Home and details

- Hero descriptions get more time before and after auto-scroll.
- The Classic hero gradient is mirrored in RTL layouts.
- Focus stays in the open sidebar while Home is still loading.
- Ambient mode settings are included in profile settings sync.
- Title and episode ratings.
- Season posters in the season tabs.
- Trailers continue on the details page; posters expand with hero trailers and hero details hide during fullscreen trailers.
- A hero trailer location that keeps posters collapsed.
- Pause the trailer while cast or network pages cover the details.
- Start with the handed-off trailer state to avoid a backdrop flash.
- Movies / Series filter in filmography.
- Talk show guest appearances are hidden from filmography.
- Same-day upcoming episodes are ordered by release time.
- Focus stays on a Continue Watching card that moves to the front after playback.
- Better image memory while browsing catalogs.

## Player and subtitles

- Stop resolving every subtitle language on each subtitle menu build.
- Show the add-on's subtitle label in the subtitle list.
- Audio-based subtitle sync fallback.
- Subtitle AutoSync is on by default.
- Styled subtitle outline width, and the styled overlay matches the native renderers.
- ASS/SSA add-on subtitles drawn by libass.
- Subtitles on trailers.
- Improved HDR10+ metadata OBUs from AV1 streams.
- Retry once over HTTP/2 after a 403 for streams with proxyHeaders.
- mpv-android-lib updated to 0.2.1.
- Draw styled subtitles at the ExoPlayer size on mpv.
- Keep the playback position when switching source.
- Loading screen during in-player episode search.
- Keep media server subtitles when starting from the stream list.
- Fall back past unresolvable debrid results and for the next episode.

## Library and collections

- TvReorderList drag reordering.
- Pinned collection parity.
- Confirmation dialog before deleting a plugin repository.
- Stremio player and library tracking add-ons.
- Readable empty-state title that stays on screen.

## Localization

- Live TV, On Demand, Sports, Calendar, OneTube, Music, VPN and recording are localized into German, Spanish, French, Italian, Dutch, Polish, Portuguese, Russian and Turkish.
