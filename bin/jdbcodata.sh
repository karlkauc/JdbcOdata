#!/usr/bin/env bash
# Starts JdbcOdata from the distribution directory (Linux/macOS).
# Configuration: config/application.yml next to this script's parent directory, or environment variables.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$HERE"
mkdir -p data logs
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! "$JAVA_BIN" -version >/dev/null 2>&1; then
  echo "Java 21 or newer is required (set JAVA_HOME or put java on the PATH)" >&2
  exit 1
fi
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx1g}"
# additional JDBC drivers in lib/ are added to the class path
exec "$JAVA_BIN" $JAVA_OPTS -Dloader.path=lib -Dspring.config.additional-location=optional:file:./config/ \
  -jar jdbcodata.jar "$@"
