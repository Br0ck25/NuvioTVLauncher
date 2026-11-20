# Nuvio One 1.2.0-beta.1.17.2.1

## Live TV

- Fixed: Restore from Google Drive could fail with HTTP 403 when the TV still had an old backup file ID. The saved ID is now reset on sign-in and after a failed read, the lookup is retried, and Google's actual reason is shown if it still fails.
- New: Restore from Phone. Scan a QR code, choose the Live TV backup (.json) from Google Drive, Downloads or Files on your phone, and send it to the TV. The phone page shows the chosen file name before sending, and the file no longer needs to be copied to the TV.
