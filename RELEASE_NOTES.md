# Nuvio Launcher — based on Nuvio One 1.2.0-beta.3.2

## Upstream: 1.2.0-beta.3.2 (SMB recording hotfix)

- Fixed SMB recording on Android TV boxes where release builds failed with `NoSuchAlgorithmException: no such algorithm: MD4 for provider BC`.
- Release builds now keep the BouncyCastle MD4 and SMB signing classes that jcifs-ng loads by name.
- Everything from 1.2.0-beta.3.1 is included.

## Upstream: 1.2.0-beta.3.1

- Direct-to-SMB recording with local staging, and playback of recordings through the recording server.
- Optional Continue Watching row for OneTube on Home (off by default).
- Upcoming episodes on the same day are ordered by release time.

## Previously (when built on Nuvio One 1.2.0-beta.1.17.2.1)

- Fixed: Restore from Google Drive could fail with HTTP 403 when the TV still had an old backup file ID. The saved ID is now reset on sign-in and after a failed read, the lookup is retried, and Google's actual reason is shown if it still fails.
- New: Restore from Phone. Scan a QR code, choose the Live TV backup (.json) from Google Drive, Downloads or Files on your phone, and send it to the TV. The phone page shows the chosen file name before sending, and the file no longer needs to be copied to the TV.

See MIGRATION_REPORT.md for the full list of integrated upstream changes.
