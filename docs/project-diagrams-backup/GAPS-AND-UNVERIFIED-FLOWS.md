# SafeStitch — gaps, risks and unverified flows

Generated 2026-11-15 · commit `d797895`. Ordered by severity. The application code was not changed; these are findings for you to decide on.

## Critical

- **App crashes after updating from a 9–11 Nov 2026 build (reproduced on the emulator).** Commit `ae57d7c` (11 Nov) changed the Room schema for version 2 (identity hash `5de8d7c2…` → `b3f741b0…`) without raising the database version or adding a migration (`SafeStitchDatabase.kt` still says `version = 2`). Any phone that installed a build from commit `c5e9516` (9 Nov), such as the debug APK handed out earlier, will crash on launch after updating. Fix: bump to version 3 with a migration (or a destructive fallback for test builds) before the next tester build.

## High — navigation bug (reproduced on the emulator)

- **The Home tab stops returning to the dashboard (reproduced twice on the emulator).** `SafeStitchNavHost.kt` opens tab destinations from inside Home without `popUpTo`, so the destination is pushed onto Home's own back stack and the bottom bar's `restoreState = true` brings it back whenever Home is tapped — even from another tab. System Back to the dashboard clears it.
- Affected routes — **runtime-verified:** Home KPI cards → `ActionsRoute` (`onOpenActions = { nav.navigate(ActionsRoute) { launchSingleTop = true } }`); Home › Find equipment → `InspectRoute` (`onFindEquipment`). **Same code pattern, code-derived only (not run):** Admin tools › Equipment → `InspectRoute` (`onEquipment`); Home live scan / From image / quick chip / typed code / 'Scan Equipment' button → `HistoryRoute(equipmentCode)` (`onNavigateToEquipmentHistory`), which pushes a filtered History tab onto Home's stack.
- Suggested fix (not applied — needs your authorisation): navigate to tab routes with the bottom bar's options (`popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true`), or switch the selected tab instead of pushing.

## High — partially implemented features

- **Voice note transcription (Home › Voice Note Transcription; chat 🎙).** `RECORD_AUDIO` is declared in the manifest but never requested at runtime, so on Android 6+ `AudioRecord` fails silently. The service then substitutes a synthetic 440 Hz tone, posts it to `http://10.0.2.2:3000/api/gemini/transcribe` and, when that fails, shows a hard-coded sentence about FE-101 / EL-204 / EX-102 as if it were the user's transcript (`AudioTranscriptionService.buildFallbackTranscription`). Reproduced on the emulator: a transcript appeared while RECORD_AUDIO was not granted.
- **Safety Assistant chat (S17).** Replies come from `http://10.0.2.2:3000/api/gemini/chat`, the developer's Mac as seen from an Android emulator, served by `scripts/preview-server.mjs`. Release builds block cleartext HTTP and real phones cannot reach 10.0.2.2, so outside the emulator the screen always shows the local rules-based `buildContextualReply` text. The model names shown (Gemini 4 Flash, Gemini 3.1 Flash Lite) are only labels sent to that dev server. `metadata.json` advertises a server-side Gemini capability that the app does not have in production.

## Medium — access control and data safety

- **Role checks are UI-only for most admin areas.** `SafeStitchNavHost` has no route guards; Admin tools (S18–S23) are only hidden by `if (user.isAdmin)` on the Account-menu item. `CatalogRepository` (locations, checklist questions) and `SyncManager.save` have no admin check. User management and corrective-action verify/send-back/reassign/reschedule are also enforced in the repositories (`AuthRepository`, `InspectionRepository.requireAdmin`). There is currently no deep link or other entry point, so an inspector cannot reach these screens, but that protection depends on the UI alone.
- **Inspectors can register new equipment.** Scanning or typing an unknown code opens 'New equipment' (S10) for any role, and `InspectionRepository.createCheckpoint` has no role check. The inspector empty-state text ('Scan a QR label…') suggests this is intended. Reproduced on the emulator. Please confirm this is the intended policy.
- **Captured photos are deleted without confirmation on the list screen (S07)**, while the details screen (S08) asks first (D07). One mis-tap deletes both the record and the photo file.
- **`settings.gradle.kts` starts a background Node server during Gradle configuration** if `/usr/local/bin/node` exists. It listens on 0.0.0.0:3000 (all network interfaces). It did not trigger on this Mac (Node is in /opt/homebrew), but any machine or CI agent with Node at that path starts a LAN-reachable server just by running Gradle.

