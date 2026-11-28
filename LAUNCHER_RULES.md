# Rules for keeping our code merge-friendly

Goal: when Nuvio One releases, `git merge` should touch as few of our lines as possible. Every line we
add inside a Nuvio One file is a possible conflict, so **our code lives in our own files** and Nuvio One's
files only get small hooks.

## 1. Where new code goes

| Kind of change | Put it in |
|---|---|
| New feature, screen, dialog, ViewModel, repository, service | A **new file**, in the same package family as the feature (`core/launcher`, `core/apps`, `ui/screens/apps`, `livetv/lists`, ...) |
| Logic an activity, screen or ViewModel needs | A **helper class or function** in a new file (see `LauncherHost.kt`, `LauncherHostCompose.kt`, `ListChannelsHooks.kt`) |
| A composable that sits inside a Nuvio One screen | A **new composable** in our own file; the Nuvio One screen calls it |
| Strings | `res/values/strings_launcher.xml` (never `strings.xml`) |
| Other resources (drawables, icons, XML) | New files with a `launcher_` prefix, not edits to existing ones |
| Manifest entries | `app/src/launcher/AndroidManifest.xml` where possible (see section 4), never scattered through the main manifest |
| Gradle values | `launcher.gradle` (Groovy: a Kotlin script applied with `apply(from)` cannot see the Android plugin; see section 4), not new blocks in `app/build.gradle.kts` |
| Tests | New test files next to ours (`core/launcher/*Test`), not additions to Nuvio One's tests |

## 2. What a hook in a Nuvio One file may look like

- **One line**, or one short block, that calls into our file: `launcher.onResume()`, `LauncherSetupGate(this)`.
- At the **edge** of a function or block (first or last statement), not threaded through its middle.
- **Additive**: a new line next to the existing code, not a rewritten existing line. If you must wrap
  upstream code, prefer an early-return guard (`if (launcher.suppressAppExit()) return`) over deleting or
  reindenting the body.
- **Never reformat, reorder or rename** Nuvio One code, and don't add blank lines or imports in unrelated places.
- A new parameter on a Nuvio One composable gets a **default value**, and goes last, so call sites keep compiling.
- State and flows we need in an activity go in our class (`LauncherHost`), not as new fields in `MainActivity`.
  Reach DI objects through a Hilt `@EntryPoint` in our file instead of a new `@Inject` field.

## 3. Before you edit a Nuvio One file

1. Ask: can this be a new file plus a one-line call? If yes, do that.
2. If the hook is new, add it to the table in `LAUNCHER_TOUCHPOINTS.md` (file and what the hook does).
3. If you are changing a hook, keep it as small as before.
4. Check how big our diff in that file is: `git diff --numstat <nuvio-one-ref> -- <file>`. If a file
   grows past about 30 added lines, move code out.

Bug fixes in Nuvio One code that are not launcher features (Live TV, player zoom, ...) are also edits to
their files. Keep each one small, comment it with `Fork:` so it is easy to grep, and list it in
`LAUNCHER_TOUCHPOINTS.md`.

## 4. Manifest and Gradle

- **Manifest:** our entries go in `app/src/launcher/AndroidManifest.xml`. `launcher.gradle` points every
  build type's source set at it, so it is merged into all variants. Add a `<service>`, `<receiver>`,
  `<queries>` entry, or an `<intent-filter>` for an existing activity (`<activity android:name=".MainActivity">`)
  there. Only an attribute change on an existing Nuvio One element (like `launchMode`) has to go in the main manifest.
- **Gradle:** our app id, update repo and dependencies go in `app/launcher.gradle`, which is applied from the
  last line of `build.gradle.kts` and so runs after Nuvio One's own settings and overrides them.
- **Home rows:** the Apps row's behaviour goes in `ModernAppsRow.kt`; the Nuvio One row file only calls it.
  Exhaustive `when`s over Nuvio One's sealed types still need a branch for our types (one line each).

## 4b. Files that are entirely ours

`.gitattributes` marks files we own outright (README, privacy text, the issue-template config, app icons,
banners, logo and wordmark) with `merge=ours`, so a merge keeps our version instead of stopping on a
conflict. Run `scripts/setup-merge-drivers.sh` once per clone to turn it on; without it Git merges normally.
Note that Nuvio One's own changes to those files are ignored: if they change something we want (new
privacy wording, say), copy it over by hand. Add a path there only if the whole file is ours.

Settings that other code needs a hook for: put the screen in our own file and give it its own ViewModel
(see `IconPackSettingsSection.kt`) instead of adding fields to a Nuvio One ViewModel and its tests.

## 5. Merging Nuvio One

0. Once per clone: `scripts/setup-merge-drivers.sh`.
1. `git fetch` Nuvio One's release tag and merge it into the working branch.
2. Conflicts should be only in the files listed in `LAUNCHER_TOUCHPOINTS.md`. Keep Nuvio One's version,
   then re-apply our hook lines.
3. A conflict in a file that is **not** on that list means we edited a file without recording it: fix the
   list and consider moving the code out.
4. Build, run `core/launcher` and `core/apps` tests.
