# Building TECHO5 Panel

`tools/build-apk.sh` builds the APK with the Android SDK's own tools. There is no Gradle. It builds
`echod` from a techo5 checkout for both ABIs (or packs CI's, for a release), compiles the app, packs it
and signs it.

## What you need

- **Android SDK** build-tools: `aapt2`, `d8`, `zipalign` and `apksigner` (build-tools 34 or later).
- **Android platform 34** (`platforms/android-34/android.jar`). The app targets API 34. A newer
  platform works too; the script takes 34 when it is there, else the newest.
- **Android NDK 28.2.13676358** exactly, for its clang: `echod` is a Go program with cgo, built for
  Android.
- **JDK** 17 or later: `javac`, `jar` and `keytool`.
- **Go 1.21 or later** on the PATH. The build uses Go 1.26.8 exactly: it sets `GOTOOLCHAIN=go1.26.8`,
  and the go command downloads that toolchain the first time.
- **A POSIX shell**: any on Linux or macOS. On Windows, Git Bash (from Git for Windows).
- **git**, and **Python 3** for fetching the wake word models.
- **A techo5 checkout**: [techo5](https://github.com/HuskerMinion/techo5) at `main`, or at a tag
  v1.3.0 or later. Those have the panel build (the `panel` Go build tag and
  `echod/internal/layout/device_panel.go`).

Go and the NDK are pinned to the versions techo5's CI builds the released `echod` with, so a build
here is as near it as a build on another machine gets. The two versions are `GO_VERSION` and
`NDK_VERSION` at the top of `tools/build-apk.sh`, the same as the panel job in techo5's
`.github/workflows/build.yml`. With any other Go or NDK the build prints a warning and goes on. A
release carries CI's own libraries, not these (see [Releasing](#releasing)).

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
| `PREBUILT_LIBS` | A folder of `echod` libraries to pack instead of building them (`libechod-<abi>.so` and `SHA256SUMS`, as techo5's CI makes them). How a release is built: `tools/release.sh` fills `build/ci/<tag>`. Not with `DEBUG=1`. |
| `VERSION_CODE` | With `PREBUILT_LIBS`, the version the libraries were stamped with, when the folder has no `version-code` file. |

The version is the number of minutes since 2026-01-01 UTC, so every build is newer than the last.
It shows as `0.1.<minutes>` in the app, on the screen and in Home Assistant. A release build takes the
version `release.sh` planned, which CI stamped into its libraries.

## Checks

Every build runs `tools/check-apk.sh`, which fails the build when:

- any entry in the APK is named with a backslash. (Android finds assets and libraries only by `/`
  paths. `aapt2 link -A` on Windows stores asset paths like `assets/models\alexa.tflite`, so the
  build adds the assets with `jar` instead, which always writes `/`.)
- `lib/arm64-v8a/libechod.so` or `lib/armeabi-v7a/libechod.so` is missing.
- `assets/models` holds anything but the twelve wake word models (each `.tflite` and `.json`).
- `assets/NOTICE` or `assets/LICENSE` is missing. Both go in the APK, and beside it in each release.
- a release build is debuggable.
- with `PREBUILT_LIBS`, a `lib/<abi>/libechod.so` is not the library the folder's `SHA256SUMS`
  lists. `release.sh` runs the same check against the attested sums before a release.

## Releasing

A release does not carry an `echod` built here. A library built on another machine never comes out
byte for byte as CI's: the C compiler's path is in Go's build ID, and the NDK is built for a
different host on Windows than on CI's Linux. So, like the Echos' images, a release carries the two
libraries techo5's CI built and attested from a tagged commit, and `build-apk.sh` packs them as they
are (`PREBUILT_LIBS`). Local builds are for development.

`tools/release.sh` takes a release a step per run. It needs [GitHub's CLI](https://cli.github.com/)
(`gh`), signed in. It only reads from GitHub: it downloads CI's libraries and checks their
attestations, and prints the tag and release commands for you to run.

1. **Plan it: `tools/release.sh ../techo5`.** The techo5 checkout has to be committed and its commit
   on GitHub (`main`, or a tag v1.3.0 or later). It picks the version, writes `build/release-plan`,
   and prints the techo5 tag for it:

   ```
   git -C <techo5> tag panel-v0.1.<code> <commit> && git -C <techo5> push origin panel-v0.1.<code>
   ```

   Pushing it has techo5's CI build both libraries from that commit, stamped with that version, and
   attest them.
2. **When CI has finished, run `tools/release.sh` again.** It finds the tag's run, refuses one that
   failed or built another commit, downloads its libraries into `build/ci/panel-v0.1.<code>`
   (`gh run download`), checks each against CI's `SHA256SUMS`, and runs
   `gh attestation verify` on both (`--repo HuskerMinion/techo5 --source-ref refs/tags/<tag>
   --signer-workflow HuskerMinion/techo5/.github/workflows/build.yml`). Only when both verify does it
   write `build/ci/<tag>/attested` and print the build command:

   ```
   KEYSTORE=<release key> KEYSTORE_PASS_FILE=<its password file> PREBUILT_LIBS=build/ci/panel-v0.1.<code> tools/build-apk.sh ../techo5
   ```

   With `PREBUILT_LIBS`, nothing is compiled for `echod`: the libraries go in as
   `lib/<abi>/libechod.so`, the APK takes the version in the tag, and `check-apk.sh` confirms the
   packed libraries are the ones `SHA256SUMS` lists. The checkout is only read for the wake word
   models.
3. **Run `tools/release.sh` again.** It refuses a debug build, a build with another update URL or
   version, and a build that does not carry those libraries; `check-apk.sh` then confirms each
   `lib/<abi>/libechod.so` in the APK has the sha256 that was attested. It writes `build/release`
   and prints the signing certificate and the `gh release create` command.
4. **Check** the signing certificate, then **release** with the printed command. The next release
   starts with `tools/release.sh new ../techo5`.

It writes `build/release/techo5-panel-<code>.apk`, `build/release/version.json`, and `NOTICE` and
`LICENSE` beside them:

```
{
  "versionCode": 418645,
  "versionName": "0.1.418645",
  "apk": "techo5-panel-418645.apk",
  "sha256": "<sha256 of the APK>"
}
```

All four go up as assets of one release, marked latest. The app reads
`https://github.com/HuskerMinion/techo5-panel/releases/latest/download/version.json` and then the APK
named there.
