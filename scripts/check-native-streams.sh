#!/usr/bin/env bash
# Offline stream-selection tests: uses the app's pinned extractor, no network/TV/Gradle.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if [[ -z "${JAVA_HOME:-}" && -x "$project_dir/.tools/jdk-17/bin/javac" ]]; then export JAVA_HOME="$project_dir/.tools/jdk-17"; fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
for tool in java javac sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || { printf 'Required command missing: %s\nUse JDK 17, or run ./scripts/bootstrap-android.sh --tools-only.\n' "$tool" >&2; exit 1; }
done
extractor_sha256='154fd5efc333d8fdc9cc60abac12d42cd6bf49c8df2f93c5a850fd60c8d7534a'
extractor_jar=''
shopt -s nullglob
candidates=("${NEWPIPE_EXTRACTOR_JAR:-$project_dir/.tools/native-source/NewPipeExtractor-v0.27.1.jar}"
    "${GRADLE_USER_HOME:-$HOME/.gradle}"/caches/modules-2/files-2.1/com.github.TeamNewPipe/NewPipeExtractor/v0.27.1/*/NewPipeExtractor-v0.27.1.jar)
for candidate in "${candidates[@]}"; do
    if [[ -f "$candidate" ]] && printf '%s  %s\n' "$extractor_sha256" "$candidate" | sha256sum --status -c -; then extractor_jar="$candidate"; break; fi
done
if [[ -z "$extractor_jar" ]]; then
    printf '%s\n' 'Pinned NewPipeExtractor v0.27.1 jar is required. Resolve the existing app dependencies once, or set NEWPIPE_EXTRACTOR_JAR to that verified jar.' >&2
    exit 1
fi
framework_jar="${ANDROID_HOME:-$project_dir/.tools/android-sdk}/platforms/android-35/android.jar"
if [[ ! -f "$framework_jar" ]]; then framework_jar="$project_dir/.tools/java-check/android-all-11-robolectric-6757853.jar"; fi
if [[ ! -f "$framework_jar" ]]; then printf '%s\n' 'Android framework jar required to compile the hardware capability bridge; run the existing Android bootstrap or check-native-provider.sh once.' >&2; exit 1; fi
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/native-streams.XXXXXX")"
trap 'rm -rf -- "$classes_dir"' EXIT
javac -J-Xmx256m --release 17 -classpath "$extractor_jar:$framework_jar" -d "$classes_dir" \
    tests/fixtures/native-queue/android/os/Bundle.java \
    app/src/main/java/dev/ytmusictv/nativeapp/model/NativeTrack.java \
    app/src/main/java/dev/ytmusictv/nativeapp/model/NativeStreamResolver.java \
    app/src/main/java/dev/ytmusictv/nativeapp/model/PlaybackSource.java \
    app/src/main/java/dev/ytmusictv/nativeapp/provider/ProviderHttp.java \
    app/src/main/java/dev/ytmusictv/nativeapp/provider/VideoCodecPolicy.java \
    app/src/main/java/dev/ytmusictv/nativeapp/provider/HardwareVideoSupport.java \
    app/src/main/java/dev/ytmusictv/nativeapp/provider/WebmVp9Header.java \
    app/src/main/java/dev/ytmusictv/nativeapp/provider/LegacyVp9Probe.java \
    app/src/main/java/dev/ytmusictv/nativeapp/provider/YouTubeStreamResolver.java \
    tests/nativeapp/provider/NativeStreamSelectionTest.java \
    tests/nativeapp/provider/NativeWebmVp9HeaderTest.java
java -Xmx64m -classpath "$classes_dir:$extractor_jar" dev.ytmusictv.nativeapp.provider.NativeStreamSelectionTest
java -Xmx64m -classpath "$classes_dir:$extractor_jar" dev.ytmusictv.nativeapp.provider.NativeWebmVp9HeaderTest
