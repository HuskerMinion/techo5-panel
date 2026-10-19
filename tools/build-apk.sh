#!/bin/sh
# Builds the TECHO5 Panel APK with the Android SDK's own tools, no Gradle: echod from a techo5
# checkout (the core with the panel build), then aapt2, javac, d8, zipalign and apksigner.
# docs/building.md has what to install and how to make a signing key.
#
#   tools/build-apk.sh [path to techo5 checkout]    (default $TECHO5, else ../techo5)
#
# Settings, from the environment:
#   KEYSTORE            the signing key (required). Kept outside every repository.
#   KEYSTORE_PASS       its password, or
#   KEYSTORE_PASS_FILE  a file holding it, outside every repository. Never put it in a script.
#   KEY_ALIAS           the key's alias, when the keystore holds more than one.
#   DEBUG=1             a debug build: debuggable, and echod with its debug tools. Never released.
#   PANEL_UPDATE_URL    where the app updates from, for a fork: an https URL, or "none" for no
#                       updates. Default: this project's latest GitHub release.
#   MODELS              the wake word models (default: the techo5 checkout's inputs/models).
#   ANDROID_SDK_ROOT    the Android SDK (default: Android Studio's place for it).
#   ANDROID_PLATFORM    the platform to build against, like android-34 (default: 34 if there,
#                       else the newest).
#
# Output: build/techo5-panel.apk, with build/version-code, build/version-name, build/update-url,
# build/kind ("release" or "debug") and build/source (the techo5 commit echod came from).
set -eu

# Where a release build updates from: the latest release's downloads (version.json and the APK).
PUBLIC_UPDATE_URL=https://github.com/HuskerMinion/techo5-panel/releases/latest/download
# Both ABIs: arm64 for 64-bit tablets, armv7 for the 32-bit ones (Fire OS) and Androids before 64-bit.
ABIS="arm64-v8a armeabi-v7a"
# The app's minSdkVersion (Android 8.0: the foreground service and its notification channel need it).
# The NDK compiler is picked for the same API level, and d8 builds for it.
API=26

here=$(cd "$(dirname "$0")/.." && pwd)
techo5=${1:-${TECHO5:-$here/../techo5}}
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}}
out=$here/build
debug=${DEBUG:-0}
exe=""
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) exe=".exe" ;; esac

die() {
	echo "build-apk: $*" >&2
	exit 1
}

# The absolute form of a path, for telling whether it sits inside this repository.
absolute() {
	(cd "$(dirname "$1")" 2>/dev/null && printf '%s/%s\n' "$(pwd)" "$(basename "$1")")
}

# Compared without case: Windows folders are the same whatever their case.
inside_repo() {
	p=$(absolute "$1" | tr '[:upper:]' '[:lower:]')
	r=$(printf '%s' "$here" | tr '[:upper:]' '[:lower:]')
	case "$p" in "$r"/*) return 0 ;; esac
	return 1
}

# A path as the SDK's Windows tools read it, where they need it inside an argument (file:<path>).
native() {
	if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi
}

# The key and its password: never in the repository, the password never on a command line.
keystore=${KEYSTORE:-}
[ -n "$keystore" ] || die "set KEYSTORE to the signing key (see docs/building.md)"
[ -f "$keystore" ] || die "no keystore at $keystore"
inside_repo "$keystore" && die "the keystore is inside this repository; keep it outside every repository"
if [ -n "${KEYSTORE_PASS_FILE:-}" ]; then
	[ -f "$KEYSTORE_PASS_FILE" ] || die "no password file at $KEYSTORE_PASS_FILE"
	inside_repo "$KEYSTORE_PASS_FILE" && die "the password file is inside this repository; keep it outside"
	kspass="file:$(native "$KEYSTORE_PASS_FILE")"
elif [ -n "${KEYSTORE_PASS:-}" ]; then
	kspass="env:KEYSTORE_PASS"
else
	die "set KEYSTORE_PASS or KEYSTORE_PASS_FILE (see docs/building.md)"
fi

[ -d "$techo5/echod" ] || die "$techo5 is not a techo5 checkout (no echod in it); name it, or set TECHO5"
[ -f "$techo5/echod/internal/layout/device_panel.go" ] ||
	die "$techo5 has no panel build (echod/internal/layout/device_panel.go); check out the core branch"
git -C "$techo5" rev-parse --git-dir >/dev/null 2>&1 || die "$techo5 is not a git checkout"

[ -d "$sdk" ] || die "no Android SDK at $sdk (set ANDROID_SDK_ROOT)"
bt=$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1)
[ -n "$bt" ] || die "no build-tools in $sdk"
if [ -n "${ANDROID_PLATFORM:-}" ]; then
	platform=$sdk/platforms/$ANDROID_PLATFORM
elif [ -d "$sdk/platforms/android-34" ]; then
	platform=$sdk/platforms/android-34
else
	platform=$(ls -d "$sdk"/platforms/android-* 2>/dev/null | sort -V | tail -1)
fi
jar=$platform/android.jar
[ -f "$jar" ] || die "no android.jar in $platform"
# d8 and apksigner are scripts: .bat ones on Windows.
d8=$bt/d8 apksigner=$bt/apksigner
[ -f "$bt/d8.bat" ] && d8=$bt/d8.bat
[ -f "$bt/apksigner.bat" ] && apksigner=$bt/apksigner.bat
ndk=$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1)
[ -n "$ndk" ] || die "no NDK in $sdk/ndk"

# Where the app updates from. A fork sets its own; only https is taken (the app allows no plain http).
url=${PANEL_UPDATE_URL:-$PUBLIC_UPDATE_URL}
case "$url" in
none) url="" ;;
https://*) url=${url%/} ;;
*) die "PANEL_UPDATE_URL has to be an https URL, or none" ;;
esac
case "$url" in *'"'* | *'\'* | *' '*) die "PANEL_UPDATE_URL has a character a URL cannot have here" ;; esac

# The version: minutes since 2026-01-01 UTC, so every build is newer than the last and fits
# Android's int. echod says the same version, as v0.1.<code>, on the setup page, the screen and Home
# Assistant.
code=$(( ( $(date -u +%s) - 1767225600 ) / 60 ))
name="0.1.$code"
if [ "$debug" = 1 ]; then
	kind=debug
	tags=panel,panel_debug
else
	kind=release
	tags=panel
fi

rm -rf "$out/apk" "$out/classes" "$out/dex" "$out/gen" "$out/res" "$out/update-url" "$out/kind" "$out/source"
mkdir -p "$out/apk" "$out/classes" "$out/dex"

commit=$(git -C "$techo5" rev-parse HEAD)
dirty=clean
[ -z "$(git -C "$techo5" status --porcelain --untracked-files=no)" ] || dirty=dirty
branch=$(git -C "$techo5" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "?")

# GOOS=android, with the NDK's compiler: a plain linux build dies inside an app the first time it starts
# a process, because Go probes pidfd_open and Android's app seccomp filter kills the caller for it
# (Go only steps around that on GOOS=android). It also gets bionic's resolver, so names resolve.
for abi in $ABIS; do
	case "$abi" in
	arm64-v8a) goarch=arm64 goarm="" triple=aarch64-linux-android ;;
	armeabi-v7a) goarch=arm goarm=7 triple=armv7a-linux-androideabi ;;
	*) die "unknown ABI $abi" ;;
	esac
	cc=$(ls "$ndk"/toolchains/llvm/prebuilt/*/bin/$triple$API-clang* 2>/dev/null | head -1)
	[ -n "$cc" ] || die "no $triple$API-clang in $ndk"
	echo "== echod for $abi ($kind build, from $branch $commit, $dirty)"
	mkdir -p "$out/apk/lib/$abi"
	(cd "$techo5/echod" && GOOS=android GOARCH=$goarch GOARM=$goarm CGO_ENABLED=1 CC="$cc" \
		go build -tags "$tags" -trimpath \
		-ldflags "-s -w -X github.com/HuskerMinion/techo5/echod/internal/layout.Version=v$name" \
		-o "$out/apk/lib/$abi/libechod.so" ./cmd/echod)
