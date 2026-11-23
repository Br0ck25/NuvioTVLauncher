# Nuvio One 1.2.0-beta.3.6

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
- The Nuvio side rail no longer rests over the Music section; open it from the left edge or Back when you need it.

## Live TV

- **Audio sync** is now in the player's Audio menu for every channel. Use Left/Right to shift the sound against the picture in 50 ms steps, within ±5 seconds.
- The correction applies immediately, is remembered per profile and per channel, and is restored the next time that channel plays.
- Live TV add-on source rows for an add-on you have uninstalled are removed on the next refresh instead of staying switched on with an error.
- A **Sync ±… ms** badge appears in the play bar while a channel has an active correction, and **Reset audio sync** returns it to zero.

