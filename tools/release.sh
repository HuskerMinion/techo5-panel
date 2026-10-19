#!/bin/sh
# Takes a release from a techo5 commit to the commands that publish it, a step per run. The APK's
# echod is the pair of libraries techo5's CI built and attested from that commit, not one built here:
# a library built on another machine never comes out byte for byte the same (the C compiler's path is
# in Go's build ID, and the NDK's host differs), so the release carries CI's own, as the Echos' images
# do. docs/building.md, "Releasing", has the whole flow.
#
#   tools/release.sh [techo5 checkout]   plans the release: the version, and the techo5 tag to push
#                                        (default $TECHO5, else ../techo5)
#   tools/release.sh                     again once CI has run: downloads its libraries for the tag
#                                        (gh run download), checks their attestations (gh attestation
#                                        verify), and prints the build-apk.sh command that packs them
#   tools/release.sh                     again after that build: checks the APK carries exactly those
#                                        libraries, writes build/release, and prints the release command
#   tools/release.sh new [checkout]      forgets the planned release and plans a new one
#
# Writes build/release-plan (the version and the commit), build/ci/<tag> (CI's libraries, their
# SHA256SUMS and, once their attestations check out, attested), and build/release:
# techo5-panel-<code>.apk, version.json (the version, the APK's name and its sha256), and NOTICE and
# LICENSE beside them. The tablets read version.json from the latest release and install the APK
# named there, so all of it goes up as assets of one release, marked latest. It only reads from
# GitHub; the tag and the release are commands it prints for you to run.
#
# Refused: a techo5 checkout with changes not committed or a commit that is not on GitHub; a CI run
# that failed or built another commit; libraries that do not match their SHA256SUMS or whose
# attestation does not verify; and an APK that is a debug build, updates from somewhere else, or
# carries any other echod than the attested pair.
set -eu

REPO=HuskerMinion/techo5-panel
CORE=HuskerMinion/techo5
WORKFLOW=.github/workflows/build.yml
PUBLIC_UPDATE_URL=https://github.com/$REPO/releases/latest/download
ABIS="arm64-v8a armeabi-v7a"

here=$(cd "$(dirname "$0")/.." && pwd)
b=$here/build
plan=$b/release-plan

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

# One "key value" line each, as this script and build-apk.sh write them.
field() {
	sed -n "s/^$2 //p" "$1" 2>/dev/null | head -1
}

# The sha256 SHA256SUMS gives a library, in sha256sum's format either way (text or binary mode).
listed() {
	sed -n "s/^\([0-9a-f]\{64\}\)  *\*\{0,1\}libechod-$2\.so$/\1/p" "$1" | head -1
}

if [ "${1:-}" = new ]; then
	rm -f "$plan"
	shift
fi

# 1. The plan: a version, and the techo5 commit CI builds echod from. The version is minutes since
# 2026-01-01 UTC, as build-apk.sh numbers a build, so every release is newer than the last; the tag
# carries it, and CI stamps it into the libraries, so the APK and its echod say the same version.
if [ ! -f "$plan" ]; then
	techo5=${1:-${TECHO5:-$here/../techo5}}
	[ -d "$techo5/echod" ] || die "$techo5 is not a techo5 checkout (no echod in it); name it, or set TECHO5"
	[ -f "$techo5/echod/internal/layout/device_panel.go" ] ||
		die "$techo5 has no panel build; check out techo5's main, or a tag v1.3.0 or later"
	git -C "$techo5" rev-parse --git-dir >/dev/null 2>&1 || die "$techo5 is not a git checkout"
	[ -z "$(git -C "$techo5" status --porcelain --untracked-files=no)" ] ||
		die "$techo5 has changes not committed"
	commit=$(git -C "$techo5" rev-parse HEAD)

	# A commit anyone can build again: on a branch of a GitHub techo5 remote.
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
	[ -n "$published" ] || die "techo5's commit $commit is on no branch of a GitHub techo5 remote (fetch, or push the core first)"

	code=$(( ( $(date -u +%s) - 1767225600 ) / 60 ))
	tag=panel-v0.1.$code
	mkdir -p "$b"
	printf 'tag %s\ncode %s\ncommit %s\n' "$tag" "$code" "$commit" > "$plan"

	t5=$(shown "$techo5")
	echo "== release 0.1.$code planned, echod from techo5 $commit (on $published)"
	echo
	echo "Next, have techo5's CI build and attest echod from that commit: tag it and push the tag."
	echo
	echo "  git -C $t5 tag $tag $commit && git -C $t5 push $published $tag"
	echo
	echo "When the run for $tag has finished, run this again: tools/release.sh"
	exit 0
fi

tag=$(field "$plan" tag) code=$(field "$plan" code) commit=$(field "$plan" commit)
[ -n "$tag" ] && [ -n "$code" ] && [ -n "$commit" ] || die "build/release-plan is not one this script wrote: tools/release.sh new"
name=0.1.$code
ci=$b/ci/$tag

