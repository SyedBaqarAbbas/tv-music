#!/usr/bin/env bash
# Pure Java artwork rendition policy checks; no SDK, browser, account or network.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if [[ -z "${JAVA_HOME:-}" && -x "$project_dir/.tools/jdk-17/bin/javac" ]]; then export JAVA_HOME="$project_dir/.tools/jdk-17"; fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/native-artwork.XXXXXX")"
trap 'rm -rf -- "$classes_dir"' EXIT
javac -J-Xmx128m --release 17 -d "$classes_dir" \
    app/src/main/java/dev/ytmusictv/nativeapp/model/NativeArtworkUrl.java \
    tests/nativeapp/model/NativeArtworkUrlTest.java
java -Xmx32m -classpath "$classes_dir" dev.ytmusictv.nativeapp.model.NativeArtworkUrlTest
