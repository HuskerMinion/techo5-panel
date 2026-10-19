#!/bin/sh
# Builds the TECHO5 Panel APK with the Android SDK's own tools, no Gradle: echod from a techo5
# checkout (main, or a tag v1.3.0 or later, which have the panel build), then aapt2, javac, d8,
# zipalign and apksigner. docs/building.md has what to install and how to make a signing key.
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
#   PREBUILT_LIBS       a folder of echod libraries to pack instead of building them: CI's, as
#                       tools/release.sh downloads and checks them into build/ci/<tag>
#                       (libechod-<abi>.so, SHA256SUMS, version-code). This is how a release is
#                       built; nothing is compiled for echod, and the techo5 checkout is only read
#                       for the wake word models (none needed with MODELS set).
#
# Go and the NDK are pinned (GO_VERSION, NDK_VERSION below, the same as techo5's CI panel job), so a
# library built here is as near CI's as a build on another machine gets. It is never byte for byte
# the same (the compiler's path is in Go's build ID, and the NDK's host differs), so a release does not
# carry it: it carries CI's own (PREBUILT_LIBS). A local build with another Go or NDK says so loudly
# and goes on.
#
# Output: build/techo5-panel.apk, with build/version-code, build/version-name, build/update-url,
# build/kind ("release" or "debug") and build/source (where echod came from: the techo5 commit, Go
# and NDK it was built with, or the prebuilt libraries and their tag).
set -eu

# Where a release build updates from: the latest release's downloads (version.json and the APK).
PUBLIC_UPDATE_URL=https://github.com/HuskerMinion/techo5-panel/releases/latest/download
# Both ABIs: arm64 for 64-bit tablets, armv7 for the 32-bit ones (Fire OS) and Androids before 64-bit.
ABIS="arm64-v8a armeabi-v7a"
# The app's minSdkVersion (Android 8.0: the foreground service and its notification channel need it).
# The NDK compiler is picked for the same API level, and d8 builds for it.
API=26
# The Go and the NDK echod is built with: techo5's .github/workflows/build.yml (the panel job) names
# the same two. Move them together.
GO_VERSION=1.26.8
NDK_VERSION=28.2.13676358
# The wake word models the APK carries, each as <name>.tflite and <name>.json: the Echos' own set
# (techo5's tools/fetch-inputs.py), and nothing else that happens to be in the folder.
MODEL_NAMES="okay_nabu hey_jarvis hey_mycroft alexa computer jarvis hey_friday glados hal terminator marvin home_assistant"

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

prebuilt=${PREBUILT_LIBS:-}
if [ -n "$prebuilt" ]; then
	[ -d "$prebuilt" ] || die "PREBUILT_LIBS: no folder at $prebuilt"
	prebuilt=$(cd "$prebuilt" && pwd)
	# CI's libraries are a release build's; a debug build carries echod's debug tools, built here.
	[ "$debug" = 1 ] && die "a debug build builds its own echod; unset PREBUILT_LIBS"
	[ -f "$prebuilt/SHA256SUMS" ] || die "no SHA256SUMS in $prebuilt"
	# The version CI stamped into them (the tag's), so the APK says the same.
	pcode=${VERSION_CODE:-$(cat "$prebuilt/version-code" 2>/dev/null || true)}
	case "$pcode" in
	'' | *[!0-9]*) die "no version for $prebuilt: set VERSION_CODE to the one in its tag, or let tools/release.sh fetch it" ;;
	esac
else
	[ -d "$techo5/echod" ] || die "$techo5 is not a techo5 checkout (no echod in it); name it, or set TECHO5"
	[ -f "$techo5/echod/internal/layout/device_panel.go" ] ||
		die "$techo5 has no panel build (echod/internal/layout/device_panel.go); check out techo5's main, or a tag v1.3.0 or later"
	git -C "$techo5" rev-parse --git-dir >/dev/null 2>&1 || die "$techo5 is not a git checkout"
fi

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
# Loud, and nothing more: a library built here is never released (PREBUILT_LIBS), but one built
# with another Go or NDK than CI's is further from what the tablets run.
mismatch() {
	echo "build-apk: ****************************************************************" >&2
	echo "build-apk: WARNING: $*" >&2
	echo "build-apk: going on; this echod is not CI's, and a release carries CI's" >&2
	echo "build-apk: ****************************************************************" >&2
}

