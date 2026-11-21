#!/usr/bin/env bash

set -euo pipefail

variant="${1:-Debug}"
variant="$(tr '[:lower:]' '[:upper:]' <<<"${variant:0:1}")${variant:1}"

./gradlew --no-daemon --max-workers=1 clean "assemble${variant}"
