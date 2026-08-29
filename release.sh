#!/usr/bin/env bash
set -e
PROJECT=$(basename "$PWD")
if grep "version '" build.gradle | grep -q '-SNAPSHOT'; then
  echo "$PROJECT snapshot cannot be published"
  exit 1
fi
source ~/.sdkman/bin/sdkman-init.sh
source jdk21
./gradlew clean build
echo "Build was successful, publishing to maven central..."
./gradlew release
echo "$PROJECT published"
echo "see https://central.sonatype.com/publishing/deployments for more info"
# browse https://oss.sonatype.org &