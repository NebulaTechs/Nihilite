#!/usr/bin/env bash
# build.sh — build the nihilite fat jar via Gradle.
#
# Output: build/libs/nihilite.jar
#
# The jar is dual-purpose:
#   standalone  : java -jar nihilite.jar                       (Main-Class)
#   attached    : java -javaagent:nihilite.jar -jar <server>   (Premain-Class)
#
# First-time setup:
#   1. Download a Gradle distribution (8.10+ recommended) and place it on
#      $PATH, OR run `./gradlew wrapper` once to generate the wrapper.
#   2. Run `./build.sh` (this script).
#
# Subsequent runs need only `./build.sh`.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# Pick the Java 25 toolchain for compilation. Minecraft 26.1 requires Java 25.
JDK25_HOME="${JDK25_HOME:-/usr/lib/jvm/java-25-openjdk}"
if [ ! -x "$JDK25_HOME/bin/javac" ]; then
    echo "ERROR: $JDK25_HOME/bin/javac not found. Set JDK25_HOME or install OpenJDK 25."
    exit 1
fi
export JAVA_HOME="$JDK25_HOME"
export PATH="$JDK25_HOME/bin:$PATH"

GRADLE_BIN="${GRADLE_BIN:-gradle}"
if ! command -v "$GRADLE_BIN" >/dev/null 2>&1; then
    # Try the wrapper
    if [ -x "$PROJECT_DIR/gradlew" ]; then
        GRADLE_BIN="$PROJECT_DIR/gradlew"
    else
        echo "ERROR: 'gradle' not on PATH and './gradlew' not present."
        echo "Install Gradle 8.10+ or run with GRADLE_BIN=/path/to/gradle"
        exit 1
    fi
fi

echo "==> using Java: $($JDK25_HOME/bin/java -version 2>&1 | head -1)"
echo "==> using Gradle: $($GRADLE_BIN --version 2>&1 | grep -i '^Gradle ' | head -1)"

case "${1:-build}" in
    clean)
        echo "==> ./gradlew clean"
        "$GRADLE_BIN" clean
        ;;
    build|"")
        echo "==> ./gradlew jar sourcesJar"
        "$GRADLE_BIN" --no-daemon jar sourcesJar
        echo ""
        # Pick the freshly-produced artifact by glob; the build.gradle
        # jar task sets archiveFileName directly to nihilite.jar.
        JAR_FILE="$(ls -1t "$PROJECT_DIR"/build/libs/*.jar 2>/dev/null \
            | grep -v -- '-sources\.jar$' | head -n1)"
        if [ -z "${JAR_FILE:-}" ]; then
            echo "ERROR: no jar produced under build/libs/" >&2
            exit 1
        fi
        echo "==> artifact: $JAR_FILE"
        ls -la "$JAR_FILE"
        echo ""
        echo "==> verify Main-Class:"
        unzip -p "$JAR_FILE" META-INF/MANIFEST.MF | grep -E 'Main-Class|Implementation-'
        echo ""
        echo "==> verify Javaagent entry points:"
        unzip -p "$JAR_FILE" META-INF/MANIFEST.MF \
            | grep -E 'Premain-Class|Agent-Class|Can-Redefine-Classes|Boot-Class-Path' \
            || echo "(no javaagent attributes — see manifest dump above)"
        echo ""
        echo "==> class file version:"
        unzip -p "$JAR_FILE" 'nihilite/server/ServerMain.class' \
            | xxd | head -1
        echo "(class file major version byte should be 0x41 = Java 25 for sourceCompatibility 25)"
        ;;
    check)
        echo "==> ./gradlew check"
        "$GRADLE_BIN" --no-daemon check
        ;;
    *)
        echo "Usage: $0 [build|clean|check]"
        exit 2
        ;;
esac