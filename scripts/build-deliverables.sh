#!/usr/bin/env bash
# =============================================================================
#  Rebuild both SafeStitch deliverables after any change:
#
#    deliverables/SafeStitch-debug.apk     install on your phone for testing
#    deliverables/SafeStitch-release.aab   signed with your upload key, for Google Play
#
#  Usage:  ./scripts/build-deliverables.sh [--device-test] [--fresh]
#    --device-test  also generate APKs FROM the release AAB with bundletool, install them
#                   on the connected phone/emulator and run one airplane-mode inspection
#                   through PDF generation (scripts/device-smoke-test.sh).
#    --fresh        with --device-test: uninstall SafeStitch first (deletes its data!).
#
#  Runs: lint, unit tests, :app:assembleDebug, :app:bundleRelease, bundletool validate,
#  jarsigner + upload-certificate check. Stops with a clear message if anything fails.
#  Nothing is copied to deliverables/ unless its own build and checks succeeded.
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
OUT="${SAFESTITCH_DELIVERABLES_DIR:-$ROOT/deliverables}"
DEBUG_APK="$OUT/SafeStitch-debug.apk"
RELEASE_AAB="$OUT/SafeStitch-release.aab"
BUILD_INFO="$OUT/BUILD-INFO.txt"
SIGNING_PROPS="${SAFESTITCH_SIGNING_PROPERTIES:-$HOME/.android-keystores/safestitch/signing.properties}"
BUNDLETOOL_VERSION="1.18.3"
BUNDLETOOL_SHA256="a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
export BUNDLETOOL_JAR="${BUNDLETOOL_JAR:-$HOME/Library/Caches/SafeStitch/bundletool-all-$BUNDLETOOL_VERSION.jar}"

step() { printf '\n==> %s\n' "$*"; }
ok() { printf '    OK  %s\n' "$*"; }
fail() { printf '\nFAILED: %s\n' "$*" >&2; exit 1; }

DEVICE_TEST=0
SMOKE_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --device-test) DEVICE_TEST=1 ;;
    --fresh) SMOKE_ARGS+=(--fresh) ;;
    -h | --help) sed -n '2,19p' "$0"; exit 0 ;;
    *) fail "Unknown option: $arg (try --help)" ;;
  esac
done

# --- Tools ---------------------------------------------------------------------------
step "Checking tools"
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)"
fi
if [[ -z "${JAVA_HOME:-}" ]] && command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)"
  JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
fi
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] || fail "JDK 17 or newer not found. Install Android Studio or Temurin 17."
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"
KEYTOOL="$JAVA_HOME/bin/keytool"
JARSIGNER="$JAVA_HOME/bin/jarsigner"
ok "Java: $("$JAVA" -version 2>&1 | head -1)"

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" && -f local.properties ]]; then SDK="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"; fi
[[ -n "$SDK" ]] || SDK="$HOME/Library/Android/sdk"
[[ -d "$SDK/platforms" ]] || fail "Android SDK not found at $SDK. Install Android Studio (it installs the SDK) or set ANDROID_HOME."
[[ -f local.properties ]] || printf 'sdk.dir=%s\n' "$SDK" > local.properties
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT2="$BUILD_TOOLS/aapt2"
[[ -x "$APKSIGNER" && -x "$AAPT2" ]] || fail "Android build-tools (apksigner, aapt2) not found in $SDK/build-tools."
export ANDROID_HOME="$SDK"
ok "Android SDK: $SDK (build-tools $(basename "$BUILD_TOOLS"))"

if [[ ! -f "$BUNDLETOOL_JAR" ]]; then
  mkdir -p "$(dirname "$BUNDLETOOL_JAR")"
  curl -fsSL -o "$BUNDLETOOL_JAR.part" \
    "https://github.com/google/bundletool/releases/download/$BUNDLETOOL_VERSION/bundletool-all-$BUNDLETOOL_VERSION.jar" \
    || fail "Could not download bundletool $BUNDLETOOL_VERSION (needs internet the first time)."
  mv "$BUNDLETOOL_JAR.part" "$BUNDLETOOL_JAR"
fi
if [[ "$(sha256_file "$BUNDLETOOL_JAR")" != "$BUNDLETOOL_SHA256" ]]; then
  rm -f "$BUNDLETOOL_JAR"
  fail "bundletool checksum mismatch; the download was removed. Run the script again."
fi
BUNDLETOOL=("$JAVA" -jar "$BUNDLETOOL_JAR")
ok "bundletool $BUNDLETOOL_VERSION (SHA-256 verified)"

