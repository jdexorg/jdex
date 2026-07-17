#!/bin/bash
# Launcher for jdex (macOS). Edit jvmopt.txt to change JVM options (e.g. the max heap).

# OPTIONAL: point this at a specific JDK 21+ if you don't want the one on PATH
#JAVA_HOME="..."

cd "$(dirname "$0")"
BASEDIR="$(pwd -P)"
cd - >/dev/null

JAR="$BASEDIR/jdex.jar"
if [ ! -f "$JAR" ]; then
  JAR="$BASEDIR/app/build/libs/jdex.jar"
fi
if [ ! -f "$JAR" ]; then
  echo "jdex.jar not found. Build it first: ./gradlew shadowJar"
  exit 1
fi

JVMOPT=
if [ -f "$BASEDIR/jvmopt.txt" ]; then
  JVMOPT=$(grep -vE '^[[:space:]]*(#|$)' "$BASEDIR/jvmopt.txt" | tr '\n' ' ')
fi

if [ -z "$JAVA_HOME" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)"
fi
if [ -n "$JAVA_HOME" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="$(command -v java)"
fi
if [ -z "$JAVA" ] || [ ! -x "$JAVA" ]; then
  echo "Java not found. jdex requires a JDK 21 or newer (set JAVA_HOME or add java to PATH)."
  exit 1
fi

VER=$("$JAVA" -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/')
if [ -n "$VER" ] && [ "$VER" -lt 21 ] 2>/dev/null; then
  echo "Java $VER detected: jdex requires a JDK 21 or newer."
  exit 1
fi

exec "$JAVA" $JVMOPT -jar "$JAR" "$@"
