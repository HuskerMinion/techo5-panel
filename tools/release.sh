#!/bin/sh
# Gets the last build ready to release on GitHub, and prints the commands that release it. It runs
# nothing on GitHub itself: you read each command and run it. docs/building.md, "Releasing", has the
# whole flow.
#
#   KEYSTORE=... KEYSTORE_PASS_FILE=... tools/build-apk.sh <techo5 checkout>
#   tools/release.sh            prints the techo5 tag that has CI build echod from the same commit
#   (CI runs; download its libraries into build/ci/<tag>, as printed)
#   tools/release.sh            again: compares the APK's echod with CI's, then prints the release
#
# Writes build/release/techo5-panel-<code>.apk, build/release/version.json (the version, the APK's
# name and its sha256), and NOTICE and LICENSE beside them. The tablets read version.json from the
# latest release and install the APK named there, so all of it goes up as assets of one release,
# marked latest.
#
# Refused: a debug build, a build that updates from somewhere else, an echod built from a techo5
# checkout with changes not committed, from a commit that is not on GitHub, or with another Go or NDK
# than build-apk.sh pins; and an APK whose echod differs from the one techo5's CI built and attested
# from the same commit.
set -eu

REPO=HuskerMinion/techo5-panel
CORE=HuskerMinion/techo5
PUBLIC_UPDATE_URL=https://github.com/$REPO/releases/latest/download
ABIS="arm64-v8a armeabi-v7a"

here=$(cd "$(dirname "$0")/.." && pwd)
b=$here/build

die() {
	echo "release: $*" >&2
	exit 1
}

# A path as both Git Bash and PowerShell take it, for the commands printed here.
shown() {
	if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}

sha256() {
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$1" | cut -d' ' -f1
	else
		shasum -a 256 "$1" | cut -d' ' -f1
	fi
}

[ -f "$b/techo5-panel.apk" ] || die "no build/techo5-panel.apk: run tools/build-apk.sh first"
[ "$(cat "$b/kind" 2>/dev/null)" = release ] || die "the last build is not a release build (DEBUG=1, or an old build)"
[ "$(cat "$b/update-url" 2>/dev/null)" = "$PUBLIC_UPDATE_URL" ] ||
	die "the last build updates from '$(cat "$b/update-url" 2>/dev/null)', not $PUBLIC_UPDATE_URL"
sh "$here/tools/check-apk.sh" "$b/techo5-panel.apk" release

# build/source, as build-apk.sh writes it: one "key value" line each.
[ -f "$b/source" ] || die "no build/source: build again with this build-apk.sh"
field() {
	sed -n "s/^$1 //p" "$b/source" | head -1
}
commit=$(field commit) dirty=$(field state) gover=$(field go) ndkver=$(field ndk) techo5=$(field techo5)
[ -n "$commit" ] && [ -n "$gover" ] && [ -n "$ndkver" ] || die "build/source is from an older build-apk.sh: build again"

# The same Go and NDK as CI's panel job, or the two libraries cannot match.
pinned() {
	sed -n "s/^$1=//p" "$here/tools/build-apk.sh" | head -1
}
[ "$gover" = "go$(pinned GO_VERSION)" ] || die "echod was built with $gover, not go$(pinned GO_VERSION)"
[ "$ndkver" = "$(pinned NDK_VERSION)" ] || die "echod was built with NDK $ndkver, not $(pinned NDK_VERSION)"

# echod has to come from a commit anyone can build again: committed, and on a GitHub remote.
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
tag=panel-v$name
apk=techo5-panel-$code.apk
sha=$(sha256 "$b/techo5-panel.apk")

rm -rf "$b/release"
mkdir -p "$b/release"
cp "$b/techo5-panel.apk" "$b/release/$apk"
cp "$here/NOTICE" "$here/LICENSE" "$b/release/"
printf '{\n  "versionCode": %s,\n  "versionName": "%s",\n  "apk": "%s",\n  "sha256": "%s"\n}\n' \
	"$code" "$name" "$apk" "$sha" > "$b/release/version.json"

echo "== release $name ready in build/release (echod from techo5 $commit, on $published; $gover, NDK $ndkver)"

# CI's libraries, from the techo5 tag for this version, in a folder named for it: until they are
# there, say how to get them.
ci=$b/ci/$tag
if [ ! -f "$ci/SHA256SUMS" ]; then
	t5=$(shown "$techo5")
	echo
	echo "Next, have techo5's CI build and attest echod from the same commit: tag it and push the tag."
	echo
	echo "  git -C $t5 tag $tag $commit && git -C $t5 push $published $tag"
	echo
	echo "When the run for $tag has finished, download its libraries and run this again:"
	echo
	echo "  gh run download --repo $CORE --name techo5-$tag --dir build/ci/$tag"
	echo "  tools/release.sh"
	exit 0
fi

# The APK's echod has to be CI's, byte for byte, for both ABIs.
unpacked=$b/release-libs
rm -rf "$unpacked"
mkdir -p "$unpacked"
(cd "$unpacked" && jar xf "$b/techo5-panel.apk" $(for abi in $ABIS; do printf 'lib/%s/libechod.so ' "$abi"; done))
for abi in $ABIS; do
	mine=$(sha256 "$unpacked/lib/$abi/libechod.so")
	theirs=$(sed -n "s/^\([0-9a-f]\{64\}\)  *\*\{0,1\}libechod-$abi\.so$/\1/p" "$ci/SHA256SUMS" | head -1)
	[ -n "$theirs" ] || die "build/ci/$tag/SHA256SUMS has no libechod-$abi.so"
	[ -f "$ci/libechod-$abi.so" ] && [ "$(sha256 "$ci/libechod-$abi.so")" = "$theirs" ] ||
		die "build/ci/$tag/libechod-$abi.so is missing or does not match CI's SHA256SUMS: download it again"
	[ "$mine" = "$theirs" ] ||
		die "the APK's lib/$abi/libechod.so ($mine) is not the one CI built from $tag ($theirs): same commit, Go and NDK?"
	echo "   lib/$abi/libechod.so is CI's: $mine"
done
rm -rf "$unpacked"

echo "   The signing certificate (check it is the release key's):"
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}}
bt=$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1)
apksigner=$bt/apksigner
[ -f "$bt/apksigner.bat" ] && apksigner=$bt/apksigner.bat
"$apksigner" verify --print-certs "$b/release/$apk" 2>/dev/null | grep -i 'certificate SHA-256' ||
	echo "   (apksigner not found: check with apksigner verify --print-certs build/release/$apk)"
echo
echo "Check CI's attestations for the two libraries (each should say it was verified):"
echo
for abi in $ABIS; do
	echo "  gh attestation verify build/ci/$tag/libechod-$abi.so --repo $CORE --source-ref refs/tags/$tag"
done
echo
echo "Then, to release it, write the notes to a file and run:"
echo
echo "  gh release create v$name --repo $REPO --latest --title \"TECHO5 Panel $name\" --notes-file <notes.md> build/release/$apk build/release/version.json build/release/NOTICE build/release/LICENSE"
