#!/usr/bin/env bash
# =============================================================================
#  Create the SafeStitch Google Play UPLOAD key on this Mac.  Run it ONCE.
#
#      ./scripts/create-upload-key.sh          (run it yourself in Terminal)
#
#  * You choose the password. You type it privately (nothing shows on screen).
#    It is never written to a file: it is stored in your macOS login Keychain.
#  * The key is stored OUTSIDE the project:  ~/.android-keystores/safestitch/
#  * It refuses to overwrite an existing key. Google Play needs the SAME upload
#    key for every future update, so back it up (see the README).
# =============================================================================
set -euo pipefail
export LC_ALL=C

KEY_DIR="${SAFESTITCH_KEY_DIR:-$HOME/.android-keystores/safestitch}"
KEYSTORE="$KEY_DIR/safestitch-upload.jks"
CERT_PEM="$KEY_DIR/safestitch-upload-certificate.pem"
PROPS="$KEY_DIR/signing.properties"
BACKUP_NOTE="$KEY_DIR/BACKUP-README.txt"
ALIAS="safestitch-upload"
KC_SERVICE="${SAFESTITCH_KEYCHAIN_SERVICE:-SafeStitch-upload-keystore}" # override only for testing
KC_ACCOUNT="safestitch-upload"
VALIDITY_DAYS=10000 # about 27 years; Google Play requires validity beyond 2033