## Low — UX inconsistencies

- S08 'Open in app' actually launches an external PDF viewer (`FileSharing.openPdf`), while S13's 'Open PDF' opens the in-app viewer. When no viewer is installed, S08 says 'No external PDF viewer installed.'
- Home 'Scan Equipment (QR / Barcode → Inspection History)': with no code typed, it opens the history of the *first* equipment in the list (`checkpoints.firstOrNull()`), which can look arbitrary.
- The Home voice-note card and the chat say they transcribe with 'gemini-3.5-transcribe' even when the result is the canned fallback.
- There is no delete action for locations, equipment or users (users can only be deactivated; questions can only be switched off). This looks intentional, since history keeps snapshots, but the README doesn't say so.
- Corrective actions (S05): when the selected filter has no matches but other actions exist (e.g. 'To do (0)' after everything is verified) the list shows only '0 shown · N corrective actions in total' with no explanation.

## Declared but unreachable code

- `InspectionListScreen` (ui/history/CapturedInspectionsScreen.kt), `CapturedInspectionDetailsScreen` (ui/history/InspectionDetailsScreen.kt) and `ChatScreen` (ui/chat/GeminiChatScreen.kt) are alias composables with no callers.
- `DatabaseSeed.seedSampleData` (sample factory 'Dhaka Plant 1', equipment FE-101/EL-204/EX-102…) is never called, so a fresh install has no equipment or locations, even though the chat prompts, the voice fallback and three seeded sample captured photos refer to FE-101/EL-204/EX-102.
- The three seeded captured photos point at image files that do not exist (`photos/IMG_FE101_gauge_check.jpg` …), so S08 shows a generated placeholder canvas instead.

## Documentation drift (README vs code)

- The README does not mention the Home live ZXing camera/barcode scanner and captured-photo tagging, captured inspections with PDFBox branded export, voice notes, the Safety Assistant chat, or the SQL Database inspector.
- README versions differ from the build files: the README says AGP 9.4.1, Kotlin 2.4.21, Compose BOM 2026.09.00 and Gradle 9.6.0; the project uses AGP 9.1.1, Kotlin 2.2.10, Compose BOM 2025.05.00 and Gradle wrapper 9.3.1.
- README 'For developers' says PDFs use `android.graphics.pdf.PdfDocument`; captured-photo export also uses PDFBox.

## Build environment issues found

- `debug.keystore` is expected in the project root but is absent (gitignored), so `assembleDebug` fails until it is created or Android Studio injects signing.
- `gradlew` has no executable bit in this checkout.
- Two Robolectric test classes need JDK 21 for SDK 36; only JDK 17 is installed.
- `ANDROID_HOME` is not set and there is no `local.properties`.
- The reference file named in the brief, `references/wireframe-diagram_updated.pdf`, is not in the workspace; the copy attached to the request was used instead.

## How the emulator walkthrough failures were classified

- **Account / Admin menu (first run):** test harness — Compose `DropdownMenu` renders in its own popup window, where the root `testTagsAsResourceId` flag does not apply; the script was changed to match menu items by text. Re-run: PASS.
- **Checkpoint form save (parts 1 and 2):** test harness — a location dropdown popup hid the form's resource-ids while scrolling. The form was filled correctly (screenshot); the save was re-run: PASS (S10 → back to Inspect with FE-12 listed).
- **Corrective actions list → action detail:** missing test data — the only action had just been verified, so the default 'To do' filter was empty. Re-run with the 'All' filter: PASS.
- **Back from the Actions tab:** test-harness expectation — System Back on a tab screen correctly returns Home.
- **Home tab after KPI card / Find equipment:** real app problem (see 'High — navigation bug').
- **'Add equipment first.' snackbar:** not verifiable with uiautomator (snackbars disappear before a dump completes); remains code-verified only.
- **Inspector 'New equipment' save (part 5):** test harness — the keyboard covered the form; re-run in part 6: PASS.

## Previous run of this task

