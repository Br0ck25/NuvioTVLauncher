# Optional branches

`claude/launcher-on-nuvioone-base` is the launcher on top of Nuvio One `1.2.0-beta.1.17.2.1`.
Everything else from our latest beta (`dev`, tag `1.2.4-beta.14`) lives on its own branch, so each
piece can be looked at or restored separately. Every branch starts from the launcher branch and
adds one feature, so to restore one:

```bash
git checkout claude/launcher-on-nuvioone-base
git merge origin/claude/opt-<name>
```

All ten merge together without conflicts (tested), so you can restore any subset in any order.
Each branch takes `dev`'s version of its files. Where a feature needs Nuvio One code that is not in
their release yet, that file is included and listed below.

| Branch | What it restores | Size | Needs from Nuvio One's unreleased code |
|---|---|---|---|
| `opt-per-profile-live-tv` | Live TV, Sports, recordings, calendar picks, playlists and the parental lock kept per profile | 22 files | `BackgroundWork`, `ListChannels`, `ProfileMatchMirror`, `SportsWatchPolicy` |
| `opt-per-profile-music-onetube` | Music and OneTube settings, plus their YouTube and Spotify sign-ins, kept per profile | 18 files | none |
| `opt-live-tv-my-lists` | Home lists as Live TV channels (My Lists), Sources button, Torbox flags, tests | 19 files | `ForkDns`, `PlaylistAddressInput`, `SportsVideos` |
| `opt-live-tv-home-row` | Live TV row polish: progress to Next, favorites, artwork, focus | 2 files | none |
| `opt-google-drive-signin` | Drive sign-in scope check, Google's real error reason, sign-in logging (logs part of the client id) | 4 files | `ForkDns`, `PlaylistServerChanges` |
| `opt-home-tile-sizes` | Tile size for Continue Watching, Upcoming and collections; Upcoming card size | 8 files | none |
| `opt-full-settings-all-profiles` | Every profile gets the full Settings, add-ons screen and side-menu pages | 12 files | `TrackingAddonConsentDialog` |
| `opt-updater` | Smoother download progress, install result handling, Android 9 and older | 1 file | none |
| `opt-on-demand-db-fix` | Opens the version 11 On Demand database from the older Plus-based beta (needed when upgrading from our current betas) | 1 file | none |
| `opt-docs-and-sync` | Performance and Monet notes, privacy text, `sync-upstream.yml` (still follows Nuvio One's `main`) | 6 files | none |
| `opt-nuvioone-unreleased` | **Reference only.** Nuvio One's `fix/livetv-green` at `ab5e959`: everything they have after their last release (player, subtitles, Multiview, translations and more). It is their branch, not built on ours | n/a | n/a |

## Notes
- Nothing here has been compiled (no Android SDK in the session). Each branch passes the same static
  checks as the launcher branch (imports, members, strings); CI is the real test.
- Upgrading existing users: `opt-on-demand-db-fix` is the one I would not skip; per-profile settings
  files (`_p2` and so on) are only read by the two per-profile branches.
- The default "Nuvio One credit" and README are already in the launcher branch.

## Branches cleared for deletion (SHAs recorded; recreate with `git branch <name> <sha>` then push)

Checked on 2026-11-20: every commit on these is in `dev` (the WIP merge branch has one unfinished commit, kept here by SHA). Deleting needs permissions the Claude session does not have, so they are still on origin until deleted by hand.
The latest beta build (Release run #65) came from `hotfix/17.2.1` at `2587f1d0e`, so that branch was kept.

| Branch | Tip SHA |
|---|---|
| `feat/per-profile-livetv` | `6f7639127a1b0169a78ec2cafef864f9dd7be72d` |
| `feat/per-profile-youtube` | `e1a52b40e1ef5ecd2580997ac235fdfaf7a1ff86` |
| `nuvioone-base` | `6295b6c2c8e21a1d254d2741860fb93ab09dce36` |
| `nuvioone-port` | `b8cc9479158e60453bbdef98847d9b4e8c8a7589` |
| `profile-port` | `0f24c473488654496ca9ef781294ce06f4f2ac18` |
| `sync/nuvioone-branches` | `0edd2ba5e7f43ae465ccee2f26a1fcf79c3acb68` |
| `sync/nuvioone-livetv-green` | `9d44e3d0ca1b52ea1a7c6df479275a750164fb9e` |
| `wip/nuvioone-livetv-merge` | `fae7a8bae4df4b5a10d6b2e04e99190978faf2e0` |

## Archived before deletion
`pr/home-tile-size`, `pr/livetv-row` and `pr/upcoming-row` were deleted from origin while they still had commits that are not in `dev`.
They were saved as `claude/archive-pr-home-tile-size` (`69a6666`), `claude/archive-pr-livetv-row` (`454c98e`) and `claude/archive-pr-upcoming-row` (`b9dcba8`).
Not yet compared with `opt-home-tile-sizes` and `opt-live-tv-home-row`; delete these three once that check is done.


## README text for features that are on optional branches
Removed from the README because the launcher branch does not have the feature. Paste back when you merge the branch.

**`opt-live-tv-my-lists`** (goes under "Live TV features", before "A proper TV guide"):

```markdown
### List channels (Home rows as TV channels)
Turn any Home row, collection folder or library list into a scheduled Live TV channel. Open **Settings → Live TV → List Channels**, pick the rows you want, and each becomes a channel in the Live TV guide with its own schedule. Press OK on one to play it through your debrid service or addons, and the next title starts when one ends. These channels count as a source, so the guide shows them even with no playlist added. They play like live TV (no seek bar), and torrent-only streams aren't played.
```

**`opt-per-profile-live-tv`** and **`opt-per-profile-music-onetube`**: the intro in "Built on Nuvio One" said
"remote button mapping, per-profile settings and more". Put "per-profile settings" back after "remote button mapping".
