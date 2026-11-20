# Nuvio One 1.2.0-beta.3.2

## SMB recording hotfix

- Fixed SMB recording on Android TV boxes where release builds failed with `NoSuchAlgorithmException: no such algorithm: MD4 for provider BC`.
- Release builds now keep the BouncyCastle MD4 and SMB signing classes that jcifs-ng loads by name.
- Everything from 1.2.0-beta.3.1 is included.
