# Nuvio One 1.2.0-beta.1.4

This is Nuvio One's first release on the Nuvio 1.2.0 codebase. It brings the Live TV work that came with it, while keeping the things that make this fork ours: Nuvio One sync, add-on channels, our sports data, OneTube, Music, and our own player and Back behaviour.

## Four channels at once

Multiview is new. Open it from the guide's top bar, from the Multiview button in the player, or from a channel's menu, then fill up to four panes. Pick a simple layout for one, two, three or four streams, or one large pane with smaller ones beside it. The picker uses your own channel groups and has a Sports tab for games that are on now.

Only the selected pane plays sound. A pane that loses its stream reconnects on its own, and a channel that won't start can fall back to a backup copy.

## Live TV

- Stalker and Ministra portals can now be added next to M3U, Xtream and add-on sources, for Live TV and On Demand.
- **Add from phone** shows a QR code, so playlist, Xtream login and guide details can be typed on a phone instead of with the TV remote.
- The guide opens from its saved copy straight away and refreshes in the background.
- Playlists load faster and take less memory.
- Recording can stop when you stop it, when the programme ends, or at a time you choose. Record and Record series are also in overlay mode (hold OK).
- Channel names can use two lines, and the play bar can show the group name.
- The programme header now includes the guide's sub-title, so a sports listing can show the actual match-up instead of a generic title.
- Search puts the closest matches first and prefers the best-quality copy of a channel.
- Channel and On Demand group management has Select all and Clear.
- Time shifts go up to ±24 hours.
- The Live TV screens are translated into German, French, Spanish, Portuguese, Italian, Dutch, Polish, Turkish and Russian.

## Sports

- Final-score alerts for the teams you follow.
- Alerts for a single game, even when neither team is followed.
- Live games are matched to channels faster.
- The league picker has search and groups leagues by sport.
- Golf, racing, tennis and combat events appear in the Calendar.

## Player and OneTube

- Seek previews now work in OneTube, as well as movies, series, catch-up, recordings, sports replays and live TV rewind. They are on by default unless the device is low on memory, and can be switched off under Settings → Playback.
- Nuvio 1.2.0 adds subtitle font and style choices, chapters, a setting for what Continue Watching opens, and automatic fallback to the next source when one fails.

## Nuvio One keeps

- Cloud sync and Google Drive backup on the existing Nuvio One accounts.
- Add-on Live TV channels, with their event details in the guide.
- Nuvio One's sports data, team pages, leagues and game pages.
- OneTube, Music, Calendar and the Nuvio One menu.
- Our player, search and Live TV Back behaviour.
- No VPN modules.

## Fixes

- Guide reloads and guide matching getting stuck.
- Disabled playlists coming back.
- Playlists rejected by their provider now load.
- The channel panel now closes after your Info Bar Stays For time.
- Pinned scoreboards in full-screen Live TV.
- On Demand progress and Continue Watching from page/search playback.
- Record series repeats and cancelled episodes.
- Recordings saved without a start time.
- Sports channel fixes surviving searches and provider renames; fight cards with only a date no longer vanish.
- 60 fps detection after a 24 or 50 fps channel.
- Multiview panes failing to start on this Media3 version.
- OneTube seek previews when YouTube supplies the storyboard late.

## Upgrading

The saved channel list and guide are stored in a new format. The first Live TV start after upgrading rebuilds both once from your playlists and guide files; after that it uses the saved copies.

## Thanks

Multiview, Stalker and Ministra support, the frame-rate work and much of the speed work came from bArKEr (@ghbarker).
