# Nuvio One 1.2.0-beta.3.3

## Recording

- Recordings on the SMB share now appear on every device that uses the same share, including the TV, Android mobile and Windows.
- The TV and phone read both recording layouts, so existing folders stay visible after the update.
- **Combine** joins a finished recording's HLS pieces into one MPEG-TS file without re-encoding, so the result is the same size and quality as the recording.
- **Export** remuxes a recording to MP4. Windows exports the file with ffmpeg (point to it in Recordings settings if it isn't on PATH); the original HLS pieces are kept.
- Exports go to an `Exports` folder on the share by default, with an option to save on the device instead.
- A recording that is still being made on another device is marked as such and cannot be stopped or deleted from the wrong device.
- Better compatibility with SMB shares that leave signing optional, and with `info.json` files saved with a byte-order mark.

## Playlists

- Recover missing playlists from the current and previous cloud backup without removing any local playlists.

## Diagnostics

- Debug reports now include an account support code that can be matched to a Nuvio account without exposing it.

## Localization

- Live TV, recording and Combine are localized into German, Spanish, French, Italian, Dutch, Polish, Portuguese, Russian and Turkish.
