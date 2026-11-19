# Nuvio One 1.2.0-beta.1.17.3

## OneTube

- OneTube now uses the full screen width when Nuvio's side menu is hidden. The first poster and the row titles still clear the menu while it is open, without the black side bars.

## Live TV

- Fixed: switching channels could leave the previous channel's frame on screen with a green tint. The old frame and decoder are now cleared before the new channel starts.
- New: Hold to Jump to the Ends, under Settings → Live TV → Groups → The List. Choose how long to hold Up or Down in the group list to jump to the top or bottom: 2.0 to 5.0 seconds in 0.5-second steps. The default is 3.5 seconds.
- Fixed: Restore from Google Drive could fail with HTTP 403 when the TV still had an old backup file ID. The saved ID is now reset on sign-in and after a failed read, the lookup is retried, and Google's actual reason is shown if it still fails.
- New: Restore from Phone. Scan a QR code, choose the Live TV backup (.json) from Google Drive, Downloads or Files on your phone, and send it to the TV. The file no longer needs to be copied to the TV.

## Localization

- Live TV now follows the app language across the guide, player, settings, Multiview, Recordings, Calendar, Sports and On Demand, including their dialogs and status messages.
- OneTube and Music now follow the app language across their main screens, settings, sources, account, Navidrome/WebDAV, Party and video player.
- Languages: German, Spanish, French, Italian, Dutch, Polish, Portuguese, Russian and Turkish. OneTube and Music also include Arabic.
