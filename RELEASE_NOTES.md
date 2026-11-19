# Nuvio One 1.2.0-beta.1.17.3

## VPN

- New: built-in WireGuard VPN. Import a WireGuard `.conf` (including NordVPN's manual WireGuard files) from your phone by scanning the QR code under Settings > VPN. The profile is read and encrypted on the TV; the private key is never shown in the QR code.
- Choose **HTTP streams only** to protect HTTP playback while HTTPS stays direct, or **Full app** to protect all Nuvio traffic. OpenVPN is not included.

## Nuvio + IPTV 1.2.0-beta.2.1

- Sports: official highlights and condensed games (ESPN, NHL, MLB and YouTube), replays and highlights from sports add-ons for every sport, and AFCON qualifying with qualification status.
- Multiview: hold OK on a pane to watch it full screen and return with every pane still playing; pane names and the highlight fade with the top bar; the Sports tab has My Sports, All Events, Live Now and per-sport filters.
- Live TV: a guide for each playlist, On Demand from M3U playlists, a DNS setting (System, Cloudflare or Google), copy several channels to a group at once, series recordings that take new episodes only, repeating recordings, a 14-day past guide, and Hide Stray Entries.
- Profiles: Copy Settings now includes Live TV favorites, groups, channel order, hidden items, Sports and Calendar choices; Keep Matched keeps matched profiles in step.
- Calendar: F1, NASCAR and IndyCar sessions show on their own day and time, and multi-day golf and tennis events show on every day they run.
- Nuvio 1.2.0-beta.2 base: Stremio player and library tracking add-ons, subtitle outline width, styled subtitle colours and positions, keeping your place when switching source, and better source fallback. mpv updated to 0.2.1.
- Fixed: Update Now re-downloads M3U playlists, overlay mode matches the guide, deleting a playlist clears its leftovers, sync keeps playlist and guide order, Stalker guide saving and error privacy, and NBA division standings order.

## OneTube

- OneTube now uses the full screen width when Nuvio's side menu is hidden. The first poster and the row titles still clear the menu while it is open, without the black side bars.

## Live TV

- Fixed: switching channels could leave the previous channel's frame on screen with a green tint. The old frame and decoder are now cleared before the new channel starts.
- New: Hold to Jump to the Ends, under Settings → Live TV → Groups → The List. Choose how long to hold Up or Down in the group list to jump to the top or bottom: 2.0 to 5.0 seconds in 0.5-second steps. The default is 3.5 seconds.
- Fixed: Restore from Google Drive could fail with HTTP 403 when the TV still had an old backup file ID. The saved ID is now reset on sign-in and after a failed read, the lookup is retried, and Google's actual reason is shown if it still fails.
- New: Restore from Phone. Scan a QR code, choose the Live TV backup (.json) from Google Drive, Downloads or Files on your phone, and send it to the TV. The file no longer needs to be copied to the TV.

## Localization

- Live TV now follows the app language across the guide, player, settings, Multiview, Recordings, Calendar, Sports and On Demand, including their dialogs and status messages.
- OneTube and Music now follow the app language across their main screens, settings, sources, account, local sharing, Spotify linking, Navidrome/WebDAV, Party and video player.
- Languages: German, Spanish, French, Italian, Dutch, Polish, Portuguese, Russian and Turkish. OneTube and Music also include Arabic.
