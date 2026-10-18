#!/bin/sh
# Gets the last build ready to release on GitHub, and prints the command that releases it. It runs
# nothing on GitHub itself: you read the command and run it.
#
#   KEYSTORE=... KEYSTORE_PASS_FILE=... tools/build-apk.sh <techo5 checkout>
#   tools/release.sh
#
# Writes build/release/techo5-panel-<code>.apk and build/release/version.json (the version, the APK's
# name and its sha256). The tablets read version.json from the latest release and install the APK
# named there, so both go up as assets of one release, marked latest.
#
# Refused: a debug build, a build that updates from somewhere else, and an echod built from a techo5
# checkout with changes not committed, or from a commit that is not on GitHub.
set -eu

REPO=HuskerMinion/techo5-panel
PUBLIC_UPDATE_URL=https://github.com/$REPO/releases/latest/download

here=$(cd "$(dirname "$0")/.." && pwd)
b=$here/build

die() {
	echo "release: $*" >&2
	exit 1
}

[ -f "$b/techo5-panel.apk" ] || die "no build/techo5-panel.apk: run tools/build-apk.sh first"
[ "$(cat "$b/kind" 2>/dev/null)" = release ] || die "the last build is not a release build (DEBUG=1, or an old build)"
[ "$(cat "$b/update-url" 2>/dev/null)" = "$PUBLIC_UPDATE_URL" ] ||
	die "the last build updates from '$(cat "$b/update-url" 2>/dev/null)', not $PUBLIC_UPDATE_URL"
sh "$here/tools/check-apk.sh" "$b/techo5-panel.apk" release

# echod has to come from a commit anyone can build again: committed, and on a GitHub remote.
read -r commit dirty techo5 < "$b/source" || die "no build/source: build again with this build-apk.sh"
[ "$dirty" = clean ] || die "echod was built from a techo5 checkout with changes not committed"
published=""
for remote in $(git -C "$techo5" remote); do
	case "$(git -C "$techo5" remote get-url "$remote")" in
	*github.com[:/]HuskerMinion/techo5 | *github.com[:/]HuskerMinion/techo5.git)
		if [ -n "$(git -C "$techo5" branch -r --contains "$commit" --list "$remote/*")" ]; then
			published=$remote
		fi
		;;
	esac
done
[ -n "$published" ] || die "echod's commit $commit is on no branch of a GitHub techo5 remote (fetch, or push the core first)"

code=$(cat "$b/version-code")
name=$(cat "$b/version-name")
apk=techo5-panel-$code.apk
if command -v sha256sum >/dev/null 2>&1; then
	sha=$(sha256sum "$b/techo5-panel.apk" | cut -d' ' -f1)
else
	sha=$(shasum -a 256 "$b/techo5-panel.apk" | cut -d' ' -f1)
fi

rm -rf "$b/release"
mkdir -p "$b/release"
cp "$b/techo5-panel.apk" "$b/release/$apk"
printf '{\n  "versionCode": %s,\n  "versionName": "%s",\n  "apk": "%s",\n  "sha256": "%s"\n}\n' \
	"$code" "$name" "$apk" "$sha" > "$b/release/version.json"

echo "== release $name ready in build/release (echod from techo5 $commit, on $published)"
echo "   The signing certificate (check it is the release key's):"
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}}
bt=$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1)
apksigner=$bt/apksigner
[ -f "$bt/apksigner.bat" ] && apksigner=$bt/apksigner.bat
"$apksigner" verify --print-certs "$b/release/$apk" 2>/dev/null | grep -i 'certificate SHA-256' ||
	echo "   (apksigner not found: check with apksigner verify --print-certs build/release/$apk)"
echo
echo "To release it, write the notes to a file, then run:"
echo
echo "  gh release create v$name --repo $REPO --latest --title \"TECHO5 Panel $name\" --notes-file <notes.md> build/release/$apk build/release/version.json"
