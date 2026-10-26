#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
bin_dir=$(mktemp -d)
trap 'rm -rf "$bin_dir"' EXIT HUP INT TERM
java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}
source_dir=android/app/src/main/java/com/novaxis/localreader
"${java_bin}javac" -d "$bin_dir" "$source_dir/NarrationPlan.java" "$source_dir/SpeechRecovery.java" "$source_dir/PlaybackWatchdog.java" checks/native/ReaderReliabilityCheck.java
"${java_bin}java" -Xmx32m -cp "$bin_dir" com.novaxis.localreader.ReaderReliabilityCheck
