#!/bin/sh
# Gradle wrapper script
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
GRADLE_VERSION="8.6"
GRADLE_DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_ZIP="${GRADLE_HOME}/wrapper/dists/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_DIR="${GRADLE_HOME}/wrapper/dists/gradle-${GRADLE_VERSION}-bin"
GRADLE_BIN="${GRADLE_DIR}/gradle-${GRADLE_VERSION}/bin/gradle"

if [ ! -f "$GRADLE_BIN" ]; then
    mkdir -p "${GRADLE_DIR}"
    wget -q "${GRADLE_DIST_URL}" -O "${GRADLE_ZIP}"
    unzip -q "${GRADLE_ZIP}" -d "${GRADLE_DIR}"
fi

exec "${GRADLE_BIN}" "$@"
