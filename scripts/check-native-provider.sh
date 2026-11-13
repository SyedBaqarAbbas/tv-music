#!/usr/bin/env bash
# Offline parser fixtures; no TV, account, Android emulator, or Gradle build.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if [[ -z "${JAVA_HOME:-}" && -x "$project_dir/.tools/jdk-17/bin/javac" ]]; then
    export JAVA_HOME="$project_dir/.tools/jdk-17"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
for tool in java javac python3 curl sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || { printf 'Required command missing: %s\nRun ./scripts/bootstrap-android.sh --tools-only for JDK 17.\n' "$tool" >&2; exit 1; }
done
framework="$project_dir/.tools/java-check/android-all-11-robolectric-6757853.jar"
framework_sha256='62f577ca49f62f0da5d6a2a839d80ef377054b9ccd14811035593bf351295b31'
if [[ ! -f "$framework" ]] || ! printf '%s  %s\n' "$framework_sha256" "$framework" | sha256sum --status -c -; then
    mkdir -p "$(dirname "$framework")"
    # Apache2 source-built Android framework, shared with check-java.sh. Test-only.
    curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
        'https://repo.maven.apache.org/maven2/org/robolectric/android-all/11-robolectric-6757853/android-all-11-robolectric-6757853.jar' \
        --output "$framework.part"
    printf '%s  %s\n' "$framework_sha256" "$framework.part" | sha256sum -c -
    mv -- "$framework.part" "$framework"
fi
classes="$(mktemp -d "${TMPDIR:-/tmp}/native-provider-check.XXXXXX")"
cleanup() { python3 -c 'import shutil,sys;shutil.rmtree(sys.argv[1])' "$classes"; }
trap cleanup EXIT
javac -J-Xmx256m --release 17 -classpath "$framework" -d "$classes" \
    app/src/main/java/dev/ytmusictv/nativeapp/model/NativeTrack.java \
    app/src/main/java/dev/ytmusictv/nativeapp/model/NativeArtworkUrl.java \
    app/src/main/java/dev/ytmusictv/nativeapp/provider/ProviderHttp.java \
    app/src/main/java/dev/ytmusictv/nativeapp/provider/NativeMusicProvider.java \
    tests/nativeapp/provider/NativeProviderParserTest.java \
    tests/nativeapp/model/NativeArtworkUrlTest.java
java -Xmx128m -classpath "$classes:$framework" dev.ytmusictv.nativeapp.provider.NativeProviderParserTest \
    "$project_dir/tests/nativeapp/provider/fixtures"
java -Xmx64m -classpath "$classes" dev.ytmusictv.nativeapp.model.NativeArtworkUrlTest
