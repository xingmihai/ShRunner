# ShRunner

Import, edit and run `.sh` scripts on Android, with live output. No Termux or other helper app.

## Open it
Open this folder in a recent Android Studio. For command-line builds run `gradle wrapper` once to generate `gradlew`.
`gradle/libs.versions.toml` holds all versions. AGP, Gradle, Kotlin and compileSdk 37 follow current docs; if sync rejects an
AndroidX/Hilt/KSP/Room pin, use Studio's version-catalog quick fix to bump it.

## Layout
- `data/` scripts on disk (`filesDir/scripts`), Room history, DataStore (favorites, approved commands, tile target)
- `shell/` `ShellExecutor` (`/system/bin/sh <file>`, Flow of lines, killed on cancel) and `DangerDetector`
- `system/` pinned/dynamic shortcuts and the Quick Settings tile
- `ui/` Compose screens (Scripts, Favorites, History tabs; Editor and Run screens) + ViewModels (Hilt)

## Settings (4th tab)
- Theme: follow system / light / dark, plus optional Material You dynamic color (Android 12+).
- Language: 简体中文 (default) or English. `values/` is Chinese, `values-en/` is English; the choice is applied in
  `MainActivity.attachBaseContext` and the Activity is recreated on change. Add a language by adding a `values-xx/` folder
  and an option in `SettingsScreen` (+ `SettingsStore`).
- Settings live in SharedPreferences (`data/SettingsStore.kt`) so they are readable synchronously at startup (no theme flash).
- History stores dangerous-command rule ids, not text, so old entries also follow the language setting.
- If you installed an earlier build, clear its app data once: the history table's column was renamed.

## Security model
- Scripts run with the app's own UID. No permissions are requested; add INTERNET or storage access yourself if needed.
- First run of a flagged line asks for confirmation; approval is tied to a hash of that exact line, so edits re-prompt.
- The text that was scanned is copied to a temp file and that copy runs, so a script can't change between scan and run.
- Shortcuts and the tile only open the Run screen; they never start a script on their own.
- `DangerDetector` is a guardrail, not a sandbox: `eval`, variables, base64 or sourced files can hide a command.
- Every run, stop and declined prompt is logged in History (status, exit code, duration, flagged labels, output tail).

## Known gaps
- No stdin, no foreground service: scripts stop if Android kills the app in the background.
- `ProcessHandle` (kill descendants) needs API 33+; on 26-32 a script's detached children can outlive Stop.
- No syntax highlighting or line numbers in the editor yet.