VERSION_CODE="$(sed -n 's/^versionCode=//p' version.properties | tr -d '[:space:]')"
VERSION_NAME="$(sed -n 's/^versionName=//p' version.properties | tr -d '[:space:]')"
APP_ID="$(sed -n 's/^val safeStitchApplicationId = "\(.*\)"$/\1/p' app/build.gradle.kts)"
[[ -n "$VERSION_CODE" && -n "$VERSION_NAME" && -n "$APP_ID" ]] || fail "Could not read versionCode/versionName (version.properties) or applicationId (app/build.gradle.kts)."
ok "App ID $APP_ID, version $VERSION_NAME (versionCode $VERSION_CODE)"

mkdir -p "$OUT"
rm -f "$DEBUG_APK" "$RELEASE_AAB" "$BUILD_INFO" # never leave stale files behind

# --- 1. Debug APK ---------------------------------------------------------------------
step "Lint, unit tests and debug APK (:app:lintDebug :app:testDebugUnitTest :app:assembleDebug)"
bash ./gradlew --console=plain :app:lintDebug :app:testDebugUnitTest :app:assembleDebug \
  || fail "Lint, unit tests or the debug build failed (see the Gradle output above). Nothing was copied."
SRC_APK="app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$SRC_APK" ]] || fail "Gradle did not produce $SRC_APK."
"$APKSIGNER" verify "$SRC_APK" >/dev/null || fail "The debug APK signature does not verify."
# (Outputs are captured first: with pipefail, `cmd | grep -q` can fail when grep exits early.)
APK_BADGING="$("$AAPT2" dump badging "$SRC_APK")" || fail "aapt2 could not read the debug APK."
grep -qF "package: name='$APP_ID.debug'" <<<"$APK_BADGING" || fail "The debug APK has an unexpected package name."
APK_PERMISSIONS="$("$AAPT2" dump permissions "$SRC_APK")" || fail "aapt2 could not read the debug APK permissions."
# Allowed: camera, plus network access used ONLY by the optional sync (off by default).
SENSITIVE_RE='permission\.(ACCESS_(FINE|COARSE|BACKGROUND)_LOCATION|RECORD_AUDIO|READ_CONTACTS|READ_(EXTERNAL_STORAGE|MEDIA_[A-Z_]+)|WRITE_EXTERNAL_STORAGE|READ_PHONE_STATE|READ_SMS|SEND_SMS|CALL_PHONE|BODY_SENSORS|MANAGE_EXTERNAL_STORAGE|QUERY_ALL_PACKAGES|SYSTEM_ALERT_WINDOW)'
if grep -Eq "$SENSITIVE_RE" <<<"$APK_PERMISSIONS"; then
  fail "The debug APK requests an unexpected sensitive permission: $(grep -Eo "$SENSITIVE_RE" <<<"$APK_PERMISSIONS" | head -1)"
fi
cp "$SRC_APK" "$DEBUG_APK"
ok "deliverables/SafeStitch-debug.apk ($(du -h "$DEBUG_APK" | cut -f1 | tr -d ' '), app ID $APP_ID.debug, no unexpected permissions)"

# --- 2. Upload key ----------------------------------------------------------------------
step "Checking the upload key"
if [[ ! -f "$SIGNING_PROPS" ]]; then
  bash "$ROOT/scripts/create-upload-key.sh" --auto
