# Building TECHO5 Panel

`tools/build-apk.sh` builds the APK with the Android SDK's own tools. There is no Gradle. It builds
`echod` from a techo5 checkout for both ABIs, compiles the app, packs it and signs it.

## What you need

- **Android SDK** build-tools: `aapt2`, `d8`, `zipalign` and `apksigner` (build-tools 34 or later).
- **Android platform 34** (`platforms/android-34/android.jar`). The app targets API 34. A newer
  platform works too; the script takes 34 when it is there, else the newest.
- **Android NDK** (r26 or later), for its clang: `echod` is a Go program with cgo, built for Android.
- **JDK** 17 or later: `javac`, `jar` and `keytool`.
- **Go**, the version techo5's `echod/go.mod` asks for.
- **A POSIX shell**: any on Linux or macOS. On Windows, Git Bash (from Git for Windows).
- **git**, and **Python 3** for fetching the wake word models.
- **A techo5 checkout with the core branch**: the
  [techo5](https://github.com/HuskerMinion/techo5) repo at the branch with the panel build (the
  `panel` Go build tag and `echod/internal/layout/device_panel.go`).

With the SDK's `sdkmanager`:

```
sdkmanager "build-tools;34.0.0" "platforms;android-34" "ndk;28.2.13676358"
```

The script finds the SDK at `ANDROID_SDK_ROOT` (or `ANDROID_HOME`), else where Android Studio puts it.

## The wake word models

The APK carries the wake word models the Echos carry, and the app puts them in place on the tablet.
techo5's `tools/fetch-inputs.py` downloads them, each pinned to a sha256, into `inputs/models` of
the techo5 checkout:

```
cd techo5
python3 tools/fetch-inputs.py
```

It fetches the Echos' other build inputs too; the panel uses only `inputs/models`. Set `MODELS` to
use another folder. The build takes the twelve models below by name, each as `<name>.tflite` and
`<name>.json`, and nothing else in the folder.

Their licenses: okay_nabu, hey_jarvis, hey_mycroft and alexa are Apache 2.0, from
[esphome/micro-wake-word-models](https://github.com/esphome/micro-wake-word-models). computer,
jarvis, hey_friday, glados, hal, terminator, marvin and home_assistant are MIT, from
[fwartner/home-assistant-wakewords-collection](https://github.com/fwartner/home-assistant-wakewords-collection).
[NOTICE](../NOTICE) has the details.

## A signing key

Android installs an update only when it is signed with the same key as the app already there. Make
a key once and keep it. **Keep the key and its password outside every repository.** Never write the
password into a script.

```
keytool -genkeypair -keystore <path outside the repo>/techo5-panel-mine.keystore -storetype PKCS12 \
  -alias panel -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=TECHO5 Panel (my build)"
```

`keytool` asks for the password. Put it in a file outside the repo (or in the `KEYSTORE_PASS`
environment variable).

An APK you sign with your own key cannot update one from this repo's releases, or the other way
round. Uninstall one before installing the other.

## Building

```
export KEYSTORE=<path>/techo5-panel-mine.keystore
export KEYSTORE_PASS_FILE=<path>/techo5-panel-mine.pass
tools/build-apk.sh ../techo5
```

The checkout defaults to `$TECHO5`, else `../techo5`. The APK lands in `build/techo5-panel.apk`.

Other settings, all from the environment:

| Variable | What it does |
|---|---|
| `KEY_ALIAS` | The key's alias, when the keystore holds more than one. |
| `DEBUG=1` | A debug build: debuggable, and `echod` built with the `panel_debug` tag for its debug tools. Never release one. |
| `PANEL_UPDATE_URL` | Where the app updates from. Default: this repo's latest release. A fork sets its own `https://` URL, or `none` for no updates. Plain `http` is refused: the app allows no cleartext traffic. |
| `MODELS` | The wake word models folder. |
| `ANDROID_PLATFORM` | The platform to build against, like `android-34`. |

The version is the number of minutes since 2026-01-01 UTC, so every build is newer than the last.
It shows as `0.1.<minutes>` in the app, on the screen and in Home Assistant.

## Checks

Every build runs `tools/check-apk.sh`, which fails the build when:

- any entry in the APK is named with a backslash. (Android finds assets and libraries only by `/`
  paths. `aapt2 link -A` on Windows stores asset paths like `assets/models\alexa.tflite`, so the
  build adds the assets with `jar` instead, which always writes `/`.)
- `lib/arm64-v8a/libechod.so` or `lib/armeabi-v7a/libechod.so` is missing.
- `assets/models` holds anything but the twelve wake word models (each `.tflite` and `.json`).
- `assets/NOTICE` or `assets/LICENSE` is missing. Both go in the APK, and beside it in each release.
- a release build is debuggable.

## Releasing

`tools/release.sh` gets the last build ready for a GitHub release and prints the `gh release create`
command. It runs nothing on GitHub itself. It refuses a debug build, a build with another update
URL, and an `echod` built from a techo5 checkout with uncommitted changes or from a commit that is
not on GitHub.

It writes `build/release/techo5-panel-<code>.apk` and `build/release/version.json`:

```
{
  "versionCode": 418645,
  "versionName": "0.1.418645",
  "apk": "techo5-panel-418645.apk",
  "sha256": "<sha256 of the APK>"
}
```

Both go up as assets of one release, marked latest. The app reads
`https://github.com/HuskerMinion/techo5-panel/releases/latest/download/version.json` and then the APK
named there.
