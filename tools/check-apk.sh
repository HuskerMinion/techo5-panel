#!/bin/sh
# Checks a built APK before it goes anywhere: every entry named with '/', echod there for both ABIs,
# exactly the twelve wake word models and nothing else beside them, NOTICE and LICENSE there, and a
# release build not debuggable. build-apk.sh runs it on each build, and release.sh again before a
# release.
#
#   tools/check-apk.sh <apk> [release|debug]     (default release)
set -eu

# The models, as build-apk.sh's MODEL_NAMES has them: each as <name>.tflite and <name>.json.
MODEL_NAMES="okay_nabu hey_jarvis hey_mycroft alexa computer jarvis hey_friday glados hal terminator marvin home_assistant"

apk=$1
kind=${2:-release}
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}}
exe=""
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) exe=".exe" ;; esac
bt=$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1)

fail() {
	echo "check-apk: $apk: $*" >&2
	exit 1
}

[ -f "$apk" ] || fail "no such file"
entries=$(jar tf "$apk" | tr -d '\r')

# Android looks assets and libraries up by '/' paths; an entry stored with a backslash is not found.
bad=$(printf '%s\n' "$entries" | grep -F '\' || true)
[ -z "$bad" ] || fail "entries named with a backslash: $(printf '%s ' $bad)"

for abi in arm64-v8a armeabi-v7a; do
	printf '%s\n' "$entries" | grep -qx "lib/$abi/libechod.so" || fail "no lib/$abi/libechod.so"
done
want=$(for m in $MODEL_NAMES; do printf 'assets/models/%s.json\nassets/models/%s.tflite\n' "$m" "$m"; done | sort)
have=$(printf '%s\n' "$entries" | grep '^assets/models/' | grep -v '/$' | sort || true)
[ "$have" = "$want" ] || fail "assets/models is not the twelve wake word models: has
$have"
for f in assets/NOTICE assets/LICENSE; do
	printf '%s\n' "$entries" | grep -qx "$f" || fail "no $f"
done

if [ "$kind" = release ]; then
	[ -n "$bt" ] || fail "no Android build-tools found to read the manifest with"
	if "$bt/aapt2$exe" dump badging "$apk" 2>/dev/null | grep -q '^application-debuggable'; then
		fail "a release build is debuggable"
	fi
fi
echo "== $apk: entries named with '/', both ABIs, the twelve models, NOTICE and LICENSE, $kind"
