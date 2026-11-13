#!/usr/bin/env bash
# Lightweight legacy/API30 check. Native AndroidX integration uses the Gradle check below.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if [[ -z "${JAVA_HOME:-}" && -x "$project_dir/.tools/jdk-17/bin/javac" ]]; then
    export JAVA_HOME="$project_dir/.tools/jdk-17"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
for tool in javac python3 curl sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || { printf 'Required command missing: %s\nRun ./scripts/bootstrap-android.sh --tools-only for JDK 17.\n' "$tool" >&2; exit 1; }
done

check_dir="$project_dir/.tools/java-check"
mkdir -p "$check_dir"
framework_jar="$check_dir/android-all-11-robolectric-6757853.jar"
framework_sha256='62f577ca49f62f0da5d6a2a839d80ef377054b9ccd14811035593bf351295b31'
if [[ ! -f "$framework_jar" ]] || ! printf '%s  %s\n' "$framework_sha256" "$framework_jar" | sha256sum --status -c -; then
    # Apache2.0 source-built Android API30 framework, only used for this check.
    # License: https://repo.maven.apache.org/maven2/org/robolectric/android-all/11-robolectric-6757853/android-all-11-robolectric-6757853.pom
    # SHA256: same Maven URL with .jar.sha256.
    curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
        'https://repo.maven.apache.org/maven2/org/robolectric/android-all/11-robolectric-6757853/android-all-11-robolectric-6757853.jar' \
        --output "$framework_jar.part"
    printf '%s  %s\n' "$framework_sha256" "$framework_jar.part" | sha256sum -c -
    mv -- "$framework_jar.part" "$framework_jar"
fi

python3 - "$project_dir" "$framework_jar" <<'PY'
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
sources = sorted(path for path in (root / 'app/src/main/java').rglob('*.java')
                 if 'nativeapp' not in path.parts)
if not sources:
    raise SystemExit('No app Java sources found.')

resources = set()
resource_dir = root / 'app/src/main/res'
for path in resource_dir.rglob('*'):
    if not path.is_file():
        continue
    kind = path.parent.name.split('-')[0]
    if kind == 'values' and path.suffix == '.xml':
        for item in ET.parse(path).getroot():
            if 'name' in item.attrib:
                resources.add((item.attrib.get('type', item.tag), item.attrib['name'].replace('.', '_')))
    elif kind != 'values':
        resources.add((kind, path.name.split('.')[0]))

references = set()
for source in sources:
    references.update(re.findall(r'(?<![\w.])R\.(\w+)\.(\w+)', source.read_text()))
missing = sorted(references - resources)
if missing:
    raise SystemExit('Undefined app resources: ' + ', '.join(f'R.{kind}.{name}' for kind, name in missing))

with tempfile.TemporaryDirectory(prefix='compile-', dir=root / '.tools/java-check') as temporary:
    directory = Path(temporary)
    generated = directory / 'dev/ytmusictv/R.java'
    generated.parent.mkdir(parents=True)
    lines = ['package dev.ytmusictv;', 'public final class R {']
    for kind in sorted({kind for kind, _ in references}):
        lines.append(f'  public static final class {kind} {{')
        for index, (_, name) in enumerate(sorted(item for item in references if item[0] == kind), 1):
            lines.append(f'    public static final int {name} = {index};')
        lines.append('  }')
    lines.append('}')
    generated.write_text('\n'.join(lines) + '\n')
    subprocess.run(['javac', '-J-Xmx512m', '--release', '17', '-Xlint:deprecation',
                    '-classpath', sys.argv[2], '-d', str(directory / 'classes'),
                    str(generated), *map(str, sources)], check=True)

print(f'Legacy Java/API30 check passed: {len(sources)} sources; {len(references)} resource references.')
print('Native Java integration: ./scripts/build.sh compileDebugJavaWithJavac')
print('APK/lint validation: ./scripts/build.sh assembleDebug lintDebug')
PY