if [ -z "$prebuilt" ]; then
	ndk=$sdk/ndk/$NDK_VERSION
	if [ ! -d "$ndk" ]; then
		other=$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1)
		[ -n "$other" ] || die "no NDK in $sdk/ndk (install ndk;$NDK_VERSION with sdkmanager)"
		mismatch "NDK $NDK_VERSION is not in $sdk/ndk (install ndk;$NDK_VERSION with sdkmanager); found $(basename "$other")"
		ndk=$other
	fi

	# Go: the pinned toolchain, which the go command fetches itself the first time (Go 1.21 and
	# later), unless GOTOOLCHAIN is set to something else on purpose.
	export GOTOOLCHAIN=${GOTOOLCHAIN:-go$GO_VERSION}
	command -v go >/dev/null 2>&1 || die "no go on PATH"
	gover=$(cd "$techo5/echod" && go env GOVERSION) || die "go did not run (GOTOOLCHAIN=$GOTOOLCHAIN)"
	[ "$gover" = "go$GO_VERSION" ] || mismatch "echod would be built with $gover, not go$GO_VERSION (unset GOTOOLCHAIN)"
fi

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
[ -n "$prebuilt" ] && code=$pcode
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

if [ -n "$prebuilt" ]; then
	# Packed as they are: check-apk.sh, at the end, confirms the APK carries exactly the libraries
	# SHA256SUMS lists.
	for abi in $ABIS; do
		lib=$prebuilt/libechod-$abi.so
		[ -f "$lib" ] || die "no libechod-$abi.so in $prebuilt"
		echo "== echod for $abi: prebuilt, $lib"
		mkdir -p "$out/apk/lib/$abi"
		cp "$lib" "$out/apk/lib/$abi/libechod.so"
	done
else
	commit=$(git -C "$techo5" rev-parse HEAD)
	dirty=clean
	[ -z "$(git -C "$techo5" status --porcelain --untracked-files=no)" ] || dirty=dirty
	branch=$(git -C "$techo5" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "?")

	# GOOS=android, with the NDK's compiler: a plain linux build dies inside an app the first time it
	# starts a process, because Go probes pidfd_open and Android's app seccomp filter kills the caller
	# for it (Go only steps around that on GOOS=android). It also gets bionic's resolver, so names
	# resolve.
	for abi in $ABIS; do
		case "$abi" in
		arm64-v8a) goarch=arm64 goarm="" triple=aarch64-linux-android ;;
		armeabi-v7a) goarch=arm goarm=7 triple=armv7a-linux-androideabi ;;
		*) die "unknown ABI $abi" ;;
		esac
		cc=$(ls "$ndk"/toolchains/llvm/prebuilt/*/bin/$triple$API-clang* 2>/dev/null | head -1)
		[ -n "$cc" ] || die "no $triple$API-clang in $ndk"
		echo "== echod for $abi ($kind build, from $branch $commit, $dirty; $gover, NDK $(basename "$ndk"))"
		mkdir -p "$out/apk/lib/$abi"
		(cd "$techo5/echod" && GOOS=android GOARCH=$goarch GOARM=$goarm CGO_ENABLED=1 CC="$cc" \
			go build -tags "$tags" -trimpath \
			-ldflags "-s -w -X github.com/HuskerMinion/techo5/echod/internal/layout.Version=v$name" \
			-o "$out/apk/lib/$abi/libechod.so" ./cmd/echod)
	done
fi

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
mkdir -p "$out/apk/assets/models"
for m in $MODEL_NAMES; do
	for ext in tflite json; do
		[ -f "$models/$m.$ext" ] || die "no $m.$ext in $models (run techo5's tools/fetch-inputs.py, or set MODELS)"
		cp "$models/$m.$ext" "$out/apk/assets/models/"
	done
done

# The licenses go in the APK with what they cover.
cp "$here/NOTICE" "$out/apk/assets/NOTICE"
cp "$here/LICENSE" "$out/apk/assets/LICENSE"

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

if [ -n "$prebuilt" ]; then
	sh "$here/tools/check-apk.sh" "$out/techo5-panel.apk" "$kind" "$prebuilt/SHA256SUMS"
else
	sh "$here/tools/check-apk.sh" "$out/techo5-panel.apk" "$kind"
fi

echo "$code" > "$out/version-code"
echo "$name" > "$out/version-name"
echo "$url" > "$out/update-url"
echo "$kind" > "$out/kind"
if [ -n "$prebuilt" ]; then
	printf 'libs prebuilt\ntag panel-v%s\nfrom %s\n' "$name" "$prebuilt" > "$out/source"
else
	printf 'libs built\ncommit %s\nstate %s\ngo %s\nndk %s\ntecho5 %s\n' "$commit" "$dirty" "$gover" "$(basename "$ndk")" "$techo5" \
		> "$out/source"
fi
echo "== $kind build $name, updating from ${url:-nowhere}"
ls -la "$out/techo5-panel.apk"
