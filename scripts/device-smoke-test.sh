#!/usr/bin/env bash
# =============================================================================
#  On-device test of the RELEASE bundle.
#
#    ./scripts/device-smoke-test.sh [--fresh] [path/to/SafeStitch-release.aab]
#
#  1. bundletool generates device APKs from the AAB (the same way Google Play does)
#     and installs them on the connected phone or emulator.
#  2. Turns on airplane mode.
#  3. Drives one complete inspection: scan screen -> new checkpoint -> checklist
#     (one failed item with note and camera photo) -> PDF report -> "Save a copy".
#  4. Pulls the PDF to the Mac, checks it, and turns airplane mode off again.
#
#  --fresh  uninstall SafeStitch first (DELETES its data on the device).
#  The device APKs are signed with this Mac's debug key just for this local install;
#  the AAB itself is untouched and Google Play re-signs it for real users.
#  Needs: one device in `adb devices` (or ANDROID_SERIAL set) and python3.
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRESH=0
AAB=""
for arg in "$@"; do
  case "$arg" in
    --fresh) FRESH=1 ;;
    -h | --help) sed -n '2,21p' "$0"; exit 0 ;;
    *) AAB="$arg" ;;
  esac
done
AAB="${AAB:-$ROOT/deliverables/SafeStitch-release.aab}"
WORK="$ROOT/build/device-smoke-test"
BUNDLETOOL_JAR="${BUNDLETOOL_JAR:-$HOME/Library/Caches/SafeStitch/bundletool-all-1.18.3.jar}"

fail() { printf '\nFAILED: %s\n' "$*" >&2; exit 1; }
ok() { printf '    OK  %s\n' "$*"; }

[[ -f "$AAB" ]] || fail "AAB not found: $AAB (run ./scripts/build-deliverables.sh first)."
[[ -f "$BUNDLETOOL_JAR" ]] || fail "bundletool not found at $BUNDLETOOL_JAR (run ./scripts/build-deliverables.sh once)."
if [[ -z "${JAVA_HOME:-}" ]]; then JAVA_HOME="$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)"; fi
JAVA="$JAVA_HOME/bin/java"
[[ -x "$JAVA" ]] || fail "JDK 17+ not found."
SDK="${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" 2>/dev/null | head -1)}"
SDK="${SDK:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
[[ -x "$ADB" ]] || fail "adb not found in $SDK/platform-tools."
command -v python3 >/dev/null || fail "python3 is required (install Xcode command line tools)."
DEBUG_KS="$HOME/.android/debug.keystore"
[[ -f "$DEBUG_KS" ]] || fail "$DEBUG_KS not found. Build any app once in Android Studio to create it."
BUNDLETOOL=("$JAVA" -jar "$BUNDLETOOL_JAR")

SERIAL="${ANDROID_SERIAL:-}"
if [[ -z "$SERIAL" ]]; then
  DEVICES=()
  while IFS= read -r line; do DEVICES+=("$line"); done < <("$ADB" devices | awk 'NR > 1 && $2 == "device" {print $1}')
  (( ${#DEVICES[@]} > 0 )) || fail "No phone or emulator found. Connect a phone with USB debugging on, or start an emulator."
  (( ${#DEVICES[@]} == 1 )) || fail "Several devices are connected (${DEVICES[*]}). Choose one: ANDROID_SERIAL=<id> $0"
  SERIAL="${DEVICES[0]}"
fi
DEVICE_DESC="$("$ADB" -s "$SERIAL" shell getprop ro.product.model | tr -d '\r') (Android $("$ADB" -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r'), API $("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r'))"
ok "Device $SERIAL: $DEVICE_DESC"

PACKAGE="$("${BUNDLETOOL[@]}" dump manifest --bundle="$AAB" --xpath=/manifest/@package | tr -d '[:space:]')"
[[ -n "$PACKAGE" ]] || fail "Could not read the package name from $AAB."

rm -rf "$WORK"
mkdir -p "$WORK"
"${BUNDLETOOL[@]}" build-apks --bundle="$AAB" --output="$WORK/SafeStitch.apks" \
  --connected-device --device-id="$SERIAL" --adb="$ADB" \
  --ks="$DEBUG_KS" --ks-pass=pass:android --ks-key-alias=androiddebugkey --key-pass=pass:android \
  || fail "bundletool could not generate APKs from the AAB."
ok "bundletool build-apks --connected-device (APK set generated from the release AAB)"

if (( FRESH )); then "$ADB" -s "$SERIAL" uninstall "$PACKAGE" >/dev/null 2>&1 || true; fi
# The workflow starts by creating the first admin, so the app must have no accounts yet.
if (( ! FRESH )) && "$ADB" -s "$SERIAL" shell pm path "$PACKAGE" >/dev/null 2>&1; then
  fail "$PACKAGE is already installed on this device. Rerun with --fresh (deletes its data on the device) or use a test emulator."
fi
"${BUNDLETOOL[@]}" install-apks --apks="$WORK/SafeStitch.apks" --device-id="$SERIAL" --adb="$ADB" \
  || fail "bundletool install-apks failed. If SafeStitch from Google Play or another key is installed, remove it or rerun with --fresh."
PKG_INFO="$("$ADB" -s "$SERIAL" shell dumpsys package "$PACKAGE")"
INSTALLED_VERSION="$(grep -m1 -o 'versionName=[^ ]*' <<<"$PKG_INFO" | cut -d= -f2)"
if grep -q "DEBUGGABLE" <<<"$(grep -m1 'pkgFlags=' <<<"$PKG_INFO")"; then fail "The installed app is debuggable; this is not the release build."; fi
SPLITS="$("$ADB" -s "$SERIAL" shell pm path "$PACKAGE" | wc -l | tr -d ' ')"
ok "bundletool install-apks: $PACKAGE $INSTALLED_VERSION installed ($SPLITS APK split(s), not debuggable)"

"$ADB" -s "$SERIAL" shell pm grant "$PACKAGE" android.permission.CAMERA
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  "$ADB" -s "$SERIAL" shell settings put global "$setting" 0
done

restore_network() {
  "$ADB" -s "$SERIAL" shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
}
trap restore_network EXIT
"$ADB" -s "$SERIAL" shell cmd connectivity airplane-mode enable
sleep 4
[[ "$("$ADB" -s "$SERIAL" shell settings get global airplane_mode_on | tr -d '\r')" == "1" ]] || fail "Could not turn on airplane mode."
if "$ADB" -s "$SERIAL" shell ping -c 1 -W 3 8.8.8.8 >/dev/null 2>&1; then
  fail "The device can still reach the internet with airplane mode on (Wi-Fi re-enabled?). Turn Wi-Fi off and retry."
fi
ok "Airplane mode on; internet unreachable (ping 8.8.8.8 fails)"

echo "    Running the full workflow on the device (admin + inspector, inspection, PDF, corrective action) ..."
python3 -I "$ROOT/scripts/smoke/ui_flow.py" --adb "$ADB" --serial "$SERIAL" --package "$PACKAGE" --out "$WORK" workflow \
  | tee "$WORK/result.log" | grep -v '^{' || true
RESULT_JSON="$(tail -1 "$WORK/result.log")"
[[ "$RESULT_JSON" == *'"result": "PASS"'* ]] || fail "On-device workflow test failed. Screenshot: $WORK/failure-workflow.png"

echo
echo "On-device smoke test PASSED on $DEVICE_DESC."
echo "  Evidence: $WORK (report PDF, screenshots, result.log)"
