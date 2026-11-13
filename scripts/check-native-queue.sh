#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if [[ -z "${JAVA_HOME:-}" && -x "$project_dir/.tools/jdk-17/bin/javac" ]]; then export JAVA_HOME="$project_dir/.tools/jdk-17"; fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
framework_jar="$project_dir/.tools/java-check/android-all-11-robolectric-6757853.jar"
if [[ ! -f "$framework_jar" ]]; then printf '%s\n' 'Run ./scripts/check-java.sh once to obtain the verified JSON fixture jar.' >&2; exit 1; fi
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/native-queue.XXXXXX")"
trap 'rm -rf -- "$classes_dir"' EXIT
javac -J-Xmx256m --release 17 -classpath "$framework_jar" -d "$classes_dir" \
    tests/fixtures/native-queue/android/content/Context.java \
    tests/fixtures/native-queue/android/content/SharedPreferences.java \
    tests/fixtures/native-queue/android/os/Bundle.java \
    app/src/main/java/dev/ytmusictv/nativeapp/model/NativeTrack.java \
    app/src/main/java/dev/ytmusictv/nativeapp/playback/QueueOrder.java \
    app/src/main/java/dev/ytmusictv/nativeapp/playback/CollectionBatch.java \
    app/src/main/java/dev/ytmusictv/nativeapp/playback/ArtworkRepair.java \
    app/src/main/java/dev/ytmusictv/nativeapp/playback/QueueCheckpoint.java \
    tests/java/dev/ytmusictv/nativeapp/playback/NativeQueueTest.java
java -Xmx64m -classpath "$classes_dir:$framework_jar" dev.ytmusictv.nativeapp.playback.NativeQueueTest