done

mkdir -p "$out/gen/app/techo5/panel"
cat > "$out/gen/app/techo5/panel/Release.java" <<EOJ
package app.techo5.panel;

/** Written by tools/build-apk.sh. */
final class Release {
    static final String UPDATE_URL = "$url";
    static final String VERSION = "$name";

    private Release() {
    }
}
EOJ

# The wake word models the Echos' images carry, as assets: the app puts any a tablet does not have
# yet in place before echod starts, as the Echos' boot does. techo5's tools/fetch-inputs.py fetches them.
models=${MODELS:-$techo5/inputs/models}
ls "$models"/*.tflite >/dev/null 2>&1 || die "no wake word models in $models (run techo5's tools/fetch-inputs.py, or set MODELS)"
mkdir -p "$out/apk/assets/models"
cp "$models"/*.tflite "$models"/*.json "$out/apk/assets/models/"

echo "== manifest and resources ($name)"
mkdir -p "$out/res"
"$bt/aapt2$exe" compile --dir "$here/app/res" -o "$out/res"
debugmode=""
[ "$debug" = 1 ] && debugmode=--debug-mode
# No -A here: aapt2 on Windows stores asset paths with backslashes (assets/models\alexa.tflite), which
# Android does not find. The assets go in below, with the native libraries, from a relative path.
"$bt/aapt2$exe" link -o "$out/base.apk" -I "$jar" --manifest "$here/app/AndroidManifest.xml" \
	$debugmode --version-code "$code" --version-name "$name" "$out"/res/*.flat

echo "== java"
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$jar" -classpath "$jar" -d "$out/classes" \
	$(find "$here/app/src" "$out/gen" -name '*.java')

echo "== dex"
d8mode=--release
[ "$debug" = 1 ] && d8mode=--debug
"$d8" $d8mode --min-api $API --output "$out/dex" $(find "$out/classes" -name '*.class')

echo "== package"
cp "$out/base.apk" "$out/unsigned.apk"
cp "$out/dex/classes.dex" "$out/apk/"
# jar names every entry with '/', on any system. The list is relative to $out/apk, files only.
(cd "$out/apk" && jar ufM "$out/unsigned.apk" $(find . -type f | sed 's#^\./##' | sort))
"$bt/zipalign$exe" -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"

"$apksigner" sign --ks "$keystore" --ks-pass "$kspass" ${KEY_ALIAS:+--ks-key-alias "$KEY_ALIAS"} \
	--out "$out/techo5-panel.apk" "$out/aligned.apk"
rm -f "$out/base.apk" "$out/unsigned.apk" "$out/aligned.apk"

sh "$here/tools/check-apk.sh" "$out/techo5-panel.apk" "$kind"

echo "$code" > "$out/version-code"
echo "$name" > "$out/version-name"
echo "$url" > "$out/update-url"
echo "$kind" > "$out/kind"
printf '%s %s %s\n' "$commit" "$dirty" "$techo5" > "$out/source"
echo "== $kind build $name, updating from ${url:-nowhere}"
ls -la "$out/techo5-panel.apk"
