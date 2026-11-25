# Nuvio One 1.2.0-beta.4

## Home & Player

- **One shared Home tile size** now drives Continue Watching, collection folders, Upcoming and the Live TV row, so the rows line up. **Layout → Tile Size** picks the size (104–140); it follows Poster Width until you choose one.
- **Per-section text and interface scaling** is under **Appearance → Section scaling**. Each section keeps its own text size and whole-page size, with a compact Normal/Your size preview beside the controls. Settings itself is never scaled.
- **Repeat** is now a player button for movies and episodes. It loops the current video until turned off; live streams and OneTube keep their own behavior.
- The **side menu no longer stays on screen** in any section. It opens with Left, Menu or Back and disappears after choosing a page.
- Selecting an **unaired episode** in Upcoming opens its details instead of trying to play it.
- The **Upcoming row** now gives every show with an episode in the next 7 days a card, even when Continue Watching already has that show. Its new wide schedule cards can be turned off in **Settings → Menu & Features → Calendar → New Upcoming Row**.
- The **Live TV Home row** shows the progress bar between the current programme and Next, keeps favorites with nothing listed right now, and has Next on by default.
- **Live TV, Sports and On Demand Home rows** can be added and configured in **Settings → Layout → Home Rows**. Change their position in **Settings → Layout → Reorder Home Catalogs**.

## Playback & device support

- **Seven-band equalizer** presets and custom frequency sliders are available for built-in movie and episode playback. Go to **Settings → Playback → Audio → Seven-band equalizer**. The Music profile is separate. Equalizer processing uses decoded PCM, so encoded audio passthrough is unavailable while it is active.
- **Device Capabilities** checks the TV/display's refresh rates, resolutions, HDR, video and audio decoders, audio outputs and passthrough formats. It can suggest playback settings, but only applies a change when you choose it. Find it in **Settings → Playback → Device Capabilities**.
- Playback buffering now adapts to available memory and responds to Android memory pressure without discarding media already buffered for playback. Buffer and network controls are under **Settings → Playback → Buffer & Network** when the built-in ExoPlayer engine is selected.
- **Best quality** selection and source sorting can use configurable resolution, source, trusted release group, HDR and audio rules. Low-quality release groups can be pushed to the bottom or hidden. Find these under **Settings → Playback → Stream Selection → Quality rules**.

## OneTube

- Opening the **OneTube panel** hides the player controls so they don't cover comments, likes and other actions. Open OneTube from the YouTube player's controls; press Down during playback for chapters and related videos.
- **Related videos are cards** with thumbnails, channel names and available view/posted info. From the OneTube panel, choose **Video actions → Related videos** to open the full list.

## Live TV group filters

- Group-tag scanning can now list **countries only** — US, UK, AR, United States and similar — instead of every word in every group name, so the filter list stays short and useful.
- Country detection reads group prefixes, separators and suffix codes (“US | Sports”, “Sky Sports AR”), ignores ordinary words such as Entertainment, Sports and Arena, and handles accents such as Türkiye/Turkiye.
- Turn **Countries only** off to scan all tags and languages as before.

## Music

- Music now plays your add-ons before YouTube by default. When a YouTube track starts, Nuvio looks for the same title, artist and length in your add-ons first, and only falls back to YouTube when there is no confident match.
- Add-on lookups can take up to six seconds before falling back, and matches and misses are cached so replaying a track is immediate.
- If more than one add-on has the track, the one added first wins, so your preferred source keeps priority.
- **Gemini suggestions** are available under Music → Integrations. Use your own Gemini API key to suggest tracks and albums from this profile's top artists, likes, recents and playlists.
- The Gemini key is stored encrypted for the active profile, carried to your other TVs by the existing credential sync, and is never shown or logged after saving.
- Gemini only proposes titles. Every suggestion is checked against YouTube Music first, and playback still uses your add-ons first, then YouTube.
- Choose which taste signals Gemini may use, change the model, generate fresh picks or clear them at any time. Suggestions are cached per profile, so opening Music does not spend API quota.
- **Spotify sync is faster**: changed playlists are fetched in parallel, two tracks are matched at a time, known tracks are matched from your library without a search, and definitive misses are remembered so repeat syncs skip them. A rate-limit response drops the rest of the run back to one search at a time.
- A track that is already in **Liked Songs** is not added again to a Spotify playlist, and the same recording matched to a different video on another device is not added to Liked Songs twice.
- **Home rows** can now be arranged from Music Settings. Move a row with Left/Right, hide or show it with OK, pin your own playlists to the top, or reset every row. Each profile keeps its own arrangement.
- The Nuvio side rail no longer rests over any section. Open it with Back anywhere, or Left outside Live TV, and it hides after a selection.
- Music videos can be **minimized to an in-app mini player** while you keep browsing Music. The mini player keeps the same video playing and gives you expand, play/pause and close buttons.
- Music has its own **seven-band equalizer profile**, separate from movie and episode playback. Open **Music → Settings → Sound → Equalizer** for presets and per-band controls.