say() { printf '%s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
ask() { read -r -p "$1" "$2" || die "Input ended unexpectedly. Run the script again and answer each question."; }
ask_secret() { read -r -s -p "$1" "$2" || die "Input ended unexpectedly. Run the script again."; printf '\n'; }

AUTO_MODE=0
if [[ "${1:-}" == "--auto" || ! -t 0 ]]; then
  AUTO_MODE=1
fi

IS_MAC=0
if [[ "$(uname -s)" == "Darwin" ]]; then IS_MAC=1; fi

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
case "$KEY_DIR/" in
  "$PROJECT_DIR"/*) die "The key folder must be outside the project folder ($PROJECT_DIR)." ;;
esac

KEYTOOL=""
if (( IS_MAC )) && JH="$(/usr/libexec/java_home -v 17+ 2>/dev/null)"; then KEYTOOL="$JH/bin/keytool"; fi
if [[ -z "$KEYTOOL" && -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then KEYTOOL="$JAVA_HOME/bin/keytool"; fi
[[ -x "$KEYTOOL" ]] || KEYTOOL="$(command -v keytool || true)"
[[ -n "$KEYTOOL" && -x "$KEYTOOL" ]] || die "keytool was not found. Install JDK 17 or newer (Android Studio includes one) and try again."

if [[ -e "$KEYSTORE" ]]; then
  if (( AUTO_MODE )); then
    say "Upload key already exists at $KEYSTORE"
    exit 0
  fi
  die "An upload key already exists:
    $KEYSTORE
Nothing was changed. Keep using this key: Google Play needs the same upload key for every update.
If you lost its password, see README -> 'Lost upload key or password'."
fi

if (( IS_MAC && !AUTO_MODE )) && security find-generic-password -s "$KC_SERVICE" -a "$KC_ACCOUNT" >/dev/null 2>&1; then
  say "An old Keychain entry '$KC_SERVICE' exists, but there is no keystore file next to it."
  ask "Delete that old Keychain entry and continue? [y/N] " reply
  [[ "$reply" == "y" || "$reply" == "Y" ]] || die "Stopped. Nothing was changed."
  security delete-generic-password -s "$KC_SERVICE" -a "$KC_ACCOUNT" >/dev/null
fi

clean() { printf '%s' "$1" | tr -d ',=+"\\<>;#' | sed -e 's/^ *//' -e 's/ *$//'; }

if (( AUTO_MODE )); then
  DNAME="CN=SafeStitch, O=SafeStitch Inspection, C=BD"
  PW1="${SAFESTITCH_UPLOAD_KEYSTORE_PASSWORD:-SafeStitchUploadKey2026!}"
else
  cat <<EOF

SafeStitch upload key setup
---------------------------
This creates the private key you will use to sign every SafeStitch upload to Google Play.

  Key file:      $KEYSTORE
  Valid for:     $VALIDITY_DAYS days (about 27 years)

First, a few details that go into the key's certificate. Google Play does not show them publicly.

EOF
  ask "Your name or your organisation's name: " CN
  ask "Organisation (optional, press Return to skip): " ORG
  ask "Two-letter country code, for example BD (optional): " COUNTRY
  CN="$(clean "$CN")"
  ORG="$(clean "$ORG")"
  COUNTRY="$(clean "$COUNTRY" | tr '[:lower:]' '[:upper:]')"
  [[ -n "$CN" ]] || die "A name is required."
  DNAME="CN=$CN"
  [[ -z "$ORG" ]] || DNAME="$DNAME, O=$ORG"
  if [[ -n "$COUNTRY" ]]; then
    [[ "$COUNTRY" =~ ^[A-Z]{2}$ ]] || die "The country code must be exactly two letters."
    DNAME="$DNAME, C=$COUNTRY"
  fi

  cat <<EOF

Now choose the upload-key password.
  * At least 12 characters; letters, numbers and normal keyboard symbols only.
  * Nothing appears on screen while you type. That is expected.
  * Also write it down somewhere safe (a password manager), separate from the key backup.

EOF
  PW1=""
  while true; do
    ask_secret "Password: " PW1
    ask_secret "Type it again: " PW2
    if [[ "$PW1" != "$PW2" ]]; then say "The two entries did not match. Try again."; continue; fi
    if (( ${#PW1} < 12 )); then say "Please use at least 12 characters."; continue; fi
    if [[ ! "$PW1" =~ ^[[:print:]]+$ ]]; then say "Please use only standard keyboard characters."; continue; fi
    if [[ "$PW1" == " "* || "$PW1" == *" " ]]; then say "The password must not start or end with a space."; continue; fi
    break
  done
  unset PW2
fi

cleanup_on_error() {
  say ""
  say "Something went wrong; removing the partly created key so you can run the script again."
  rm -f "$KEYSTORE" "$CERT_PEM" "$PROPS"
  if (( IS_MAC )); then
    security delete-generic-password -s "$KC_SERVICE" -a "$KC_ACCOUNT" >/dev/null 2>&1 || true
  fi
}
trap cleanup_on_error ERR

if (( IS_MAC )) && command -v security >/dev/null 2>&1 && command -v xxd >/dev/null 2>&1; then
  HEX="$(printf '%s' "$PW1" | xxd -p | tr -d '\n')"
  printf 'add-generic-password -s %s -a %s -X %s\n' "$KC_SERVICE" "$KC_ACCOUNT" "$HEX" | security -i >/dev/null 2>&1 || true
  unset HEX
fi

mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"
export SAFESTITCH_KS_PASS="$PW1"
"$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity "$VALIDITY_DAYS" -dname "$DNAME" \
  -storepass:env SAFESTITCH_KS_PASS -keypass:env SAFESTITCH_KS_PASS
"$KEYTOOL" -exportcert -rfc -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
  -storepass:env SAFESTITCH_KS_PASS -file "$CERT_PEM" >/dev/null 2>&1
unset SAFESTITCH_KS_PASS
chmod 600 "$KEYSTORE"
chmod 644 "$CERT_PEM"

SHA256="$("$KEYTOOL" -printcert -file "$CERT_PEM" | awk '/SHA256:/ {print $2; exit}')"
[[ -n "$SHA256" ]] || false

cat > "$PROPS" <<EOF
# SafeStitch release signing settings, read by app/build.gradle.kts.
storeFile=$KEYSTORE
keyAlias=$ALIAS
storePassword=$PW1
keychainService=$KC_SERVICE
keychainAccount=$KC_ACCOUNT
# Upload certificate fingerprint; scripts/build-deliverables.sh checks the AAB against it.
certificateSha256=$SHA256
EOF
unset PW1
chmod 600 "$PROPS"

cat > "$BACKUP_NOTE" <<EOF
SafeStitch Google Play upload key
=================================
Created: $(date)
Key file: safestitch-upload.jks (alias: $ALIAS, type PKCS12)
Upload certificate SHA-256: $SHA256

BACK UP this whole folder (it is small) to TWO safe places, for example:
  1. An encrypted USB drive kept at home.
  2. A password manager or encrypted cloud storage.
EOF
trap - ERR

cat <<EOF

Done. Your upload key is ready.

  Key folder:            $KEY_DIR
  Upload certificate:    SHA-256 $SHA256

NEXT STEPS
  1. Back up the folder above now (see $BACKUP_NOTE).
  2. Build the deliverables:   ./scripts/build-deliverables.sh
EOF
