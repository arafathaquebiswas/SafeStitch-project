#!/usr/bin/env bash
# =============================================================================
#  Prepare a new version before uploading another AAB to Google Play.
#
#    ./scripts/bump-version.sh            versionCode + 1, versionName unchanged
#    ./scripts/bump-version.sh 1.0.1      versionCode + 1, versionName = 1.0.1
#
#  Google Play rejects an AAB whose versionCode was already uploaded, so run this
#  before every upload, then ./scripts/build-deliverables.sh.
# =============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FILE="$ROOT/version.properties"

current_code="$(sed -n 's/^versionCode=//p' "$FILE" | tr -d '[:space:]')"
current_name="$(sed -n 's/^versionName=//p' "$FILE" | tr -d '[:space:]')"
[[ "$current_code" =~ ^[0-9]+$ ]] || { echo "versionCode in $FILE is not a number." >&2; exit 1; }

new_code=$((current_code + 1))
new_name="${1:-$current_name}"
[[ "$new_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.+][0-9A-Za-z.]+)?$ ]] || { echo "versionName must look like 1.0.1" >&2; exit 1; }

sed -i '' -e "s/^versionCode=.*/versionCode=$new_code/" -e "s/^versionName=.*/versionName=$new_name/" "$FILE"
echo "Version: $current_name ($current_code)  ->  $new_name ($new_code)"
echo "Next: ./scripts/build-deliverables.sh"
