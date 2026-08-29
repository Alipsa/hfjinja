#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "$SCRIPT_DIR"

PROJECT=$(basename "$SCRIPT_DIR")
VERSION=$(sed -nE "s/^[[:space:]]*version[[:space:]]*=[[:space:]]*'([^']+)'[[:space:]]*$/\1/p" build.gradle | head -n 1)
if [[ -z "$VERSION" ]]; then
  echo "Could not determine $PROJECT version from $SCRIPT_DIR/build.gradle" >&2
  exit 1
fi
if [[ "$VERSION" == *-SNAPSHOT ]]; then
  echo "$PROJECT snapshot cannot be published" >&2
  exit 1
fi

set +u
set +o pipefail
source ~/.sdkman/bin/sdkman-init.sh
source jdk21
set -u
set -o pipefail

echo "Building and validating release bundle for $PROJECT $VERSION before publishing to Maven Central..."
./gradlew clean build release
echo "$PROJECT published"
echo "see https://central.sonatype.com/publishing/deployments for more info"
# browse https://oss.sonatype.org &
