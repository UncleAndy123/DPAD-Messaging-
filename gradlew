#!/usr/bin/env sh
# Gradle start up script for UN*X
# Minimal portable wrapper that launches the gradle-wrapper.jar with Java.
basedir=$(dirname "$0")
if [ "${basedir}" = "." ]; then
  basedir="$(pwd)"
fi

CLASSPATH="$basedir/gradle/wrapper/gradle-wrapper.jar"

exec java -jar "$CLASSPATH" "$@"
