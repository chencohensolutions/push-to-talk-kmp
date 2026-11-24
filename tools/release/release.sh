#!/bin/sh
# Publishes the app's signed release on GitHub: tools/release/release.sh <notes.md>
#
# Builds the release APK (signed with the release key, see androidApp/build.gradle.kts) at the version in
# androidApp/build.gradle.kts, then makes release v<version> from main, marked latest (never a pre-release), with:
#   talk-<version>.apk  the APK under its version's name
#   talk.apk            the same file under a fixed name, so that
#                       https://github.com/chencohensolutions/push-to-talk-kmp/releases/latest/download/talk.apk
#                       is always the newest APK (the link pages' download button points there)
#   SHA256SUMS
# Run it from a checkout of main with the version commit pushed.
set -eu
notes=${1:?usage: tools/release/release.sh <notes.md>}
cd "$(dirname "$0")/../.."
version=$(sed -n 's/.*versionName = .*?: "\([0-9.]*\)".*/\1/p' androidApp/build.gradle.kts)
[ -n "$version" ] || { echo "No versionName in androidApp/build.gradle.kts" >&2; exit 1; }
./gradlew -q :androidApp:assembleRelease
out=build/release/$version
rm -rf "$out" && mkdir -p "$out"
cp androidApp/build/outputs/apk/release/androidApp-release.apk "$out/talk-$version.apk"
cp "$out/talk-$version.apk" "$out/talk.apk"
(cd "$out" && shasum -a 256 "talk-$version.apk" talk.apk > SHA256SUMS)
gh release create "v$version" --target main --latest --title "Talk $version" --notes-file "$notes" \
  "$out/talk-$version.apk" "$out/talk.apk" "$out/SHA256SUMS"
echo "Published v$version: $(gh release view "v$version" --json url -q .url)"
