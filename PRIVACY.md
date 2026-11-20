# Nuvio One Privacy Policy

Last updated: November 13, 2026

Nuvio One is a client application for playing media from sources you provide. It does not host, store or distribute media, and it ships with no channels, playlists or content sources.

## Account

Nuvio One uses a Nuvio account for sign-in. Account authentication is handled by Nuvio's service. The app receives the account identifier and email address needed to identify your account and to keep your settings in sync. Your Nuvio password is never handled by Nuvio One.

## Live TV setup sync

If you turn on Setup Sync, the app stores your Live TV setup (playlist and EPG definitions, favorites, hidden groups, sports and calendar choices, and look/layout settings) in a Nuvio One sync service backed by Cloudflare D1. The data is encrypted at rest, keyed to your Nuvio account, and is only returned to a device signed in to the same account. IPTV usernames and passwords are part of that encrypted setup. You can stop syncing or delete the saved copy at any time from Settings → Live TV → Setup Sync.

## Google Drive (optional)

If you connect Google Drive, the app stores a backup of the Live TV setup in a file the app creates in your own Google Drive, and Music uploads go to a folder the app creates in your Drive. Google OAuth tokens are kept on the device. You can disconnect Google Drive at any time; the files remain in your Drive until you delete them.

## Diagnostics

The app may offer optional debug or playback reports to help diagnose problems. Those reports are only sent when you choose to send them. Nuvio One does not sell personal data.

## Contact

For privacy questions, open an issue at https://github.com/Br0ck25/NuvioTVLauncher/issues.