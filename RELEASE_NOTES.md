# Nuvio One 1.2.0-beta.3.5

## Live TV setup

- Xtream and M3U address fields now have a **Use HTTPS** switch, so an HTTPS provider no longer means typing the full scheme on the TV keyboard.
- URL fields use the TV keyboard's URL layout with slash and colon keys.
- Typing or pasting a complete `http://` or `https://` address still works, and editing a source starts the switch from its saved scheme.

## Stability

- Fixed the random return to the TV home screen / “Nuvio One isn’t responding” freeze. The launcher-rows alarm could keep a foreground broadcast open for up to 25 seconds; Android closes one after about 10 seconds. The refresh now starts in the background and the broadcast finishes immediately.
- On 1 GB TVs the launcher rows now draw 12 cards instead of 24, use lower-memory card bitmaps, refresh at most every 15 minutes, and wait for startup sync to settle before drawing.

## Also in this build

- The music Now Playing crash fix from 1.2.0-beta.3.4.
- SMB recordings, Combine, MP4 export and the Live TV source fixes from earlier builds.
