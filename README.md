# SafeStitch

SafeStitch is an offline Android app for garment-factory safety and quality inspections.
Inspectors scan the QR label on a checkpoint (a sewing line, fire point, electrical panel or
work area), work through its checklist, attach camera photos, and produce a signed-off PDF
report. Everything works in airplane mode.

It is a native Android app: Kotlin, Jetpack Compose with Material 3, Gradle Kotlin DSL.
It runs on Android 9 (API 28) and newer.

| | |
|---|---|
| Application ID | `com.safestitch.inspection` (debug build: `com.safestitch.inspection.debug`) |
| Version | `1.0.0` (versionCode `1`), set in [`version.properties`](version.properties) |
| minSdk / targetSdk | 28 (Android 9) / 36 (Android 16) |
| Test APK | `deliverables/SafeStitch-debug.apk` |
| Play upload | `deliverables/SafeStitch-release.aab` (signed with your upload key) |

---

## Contents

1. [What the app does](#what-the-app-does)
2. [Install the test APK on your phone](#install-the-test-apk-on-your-phone)
3. [One-time setup on your Mac](#one-time-setup-on-your-mac)
4. [Create your upload key](#create-your-upload-key)
5. [Back up the upload key](#back-up-the-upload-key)
6. [Rebuild both files](#rebuild-both-files)
7. [Version numbers](#version-numbers)
8. [Application ID](#application-id)
9. [Upload to Google Play internal testing](#upload-to-google-play-internal-testing)
10. [Release signing troubleshooting](#release-signing-troubleshooting)
11. [Lost upload key or password](#lost-upload-key-or-password)
12. [For developers](#for-developers)

---

## What the app does

- **Accounts.** The first person to open the app creates the **admin** account. Admins add
  inspectors and other admins (**Admin tools › Users**), reset passwords and deactivate
  accounts. Passwords are stored only as salted hashes, on the phone.
- **Locations.** **Admin tools › Locations**: factory › building › floor › area.
- **Equipment with QR labels.** Five types, each with its own checklist: **Fire extinguisher,
  Electrical panel, Fire alarm, Emergency exit, Boiler**. Equipment created before these types
  existed keeps its original type (Sewing line, Fire safety point, General work area) until an
  admin changes it. Admins print a sheet of QR labels (Inspect tab › PDF icon).
- **Find equipment.** Scan its QR label with the camera, read a QR code from a picture, type the
  code, or search and filter by location on the **Inspect** tab. An unfinished inspection is
  resumed; an unknown code opens the "New equipment" form.
- **Editable checklists.** **Admin tools › Checklist questions**: add, edit, reorder or switch
  off questions per type. Each inspection keeps a snapshot of its questions, equipment name and
  location, so later edits never change past reports.
- **Inspections.** Pass / Fail / N/A per item; critical items fail the whole checkpoint; the
  pass mark is 80%. A failed item needs a note, a priority, a due date and a **responsible
  person** (the inspector by default). Camera photos per item or in general. Everything is
  saved as you go, so a draft survives the app or phone restarting.
- **Corrective actions.** Submitting creates **exactly one** corrective action per failed item
  (submitting twice never creates a second one). Each moves **Open → In progress → Resolved →
  Verified**: anyone signed in can start work and report the fix (with optional after-fix
  photos); **only an admin can verify** or send it back. Every step records who did it and when.
- **PDF report.** An A4 report with the result, score, findings, corrective actions and their
  history, all photos, the equipment's QR code and sign-off lines. **Open PDF** shows it inside
  the app (no PDF app needed); **Share** and **Save a copy…** send or store it.
- **History and dashboard.** History filters by status, result, period, location, inspector,
  type and text. Home shows inspection totals, pass rate and average score, corrective actions
  by status, overdue items and pass rate per building.
- **Works offline.** Everything is stored on the phone. The internet is used **only** if an admin
  turns on the optional sync (**Admin tools › Sync**, off by default): submitted inspections,
  corrective-action updates and their photos are then uploaded to your server when the phone is
  online. Accounts, passwords and password hashes are never uploaded. The queue survives
  restarts; retries never create duplicates; conflicting records are listed on the Sync screen.
  Release builds only connect to `https://` servers.

## Install the test APK on your phone

`deliverables/SafeStitch-debug.apk` is the test version. It installs as a separate app called
SafeStitch (ID `com.safestitch.inspection.debug`), so it can sit next to the Play Store
version later.

**Option A: copy the file to the phone (easiest)**
1. Send `SafeStitch-debug.apk` to your phone, for example with Google Drive, a USB cable or
   Nearby Share.
2. On the phone, tap the file in **Files** or **Downloads**.
3. If Android asks, allow **Install unknown apps** for that app (Files, Drive, …), then tap
   **Install**.
4. Open SafeStitch and allow the camera when asked.

**Option B: USB cable with adb**
1. On the phone, turn on **Developer options** (tap **Settings › About phone › Build number**
   seven times), then **USB debugging**.
2. Connect the phone and accept the "Allow USB debugging?" prompt.
3. On the Mac:
   ```bash
   ~/Library/Android/sdk/platform-tools/adb install -r "deliverables/SafeStitch-debug.apk"
   ```

## One-time setup on your Mac

You need **Android Studio** (it includes the Android SDK and Java 17). It is already set up
on this Mac. On a new Mac, install Android Studio, open this folder once so it downloads the
SDK, and you are ready.

## Create your upload key

Google Play needs every upload signed with the same **upload key**. Create it once:

```bash
cd "/Users/arafat/Desktop/SafeStitch project"
./scripts/create-upload-key.sh
```

Run it yourself in the **Terminal** app. It asks for your name, organisation and country,
then for a password. You type the password privately; nothing shows on screen and you never
paste it into chat or into a file.

What it creates:

| Where | What |
|---|---|
| `~/.android-keystores/safestitch/safestitch-upload.jks` | The upload key (private, PKCS12) |
| `~/.android-keystores/safestitch/safestitch-upload-certificate.pem` | The public upload certificate |
| `~/.android-keystores/safestitch/signing.properties` | Path, alias and certificate fingerprint. No password. |
| `~/.android-keystores/safestitch/BACKUP-README.txt` | Backup and restore notes |
| macOS **Keychain** item `SafeStitch-upload-keystore` | The password |

Nothing secret is stored inside this project or in `deliverables/`. Gradle
([`app/build.gradle.kts`](app/build.gradle.kts)) reads the key's location from
`signing.properties` and the password from the Keychain when it builds the release. If macOS
asks whether **security** may use the Keychain item, choose **Always Allow**.

The script refuses to overwrite an existing key.

## Back up the upload key

Do this right after creating the key, and keep the backup for as long as the app exists.

1. In Finder press **⌘⇧G**, type `~/.android-keystores/safestitch` and press Return.
2. Copy the whole folder to **two** safe places, for example:
   - an encrypted USB drive;
   - a password manager that stores files, or encrypted cloud storage.
3. Save the **password** in your password manager. Keep it separate from the key file.

To restore on a new Mac:
1. Copy the folder back to `~/.android-keystores/safestitch/`.
2. Run the following and type the password when asked:
   ```bash
   security add-generic-password -s SafeStitch-upload-keystore -a safestitch-upload -w
   ```

## Rebuild both files

After any change, run:

```bash
./scripts/build-deliverables.sh
```

It runs, in order:
1. Lint and the unit tests, then builds the debug APK (`:app:assembleDebug`).
2. Checks that the debug APK is signed and asks for no unexpected permissions, then copies it to
   `deliverables/SafeStitch-debug.apk`.
3. Checks your upload key, then builds the signed release bundle (`:app:bundleRelease`).
4. Checks the release bundle:
   - `bundletool validate`;
   - `jarsigner -verify`;
   - the signing certificate matches your **upload certificate** (not a debug key);
   - the manifest has the right package, version and targetSdk ≥ 36, is not debuggable and
     asks for no unexpected permissions, and only allows https connections.
5. Only then copies it to `deliverables/SafeStitch-release.aab`, and writes the checksums to
   `deliverables/BUILD-INFO.txt`.

If anything fails, the script stops with a message that says what is wrong, and nothing
unverified is left in `deliverables/`.

**Optional device test.** Connect a phone with USB debugging, or start an emulator, then run:

```bash
./scripts/build-deliverables.sh --device-test
```

The device test:
1. Generates APKs from the release AAB with bundletool, the way Google Play does, and
   installs them on the device.
2. Turns on airplane mode.
3. Runs one full inspection: new checkpoint, checklist, a failed item with a note and a
   camera photo, PDF report, Save a copy.
4. Pulls the PDF back to the Mac, then turns airplane mode off again.

Add `--fresh` to uninstall SafeStitch first. This deletes its data on the device. Evidence
(the PDF and screenshots) goes to `build/device-smoke-test/`.

## Version numbers

The version lives in one place: [`version.properties`](version.properties).

```properties
versionCode=1
versionName=1.0.0
```

- **versionCode** must go up by at least 1 for **every** AAB you upload to Play, internal
  testing included. Play rejects a number it has already seen.
- **versionName** is what people see, for example 1.0.1.

Before each new upload:

```bash
./scripts/bump-version.sh 1.0.1      # versionCode +1, versionName 1.0.1
./scripts/build-deliverables.sh
```

## Application ID

The app ID is **`com.safestitch.inspection`**. It is set at the top of
[`app/build.gradle.kts`](app/build.gradle.kts) (`safeStitchApplicationId`).

> **Confirm it before your first Play upload.** Once an AAB is uploaded, the ID is permanent
> for that Play listing and can never change. Until then you can change that one line and
> rebuild. If you own a web domain, the usual style is the reversed domain, for example
> `com.yourcompany.safestitch`.

## Upload to Google Play internal testing

You need a Google Play Console developer account (play.google.com/console).

1. **Create the app.**
   1. In Play Console click **Create app**.
   2. Enter the name SafeStitch, the default language, **App** and **Free**, then accept the
      declarations.
2. **Open internal testing.**
   1. In the left menu go to **Test and release › Testing › Internal testing**.
   2. Click **Create new release**.
3. **App signing.** The first time, Play asks how to sign the app. Choose
   **Use Google-generated key** ("Play App Signing", recommended). Google then keeps the final
   app-signing key safe, and the key you created on your Mac becomes your **upload key**.
4. **Upload.** Drag `deliverables/SafeStitch-release.aab` into **App bundles**, and add short
   release notes such as "First test build".
5. **Testers.** Open the **Testers** tab, create an email list (yours, plus anyone testing),
   and save.
6. **Roll out.** Click **Next › Save and publish** (or **Start rollout to Internal testing**).
   - Play Console may first ask you to finish items under **Dashboard › Set up your app**:
     privacy policy, app access, ads, content rating, target audience and **Data safety**.
   - For Data safety: with sync off (the default) nothing leaves the phone. If you turn sync on,
     inspection data and photos are sent to *your* server over https. The camera is used only
     for QR scanning and inspection photos.
7. **Install as a tester.** Open the **Copy link** URL from the Testers tab on your phone,
   accept the invitation, and install from the Play Store.

For each later upload: `./scripts/bump-version.sh <new version>`, then
`./scripts/build-deliverables.sh`, then upload the new AAB as a new release.

Heads-up: new *personal* developer accounts must run a **closed** test with a minimum number
of testers for a minimum period before they can publish to production. Play Console shows
the current requirement. Internal testing is not affected.

**Target API level.** Google Play has required new apps and updates to target **API 36
(Android 16)** since 31 August 2026 (official page: developer.android.com/google/play/requirements/target-sdk).
SafeStitch targets 36 and keeps `minSdk = 28` separate, so Android 9+ phones still work.
Google raises this requirement about once a year. When it does, update `targetSdk` in
`app/build.gradle.kts`, rebuild, and re-test.

## Release signing troubleshooting

`build-deliverables.sh` and Gradle print the exact problem. Common ones:

| Message | Fix |
|---|---|
| `signing.properties does not exist` | Create the key: `./scripts/create-upload-key.sh` |
| `Upload keystore not found` | Restore `~/.android-keystores/safestitch/` from your backup |
| `password is not in the Keychain` / `Could not read the upload-key password` | `security add-generic-password -s SafeStitch-upload-keystore -a safestitch-upload -w`, then type the password |
| `signed with certificate …, not the upload certificate` | The keystore and `signing.properties` don't belong together; restore both from the same backup |
| macOS asks about Keychain access | Click **Always Allow** (it is the `security` tool reading the SafeStitch item) |

For CI or another machine without a Keychain, set `SAFESTITCH_UPLOAD_KEYSTORE_PASSWORD` and,
if needed, `SAFESTITCH_SIGNING_PROPERTIES`, as environment variables.

## Lost upload key or password

If you use Play App Signing (step 3 above), a lost **upload** key can be replaced:
1. In Play Console go to **Test and release › Setup › App signing** and request an
   **upload key reset**.
2. Create a new key with `./scripts/create-upload-key.sh`. Move the old folder away first.
3. Upload the new `safestitch-upload-certificate.pem` when Play asks for it.

Google needs a few days to approve a reset. A backup avoids all of this.

## For developers

```
app/src/main/java/com/safestitch/app/
  domain/      checklists, scoring and completion rules, QR payload format (pure Kotlin, unit-tested)
  data/        Room database (checkpoints, inspections, checklist items, photos), settings, photo files
  qr/          ZXing QR encode/decode + CameraX analyzer (offline, no Play services)
  pdf/         PDF report and QR-label sheet (android.graphics.pdf.PdfDocument)
  ui/          Compose Material 3 screens: home, scan, checkpoint form/list, inspection, camera, report, settings
app/src/test/         JVM unit tests (QR decoding, scoring, templates, file names)
app/src/androidTest/  instrumented tests (Room repository, PDF report, printed QR label decodes back)
scripts/              build-deliverables, create-upload-key, bump-version, device-smoke-test
```

Useful commands:

```bash
./gradlew :app:lintDebug :app:testDebugUnitTest      # lint + unit tests
./gradlew :app:connectedDebugAndroidTest             # instrumented tests (device/emulator needed)
./gradlew :app:assembleDebug                         # debug APK only
```

Pinned versions are in [`gradle/libs.versions.toml`](gradle/libs.versions.toml): AGP 9.4.1,
Kotlin 2.4.21, Compose BOM 2026.09.00, CameraX 1.6.2, Room 2.8.5, ZXing 3.5.4, Gradle 9.6.0
and bundletool 1.18.3. The bundletool download is checked against its published SHA-256.
When a database entity changes, increase the Room `version` and add a migration so
inspections already on phones are kept.
