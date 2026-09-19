#!/usr/bin/env sh
set -eu
VERSION=9.3.1
BASE="${HOME}/.gradle-bootstrap"
DIST="${BASE}/gradle-${VERSION}"
ZIP="${BASE}/gradle-${VERSION}-bin.zip"
if [ ! -x "${DIST}/bin/gradle" ]; then
  mkdir -p "${BASE}"
  if [ ! -f "${ZIP}" ]; then
    curl -fL "https://services.gradle.org/distributions/gradle-${VERSION}-bin.zip" -o "${ZIP}"
  fi
  unzip -q -o "${ZIP}" -d "${BASE}"
fi
exec "${DIST}/bin/gradle" "$@"