- An earlier, static-only run of this task (23 screens, 39 transitions, no emulator test) was committed as `f60b43c` ('add'). Its files were moved unchanged to `docs/project-diagrams/_previous-run-2026-11-15/` and also remain in git history; delete the folder when you no longer need it. Application source is identical between `d797895` and `f60b43c`.

## Transitions verified by code trace only (not exercised on the emulator)

| From | Action | To | Why not exercised |
|---|---|---|---|
| APP (cold start / process) | Cold start | S00 App launch / session check | not part of the scripted walkthrough |
| S03 Home dashboard | Live ZXing scan recognises a QR/barcode | S06 Inspection history | Not exercised on the emulator (needs a printed code in front of the camera). |
| S03 Home dashboard | From image → photo picker → decode | S06 Inspection history | not part of the scripted walkthrough |
| S03 Home dashboard | Scan Equipment (→ Inspection History) button | S06 Inspection history | not part of the scripted walkthrough |
| S03 Home dashboard | Summary card: recent captured photo | S08 Captured inspection details | not part of the scripted walkthrough |
| S03 Home dashboard | Summary card: recent submitted checklist | S13 Inspection report | not part of the scripted walkthrough |
| S03 Home dashboard | Summary card: recent draft checklist | S11 Inspection checklist | not part of the scripted walkthrough |
| S03 Home dashboard | Camera permission gate (asks once) | D16 Camera permission gate (pop-up) | not part of the scripted walkthrough |
| S03 Home dashboard | From image | X01 System photo picker (Android/system) | hands off to another app, system UI or a server |
| S03 Home dashboard | Shutter: save tagged captured photo (create) | S03 Home dashboard | in-screen behaviour, code-traced |
| S03 Home dashboard | Tap captured thumbnail: toggle selected tag (update) | S03 Home dashboard | in-screen behaviour, code-traced |
| S03 Home dashboard | Capture-tag / filter chips, torch, Hide/Show viewfinder | S03 Home dashboard | in-screen behaviour, code-traced |
| S03 Home dashboard | Invalid code / no barcode in picture → snackbar | S03 Home dashboard | in-screen behaviour, code-traced |
| D02 QR labels ready (pop-up) | Share / print | X02 Android share sheet (Android/system) | hands off to another app, system UI or a server |
| D02 QR labels ready (pop-up) | Open | X03 External PDF app (Android/system) | hands off to another app, system UI or a server |
| S04 Inspect — find equipment | Search, location filter | S04 Inspect — find equipment | in-screen behaviour, code-traced |
| S04 Inspect — find equipment | Location filter dropdowns | D15 Select-field dropdown (pop-up) | not part of the scripted walkthrough |
| S05 Corrective actions | Filter chips (To do / Open / In progress / Overdue / Awaiting / Verified / All) | S05 Corrective actions | in-screen behaviour, code-traced |
| S06 Inspection history | Inspection card (draft) | S11 Inspection checklist | not part of the scripted walkthrough |
| S06 Inspection history | Search, status/result/period chips, More filters, Clear filters, Show all | S06 Inspection history | in-screen behaviour, code-traced |
| S06 Inspection history | More filters: location / inspector / type dropdowns | D15 Select-field dropdown (pop-up) | not part of the scripted walkthrough |
| S07 Captured inspections (photo list) | Tag filter chips; toggle tags; 🗑 delete (immediate, no confirmation) | S07 Captured inspections (photo list) | in-screen behaviour, code-traced |
| D07 Delete captured inspection? (pop-up) | Delete → returns to the previous screen | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S08 Captured inspection details | Share PDF | X02 Android share sheet (Android/system) | hands off to another app, system UI or a server |
| S08 Captured inspection details | Save a copy… | X04 System 'Save a copy' (Android/system) | hands off to another app, system UI or a server |
| S08 Captured inspection details | 'Open in app' (actually launches an external PDF viewer) | X03 External PDF app (Android/system) | hands off to another app, system UI or a server |
| S08 Captured inspection details | Export Branded PDF (PDFBox), link equipment, toggle/add/remove tags, zoom | S08 Captured inspection details | in-screen behaviour, code-traced |
| S09 Scan checkpoint | Scan a QR code from a picture | X01 System photo picker (Android/system) | hands off to another app, system UI or a server |
| S09 Scan checkpoint | Camera permission gate | D16 Camera permission gate (pop-up) | not part of the scripted walkthrough |
| S09 Scan checkpoint | Unusable / missing QR → snackbar, analyzer resumes | S09 Scan checkpoint | in-screen behaviour, code-traced |
| S10 New / edit equipment | Validation / duplicate-code errors | S10 New / edit equipment | in-screen behaviour, code-traced |
| S10 New / edit equipment | Location dropdowns (Factory › Building › Floor › Area) | D15 Select-field dropdown (pop-up) | not part of the scripted walkthrough |
| S11 Inspection checklist | Tap a photo thumbnail | D05 Remove photo? (pop-up) | not part of the scripted walkthrough |
| S11 Inspection checklist | Answer Pass/Fail/N/A, notes, priority, general notes (auto-saved) | S11 Inspection checklist | in-screen behaviour, code-traced |
| S12 Take photo | Camera permission gate | D16 Camera permission gate (pop-up) | not part of the scripted walkthrough |
| S13 Inspection report | Share | X02 Android share sheet (Android/system) | hands off to another app, system UI or a server |
| S13 Inspection report | Save a copy… | X04 System 'Save a copy' (Android/system) | hands off to another app, system UI or a server |
| S13 Inspection report | Other app | X03 External PDF app (Android/system) | hands off to another app, system UI or a server |
| S13 Inspection report | Create again / Create PDF again (regenerate) | S13 Inspection report | in-screen behaviour, code-traced |
| S14 PDF viewer (in-app) | Share icon | X02 Android share sheet (Android/system) | hands off to another app, system UI or a server |
| D06 Send back to In progress (pop-up) | Send back → status In progress | S15 Corrective action detail | in-screen behaviour, code-traced |
| S15 Corrective action detail | Admin: due date | D14 Due-date picker (pop-up) | not part of the scripted walkthrough |
| S15 Corrective action detail | Admin: responsible person | D15 Select-field dropdown (pop-up) | not part of the scripted walkthrough |
| S15 Corrective action detail | Admin: change priority (resets the due date) | S15 Corrective action detail | in-screen behaviour, code-traced |
| D13 Change password (pop-up) | Save → 'Password changed.' | S16 Settings | in-screen behaviour, code-traced |
| S17 Safety Assistant Chat | Persona / model chips, quick prompts, mic, Clear | S17 Safety Assistant Chat | in-screen behaviour, code-traced |
| S19 Users | Activate / Deactivate (not yourself; keep ≥ 1 active admin) | S19 Users | in-screen behaviour, code-traced |
| S21 Checklist questions editor | Type chips, move ↑/↓, In use switch | S21 Checklist questions editor | in-screen behaviour, code-traced |
| S22 Sync (optional server upload) | Re-upload everything | D12 Re-upload everything? (pop-up) | not part of the scripted walkthrough |
| S22 Sync (optional server upload) | Conflicts: Keep server copy / Send mine again | S22 Sync (optional server upload) | in-screen behaviour, code-traced |
| S23 SQL Database inspector | Preset queries, Run SQL Query, Refresh Counts, table card → SELECT * | S23 SQL Database inspector | in-screen behaviour, code-traced |
| D16 Camera permission gate (pop-up) | Open app settings | X05 Android app settings (Android/system) | hands off to another app, system UI or a server |
| APP (cold start / process) | App start / any syncable change → requestSync() | X06 Sync server (HTTPS, WorkManager) (Android/system) | not part of the scripted walkthrough |
| D01 Account menu (pop-up) | Dismiss menu (tap outside / Back) | S03 Home dashboard | not part of the scripted walkthrough |
| D02 QR labels ready (pop-up) | Dismiss (tap outside / Back) | S04 Inspect — find equipment | not part of the scripted walkthrough |
| D03 Almost done (pop-up) | OK / dismiss → fix the listed problems | S11 Inspection checklist | not part of the scripted walkthrough |
| D04 Delete this inspection? (pop-up) | Cancel / dismiss (inspection kept) | S11 Inspection checklist | not part of the scripted walkthrough |
| D05 Remove photo? (pop-up) | Remove → photo deleted (not in the PDF) | S11 Inspection checklist | not part of the scripted walkthrough |
| D05 Remove photo? (pop-up) | Keep / dismiss | S11 Inspection checklist | not part of the scripted walkthrough |
| D06 Send back to In progress (pop-up) | Cancel / dismiss (stays Resolved) | S15 Corrective action detail | not part of the scripted walkthrough |
| D07 Delete captured inspection? (pop-up) | Cancel / dismiss (record kept) | S08 Captured inspection details | not part of the scripted walkthrough |
| D08 Add user (pop-up) | Add → user created (snackbar); errors stay in the dialog | S19 Users | not part of the scripted walkthrough |
| D08 Add user (pop-up) | Cancel / dismiss | S19 Users | not part of the scripted walkthrough |
| D09 New password for <name> (pop-up) | Save → password reset (snackbar) / Cancel | S19 Users | not part of the scripted walkthrough |
| D10 Add / Rename <level> (pop-up) | Save → location added or renamed / Cancel | S20 Locations (4 levels) | not part of the scripted walkthrough |
| D11 Add / Edit question (pop-up) | Save → question added or edited / Cancel | S21 Checklist questions editor | not part of the scripted walkthrough |
| D12 Re-upload everything? (pop-up) | Re-upload → reset sync state + sync now | X06 Sync server (HTTPS, WorkManager) (Android/system) | hands off to another app, system UI or a server |
| D12 Re-upload everything? (pop-up) | Cancel / dismiss | S22 Sync (optional server upload) | not part of the scripted walkthrough |
| D13 Change password (pop-up) | Cancel / dismiss | S16 Settings | not part of the scripted walkthrough |
| D14 Due-date picker (pop-up) | OK → due date set / Cancel | S11 Inspection checklist | not part of the scripted walkthrough |
| D14 Due-date picker (pop-up) | OK → due date set / Cancel | S15 Corrective action detail | not part of the scripted walkthrough |
| D15 Select-field dropdown (pop-up) | Option selected → field updated | S04 Inspect — find equipment | not part of the scripted walkthrough |
| D15 Select-field dropdown (pop-up) | Option selected → field updated | S06 Inspection history | not part of the scripted walkthrough |
| D15 Select-field dropdown (pop-up) | Option selected → field updated | S10 New / edit equipment | not part of the scripted walkthrough |
| D15 Select-field dropdown (pop-up) | Option selected → field updated | S11 Inspection checklist | not part of the scripted walkthrough |
| D15 Select-field dropdown (pop-up) | Option selected → field updated | S15 Corrective action detail | not part of the scripted walkthrough |
| D16 Camera permission gate (pop-up) | Permission granted → camera preview shown | S03 Home dashboard | not part of the scripted walkthrough |
| D16 Camera permission gate (pop-up) | Permission granted → camera preview shown | S09 Scan checkpoint | not part of the scripted walkthrough |
| D16 Camera permission gate (pop-up) | Permission granted → camera preview shown | S12 Take photo | not part of the scripted walkthrough |
| S07 Captured inspections (photo list) | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S08 Captured inspection details | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S09 Scan checkpoint | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S10 New / edit equipment | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S11 Inspection checklist | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S12 Take photo | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S13 Inspection report | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S14 PDF viewer (in-app) | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S15 Corrective action detail | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S16 Settings | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S17 Safety Assistant Chat | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S18 Admin tools | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S19 Users | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S20 Locations (4 levels) | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S21 Checklist questions editor | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S22 Sync (optional server upload) | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S23 SQL Database inspector | ← / system Back → previous screen on the back stack | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S08 Captured inspection details | 'Back to list' (record not found state) | BACK (previous screen via popBackStack) | not part of the scripted walkthrough |
| S12 Take photo | Shutter: capture + save photo (spinner); error → snackbar, stays | S12 Take photo | in-screen behaviour, code-traced |

## Emulator checks that failed

- TABS → S03 Bottom bar: Home: APP PROBLEM (reproduced 3×): after Home › KPI card opens Corrective actions, tapping the Home tab keeps showing Corrective actions — also from the History tab. NavHost onOpenActions navigates without popUpTo, so the Actions screen sits in Home's saved back stack and is restored. System Back returns to the dashboard.; On Inspect opened via Home › Find equipment, tapping the Home tab kept showing Inspect (NavHost onFindEquipment navigates without popUpTo; Home's saved back stack is restored). System Back returns to the dashboard.