# 2. CI's libraries: the run the tag started, downloaded, checked against its SHA256SUMS, and each
# library's attestation verified as built by techo5's workflow from that tag.
if [ ! -f "$ci/attested" ]; then
	command -v gh >/dev/null 2>&1 || die "no gh on PATH (GitHub's CLI downloads and verifies CI's libraries)"
	if [ ! -f "$ci/SHA256SUMS" ]; then
		run=$(gh run list --repo "$CORE" --workflow build.yml --branch "$tag" --limit 1 \
			--json databaseId,status,conclusion,headSha \
			--jq '.[] | "\(.databaseId) \(.status) \(.conclusion) \(.headSha)"') ||
			die "gh run list failed (gh auth status?)"
		[ -n "$run" ] || die "no CI run for $tag yet: push the tag (tools/release.sh printed how), then run this again"
		set -- $run
		[ "$2" = completed ] || die "CI's run for $tag is $2: run this again when it has finished"
		[ "$3" = success ] || die "CI's run for $tag ended $3; nothing to release from it"
		[ "$4" = "$commit" ] || die "CI's run for $tag built $4, not the planned $commit: was the tag moved?"
		rm -rf "$ci"
		mkdir -p "$ci"
		gh run download "$1" --repo "$CORE" --name "techo5-$tag" --dir "$ci" || die "downloading CI's libraries failed"
	fi
	[ -f "$ci/SHA256SUMS" ] || die "CI's download has no SHA256SUMS"
	for abi in $ABIS; do
		lib=$ci/libechod-$abi.so
		sum=$(listed "$ci/SHA256SUMS" "$abi")
		[ -n "$sum" ] || die "build/ci/$tag/SHA256SUMS has no libechod-$abi.so"
		[ -f "$lib" ] && [ "$(sha256 "$lib")" = "$sum" ] ||
			die "build/ci/$tag/libechod-$abi.so is missing or does not match CI's SHA256SUMS: remove build/ci/$tag and run this again"
		gh attestation verify "$lib" --repo "$CORE" --source-ref "refs/tags/$tag" \
			--signer-workflow "$CORE/$WORKFLOW" >/dev/null ||
			die "the attestation for libechod-$abi.so did not verify: not released"
		echo "   libechod-$abi.so: $sum, attested by $CORE's CI for $tag"
	done
	for abi in $ABIS; do
		printf '%s  libechod-%s.so\n' "$(listed "$ci/SHA256SUMS" "$abi")" "$abi"
	done > "$ci/attested"
	echo "$code" > "$ci/version-code"

	echo
	echo "Next, build the APK around them (the techo5 checkout is only read for the wake word models):"
	echo
	echo "  KEYSTORE=<release key> KEYSTORE_PASS_FILE=<its password file> PREBUILT_LIBS=build/ci/$tag tools/build-apk.sh <techo5 checkout>"
	echo
	echo "and run this again: tools/release.sh"
	exit 0
fi

# 3. The APK: a release build updating from this project's releases, at the planned version, around
# exactly the attested libraries.
[ -f "$b/techo5-panel.apk" ] || die "no build/techo5-panel.apk: build it as printed before (PREBUILT_LIBS=build/ci/$tag)"
[ "$(cat "$b/kind" 2>/dev/null)" = release ] || die "the last build is not a release build (DEBUG=1, or an old build)"
[ "$(cat "$b/update-url" 2>/dev/null)" = "$PUBLIC_UPDATE_URL" ] ||
	die "the last build updates from '$(cat "$b/update-url" 2>/dev/null)', not $PUBLIC_UPDATE_URL"
[ "$(cat "$b/version-code" 2>/dev/null)" = "$code" ] ||
	die "the last build is version $(cat "$b/version-code" 2>/dev/null), not the planned $code"
[ "$(field "$b/source" libs)" = prebuilt ] && [ "$(field "$b/source" tag)" = "$tag" ] ||
	die "the last build does not carry CI's libraries for $tag: build with PREBUILT_LIBS=build/ci/$tag"
sh "$here/tools/check-apk.sh" "$b/techo5-panel.apk" release "$ci/attested"

apk=techo5-panel-$code.apk
sha=$(sha256 "$b/techo5-panel.apk")
rm -rf "$b/release"
mkdir -p "$b/release"
cp "$b/techo5-panel.apk" "$b/release/$apk"
cp "$here/NOTICE" "$here/LICENSE" "$b/release/"
printf '{\n  "versionCode": %s,\n  "versionName": "%s",\n  "apk": "%s",\n  "sha256": "%s"\n}\n' \
	"$code" "$name" "$apk" "$sha" > "$b/release/version.json"

echo "== release $name ready in build/release (echod as CI built and attested it from techo5 $commit, $tag)"
echo "   The signing certificate (check it is the release key's):"
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}}
bt=$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1)
apksigner=$bt/apksigner
[ -f "$bt/apksigner.bat" ] && apksigner=$bt/apksigner.bat
"$apksigner" verify --print-certs "$b/release/$apk" 2>/dev/null | grep -i 'certificate SHA-256' ||
	echo "   (apksigner not found: check with apksigner verify --print-certs build/release/$apk)"
echo
echo "To release it, write the notes to a file and run:"
echo
echo "  gh release create v$name --repo $REPO --latest --title \"TECHO5 Panel $name\" --notes-file <notes.md> build/release/$apk build/release/version.json build/release/NOTICE build/release/LICENSE"
echo
echo "The next release starts with: tools/release.sh new"