fi
prop() { sed -n "s/^$1=//p" "$SIGNING_PROPS" | head -1; }
STORE_FILE="$(prop storeFile)"
STORE_PASS="$(prop storePassword)"
EXPECTED_SHA256="$(prop certificateSha256 | tr -d '[:space:]')"
KC_SERVICE="$(prop keychainService)"; KC_SERVICE="${KC_SERVICE:-SafeStitch-upload-keystore}"
KC_ACCOUNT="$(prop keychainAccount)"; KC_ACCOUNT="${KC_ACCOUNT:-safestitch-upload}"
[[ -f "$STORE_FILE" ]] || fail "The upload keystore '$STORE_FILE' is missing. Restore it from your backup (README -> 'Back up the upload key')."
case "$STORE_FILE" in "$ROOT"/* | "$OUT"/*) fail "The upload keystore must live outside the project and deliverables folders." ;; esac
[[ "$EXPECTED_SHA256" =~ ^([0-9A-F]{2}:){31}[0-9A-F]{2}$ ]] || fail "certificateSha256 in $SIGNING_PROPS is missing or malformed."
CERT_PEM="$(dirname "$STORE_FILE")/safestitch-upload-certificate.pem"
if [[ -f "$CERT_PEM" ]]; then
  PEM_SHA256="$("$KEYTOOL" -printcert -file "$CERT_PEM" | awk '/SHA256:/ {print $2; exit}')"
  [[ "$PEM_SHA256" == "$EXPECTED_SHA256" ]] || fail "The upload certificate file does not match certificateSha256 in $SIGNING_PROPS."
fi
if [[ -z "${SAFESTITCH_UPLOAD_KEYSTORE_PASSWORD:-}" && -z "${STORE_PASS:-}" ]]; then
  security find-generic-password -s "$KC_SERVICE" -a "$KC_ACCOUNT" >/dev/null 2>&1 \
    || fail "The upload-key password is not in the Keychain (item '$KC_SERVICE', account '$KC_ACCOUNT').
  Re-add it with:  security add-generic-password -s $KC_SERVICE -a $KC_ACCOUNT -w"
fi
ok "Keystore $STORE_FILE, expected upload certificate SHA-256 $EXPECTED_SHA256"

# --- 3. Signed release AAB --------------------------------------------------------------
step "Signed release bundle (:app:bundleRelease)"
bash ./gradlew --console=plain :app:bundleRelease \
  || fail "The release bundle could not be built or signed (see the Gradle output above). Nothing was copied."
SRC_AAB="app/build/outputs/bundle/release/app-release.aab"
[[ -f "$SRC_AAB" ]] || fail "Gradle did not produce $SRC_AAB."

step "Verifying the release bundle"
"${BUNDLETOOL[@]}" validate --bundle="$SRC_AAB" >/dev/null || fail "bundletool validate rejected the bundle."
ok "bundletool validate"
JS_OUT="$("$JARSIGNER" -verify -verbose -certs "$SRC_AAB" 2>&1)" || fail "jarsigner could not verify the bundle."
grep -q "jar verified" <<<"$JS_OUT" || fail "The bundle is NOT signed: $(tail -2 <<<"$JS_OUT")"
if grep -q "unsigned entries" <<<"$JS_OUT"; then fail "The bundle contains unsigned entries."; fi
ok "jarsigner: jar verified"
CERT_INFO="$("$KEYTOOL" -printcert -jarfile "$SRC_AAB")"
ACTUAL_SHA256="$(awk '/SHA256:/ {print $2; exit}' <<<"$CERT_INFO")"
if grep -q "CN=Android Debug" <<<"$CERT_INFO"; then fail "The bundle is signed with the Android DEBUG key. Refusing to deliver it."; fi
if [[ -f "$HOME/.android/debug.keystore" ]]; then
  DEBUG_SHA256="$("$KEYTOOL" -list -v -keystore "$HOME/.android/debug.keystore" -storepass android -alias androiddebugkey 2>/dev/null | awk '/SHA256:/ {print $2; exit}')"
  if [[ -n "$DEBUG_SHA256" && "$ACTUAL_SHA256" == "$DEBUG_SHA256" ]]; then fail "The bundle is signed with this Mac's debug key. Refusing to deliver it."; fi
fi
[[ "$ACTUAL_SHA256" == "$EXPECTED_SHA256" ]] \
  || fail "The bundle is signed with certificate $ACTUAL_SHA256, not the upload certificate $EXPECTED_SHA256."
ok "Signed with the upload certificate (SHA-256 $ACTUAL_SHA256)"

manifest() { "${BUNDLETOOL[@]}" dump manifest --bundle="$SRC_AAB" --xpath="$1" 2>/dev/null | tr -d '[:space:]'; }
M_PACKAGE="$(manifest /manifest/@package)"
M_CODE="$(manifest /manifest/@android:versionCode)"
M_NAME="$(manifest /manifest/@android:versionName)"
M_MIN="$(manifest /manifest/uses-sdk/@android:minSdkVersion)"
M_TARGET="$(manifest /manifest/uses-sdk/@android:targetSdkVersion)"
M_DEBUGGABLE="$(manifest /manifest/application/@android:debuggable)"
[[ "$M_PACKAGE" == "$APP_ID" ]] || fail "Bundle package is '$M_PACKAGE', expected '$APP_ID'."
[[ "$M_CODE" == "$VERSION_CODE" && "$M_NAME" == "$VERSION_NAME" ]] || fail "Bundle version is $M_NAME ($M_CODE), expected $VERSION_NAME ($VERSION_CODE)."
[[ "$M_DEBUGGABLE" != "true" ]] || fail "The release bundle is marked debuggable."
(( M_TARGET >= 36 )) || fail "targetSdkVersion is $M_TARGET; Google Play requires 36 or higher."
AAB_MANIFEST="$("${BUNDLETOOL[@]}" dump manifest --bundle="$SRC_AAB")" || fail "bundletool could not read the bundle manifest."
if grep -Eq "$SENSITIVE_RE" <<<"$AAB_MANIFEST"; then
  fail "The release bundle requests an unexpected sensitive permission."
fi
if grep -q 'usesCleartextTraffic.*true' <<<"$AAB_MANIFEST"; then
  fail "The release bundle allows unencrypted (http) traffic."
fi
# The release network security config must forbid plain http everywhere (debug builds allow 10.0.2.2/localhost for testing).
UNIVERSAL_DIR="$(mktemp -d)"
DEBUG_KS="$ROOT/debug.keystore"
[[ -f "$DEBUG_KS" ]] || DEBUG_KS="$HOME/.android/debug.keystore"
"${BUNDLETOOL[@]}" build-apks --bundle="$SRC_AAB" --output="$UNIVERSAL_DIR/u.apks" --mode=universal \
  --ks="$DEBUG_KS" --ks-pass=pass:android --ks-key-alias=androiddebugkey --key-pass=pass:android >/dev/null \
  || fail "bundletool could not build a universal APK to inspect."
unzip -q -o "$UNIVERSAL_DIR/u.apks" universal.apk -d "$UNIVERSAL_DIR"
NSC_PATH="$("$AAPT2" dump resources "$UNIVERSAL_DIR/universal.apk" | grep -A2 'xml/network_security_config' | grep -Eo 'res/[^ ]+\.xml' | head -1)"
[[ -n "$NSC_PATH" ]] || fail "network_security_config not found in the release build."
NSC="$("$AAPT2" dump xmltree --file "$NSC_PATH" "$UNIVERSAL_DIR/universal.apk")"
if ! grep -q 'cleartextTrafficPermitted.*=false' <<<"$NSC" || grep -q 'domain-config' <<<"$NSC"; then
  fail "The release build's network security config allows plain http."
fi
rm -rf "$UNIVERSAL_DIR"
ok "Manifest: $M_PACKAGE, versionName $M_NAME, versionCode $M_CODE, minSdk $M_MIN, targetSdk $M_TARGET, not debuggable, https-only network config, no unexpected permissions"

cp "$SRC_AAB" "$RELEASE_AAB"
cmp -s "$SRC_AAB" "$RELEASE_AAB" || fail "Copying the bundle to $RELEASE_AAB failed."
ok "deliverables/SafeStitch-release.aab ($(du -h "$RELEASE_AAB" | cut -f1 | tr -d ' '))"

cat > "$BUILD_INFO" <<EOF
SafeStitch build $(date '+%Y-%m-%d %H:%M %Z')
Application ID:   $APP_ID   (debug APK: $APP_ID.debug)
Version:          $VERSION_NAME (versionCode $VERSION_CODE)
minSdk/targetSdk: $M_MIN / $M_TARGET

SafeStitch-debug.apk    SHA-256 $(sha256_file "$DEBUG_APK")
SafeStitch-release.aab  SHA-256 $(sha256_file "$RELEASE_AAB")
Release signed by upload certificate SHA-256:
  $ACTUAL_SHA256
Checks passed: lintDebug, testDebugUnitTest, assembleDebug, apksigner verify (APK),
bundleRelease (incl. lintVitalRelease + R8), bundletool validate, jarsigner verify,
upload-certificate match, manifest (package/version/targetSdk/not debuggable/permissions), release network config https-only.
EOF

# --- 4. Optional on-device test ---------------------------------------------------------
if (( DEVICE_TEST )); then
  step "On-device smoke test of the release AAB (airplane mode)"
  "$ROOT/scripts/device-smoke-test.sh" ${SMOKE_ARGS[@]+"${SMOKE_ARGS[@]}"} "$RELEASE_AAB" \
    || fail "The on-device smoke test failed (see above). The deliverables were built and verified, but check the app."
  printf 'Device smoke test: passed (%s)\n' "$(date '+%Y-%m-%d %H:%M')" >> "$BUILD_INFO"
fi

cat <<EOF

All done.
  Debug APK:    $DEBUG_APK
  Release AAB:  $RELEASE_AAB
  Details:      $BUILD_INFO
EOF