## Live TV

- **Audio sync** is now in the player's Audio menu for every channel. Use Left/Right to shift the sound against the picture in 50 ms steps, within ±5 seconds.
- The correction applies immediately, is remembered per profile and per channel, and is restored the next time that channel plays.
- Live TV add-on source rows for an add-on you have uninstalled are removed on the next refresh instead of staying switched on with an error.
- Automatic EPG matching now considers **dummy/event guide channels with no listings in the kept window**. They match at a lower priority than channels with real listings, so providers with hundreds of placeholder channels no longer need manual assignment.
- A **Sync ±… ms** badge appears in the play bar while a channel has an active correction, and **Reset audio sync** returns it to zero.
- **Playlists and guides are per profile and sync between TVs.** Each profile has its own list. Profiles that already exist keep a copy of the playlists they had, and nothing is removed. New profiles start empty. If the same playlist or guide URL is added on another profile, the downloaded file is shared, so space isn't used twice. Matching Xtream server/user/password logins also share one cached catalogue. Deleting a source in one profile only removes it from that profile; other profiles keep their copies.
- **Parental controls are per profile.** Each profile keeps its own PIN, adult-content lock and locked groups/categories. Profile 1 keeps its existing settings; other profiles no longer inherit them, and a group unlocked in one profile stays locked in another.
- **Move a secondary profile's Live TV setup to another Nuvio account** with a one-time transfer code. On the source account, switch to the secondary profile and go to **Settings → Live TV → Setup → Move this profile to another Nuvio account → Generate code**. On the receiving account, open **Move Live TV setup to this account** from its primary profile and redeem the code within 15 minutes. The code can be used once, and transfers only the Live TV setup—not the profile itself or other app data. Live TV sync must already be set up on the source account's primary profile.
- The guide now has a separate **Playlists** column to the left of the groups. Press Left from the groups to open it and pick All playlists or one playlist; the group list and All channels then narrow to it, while Favorites and Recently watched still pull from every playlist. Right returns to the groups.
- Playlist and Xtream setup accepts full **HTTPS URLs** as well as the usual provider addresses.
- The launcher no longer freezes while Home rows refresh, and its low-memory behavior follows the device's available RAM. Playback buffers also adjust to memory pressure without throwing away already-buffered video.
- Removing a profile clears its local Live TV lists and parental-lock data without touching other profiles.

## Sports

- Game details now include a **Top Players** view with player photos, side-by-side team leaders and their game stats. Select a player to open their profile when roster information is available.
- Available game details can also include team stats, recent form, previous meetings, league tables and lineups; sections appear when the data is provided for that game.

## VPN

- **OpenVPN profiles** can now be imported alongside WireGuard profiles. Go to **Settings → VPN → OpenVPN**, import a self-contained `.ovpn` file or a supported certificate ZIP bundle, then select the profile and choose what Nuvio protects. Some profiles may require service credentials.
- OpenVPN uses its native engine, with an isolated compatibility engine for supported fragmentation profiles. Unsupported profile options are rejected rather than silently ignored.

## Downloads

- Choose **Download** in a movie or episode's player controls to save to device storage, a connected USB drive, or a configured SMB share. Nuvio asks you to confirm that offline saving is permitted for the selected source, and the dialog stays open independently of the auto-hiding controls.
- If Download is hidden, enable it in **Settings → Playback → Player Interface → Visible buttons → Download**. To configure an SMB destination, go to **Settings → Live TV → Playback → Recordings → Set up SMB share**.
- The first version supports direct HTTP(S) video files only. Adaptive playlists, known DRM-protected playback, YouTube, Live TV and Stalker routes are not downloaded.
