#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
bin_dir=$(mktemp -d)
trap 'rm -rf "$bin_dir"' EXIT HUP INT TERM
java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}
source_dir=android/app/src/main/java/com/novaxis/localreader
kotlin_jar=$(python3 - <<'PYTHON'
from pathlib import Path
import os
explicit = os.environ.get('KOTLIN_STDLIB_JAR')
if explicit: print(explicit)
else:
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
    jars = list((cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.1.20').glob('*/*.jar'))
    if not jars: raise SystemExit('Build Android once to cache Kotlin stdlib, or set KOTLIN_STDLIB_JAR.')
    print(jars[0])
PYTHON
)
"${java_bin}javac" -cp "$kotlin_jar" -d "$bin_dir" "$source_dir/NarrationPlan.java" "$source_dir/SpeechRecovery.java" "$source_dir/PlaybackWatchdog.java" "$source_dir/PiperSynthesisCallback.java" checks/native/ReaderReliabilityCheck.java
"${java_bin}java" -Xmx32m -cp "$bin_dir:$kotlin_jar" com.novaxis.localreader.ReaderReliabilityCheck
