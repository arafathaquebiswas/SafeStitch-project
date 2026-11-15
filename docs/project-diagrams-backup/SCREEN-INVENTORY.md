# SafeStitch — screen inventory

Generated 2026-11-15 from commit `d797895` by `docs/project-diagrams/generator/` (`model.py` is the single source of truth for every diagram and report; line numbers are re-resolved from the current source on every build).

**Scope:** 24 screens = 3 authentication states rendered directly by `MainActivity` + 21 type-safe routes registered in `SafeStitchNavHost`. Pop-ups (D01–D16) and Android/system hand-offs (X01–X07) follow the screens.

**Status vocabulary:** IMPLEMENTED · PARTIALLY IMPLEMENTED · DECLARED BUT UNREACHABLE · DOCUMENTED ONLY · NOT VERIFIED. ✓ = reached during the Android 16 emulator walkthrough on 2026-11-15.

## Summary

| ID | Screen | Route / state | Source | Roles | Status | Emulator |
|---|---|---|---|---|---|---|
| S00 | App launch / session check | `AuthState.Loading (MainActivity)` | [MainActivity.kt:38](../../app/src/main/java/com/safestitch/app/MainActivity.kt#L38) | All | IMPLEMENTED | ✓ |
| S01 | Set up SafeStitch (first admin) | `AuthState.NeedsSetup` | [AuthScreens.kt:42](../../app/src/main/java/com/safestitch/app/ui/auth/AuthScreens.kt#L42) | First launch (no accounts) | IMPLEMENTED | ✓ |
| S02 | Sign in | `AuthState.SignedOut` | [AuthScreens.kt:88](../../app/src/main/java/com/safestitch/app/ui/auth/AuthScreens.kt#L88) | All | IMPLEMENTED | ✓ |
| S03 | Home dashboard | `DashboardRoute (start destination)` | [DashboardScreen.kt:233](../../app/src/main/java/com/safestitch/app/ui/dashboard/DashboardScreen.kt#L233) | Admin, Inspector | IMPLEMENTED | ✓ |
| S04 | Inspect — find equipment | `InspectRoute (bottom tab)` | [FindEquipmentScreen.kt:117](../../app/src/main/java/com/safestitch/app/ui/inspect/FindEquipmentScreen.kt#L117) | Admin, Inspector | IMPLEMENTED | ✓ |
| S05 | Corrective actions | `ActionsRoute (bottom tab)` | [ActionsScreens.kt:111](../../app/src/main/java/com/safestitch/app/ui/actions/ActionsScreens.kt#L111) | Admin, Inspector | IMPLEMENTED | ✓ |
| S06 | Inspection history | `HistoryRoute(equipmentCode) (bottom tab)` | [HistoryScreen.kt:114](../../app/src/main/java/com/safestitch/app/ui/history/HistoryScreen.kt#L114) | Admin, Inspector | IMPLEMENTED | ✓ |
| S07 | Captured inspections (photo list) | `CapturedInspectionsRoute` | [CapturedInspectionsScreen.kt:100](../../app/src/main/java/com/safestitch/app/ui/history/CapturedInspectionsScreen.kt#L100) | Admin, Inspector | IMPLEMENTED | ✓ |
| S08 | Captured inspection details | `CapturedInspectionDetailRoute(inspectionId)` | [InspectionDetailsScreen.kt:295](../../app/src/main/java/com/safestitch/app/ui/history/InspectionDetailsScreen.kt#L295) | Admin, Inspector | IMPLEMENTED | ✓ |
| S09 | Scan checkpoint | `ScanRoute` | [ScanScreen.kt:149](../../app/src/main/java/com/safestitch/app/ui/scan/ScanScreen.kt#L149) | Admin, Inspector | IMPLEMENTED | ✓ |
| S10 | New / edit equipment | `CheckpointFormRoute(code?, checkpointId)` | [CheckpointFormScreen.kt:143](../../app/src/main/java/com/safestitch/app/ui/checkpoint/CheckpointFormScreen.kt#L143) | Admin (+ / Edit); any role via an unknown scanned code | IMPLEMENTED | ✓ |
| S11 | Inspection checklist | `InspectionRoute(inspectionId)` | [InspectionScreen.kt:81](../../app/src/main/java/com/safestitch/app/ui/inspection/InspectionScreen.kt#L81) | Admin, Inspector | IMPLEMENTED | ✓ |
| S12 | Take photo | `PhotoCaptureRoute(inspectionId, itemId, actionId)` | [PhotoCaptureScreen.kt:97](../../app/src/main/java/com/safestitch/app/ui/camera/PhotoCaptureScreen.kt#L97) | Admin, Inspector | IMPLEMENTED | ✓ |
| S13 | Inspection report | `ReportRoute(inspectionId)` | [ReportScreen.kt:153](../../app/src/main/java/com/safestitch/app/ui/report/ReportScreen.kt#L153) | Admin, Inspector | IMPLEMENTED | ✓ |
| S14 | PDF viewer (in-app) | `PdfViewerRoute(inspectionId)` | [PdfViewerScreen.kt:47](../../app/src/main/java/com/safestitch/app/ui/report/PdfViewerScreen.kt#L47) | Admin, Inspector | IMPLEMENTED | ✓ |
| S15 | Corrective action detail | `ActionDetailRoute(actionId)` | [ActionsScreens.kt:224](../../app/src/main/java/com/safestitch/app/ui/actions/ActionsScreens.kt#L224) | Admin, Inspector | IMPLEMENTED | ✓ |
| S16 | Settings | `SettingsRoute` | [SettingsScreen.kt:42](../../app/src/main/java/com/safestitch/app/ui/settings/SettingsScreen.kt#L42) | Admin, Inspector | IMPLEMENTED | ✓ |
| S17 | Safety Assistant Chat | `GeminiChatRoute` | [GeminiChatScreen.kt:179](../../app/src/main/java/com/safestitch/app/ui/chat/GeminiChatScreen.kt#L179) | Admin, Inspector | PARTIALLY IMPLEMENTED | ✓ |
| S18 | Admin tools | `AdminRoute` | [AdminScreens.kt:82](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L82) | Admin | IMPLEMENTED | ✓ |
| S19 | Users | `UsersRoute` | [AdminScreens.kt:127](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L127) | Admin | IMPLEMENTED | ✓ |
| S20 | Locations (4 levels) | `LocationsRoute(level, parentId, title)` | [LocationsScreen.kt:53](../../app/src/main/java/com/safestitch/app/ui/admin/LocationsScreen.kt#L53) | Admin | IMPLEMENTED | ✓ |
| S21 | Checklist questions editor | `ChecklistEditorRoute` | [ChecklistEditorScreen.kt:59](../../app/src/main/java/com/safestitch/app/ui/admin/ChecklistEditorScreen.kt#L59) | Admin | IMPLEMENTED | ✓ |
| S22 | Sync (optional server upload) | `SyncRoute` | [AdminScreens.kt:272](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L272) | Admin | IMPLEMENTED | ✓ |
| S23 | SQL Database inspector | `SqlDatabaseRoute` | [AdminScreens.kt:410](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L410) | Admin | IMPLEMENTED | ✓ |

## Screen details

### S00 — App launch / session check

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `AuthState.Loading (MainActivity)`
- **Source:** [MainActivity.kt:38](../../app/src/main/java/com/safestitch/app/MainActivity.kt#L38)
- **Roles allowed:** All
- **How it is reached:** Every cold start.
- **Back behaviour:** System Back closes the app.
- **UI components:** loading spinner
- **Form fields / inputs:** —
- **Buttons & actions:** —
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** —
- **Loading / empty / error / success states:** Loading spinner only.
- **Previous screens (incoming):** APP (cold start / process) — launch
- **Next screens / targets (outgoing):** S01 Set up SafeStitch (first admin) — no accounts; S02 Sign in — signed out; S03 Home dashboard — remembered user
- **In-screen actions (no navigation):** —

### S01 — Set up SafeStitch (first admin)

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `AuthState.NeedsSetup`
- **Source:** [AuthScreens.kt:42](../../app/src/main/java/com/safestitch/app/ui/auth/AuthScreens.kt#L42)
- **Roles allowed:** First launch (no accounts)
- **How it is reached:** Only when the users table is empty.
- **Back behaviour:** System Back closes the app.
- **UI components:** —
- **Form fields / inputs:** Your name; Username; Password (at least 8 characters); Repeat password
- **Buttons & actions:** Create admin account
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** —
- **Loading / empty / error / success states:** Inline validation errors (name, username rules, password length/match); busy label 'Creating…'.
- **Previous screens (incoming):** S00 App launch / session check — no accounts
- **Next screens / targets (outgoing):** S03 Home dashboard — Create admin account
- **In-screen actions (no navigation):** Validation error shown inline

### S02 — Sign in

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `AuthState.SignedOut`
- **Source:** [AuthScreens.kt:88](../../app/src/main/java/com/safestitch/app/ui/auth/AuthScreens.kt#L88)
- **Roles allowed:** All
- **How it is reached:** Accounts exist and nobody is remembered, or after Sign out.
- **Back behaviour:** System Back closes the app.
- **UI components:** —
- **Form fields / inputs:** Username; Password
- **Buttons & actions:** Sign in
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** —
- **Loading / empty / error / success states:** Errors: wrong credentials, deactivated account, 30 s lock after 5 failed attempts.
- **Previous screens (incoming):** D01 Account menu (pop-up) — Sign out; S00 App launch / session check — signed out; S16 Settings — Sign out
- **Next screens / targets (outgoing):** S03 Home dashboard — Sign in
- **In-screen actions (no navigation):** Sign-in rejected

### S03 — Home dashboard

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `DashboardRoute (start destination)`
- **Source:** [DashboardScreen.kt:233](../../app/src/main/java/com/safestitch/app/ui/dashboard/DashboardScreen.kt#L233)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** After sign-in, session restore, or Home tab.
- **Back behaviour:** Start destination: System Back closes the app.
- **UI components:** top app bar; actions: ◉; card: Live Camera & ZXing Equipment Scanner; camera preview: ZXing live: point at QR / barcode, or tap shutter; card: Scan Equipment (QR / Barcode → History); card: Voice Note Transcription; card: Inspection Summary Overview      [2 Pending Reports]; KPI cards; KPI cards; KPI cards; KPI cards; KPI cards; KPI cards; KPI cards; card: BlockB                               100%  (1/1); card: Works offline; bottom navigation bar (active: Home)
- **Form fields / inputs:** Enter or scan QR / barcode (e.g. FE-101); Transcribed Audio (gemini-3.5-transcribe)
- **Buttons & actions:** From image; History; ▣ NEEDS REVIEW; ▣ FAIL · FOLLOW UP; ▣ PASS; View all captured inspections (3); Scan Equipment (QR / Barcode → Inspection History); Scan for Checklist; Find equipment; Safety Assistant Chat (Multi-Turn Advisor); Record Mic
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** ZXing QR / Barcode Active / Capture tag: / NEEDS REVIEW / PASS / FAIL; Quick scan tag: / FE-12 · Fire extinguisher FE-12; Captured frames (3/3): / All / NEEDS REVIEW / PASS / FAIL
- **Dialogs & pop-ups:** D01 Account menu; D16 Camera permission gate
- **Loading / empty / error / success states:** Camera permission gate; snackbars (invalid code, no barcode in picture, photo saved); more KPIs, pass rate by building and the 'Works offline' sync card below (scroll).
- **Previous screens (incoming):** D01 Account menu (pop-up) — dismiss; D16 Camera permission gate (pop-up) — granted; S00 App launch / session check — remembered user; S01 Set up SafeStitch (first admin) — Create admin account; S02 Sign in — Sign in; S03/S04/S05/S06 bottom navigation bar — Home tab
- **Next screens / targets (outgoing):** D01 Account menu (pop-up) — account icon; D16 Camera permission gate (pop-up) — camera permission; S04 Inspect — find equipment — Find equipment; S05 Corrective actions — KPI cards; S06 Inspection history — History; S06 Inspection history — Scan Equipment button; S06 Inspection history — code scanned; S06 Inspection history — from image; S06 Inspection history — quick chip; S07 Captured inspections (photo list) — View all captured; S08 Captured inspection details — recent photo; S09 Scan checkpoint — Scan for Checklist; S11 Inspection checklist — recent (draft); S13 Inspection report — recent (submitted); S17 Safety Assistant Chat — Chat; X01 System photo picker (Android/system) — photo picker; X07 Gemini dev endpoint :3000 (Android/system) — voice note
- **In-screen actions (no navigation):** Shutter: save tagged captured photo (create); Tap captured thumbnail: toggle selected tag (update); Capture-tag / filter chips, torch, Hide/Show viewfinder; Invalid code / no barcode in picture → snackbar

### S04 — Inspect — find equipment

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `InspectRoute (bottom tab)`
- **Source:** [FindEquipmentScreen.kt:117](../../app/src/main/java/com/safestitch/app/ui/inspect/FindEquipmentScreen.kt#L117)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** Inspect tab, Home › Find equipment, Admin › Equipment.
- **Back behaviour:** Bottom-tab destination: System Back returns Home.
- **UI components:** top app bar; actions: PDF *(admin only)*; card: Fire extinguisher FE-12; bottom navigation bar (active: Inspect)
- **Form fields / inputs:** ⌕ Search name, code or location
- **Buttons & actions:** Scan the equipment's QR code; Inspect; Edit *(admin only)*; FAB + *(admin only)*
- **Dropdowns / selectors:** Factory
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** D02 QR labels ready; D15 Select-field dropdown
- **Loading / empty / error / success states:** Empty-state text differs by role; snackbar 'Add equipment first.' when printing labels with no equipment.
- **Previous screens (incoming):** D02 QR labels ready (pop-up) — dismiss; D15 Select-field dropdown (pop-up) — option selected; S03 Home dashboard — Find equipment; S03/S04/S05/S06 bottom navigation bar — Inspect tab; S10 New / edit equipment — Save ⇒ back; S18 Admin tools — Equipment
- **Next screens / targets (outgoing):** D02 QR labels ready (pop-up) — print labels; D15 Select-field dropdown (pop-up) — Location filter dropdowns; S09 Scan checkpoint — Scan QR; S10 New / edit equipment — + Add; S10 New / edit equipment — Edit; S11 Inspection checklist — Inspect
- **In-screen actions (no navigation):** Search, location filter

### S05 — Corrective actions

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `ActionsRoute (bottom tab)`
- **Source:** [ActionsScreens.kt:111](../../app/src/main/java/com/safestitch/app/ui/actions/ActionsScreens.kt#L111)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** Actions tab or Home KPI cards.
- **Back behaviour:** Bottom-tab destination: System Back returns Home.
- **UI components:** top app bar; card: [Medium] [In progress]; bottom navigation bar (active: Actions)
- **Form fields / inputs:** —
- **Buttons & actions:** —
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** To do (1) / Open (0) / In progress (1) / Overdue (0) / Awaiting verification / Verified / All
- **Dialogs & pop-ups:** —
- **Loading / empty / error / success states:** Empty state: 'No corrective actions yet…'.
- **Previous screens (incoming):** S03 Home dashboard — KPI cards; S03/S04/S05/S06 bottom navigation bar — Actions tab
- **Next screens / targets (outgoing):** S15 Corrective action detail — open action
- **In-screen actions (no navigation):** Filter chips (To do / Open / In progress / Overdue / Awaiting / Verified / All)

### S06 — Inspection history

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `HistoryRoute(equipmentCode) (bottom tab)`
- **Source:** [HistoryScreen.kt:114](../../app/src/main/java/com/safestitch/app/ui/history/HistoryScreen.kt#L114)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** History tab; Home scan/code/quick-chip with an equipment code.
- **Back behaviour:** Bottom-tab destination: System Back returns Home.
- **UI components:** top app bar; actions: Captured (3); card: Equipment Identified via QR / Barcode; card: 📷 Captured Inspection Photos (3); card: Fire extinguisher FE-12            [PASS 85%]; card: Fire extinguisher FE-12            [Draft 3/7]; bottom navigation bar (active: History)
- **Form fields / inputs:** ⌕ Search equipment, location or inspector
- **Buttons & actions:** Show all; Nov 15 5:06 · NEEDS REVIEW; Nov 15 4:21 · FAIL; More filters: location, inspector, type
- **Dropdowns / selectors:** Factory ▾ All; Inspector ▾ All; Type ▾ All
- **Chips / tabs / filters:** All / Drafts / Completed; Any result / Passed / Failed; 7 days / 30 days / 90 days / All time
- **Dialogs & pop-ups:** D15 Select-field dropdown
- **Loading / empty / error / success states:** Banner only when opened with an equipment code; 'Clear filters' appears when filters are not default.
- **Previous screens (incoming):** D15 Select-field dropdown (pop-up) — option selected; S03 Home dashboard — History; S03 Home dashboard — Scan Equipment button; S03 Home dashboard — code scanned; S03 Home dashboard — from image; S03 Home dashboard — quick chip; S03/S04/S05/S06 bottom navigation bar — History tab
- **Next screens / targets (outgoing):** D15 Select-field dropdown (pop-up) — More filters: location / inspector / type dropdowns; S07 Captured inspections (photo list) — Captured (n); S08 Captured inspection details — photo card; S11 Inspection checklist — draft; S13 Inspection report — submitted
- **In-screen actions (no navigation):** Search, status/result/period chips, More filters, Clear filters, Show all

### S07 — Captured inspections (photo list)

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `CapturedInspectionsRoute`
- **Source:** [CapturedInspectionsScreen.kt:100](../../app/src/main/java/com/safestitch/app/ui/history/CapturedInspectionsScreen.kt#L100)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** Home 'View all captured inspections', History 'Captured (n)' or banner.
- **Back behaviour:** ← / System Back: popBackStack.
- **UI components:** top app bar with ← back; card: ▣  Nov 15, 2026, 5:06 AM            🗑; card: ▣  Nov 15, 2026, 4:21 AM            🗑; card: ▣  Nov 15, 2026, 3:21 AM            🗑
- **Form fields / inputs:** —
- **Buttons & actions:** —
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** All (3) / NEEDS REVIEW (1) / PASS (1) / FAIL (1) / FOLLOW UP (1); Tags: / NEEDS REVIEW / PASS / FAIL / FOLLOW UP; Tags: / NEEDS REVIEW / PASS / FAIL / FOLLOW UP; Tags: / NEEDS REVIEW / PASS / FAIL
- **Dialogs & pop-ups:** —
- **Loading / empty / error / success states:** Empty state per tag filter.
- **Previous screens (incoming):** S03 Home dashboard — View all captured; S06 Inspection history — Captured (n)
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; S08 Captured inspection details — open photo
- **In-screen actions (no navigation):** Tag filter chips; toggle tags; 🗑 delete (immediate, no confirmation)

### S08 — Captured inspection details

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `CapturedInspectionDetailRoute(inspectionId)`
- **Source:** [InspectionDetailsScreen.kt:295](../../app/src/main/java/com/safestitch/app/ui/history/InspectionDetailsScreen.kt#L295)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** Captured list card, History captured strip, Home summary (photo update).
- **Back behaviour:** ← / System Back: popBackStack.
- **UI components:** top app bar with ← back; actions: PDF, 🗑; card: Full-Resolution Image (1.0x)                [−] [+] [Reset]; image: photo — or a generated placeholder when the file is missing; card: Equipment Details                      [Auto-linked]; card: Capture & File Details; card: Status Tags; card: Branded PDF Export (PDFBox); image: Exported PDF Preview (Page 1)
- **Form fields / inputs:** Add custom tag (e.g. LINE_4, URGENT)
- **Buttons & actions:** Add tag; Share PDF; Save a copy…; Open in app; Export Branded PDF
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** Auto-link / FE-12 · Fire extinguisher FE-12; NEEDS REVIEW / PASS / FAIL / FOLLOW UP
- **Dialogs & pop-ups:** D07 Delete captured inspection?
- **Loading / empty / error / success states:** 'Captured inspection record not found.' + [Back to list]; missing image file → generated sample canvas; export snackbars.
- **Previous screens (incoming):** D07 Delete captured inspection? (pop-up) — Cancel; S03 Home dashboard — recent photo; S06 Inspection history — photo card; S07 Captured inspections (photo list) — open photo
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — Back to list; BACK (previous screen via popBackStack) — ← back; D07 Delete captured inspection? (pop-up) — 🗑; X02 Android share sheet (Android/system) — Share PDF; X03 External PDF app (Android/system) — Open in app; X04 System 'Save a copy' (Android/system) — Save a copy
- **In-screen actions (no navigation):** Export Branded PDF (PDFBox), link equipment, toggle/add/remove tags, zoom

### S09 — Scan checkpoint

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `ScanRoute`
- **Source:** [ScanScreen.kt:149](../../app/src/main/java/com/safestitch/app/ui/scan/ScanScreen.kt#L149)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** Home 'Scan for Checklist', Inspect 'Scan the equipment's QR code'.
- **Back behaviour:** ← / System Back: popBackStack. Scan is popped from the back stack when it opens the next screen.
- **UI components:** top app bar with ← back; actions: ⚡; camera preview: CameraX + ZXing analyzer · QR viewfinder
- **Form fields / inputs:** Or type the checkpoint code
- **Buttons & actions:** Scan a QR code from a picture; Continue
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** D16 Camera permission gate
- **Loading / empty / error / success states:** Camera permission gate; snackbar for unusable/no QR code (analyzer resumes after 1.5 s).
- **Previous screens (incoming):** D16 Camera permission gate (pop-up) — granted; S03 Home dashboard — Scan for Checklist; S04 Inspect — find equipment — Scan QR
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; D16 Camera permission gate (pop-up) — Camera permission gate; S10 New / edit equipment — unknown code; S11 Inspection checklist — known code; X01 System photo picker (Android/system) — photo picker
- **In-screen actions (no navigation):** Unusable / missing QR → snackbar, analyzer resumes

### S10 — New / edit equipment

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `CheckpointFormRoute(code?, checkpointId)`
- **Source:** [CheckpointFormScreen.kt:143](../../app/src/main/java/com/safestitch/app/ui/checkpoint/CheckpointFormScreen.kt#L143)
- **Roles allowed:** Admin (+ / Edit); any role via an unknown scanned code
- **How it is reached:** Inspect + (admin), Inspect › Edit (admin), Scan with unknown code (any role).
- **Back behaviour:** ← / System Back: popBackStack.
- **UI components:** top app bar with ← back; card: Code NEW-99 is not registered yet. Describe it once; every later scan opens its checklist.; radio group: Fire extinguisher, Electrical panel, Fire alarm, Emergency exit, Boiler
- **Form fields / inputs:** Equipment code (on the QR label); Name; Exact spot (optional)
- **Buttons & actions:** Save and start inspection | Save equipment | Save changes
- **Dropdowns / selectors:** Factory; Building ▾; Floor ▾; Area ▾
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** D15 Select-field dropdown
- **Loading / empty / error / success states:** Errors: missing/too-long code, missing name, location not chosen down to area, duplicate code.
- **Previous screens (incoming):** D15 Select-field dropdown (pop-up) — option selected; S04 Inspect — find equipment — + Add; S04 Inspect — find equipment — Edit; S09 Scan checkpoint — unknown code
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; D15 Select-field dropdown (pop-up) — Location dropdowns (Factory › Building › Floor › Area); S04 Inspect — find equipment — Save ⇒ back; S11 Inspection checklist — Save & start
- **In-screen actions (no navigation):** Validation / duplicate-code errors

### S11 — Inspection checklist

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `InspectionRoute(inspectionId)`
- **Source:** [InspectionScreen.kt:81](../../app/src/main/java/com/safestitch/app/ui/inspection/InspectionScreen.kt#L81)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** Inspect › Inspect, Scan with known code, saved scanned equipment, History/Home draft.
- **Back behaviour:** ← / System Back: popBackStack (draft kept).
- **UI components:** top app bar with ← back; actions: 🗑; card: Inspector: Walk Admin; progress: 0 of 7 answered; card: 1. Extinguisher is in its marked place and mounted at the right height; segmented answer: Pass / Fail / N/A; image: ▣ photo thumbnail — tap → 'Remove photo?'; card: 2. Pressure gauge reads in the green (charged) zone; segmented answer: Pass / Fail / N/A; card: General notes & photos; bottom submit bar: 0/7 answered · 7 left
- **Form fields / inputs:** Problem and corrective action (required); General notes (optional)
- **Buttons & actions:** Due Nov 22, 2026; 📷 Photo (1); 📷 Add photo; Add note; 📷 Add general photo; Submit & create PDF
- **Dropdowns / selectors:** Responsible for the fix
- **Chips / tabs / filters:** 0 pass / 0 fail / 0 N/A; Critical / High / Medium / Low
- **Dialogs & pop-ups:** D03 Almost done; D04 Delete this inspection?; D05 Remove photo?; D14 Due-date picker; D15 Select-field dropdown
- **Loading / empty / error / success states:** Every change auto-saves (draft survives restarts); loading spinner; error snackbars.
- **Previous screens (incoming):** D03 Almost done (pop-up) — OK; D04 Delete this inspection? (pop-up) — Cancel; D05 Remove photo? (pop-up) — Keep; D05 Remove photo? (pop-up) — Remove; D14 Due-date picker (pop-up) — OK / Cancel; D15 Select-field dropdown (pop-up) — option selected; S03 Home dashboard — recent (draft); S04 Inspect — find equipment — Inspect; S06 Inspection history — draft; S09 Scan checkpoint — known code; S10 New / edit equipment — Save & start; S12 Take photo — saved ⇒ back
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; D03 Almost done (pop-up) — Submit with problems; D04 Delete this inspection? (pop-up) — Trash icon; D05 Remove photo? (pop-up) — Tap a photo thumbnail; D14 Due-date picker (pop-up) — Due date button (failed item); D15 Select-field dropdown (pop-up) — Responsible for the fix ▾ (failed item); S12 Take photo — Add photo; S13 Inspection report — Submit & create PDF
- **In-screen actions (no navigation):** Answer Pass/Fail/N/A, notes, priority, general notes (auto-saved)

### S12 — Take photo

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `PhotoCaptureRoute(inspectionId, itemId, actionId)`
- **Source:** [PhotoCaptureScreen.kt:97](../../app/src/main/java/com/safestitch/app/ui/camera/PhotoCaptureScreen.kt#L97)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** Inspection item/general photo, Action detail after-fix photo.
- **Back behaviour:** ← / System Back: popBackStack (no photo saved).
- **UI components:** top app bar with ← back; camera preview: CameraX preview (full screen)
- **Form fields / inputs:** —
- **Buttons & actions:** —
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** D16 Camera permission gate
- **Loading / empty / error / success states:** Camera permission gate; capture error snackbar; spinner on the shutter while saving.
- **Previous screens (incoming):** D16 Camera permission gate (pop-up) — granted; S11 Inspection checklist — Add photo; S15 Corrective action detail — after-fix photo
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; D16 Camera permission gate (pop-up) — Camera permission gate; S11 Inspection checklist — saved ⇒ back; S15 Corrective action detail — after-fix ⇒ back
- **In-screen actions (no navigation):** Shutter: capture + save photo (spinner); error → snackbar, stays

### S13 — Inspection report

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `ReportRoute(inspectionId)`
- **Source:** [ReportScreen.kt:153](../../app/src/main/java/com/safestitch/app/ui/report/ReportScreen.kt#L153)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** Inspection submit, History/Home submitted inspection, Action detail 'Open inspection report'.
- **Back behaviour:** ← / System Back: popBackStack (the inspection was popped on submit, so Back skips it).
- **UI components:** top app bar with ← back; card: Fire extinguisher FE-12; card: PDF report; card: Corrective actions (1); image: PDF page 1 (rendered with PdfRenderer)
- **Form fields / inputs:** —
- **Buttons & actions:** Open PDF; Share; Save a copy…; Other app; Create again
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** —
- **Loading / empty / error / success states:** 'The PDF file is missing on this phone.' + [Create PDF again]; snackbars for save/regenerate.
- **Previous screens (incoming):** S03 Home dashboard — recent (submitted); S06 Inspection history — submitted; S11 Inspection checklist — Submit & create PDF; S15 Corrective action detail — Open inspection report
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; S14 PDF viewer (in-app) — Open PDF; S15 Corrective action detail — action card; X02 Android share sheet (Android/system) — Share; X03 External PDF app (Android/system) — Other app; X04 System 'Save a copy' (Android/system) — Save a copy
- **In-screen actions (no navigation):** Create again / Create PDF again (regenerate)

### S14 — PDF viewer (in-app)

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `PdfViewerRoute(inspectionId)`
- **Source:** [PdfViewerScreen.kt:47](../../app/src/main/java/com/safestitch/app/ui/report/PdfViewerScreen.kt#L47)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** Report › Open PDF.
- **Back behaviour:** ← / System Back: popBackStack.
- **UI components:** top app bar with ← back; actions: ⇪; image: Page 1 (PdfRenderer bitmap); image: Page 2 — Photos
- **Form fields / inputs:** —
- **Buttons & actions:** —
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** —
- **Loading / empty / error / success states:** Spinner while loading; 'The PDF could not be opened.'
- **Previous screens (incoming):** S13 Inspection report — Open PDF
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; X02 Android share sheet (Android/system) — Share
- **In-screen actions (no navigation):** —

### S15 — Corrective action detail

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `ActionDetailRoute(actionId)`
- **Source:** [ActionsScreens.kt:224](../../app/src/main/java/com/safestitch/app/ui/actions/ActionsScreens.kt#L224)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** Actions list card, Report corrective-action card.
- **Back behaviour:** ← / System Back: popBackStack.
- **UI components:** top app bar with ← back; card: Fire extinguisher FE-12 (FE-12); card: History; card: Admin: responsible person, priority and due date *(admin only)*
- **Form fields / inputs:** What was done to fix it?
- **Buttons & actions:** Open inspection report; Start work; Add after-fix photo (optional); Mark as resolved; Verify & close *(admin only)*; Not fixed: send back *(admin only)*; Due Nov 22, 2026 *(admin only)*
- **Dropdowns / selectors:** Responsible person *(admin only)*
- **Chips / tabs / filters:** Medium / In progress / Overdue (if late); Critical / High / Medium / Low
- **Dialogs & pop-ups:** D06 Send back to In progress; D14 Due-date picker; D15 Select-field dropdown
- **Loading / empty / error / success states:** Status-dependent controls; snackbar 'This action changed in the meantime; nothing was saved.'
- **Previous screens (incoming):** D06 Send back to In progress (pop-up) — Cancel; D14 Due-date picker (pop-up) — OK / Cancel; D15 Select-field dropdown (pop-up) — option selected; S05 Corrective actions — open action; S12 Take photo — after-fix ⇒ back; S13 Inspection report — action card
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; D06 Send back to In progress (pop-up) — Not fixed: send back; D14 Due-date picker (pop-up) — Admin: due date; D15 Select-field dropdown (pop-up) — Admin: responsible person; S12 Take photo — after-fix photo; S13 Inspection report — Open inspection report
- **In-screen actions (no navigation):** Start work: Open → In progress; Mark as resolved: In progress → Resolved; Verify & close: Resolved → Verified; Inspector at Resolved: 'Only an admin can verify this fix.'; Admin: change priority (resets the due date)

### S16 — Settings

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `SettingsRoute`
- **Source:** [SettingsScreen.kt:42](../../app/src/main/java/com/safestitch/app/ui/settings/SettingsScreen.kt#L42)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** Account menu › Settings.
- **Back behaviour:** ← / System Back: popBackStack.
- **UI components:** top app bar with ← back; card: Account; card: About SafeStitch
- **Form fields / inputs:** —
- **Buttons & actions:** Change my password; Sign out
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** D13 Change password
- **Loading / empty / error / success states:** Snackbar 'Password changed.'
- **Previous screens (incoming):** D01 Account menu (pop-up) — Settings; D13 Change password (pop-up) — Cancel
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; D13 Change password (pop-up) — Change my password; S02 Sign in — Sign out
- **In-screen actions (no navigation):** —

### S17 — Safety Assistant Chat

- **Status:** PARTIALLY IMPLEMENTED · reached on the emulator
- **Route / state:** `GeminiChatRoute`
- **Source:** [GeminiChatScreen.kt:179](../../app/src/main/java/com/safestitch/app/ui/chat/GeminiChatScreen.kt#L179)
- **Roles allowed:** Admin, Inspector
- **How it is reached:** Home chat button, Account menu › Safety Assistant Chat.
- **Back behaviour:** ← / System Back: popBackStack.
- **UI components:** top app bar with ← back; actions: Clear; card: Specialist Role & System Instruction; chat message list
- **Form fields / inputs:** Ask or use Mic…
- **Buttons & actions:** 🎙; Send
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** Safety Compliance Auditor / Corrective Action Advisor / Rapid Field Triage; Model: / Gemini 4 Flash (General) / Gemini 3.1 Flash Lite; Summarize open corrective actions / What should I check on FE-101?
- **Dialogs & pop-ups:** —
- **Loading / empty / error / success states:** Typing/transcribing status lines; Clear history.
- **Previous screens (incoming):** D01 Account menu (pop-up) — Chat; S03 Home dashboard — Chat
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; X07 Gemini dev endpoint :3000 (Android/system) — send prompt
- **In-screen actions (no navigation):** Persona / model chips, quick prompts, mic, Clear

### S18 — Admin tools

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `AdminRoute`
- **Source:** [AdminScreens.kt:82](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L82)
- **Roles allowed:** Admin
- **How it is reached:** Account menu › Admin tools (item rendered only when user.isAdmin). No route-level guard.
- **Back behaviour:** ← / System Back: popBackStack (Home).
- **UI components:** top app bar with ← back; card: Users; card: Locations; card: Equipment; card: Checklist questions; card: SQL Database; card: Sync
- **Form fields / inputs:** —
- **Buttons & actions:** —
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** —
- **Loading / empty / error / success states:** —
- **Previous screens (incoming):** D01 Account menu (pop-up) — Admin tools
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; S04 Inspect — find equipment — Equipment; S19 Users — Users; S20 Locations (4 levels) — Locations; S21 Checklist questions editor — Checklist questions; S22 Sync (optional server upload) — Sync; S23 SQL Database inspector — SQL Database
- **In-screen actions (no navigation):** —

### S19 — Users

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `UsersRoute`
- **Source:** [AdminScreens.kt:127](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L127)
- **Roles allowed:** Admin
- **How it is reached:** Admin tools › Users.
- **Back behaviour:** ← / System Back: popBackStack.
- **UI components:** top app bar with ← back; card: Walk Admin (you); card: Walk Inspector; card: Old Inspector
- **Form fields / inputs:** —
- **Buttons & actions:** Reset password; Reset password; Deactivate; Reset password; Activate; FAB +
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** D08 Add user; D09 New password for <name>
- **Loading / empty / error / success states:** Snackbars; guards 'You cannot deactivate your own account.' / 'Keep at least one active admin.'
- **Previous screens (incoming):** D08 Add user (pop-up) — Add; D08 Add user (pop-up) — Cancel; D09 New password for <name> (pop-up) — Save / Cancel; S18 Admin tools — Users
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; D08 Add user (pop-up) — + (FAB); D09 New password for <name> (pop-up) — Reset password
- **In-screen actions (no navigation):** Activate / Deactivate (not yourself; keep ≥ 1 active admin)

### S20 — Locations (4 levels)

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `LocationsRoute(level, parentId, title)`
- **Source:** [LocationsScreen.kt:53](../../app/src/main/java/com/safestitch/app/ui/admin/LocationsScreen.kt#L53)
- **Roles allowed:** Admin
- **How it is reached:** Admin tools › Locations, then row taps.
- **Back behaviour:** ← / System Back: popBackStack (one level up).
- **UI components:** top app bar with ← back; card: PlantA                                              ✎    ›; card: PlantB                                              ✎    ›
- **Form fields / inputs:** —
- **Buttons & actions:** FAB + Add factory | building | floor | area
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** D10 Add / Rename <level>
- **Loading / empty / error / success states:** Empty state 'No factories yet…'.
- **Previous screens (incoming):** D10 Add / Rename <level> (pop-up) — Save / Cancel; S18 Admin tools — Locations; S20 Locations (4 levels) — row › next level
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; D10 Add / Rename <level> (pop-up) — Add <level> (FAB) / ✎ rename; S20 Locations (4 levels) — row › next level
- **In-screen actions (no navigation):** —

### S21 — Checklist questions editor

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `ChecklistEditorRoute`
- **Source:** [ChecklistEditorScreen.kt:59](../../app/src/main/java/com/safestitch/app/ui/admin/ChecklistEditorScreen.kt#L59)
- **Roles allowed:** Admin
- **How it is reached:** Admin tools › Checklist questions.
- **Back behaviour:** ← / System Back: popBackStack.
- **UI components:** top app bar with ← back; card: Fire extinguisher; card: Fire extinguisher; card: Fire extinguisher
- **Form fields / inputs:** —
- **Buttons & actions:** FAB + Add question
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** Fire extinguisher / Electrical panel / Fire alarm / Emergency exit / Boiler / Sewing line (earlier)
- **Dialogs & pop-ups:** D11 Add / Edit question
- **Loading / empty / error / success states:** —
- **Previous screens (incoming):** D11 Add / Edit question (pop-up) — Save / Cancel; S18 Admin tools — Checklist questions
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; D11 Add / Edit question (pop-up) — Add question (FAB) / ✎ edit
- **In-screen actions (no navigation):** Type chips, move ↑/↓, In use switch

### S22 — Sync (optional server upload)

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `SyncRoute`
- **Source:** [AdminScreens.kt:272](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L272)
- **Roles allowed:** Admin
- **How it is reached:** Admin tools › Sync.
- **Back behaviour:** ← / System Back: popBackStack.
- **UI components:** top app bar with ← back; switch: Sync to server; card: Status: Sync is off
- **Form fields / inputs:** Server address; API key
- **Buttons & actions:** Save; Sync now; Re-upload everything
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** D12 Re-upload everything?
- **Loading / empty / error / success states:** URL validation error (release: https only); conflict cards [Keep server copy] [Send mine again].
- **Previous screens (incoming):** D12 Re-upload everything? (pop-up) — Cancel; S18 Admin tools — Sync
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back; D12 Re-upload everything? (pop-up) — Re-upload everything; X06 Sync server (HTTPS, WorkManager) (Android/system) — Sync now
- **In-screen actions (no navigation):** Conflicts: Keep server copy / Send mine again

### S23 — SQL Database inspector

- **Status:** IMPLEMENTED · reached on the emulator
- **Route / state:** `SqlDatabaseRoute`
- **Source:** [AdminScreens.kt:410](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L410)
- **Roles allowed:** Admin
- **How it is reached:** Admin tools › SQL Database.
- **Back behaviour:** ← / System Back: popBackStack.
- **UI components:** top app bar with ← back; card: Engine: SQLite 3 · Room v2 (safestitch.db); card: Result: 0 row(s) in 3 ms; card: action_events                                   0 row(s); card: areas                                               1 row(s)
- **Form fields / inputs:** Read-only SQL query (SELECT / PRAGMA)
- **Buttons & actions:** Equipment & Inspections; Captured Photos; Corrective Actions; Factory Locations; Run SQL Query; Refresh Counts
- **Dropdowns / selectors:** —
- **Chips / tabs / filters:** —
- **Dialogs & pop-ups:** —
- **Loading / empty / error / success states:** Query error text (non-SELECT rejected).
- **Previous screens (incoming):** S18 Admin tools — SQL Database
- **Next screens / targets (outgoing):** BACK (previous screen via popBackStack) — ← back
- **In-screen actions (no navigation):** Preset queries, Run SQL Query, Refresh Counts, table card → SELECT *

## Pop-ups, menus and permission gates

| ID | Title | Opened from | Source | Roles | Buttons |
|---|---|---|---|---|---|
| D01 | Account menu | S03 | [DashboardScreen.kt:359](../../app/src/main/java/com/safestitch/app/ui/dashboard/DashboardScreen.kt#L359) | Admin, Inspector | (menu items) |
| D02 | QR labels ready | S04 | [FindEquipmentScreen.kt:240](../../app/src/main/java/com/safestitch/app/ui/inspect/FindEquipmentScreen.kt#L240) | Admin | Open ⇒ PDF app · Share / print ⇒ share sheet |
| D03 | Almost done | S11 | [InspectionScreen.kt:175](../../app/src/main/java/com/safestitch/app/ui/inspection/InspectionScreen.kt#L175) | Admin, Inspector | OK ⇒ back to the checklist |
| D04 | Delete this inspection? | S11 | [InspectionScreen.kt:183](../../app/src/main/java/com/safestitch/app/ui/inspection/InspectionScreen.kt#L183) | Admin, Inspector | Cancel ⇒ stays · Delete ⇒ previous screen |
| D05 | Remove photo? | S11 | [InspectionScreen.kt:192](../../app/src/main/java/com/safestitch/app/ui/inspection/InspectionScreen.kt#L192) | Admin, Inspector | Keep · Remove ⇒ photo deleted |
| D06 | Send back to In progress | S15 | [ActionsScreens.kt:387](../../app/src/main/java/com/safestitch/app/ui/actions/ActionsScreens.kt#L387) | Admin | Cancel · Send back ⇒ In progress |
| D07 | Delete captured inspection? | S08 | [InspectionDetailsScreen.kt:874](../../app/src/main/java/com/safestitch/app/ui/history/InspectionDetailsScreen.kt#L874) | Admin, Inspector | Cancel · Delete ⇒ previous screen |
| D08 | Add user | S19 | [AdminScreens.kt:185](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L185) | Admin | Cancel · Add ⇒ user created |
| D09 | New password for <name> | S19 | [AdminScreens.kt:242](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L242) | Admin | Cancel · Save ⇒ password reset |
| D10 | Add / Rename <level> | S20 | [LocationsScreen.kt:122](../../app/src/main/java/com/safestitch/app/ui/admin/LocationsScreen.kt#L122) | Admin | Cancel · Save ⇒ added / renamed |
| D11 | Add / Edit question | S21 | [ChecklistEditorScreen.kt:134](../../app/src/main/java/com/safestitch/app/ui/admin/ChecklistEditorScreen.kt#L134) | Admin | Cancel · Save ⇒ question saved |
| D12 | Re-upload everything? | S22 | [AdminScreens.kt:387](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L387) | Admin | Cancel · Re-upload ⇒ sync queued |
| D13 | Change password | S16 | [SettingsScreen.kt:86](../../app/src/main/java/com/safestitch/app/ui/settings/SettingsScreen.kt#L86) | Admin, Inspector | Cancel · Save ⇒ password changed |
| D14 | Due-date picker | S11, S15 | [Pickers.kt:222](../../app/src/main/java/com/safestitch/app/ui/components/Pickers.kt#L222) | Admin, Inspector | Cancel · OK ⇒ due date set |
| D15 | Select-field dropdown | S04, S06, S10, S11, S15 | [Pickers.kt:85](../../app/src/main/java/com/safestitch/app/ui/components/Pickers.kt#L85) | Admin, Inspector | (menu items) |
| D16 | Camera permission gate | S03, S09, S12 | [Common.kt:144](../../app/src/main/java/com/safestitch/app/ui/components/Common.kt#L144) | Admin, Inspector | Allow camera ⇒ preview · Open app settings |

## Android / system hand-offs

| ID | Interaction | Used from | Source | Status | Note |
|---|---|---|---|---|---|
| X01 | System photo picker | S03, S09 | [ScanScreen.kt:265](../../app/src/main/java/com/safestitch/app/ui/scan/ScanScreen.kt#L265) | IMPLEMENTED | — |
| X02 | Android share sheet | S08, S13, S14, D02 | [FileSharing.kt:29](../../app/src/main/java/com/safestitch/app/util/FileSharing.kt#L29) | IMPLEMENTED | — |
| X03 | External PDF app | S08, S13, D02 | [FileSharing.kt:37](../../app/src/main/java/com/safestitch/app/util/FileSharing.kt#L37) | IMPLEMENTED | — |
| X04 | System 'Save a copy' | S08, S13 | [ReportScreen.kt:166](../../app/src/main/java/com/safestitch/app/ui/report/ReportScreen.kt#L166) | IMPLEMENTED | — |
| X05 | Android app settings | D16 | [Common.kt:133](../../app/src/main/java/com/safestitch/app/ui/components/Common.kt#L133) | IMPLEMENTED | — |
| X06 | Sync server (HTTPS, WorkManager) | S22, APP | [SyncManager.kt:128](../../app/src/main/java/com/safestitch/app/sync/SyncManager.kt#L128) | IMPLEMENTED | Only when an admin enables sync; not exercised against a real server in this analysis. |
| X07 | Gemini dev endpoint :3000 | S17, S03 | [SafetyChatService.kt:90](../../app/src/main/java/com/safestitch/app/chat/SafetyChatService.kt#L90) | PARTIALLY IMPLEMENTED | Emulator/debug-only host; cleartext is blocked in release builds, so the app always falls back to local text there. |

## Declared but unreachable

| Item | Source | Why |
|---|---|---|
| InspectionListScreen (alias of CapturedInspectionsScreen) | [ui/history/CapturedInspectionsScreen.kt:226](../../app/src/main/java/com/safestitch/app/ui/history/CapturedInspectionsScreen.kt#L226) | no call site anywhere in `app/src` |
| CapturedInspectionDetailsScreen (alias of InspectionDetailsScreen) | [ui/history/InspectionDetailsScreen.kt:900](../../app/src/main/java/com/safestitch/app/ui/history/InspectionDetailsScreen.kt#L900) | no call site anywhere in `app/src` |
| ChatScreen (alias of GeminiChatScreen) | [ui/chat/GeminiChatScreen.kt:165](../../app/src/main/java/com/safestitch/app/ui/chat/GeminiChatScreen.kt#L165) | no call site anywhere in `app/src` |
| DatabaseSeed.seedSampleData (sample factory, FE-101… equipment and inspections) | [data/db/DatabaseSeed.kt:103](../../app/src/main/java/com/safestitch/app/data/db/DatabaseSeed.kt#L103) | no call site anywhere in `app/src` |
