# ShRunner

Import, edit and run `.sh` scripts on Android, with live output. No Termux or other helper app.

## Open it
Open this folder in a recent Android Studio. minSdk is 33 (Glasmio needs Android 13+). For command-line builds run `gradle wrapper` once to generate `gradlew`.
`gradle/libs.versions.toml` holds all versions. AGP, Gradle, Kotlin and compileSdk 37 follow current docs; if sync rejects an
AndroidX/Hilt/KSP/Room pin, use Studio's version-catalog quick fix to bump it.

## Layout
- `data/` scripts on disk (`filesDir/scripts`), Room history, DataStore (favorites, approved commands, tile target)
- `shell/` `ShellExecutor` (`/system/bin/sh <file>`, Flow of lines, process tree killed on cancel) and `DangerDetector`
- `system/` pinned/dynamic shortcuts and the Quick Settings tile
- `ui/` Compose screens (Scripts, Favorites, History tabs; Editor and Run screens) + ViewModels (Hilt)

## Settings (4th tab)
- Theme: follow system / light / dark, and a Reduce Transparency switch (Glasmio solid-background mode).
- Language: 简体中文 (default) or English. `values/` is Chinese, `values-en/` is English; the choice is applied in
  `MainActivity.attachBaseContext` and the Activity is recreated on change. Add a language by adding a `values-xx/` folder
  and an option in `SettingsScreen` (+ `SettingsStore`).
- Settings live in SharedPreferences (`data/SettingsStore.kt`) so they are readable synchronously at startup (no theme flash).
- History stores dangerous-command rule ids, not text, so old entries also follow the language setting.
- If you installed an earlier build, clear its app data once: the history table's column was renamed.

## UI: Glasmio
- Screens use Glasmio (`io.github.neoruaa.glasmio:*:0.1.0`): `GlasmioTheme` + `GlasmioBackdropHost`, `GlasmioPinnedListPage`,
  grouped lists, buttons, menus, alerts, bottom sheets, segmented controls. Shared wrappers live in `ui/glass/GlassKit.kt`.
- Only APIs shown in Glasmio's `docs/components.md` and sample app are used. Not used: `GlasmioNavigationBar` (needs a shared
  layer backdrop and scroll state through the whole tree), SF symbol icons (the SF font is not redistributable; Material icons
  are used instead), the message slot of alerts (the second line is appended to the title).
- Bottom navigation is a `GlasmioSegmentedControl` with icon + label per tab.
- If a Glasmio signature differs from what the docs show, fix it in `GlassKit.kt` or the one screen that uses it.

## Security model
- Scripts run with the app's own UID. No permissions are requested; add INTERNET or storage access yourself if needed.
- First run of a flagged line asks for confirmation; approval is tied to a hash of that exact line, so edits re-prompt.
- The text that was scanned is copied to a temp file and that copy runs, so a script can't change between scan and run.
- Shortcuts and the tile only open the Run screen; they never start a script on their own.
- `DangerDetector` is a guardrail, not a sandbox: `eval`, variables, base64 or sourced files can hide a command.
- Every run, stop and declined prompt is logged in History (status, exit code, duration, flagged labels, output tail).

## Known gaps
- No stdin, no foreground service: scripts stop if Android kills the app in the background.
- Stop kills the script's process tree (found through /proc). Processes that already daemonized and were re-parented to init before Stop can survive it.
- No syntax highlighting or line numbers in the editor yet.
