# SafeStitch — coverage and traceability report

Generated 2026-11-15 · commit `d797895` · generator `docs/project-diagrams/generator/build.py`.

## Final coverage metrics (independently computed)

| Metric | Result |
|---|---|
| Screens discovered | **24** (21 NavHost routes + 3 auth states) |
| Screens diagrammed | **24** |
| Navigation transitions discovered (extractor operations that leave the screen) | **188** |
| Transitions represented (operations matched to a drawn diagram edge) | **188** |
| Missing arrows | **0** |
| Unsupported or incorrect arrows (evidence token not found) | **0** |
| Diagram edges not re-detected by the extractor (their evidence still resolves) | 0 — none |
| Dialog transitions (open pop-up + dialog outcomes) | **60** |
| Runtime-verified transitions | **71** |
| Code-only transitions | **98** |
| Unverified transitions (no runtime pass and no extractor match) | **0** |
| Partially implemented areas | **2** — Safety Assistant chat (S17) and voice-note transcription (Home card / chat mic) |
| Visual routing problems (automated geometry check, all diagrams) | **0** |
| Total arrows drawn across all diagrams | 374 (master: 67) |

## Headline numbers

| Measure | Count |
|---|---|
| Screens discovered in code (21 NavHost routes + 3 auth states) | **24** |
| Screens drawn as phone frames (any diagram) | **24 / 24** |
| Screens in the master wireflow | **24 / 24** |
| Pop-ups / menus / permission gates | 16 — all shown in the master (pop-up trays + the D01 node) |
| Android / system hand-offs | 7 |
| Transitions traced (navigation, pop-ups, hand-offs, returns, tabs, in-screen state changes, background) | **170** |
| … navigation / pop-up / hand-off / return / tab / background | 142 |
| … in-screen state changes (CRUD, status changes, validation) | 28 |
| Transitions documented in NAVIGATION-MATRIX.csv | **170 / 170** |
| Transitions represented in at least one diagram | **170 / 170** |
| Transitions confirmed on the emulator (every mapped check passed) | 71 |
| Transitions with a failed emulator check | 1 |
| Transitions verified by code trace only | 98 |
| Partially implemented transitions | 2 |
| Independent-scan items the model does not explain (sections 3–7, 9) | **0** |
| Emulator checks run | 101 (95 passed, 6 failed) |

**What the completeness claim covers:** every route class, every `composable<…>` registration, every `nav.navigate`/`popBackStack` call site in the NavHost, every `on…` callback parameter of every screen composable, every `AlertDialog` / `DatePickerDialog` / `DropdownMenu` in `ui/`, and every `…Screen` composable was re-scanned from source, independently of the model (sections 2–6). **What it does not cover:** behaviour that only exists at runtime (e.g. what a server returns), and transitions listed as code-trace-only in GAPS-AND-UNVERIFIED-FLOWS.md.

## 1 · Build, test and run results

- **Toolchain found:** macOS (Darwin 25.5), JDK 17 (Temurin 17.0.19, JetBrains 17.0.14), Android SDK at ~/Library/Android/sdk (ANDROID_HOME not set, no local.properties — ANDROID_HOME was passed on the command line only), Gradle wrapper 9.3.1, AGP 9.1.1, Kotlin 2.2.10, Compose BOM 2025.05.00, compileSdk/targetSdk 36, minSdk 28.
- **./gradlew:** The wrapper script is not executable in this checkout (permission denied); it was run as `bash ./gradlew` instead of changing file modes.
- **Unit tests (:app:testDebugUnitTest):** 61 tests in 16 classes: 59 passed, 2 failed. Both failures are environmental: the Robolectric suites ProductionValidationSuiteTest and CapturedInspectionsMigrationAndOfflineFlowTest stop with 'Android SDK 36 requires Java 21 (have Java 17)'. No JDK 21 is installed, and none was installed.
- **Lint (:app:lintDebug):** Passed: 0 errors, 7 warnings (UseKtx, UseTomlInstead, ModifierParameter). Report: app/build/reports/lint-results-debug.html.
- **Debug build (:app:assembleDebug):** Kotlin compiles. Packaging fails at validateSigningDebug because the build expects debug.keystore in the project root (gitignored, absent). A test APK was produced without creating any signing file, by passing Android Studio's injected-signing properties pointing at the existing SDK key ~/.android/debug.keystore → app/build/outputs/apk/debug/app-debug.apk (49.9 MB, com.safestitch.inspection.debug).
- **Instrumented tests (:app:connectedDebugAndroidTest):** The Gradle task skipped the emulator ('Unknown API Level' — device detection in the Android Gradle plugin), so the APKs it built were run directly with `adb shell am instrument -w com.safestitch.inspection.debug.test/androidx.test.runner.AndroidJUnitRunner`: OK (11 tests) — CorrectiveActionFlowTest, InspectionRepositoryTest, MigrationTest, ReportPdfWriterTest.
- **Release bundle:** Not built: it needs the private upload key, which is not present. Out of scope and not attempted.
- **Emulator walkthrough:** Scripted with adb + uiautomator against the debug APK on a read-only emulator (changes discarded at shutdown). Both roles were exercised: first-admin setup, users, the full 4-level location tree, checklist editor, SQL inspector, sync screen, equipment create/edit/print labels, a full inspection with a failed item, item photo, PDF report and in-app viewer, the complete corrective-action lifecycle, history, captured photos, scan with known and unknown codes, chat, settings, sign-out and sign-in errors, inspector restrictions, and session restore. Run in 6 parts (the scripted run was resumed from the current state after harness stops instead of resetting data); every planned check was eventually executed.
- **Upgrade over the earlier build:** The AVD already had an older build of the debug app with data. Installing the current build over it crashed on launch: 'Room cannot verify the data integrity… Expected identity hash b3f741…, found 5de8d7…'. App data was then cleared inside the throw-away emulator session only.
- **Arrow audit (this revision):** Before correction: master 50 arrows / 260 across 11 diagrams in the first draft of this run (the earlier static run had 38 / 113). The independent extractor then found every interactive operation in the source; 59 had no arrow (dialog outcomes, stack-dependent Back, permission results, Account-menu wiring that was attributed to the wrong node). 45 transitions were added to the model (125 → 170) and the extractor now matches every operation it finds to a drawn edge.

## 2 · Route ↔ composable ↔ screen ↔ diagram

Route classes in `Routes.kt`: **21** · `composable<…>` registrations: **21** · routes without a registration: **0** · registrations without a route class: **0**.

`MainActivity` branches on: Loading, NeedsSetup, SignedOut, SignedIn, SignedIn → S00 (Loading), S01 (NeedsSetup), S02 (SignedOut), NavHost/S03 (SignedIn).

| Route class | NavHost line | Screen | Diagrams (by number) |
|---|---|---|---|
| `DashboardRoute` | [93](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L93) | S03 | 01, 02, 03, 09, 10, 11 |
| `InspectRoute` | [111](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L111) | S04 | 01, 04, 10, 11 |
| `ActionsRoute` | [121](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L121) | S05 | 01, 06, 10, 11 |
| `HistoryRoute` | [124](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L124) | S06 | 01, 07, 10, 11 |
| `CapturedInspectionsRoute` | [134](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L134) | S07 | 01, 07, 10, 11 |
| `CapturedInspectionDetailRoute` | [141](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L141) | S08 | 01, 07, 10, 11 |
| `ScanRoute` | [149](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L149) | S09 | 01, 04, 10, 11 |
| `CheckpointFormRoute` | [158](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L158) | S10 | 01, 04, 10, 11 |
| `InspectionRoute` | [170](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L170) | S11 | 01, 04, 10, 11 |
| `PhotoCaptureRoute` | [181](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L181) | S12 | 01, 04, 06, 10, 11 |
| `ReportRoute` | [191](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L191) | S13 | 01, 05, 10, 11 |
| `PdfViewerRoute` | [201](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L201) | S14 | 01, 05, 10, 11 |
| `ActionDetailRoute` | [204](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L204) | S15 | 01, 06, 10, 11 |
| `SettingsRoute` | [214](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L214) | S16 | 01, 02, 10, 11 |
| `AdminRoute` | [217](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L217) | S18 | 01, 08, 10 |
| `UsersRoute` | [228](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L228) | S19 | 01, 08, 10 |
| `LocationsRoute` | [229](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L229) | S20 | 01, 08, 10 |
| `ChecklistEditorRoute` | [241](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L241) | S21 | 01, 08, 10 |
| `SyncRoute` | [242](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L242) | S22 | 01, 08, 10 |
| `SqlDatabaseRoute` | [243](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L243) | S23 | 01, 08, 10 |
| `GeminiChatRoute` | [244](../../app/src/main/java/com/safestitch/app/ui/navigation/SafeStitchNavHost.kt#L244) | S17 | 01, 09, 10, 11 |

## 3 · NavHost navigation call sites

54 lines in `SafeStitchNavHost.kt` call `nav.navigate(…)` or `nav.popBackStack()`. Plain `onBack = { nav.popBackStack() }` lines are covered by the diagram convention (← returns to the previous screen). Uncovered call sites: **0**.

| Line | Call | Matrix rows citing this line |
|---|---|---|
| 65 | `fun openInspection(id: Long, completed: Boolean) = nav.navigate(if (completed) ReportRoute(id) ` | S03→S13 recent (submitted) |
| 77 | `nav.navigate(tab.route) {` | TABS→S03 Home tab; TABS→S04 Inspect tab; TABS→S05 Actions tab; TABS→S06 History tab |
| 97 | `onScan = { nav.navigate(ScanRoute) },` | S03→S09 Scan for Checklist |
| 98 | `onFindEquipment = { nav.navigate(InspectRoute) { launchSingleTop = true } },` | S03→S04 Find equipment |
| 99 | `onOpenActions = { nav.navigate(ActionsRoute) { launchSingleTop = true } },` | S03→S05 KPI cards |
| 100 | `onOpenCapturedInspections = { nav.navigate(CapturedInspectionsRoute) },` | S03→S07 View all captured |
| 102 | `onOpenCapturedInspectionDetail = { id -> nav.navigate(CapturedInspectionDetailRoute(id)) },` | S03→S08 recent photo |
| 104 | `nav.navigate(HistoryRoute(equipmentCode = code))` | S03→S06 code scanned |
| 106 | `onOpenChat = { nav.navigate(GeminiChatRoute) },` | D01→S17 Chat |
| 107 | `onAdmin = { nav.navigate(AdminRoute) },` | D01→S18 Admin tools |
| 108 | `onSettings = { nav.navigate(SettingsRoute) },` | D01→S16 Settings |
| 115 | `onScan = { nav.navigate(ScanRoute) },` | S04→S09 Scan QR |
| 116 | `onInspectionReady = { nav.navigate(InspectionRoute(it)) },` | S04→S11 Inspect |
| 117 | `onAddEquipment = { nav.navigate(CheckpointFormRoute()) },` | S04→S10 + Add |
| 118 | `onEditEquipment = { nav.navigate(CheckpointFormRoute(checkpointId = it)) },` | S04→S10 Edit |
| 122 | `ActionsScreen(container = container, onOpen = { nav.navigate(ActionDetailRoute(it)) })` | S05→S15 open action |
| 129 | `onOpenCapturedInspections = { nav.navigate(CapturedInspectionsRoute) },` | S06→S07 Captured (n) |
| 130 | `onOpenCapturedInspectionDetail = { id -> nav.navigate(CapturedInspectionDetailRoute(id)) },` | S06→S08 photo card |
| 137 | `onBack = { nav.popBackStack() },` | generic ← Back (convention) |
| 138 | `onOpenInspection = { id -> nav.navigate(CapturedInspectionDetailRoute(id)) },` | S07→S08 open photo |
| 146 | `onBack = { nav.popBackStack() },` | generic ← Back (convention) |
| 153 | `onBack = { nav.popBackStack() },` | generic ← Back (convention) |
| 154 | `onInspectionReady = { id -> nav.navigate(InspectionRoute(id)) { popUpTo<ScanRoute> { inclusive ` | S09→S11 known code |
| 155 | `onNewCheckpoint = { code -> nav.navigate(CheckpointFormRoute(code = code)) { popUpTo<ScanRoute>` | S09→S10 unknown code |
| 165 | `onBack = { nav.popBackStack() },` | generic ← Back (convention) |
| 166 | `onSaved = { nav.popBackStack() },` | S10→S04 Save ⇒ back |
| 167 | `onInspectionReady = { id -> nav.navigate(InspectionRoute(id)) { popUpTo<CheckpointFormRoute> { ` | S10→S11 Save & start |
| 175 | `onBack = { nav.popBackStack() },` | generic ← Back (convention) |
| 176 | `onTakePhoto = { itemId -> nav.navigate(PhotoCaptureRoute(route.inspectionId, itemId ?: -1L)) },` | S11→S12 Add photo |
| 177 | `onCompleted = { nav.navigate(ReportRoute(route.inspectionId)) { popUpTo<InspectionRoute> { incl` | S11→S13 Submit & create PDF |
| 178 | `onDeleted = { nav.popBackStack() },` | D04→BACK Delete ⇒ back |
| 188 | `onDone = { nav.popBackStack() },` | S12→S11 saved ⇒ back |
| 196 | `onBack = { nav.popBackStack() },` | generic ← Back (convention) |
| 197 | `onViewPdf = { nav.navigate(PdfViewerRoute(route.inspectionId)) },` | S13→S14 Open PDF |
| 198 | `onOpenAction = { nav.navigate(ActionDetailRoute(it)) },` | S13→S15 action card |
| 202 | `PdfViewerScreen(container = container, inspectionId = entry.toRoute<PdfViewerRoute>().inspectio` | generic ← Back (convention) |
| 209 | `onBack = { nav.popBackStack() },` | generic ← Back (convention) |
| 210 | `onOpenInspection = { nav.navigate(ReportRoute(it)) },` | S15→S13 Open inspection report |
| 211 | `onAddAfterFixPhoto = { inspectionId, actionId -> nav.navigate(PhotoCaptureRoute(inspectionId, a` | S15→S12 after-fix photo |
| 215 | `SettingsScreen(container = container, user = user, onBack = { nav.popBackStack() })` | generic ← Back (convention) |
| 219 | `onBack = { nav.popBackStack() },` | generic ← Back (convention) |
| 220 | `onUsers = { nav.navigate(UsersRoute) },` | S18→S19 Users |
| 221 | `onLocations = { nav.navigate(LocationsRoute()) },` | S18→S20 Locations |
| 222 | `onChecklists = { nav.navigate(ChecklistEditorRoute) },` | S18→S21 Checklist questions |
| 223 | `onSync = { nav.navigate(SyncRoute) },` | S18→S22 Sync |
| 224 | `onEquipment = { nav.navigate(InspectRoute) { launchSingleTop = true } },` | S18→S04 Equipment |
| 225 | `onSqlDatabase = { nav.navigate(SqlDatabaseRoute) },` | S18→S23 SQL Database |
| 228 | `composable<UsersRoute> { UsersScreen(container = container, admin = user, onBack = { nav.popBac` | generic ← Back (convention) |
| 237 | `onBack = { nav.popBackStack() },` | generic ← Back (convention) |
| 238 | `onOpenChild = { childLevel, nodeId, nodeName -> nav.navigate(LocationsRoute(childLevel.ordinal,` | S20→S20 row › next level |
| 241 | `composable<ChecklistEditorRoute> { ChecklistEditorScreen(container = container, onBack = { nav.` | generic ← Back (convention) |
| 242 | `composable<SyncRoute> { SyncScreen(container = container, onBack = { nav.popBackStack() }) }` | generic ← Back (convention) |
| 243 | `composable<SqlDatabaseRoute> { SqlDatabaseScreen(container = container, onBack = { nav.popBackS` | generic ← Back (convention) |
| 244 | `composable<GeminiChatRoute> { GeminiChatScreen(container = container, onBack = { nav.popBackSta` | generic ← Back (convention) |

## 4 · Navigation callbacks declared by screen composables

54 `on…` callback parameters. Declared but never passed by the NavHost (stay default no-ops): **0**. Wired but not cited by any matrix row: **0**.

| Screen | Callback | Passed by NavHost | Cited in matrix |
|---|---|---|---|
| S03 | `onScan` | yes | yes |
| S03 | `onFindEquipment` | yes | yes |
| S03 | `onOpenActions` | yes | yes |
| S03 | `onAdmin` | yes | yes |
| S03 | `onSettings` | yes | yes |
| S03 | `onOpenCapturedInspections` | yes | yes |
| S03 | `onOpenChecklistInspection` | yes | yes |
| S03 | `onOpenCapturedInspectionDetail` | yes | yes |
| S03 | `onNavigateToEquipmentHistory` | yes | yes |
| S03 | `onOpenChat` | yes | yes |
| S04 | `onScan` | yes | yes |
| S04 | `onInspectionReady` | yes | yes |
| S04 | `onAddEquipment` | yes | yes |
| S04 | `onEditEquipment` | yes | yes |
| S05 | `onOpen` | yes | yes |
| S06 | `onOpen` | yes | yes |
| S06 | `onOpenCapturedInspections` | yes | yes |
| S06 | `onOpenCapturedInspectionDetail` | yes | yes |
| S07 | `onBack` | yes | yes |
| S07 | `onOpenInspection` | yes | yes |
| S08 | `onBack` | yes | yes |
| S09 | `onBack` | yes | yes |
| S09 | `onInspectionReady` | yes | yes |
| S09 | `onNewCheckpoint` | yes | yes |
| S10 | `onBack` | yes | yes |
| S10 | `onSaved` | yes | yes |
| S10 | `onInspectionReady` | yes | yes |
| S11 | `onBack` | yes | yes |
| S11 | `onTakePhoto` | yes | yes |
| S11 | `onCompleted` | yes | yes |
| S11 | `onDeleted` | yes | yes |
| S12 | `onDone` | yes | yes |
| S13 | `onBack` | yes | yes |
| S13 | `onViewPdf` | yes | yes |
| S13 | `onOpenAction` | yes | yes |
| S14 | `onBack` | yes | yes |
| S15 | `onBack` | yes | yes |
| S15 | `onOpenInspection` | yes | yes |
| S15 | `onAddAfterFixPhoto` | yes | yes |
| S16 | `onBack` | yes | yes |
| S17 | `onBack` | yes | yes |
| S18 | `onBack` | yes | yes |
| S18 | `onUsers` | yes | yes |
| S18 | `onLocations` | yes | yes |
| S18 | `onChecklists` | yes | yes |
| S18 | `onSync` | yes | yes |
| S18 | `onEquipment` | yes | yes |
| S18 | `onSqlDatabase` | yes | yes |
| S19 | `onBack` | yes | yes |
| S20 | `onBack` | yes | yes |
| S20 | `onOpenChild` | yes | yes |
| S21 | `onBack` | yes | yes |
| S22 | `onBack` | yes | yes |
| S23 | `onBack` | yes | yes |

## 5 · Dialogs, date pickers and dropdown menus in `ui/`

15 occurrences; not matched to a pop-up node: **0**.

| File:line | Code | Pop-up node |
|---|---|---|
| [ui/settings/SettingsScreen.kt:84](../../app/src/main/java/com/safestitch/app/ui/settings/SettingsScreen.kt#L84) | `AlertDialog(` | D13 |
| [ui/inspect/FindEquipmentScreen.kt:238](../../app/src/main/java/com/safestitch/app/ui/inspect/FindEquipmentScreen.kt#L238) | `AlertDialog(` | D02 |
| [ui/admin/AdminScreens.kt:182](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L182) | `AlertDialog(` | D08 |
| [ui/admin/AdminScreens.kt:239](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L239) | `AlertDialog(` | D09 |
| [ui/admin/AdminScreens.kt:385](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L385) | `AlertDialog(` | D12 |
| [ui/admin/ChecklistEditorScreen.kt:131](../../app/src/main/java/com/safestitch/app/ui/admin/ChecklistEditorScreen.kt#L131) | `AlertDialog(` | D11 |
| [ui/admin/LocationsScreen.kt:119](../../app/src/main/java/com/safestitch/app/ui/admin/LocationsScreen.kt#L119) | `AlertDialog(` | D10 |
| [ui/inspection/InspectionScreen.kt:173](../../app/src/main/java/com/safestitch/app/ui/inspection/InspectionScreen.kt#L173) | `AlertDialog(` | D03 |
| [ui/inspection/InspectionScreen.kt:181](../../app/src/main/java/com/safestitch/app/ui/inspection/InspectionScreen.kt#L181) | `AlertDialog(` | D03, D04 |
| [ui/inspection/InspectionScreen.kt:190](../../app/src/main/java/com/safestitch/app/ui/inspection/InspectionScreen.kt#L190) | `AlertDialog(` | D04, D05 |
| [ui/dashboard/DashboardScreen.kt:359](../../app/src/main/java/com/safestitch/app/ui/dashboard/DashboardScreen.kt#L359) | `DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = fals` | D01 |
| [ui/components/Pickers.kt:85](../../app/src/main/java/com/safestitch/app/ui/components/Pickers.kt#L85) | `DropdownMenu(expanded = expanded, onDismissRequest = { expanded = fals` | D15 |
| [ui/components/Pickers.kt:222](../../app/src/main/java/com/safestitch/app/ui/components/Pickers.kt#L222) | `DatePickerDialog(` | D14 |
| [ui/history/InspectionDetailsScreen.kt:872](../../app/src/main/java/com/safestitch/app/ui/history/InspectionDetailsScreen.kt#L872) | `AlertDialog(` | D07 |
| [ui/actions/ActionsScreens.kt:384](../../app/src/main/java/com/safestitch/app/ui/actions/ActionsScreens.kt#L384) | `AlertDialog(` | D06 |

## 6 · `…Screen` composables

| Composable | File | Drawn | Note |
|---|---|---|---|
| `SettingsScreen` | `ui/settings/SettingsScreen.kt` | yes |  |
| `CheckpointFormScreen` | `ui/checkpoint/CheckpointFormScreen.kt` | yes |  |
| `FindEquipmentScreen` | `ui/inspect/FindEquipmentScreen.kt` | yes |  |
| `ChatScreen` | `ui/chat/GeminiChatScreen.kt` | no | DECLARED BUT UNREACHABLE (no callers) |
| `GeminiChatScreen` | `ui/chat/GeminiChatScreen.kt` | yes |  |
| `SetupAdminScreen` | `ui/auth/AuthScreens.kt` | yes |  |
| `LoginScreen` | `ui/auth/AuthScreens.kt` | yes |  |
| `PhotoCaptureScreen` | `ui/camera/PhotoCaptureScreen.kt` | yes |  |
| `AdminScreen` | `ui/admin/AdminScreens.kt` | yes |  |
| `UsersScreen` | `ui/admin/AdminScreens.kt` | yes |  |
| `SyncScreen` | `ui/admin/AdminScreens.kt` | yes |  |
| `SqlDatabaseScreen` | `ui/admin/AdminScreens.kt` | yes |  |
| `ChecklistEditorScreen` | `ui/admin/ChecklistEditorScreen.kt` | yes |  |
| `LocationsScreen` | `ui/admin/LocationsScreen.kt` | yes |  |
| `ScanScreen` | `ui/scan/ScanScreen.kt` | yes |  |
| `InspectionScreen` | `ui/inspection/InspectionScreen.kt` | yes |  |
| `DashboardScreen` | `ui/dashboard/DashboardScreen.kt` | yes |  |
| `CapturedInspectionsScreen` | `ui/history/CapturedInspectionsScreen.kt` | yes |  |
| `InspectionListScreen` | `ui/history/CapturedInspectionsScreen.kt` | no | DECLARED BUT UNREACHABLE (no callers) |
| `HistoryScreen` | `ui/history/HistoryScreen.kt` | yes |  |
| `InspectionDetailsScreen` | `ui/history/InspectionDetailsScreen.kt` | yes |  |
| `CapturedInspectionDetailsScreen` | `ui/history/InspectionDetailsScreen.kt` | no | DECLARED BUT UNREACHABLE (no callers) |
| `ActionsScreen` | `ui/actions/ActionsScreens.kt` | yes |  |
| `ActionDetailScreen` | `ui/actions/ActionsScreens.kt` | yes |  |
| `PdfViewerScreen` | `ui/report/PdfViewerScreen.kt` | yes |  |
| `ReportScreen` | `ui/report/ReportScreen.kt` | yes |  |

## 7 · Transitions not drawn in any diagram

- None. Every matrix row appears as an arrow, a pop-up tray entry, the bottom-tab container, a findings note or a module state note.

## 8 · Emulator walkthrough checks

Device: SafeStitch_Disposable_API36 AVD (Pixel 6 profile, Android 16 / API 36, Google Play arm64 image), started with -read-only -no-snapshot so nothing is written back to the AVD; debug APK installed; CAMERA granted via adb; RECORD_AUDIO deliberately left ungranted because the app never requests it.

Each check waits for the destination's Compose test tag (exposed as a resource-id) or a dialog title, then saves a screenshot. Screenshots stay in the session scratchpad and are not committed.

| Check | Result | Role | Detail |
|---|---|---|---|
| `APP>S01:no-users` | PASS | All | reached S01 |
| `S01:password-mismatch` | PASS | All | inline error 'The two passwords do not match.' |
| `S01>S03:create-admin` | PASS | Admin | reached S03 |
| `S03>D01:account-menu` | PASS | Admin | reached D01 |
| `D01:admin-tools-visible-for-admin` | PASS | Admin | Admin tools item present |
| `D01>S18:admin-tools` | PASS | Admin | reached S18 |
| `S18>S19:users` | PASS | Admin | reached S19 |
| `S19>D08:add-user` | PASS | Admin | reached D08 |
| `D08>S19:add-user-save` | PASS | Admin | inspector row appears |
| `S19>D09:reset-password` | PASS | Admin | reached D09 |
| `S18>S20:locations` | PASS | Admin | reached S20 |
| `S20>D10:add-location-L0` | PASS | Admin | reached D10 |
| `S20>S20:open-child-L1` | PASS | Admin | reached S20 |
| `S20>D10:add-location-L1` | PASS | Admin | reached D10 |
| `S20>S20:open-child-L2` | PASS | Admin | reached S20 |
| `S20>D10:add-location-L2` | PASS | Admin | reached D10 |
| `S20>S20:open-child-L3` | PASS | Admin | reached S20 |
| `S20>D10:add-location-L3` | PASS | Admin | reached D10 |
| `S18>S21:checklists` | PASS | Admin | reached S21 |
| `S21>D11:add-question` | PASS | Admin | reached D11 |
| `S18>S23:sql-database` | PASS | Admin | reached S23 |
| `S18>S22:sync` | PASS | Admin | reached S22 |
| `S22:sync-now-disabled-when-off` | PASS | Admin | 'Sync now' disabled while sync is off |
| `S18>S04:equipment` | PASS | Admin | reached S04 |
| `S04:admin-controls-visible` | PASS | Admin | FAB + and print-labels visible for admin |
| `S04>S10:add-equipment` | PASS | Admin | reached S10 |
| `HARNESS:part1-stopped-at-equipment-save` | **FAIL** |  | Test harness: a location DropdownMenu popup was still open, so uiautomator could not see the form's resource-ids while scrolling to 'Save equipment'. The form itself was  |
| `S10>S04:save-equipment` | PASS | Admin | returns to Inspect list with FE-12 |
| `S04>D02:print-labels` | PASS | Admin | reached D02 |
| `S04>S10:edit-equipment` | PASS | Admin | reached S10 |
| `S04>S11:inspect` | PASS | Admin | reached S11 |
| `S11>D03:submit-incomplete` | PASS | Admin | reached D03 |
| `S11>D14:due-date-picker` | PASS | Admin | date picker dialog opens |
| `S11>D15:responsible-select` | PASS |  | dropdown lists 2 people |
| `S11>S12:item-photo` | PASS | Admin | reached S12 |
| `S12>S11:shutter-saved` | PASS | Admin | reached S11 |
| `S11:photo-attached` | PASS | Admin | item photo button reads ' Photo (1) ' |
| `S11>S13:submit` | PASS | Admin | reached S13 |
| `S13>S14:open-pdf` | PASS | Admin | reached S14 |
| `S13>S15:open-action` | PASS | Admin | reached S15 |
| `S15:status-open` | PASS | Admin | action starts Open with 'Start work' |
| `S15:start-work` | PASS | Admin | status -> In progress |
| `S15>S12:after-fix-photo` | PASS | Admin | reached S12 |
| `S12>S15:shutter-saved` | PASS | Admin | reached S15 |
| `S15:resolve` | PASS | Admin | status -> Resolved; admin sees Verify |
| `S15>D06:send-back` | PASS | Admin | reached D06 |
| `S15:verify` | PASS | Admin | status -> Verified |
| `S15>S13:open-inspection-report` | PASS | Admin | reached S13 |
| `TAB>S05:tab-actions` | PASS | Admin | reached S05 |
| `HARNESS:S05>S15:open-action-empty-filter` | **FAIL** | Admin | Missing test data: the only corrective action had just been verified, so the default 'To do' filter was empty. Re-run with the 'All' filter in part 3: PASS. |
| `HARNESS:part2-back-from-actions-tab` | **FAIL** |  | Test harness expectation: System Back on a bottom-tab screen returns Home (correct app behaviour); the script expected to stay on Actions. |
| `TAB>S05:tab-actions#2` | PASS | Admin | reached S05 |
| `S05>S15:open-action` | PASS | Admin | reached S15 |
| `TAB>S06:tab-history` | PASS | Admin | reached S06 |
| `S06>S13:open-completed` | PASS | Admin | reached S13 |
| `S06>S07:captured-button` | PASS | Admin | reached S07 |
| `S07>S08:open-captured` | PASS | Admin | reached S08 |
| `S08>D07:delete` | PASS | Admin | reached D07 |
| `S06>S08:captured-strip-item` | PASS | Admin | reached S08 |
| `TAB>S03:tab-home` | PASS | Admin | reached S03 |
| `TAB>S04:tab-inspect` | PASS | Admin | reached S04 |
| `S03>S06:manual-code-history` | PASS | Admin | reached S06 |
| `S06:scanned-equipment-banner` | PASS | Admin | banner 'Equipment Identified via QR / Barcode' |
| `S03>S07:view-captured` | PASS | Admin | reached S07 |
| `S03>S06:quick-scan-chip` | PASS | Admin | reached S06 |
| `S03:voice-note` | PASS |  | transcript shown although RECORD_AUDIO granted=false; text: 'Field safety voice note: checked equipment FE-101 pressure gauge in green zone, verified electrical panel EL- |
| `S03>S05:kpi-card` | PASS | Admin | reached S05 |
| `BUG:home-tab-after-kpi-card` | **FAIL** |  | APP PROBLEM (reproduced 3×): after Home › KPI card opens Corrective actions, tapping the Home tab keeps showing Corrective actions — also from the History tab. NavHost on |
| `S03>S09:scan-for-checklist` | PASS | Admin | reached S09 |
| `S09>S10:unknown-code` | PASS | Admin | reached S10 |
| `S10:back-skips-scan` | PASS | Admin | Back from scanned-code form returns to Home (Scan was popped) |
| `S03>S09:scan-for-checklist#2` | PASS | Admin | reached S09 |
| `S09>S11:known-code` | PASS | Admin | reached S11 |
| `S11>D04:delete` | PASS | Admin | reached D04 |
| `D04>BACK:delete-confirm` | PASS | Admin | reached S03 |
| `S03>S04:find-equipment` | PASS | Admin | reached S04 |
| `S04>S09:scan-button` | PASS | Admin | reached S09 |
| `BUG:home-tab-after-find-equipment` | **FAIL** | Admin | On Inspect opened via Home › Find equipment, tapping the Home tab kept showing Inspect (NavHost onFindEquipment navigates without popUpTo; Home's saved back stack is rest |
| `S03>S17:dashboard-chat-button` | PASS | Admin | reached S17 |
| `S17:send-quick-prompt` | PASS |  | 3 visible message bubbles after quick prompt (no server on 10.0.2.2:3000 -> local fallback reply) |
| `D01>S17:menu-chat` | PASS | Admin | reached S17 |
| `D01>S16:settings` | PASS | Admin | reached S16 |
| `S16>D13:change-password` | PASS | Admin | reached D13 |
| `S16>S02:sign-out` | PASS | All | reached S02 |
| `S02:wrong-password` | PASS | All | 'Wrong username or password.' |
| `S02>S03:sign-in` | PASS | Inspector | reached S03 |
| `S03>D01:account-menu#inspector` | PASS | Inspector | reached D01 |
| `D01:admin-tools-hidden-for-inspector` | PASS | Inspector | no 'Admin tools' item |
| `TAB>S04:tab-inspect#inspector` | PASS | Inspector | reached S04 |
| `S04:admin-controls-hidden-for-inspector` | PASS | Inspector | no FAB, no print-labels, no Edit for inspector |
| `S04>S11:inspect#inspector` | PASS | Inspector | reached S11 |
| `S11>S13:submit#inspector` | PASS | Inspector | reached S13 |
| `S13>S15:open-action#inspector` | PASS | Inspector | reached S15 |
| `S15:verify-locked-for-inspector` | PASS | Inspector | 'Only an admin can verify this fix.' |
| `S03>S09:scan#inspector` | PASS | Inspector | reached S09 |
| `S09>S10:unknown-code#inspector` | PASS | Inspector | reached S10 |
| `HARNESS:part5-scroll-to-save-with-keyboard` | **FAIL** |  | Test harness: the on-screen keyboard still covered the inspector's 'New equipment' form while scrolling to Save. Re-run in part 6 after hiding the keyboard: PASS. |
| `S10>S11:save-and-start#inspector` | PASS | Inspector | inspector registered new equipment and its inspection opened |
| `APP>S03:session-restore` | PASS | Inspector | remembered user lands on Home |
| `D01>S02:sign-out` | PASS | All | reached S02 |
| `APP>S02:signed-out` | PASS | All | signed-out launch opens Sign in |

## 9 · Generated file validation

| Diagram | .drawio | .svg | .pdf | .png | Arrows | Layout warnings |
|---|---|---|---|---|---|---|
| 01-Full-Application-Wireflow | ✅ valid XML · 1563 shapes · 67 connectors · 0 dangling | ✅ valid XML · 103 groups · 146 paths | ✅ 1633 KB · 1 page · 47.0×43.6 in | ✅ 4510×4186 px · 1437 KB | 67 | 0 |
| 02-Module-Authentication-and-Session | ✅ valid XML · 319 shapes · 21 connectors · 0 dangling | ✅ valid XML · 19 groups · 54 paths | ✅ 572 KB · 1 page · 49.0×24.7 in | ✅ 7050×3554 px · 632 KB | 21 | 0 |
| 03-Module-Home-Dashboard | ✅ valid XML · 267 shapes · 25 connectors · 0 dangling | ✅ valid XML · 24 groups · 62 paths | ✅ 515 KB · 1 page · 32.5×17.7 in | ✅ 4680×2550 px · 528 KB | 25 | 0 |
| 04-Module-Equipment-Scan-and-Inspection | ✅ valid XML · 405 shapes · 52 connectors · 0 dangling | ✅ valid XML · 37 groups · 116 paths | ✅ 725 KB · 1 page · 49.0×13.5 in | ✅ 7050×1943 px · 687 KB | 52 | 0 |
| 05-Module-Inspection-Report-and-PDF | ✅ valid XML · 158 shapes · 13 connectors · 0 dangling | ✅ valid XML · 15 groups · 38 paths | ✅ 321 KB · 1 page · 24.3×12.7 in | ✅ 3495×1824 px · 294 KB | 13 | 0 |
| 06-Module-Corrective-Actions | ✅ valid XML · 263 shapes · 20 connectors · 0 dangling | ✅ valid XML · 19 groups · 52 paths | ✅ 525 KB · 1 page · 32.5×17.2 in | ✅ 4680×2483 px · 538 KB | 20 | 0 |
| 07-Module-History-and-Captured-Photos | ✅ valid XML · 273 shapes · 22 connectors · 0 dangling | ✅ valid XML · 18 groups · 56 paths | ✅ 570 KB · 1 page · 32.5×13.8 in | ✅ 4680×1982 px · 510 KB | 22 | 0 |
| 08-Module-Admin-Tools | ✅ valid XML · 329 shapes · 30 connectors · 0 dangling | ✅ valid XML · 25 groups · 72 paths | ✅ 544 KB · 1 page · 40.7×19.7 in | ✅ 5865×2843 px · 581 KB | 30 | 0 |
| 09-Module-Safety-Assistant-and-Voice-Notes | ✅ valid XML · 245 shapes · 8 connectors · 0 dangling | ✅ valid XML · 12 groups · 28 paths | ✅ 481 KB · 1 page · 24.3×17.7 in | ✅ 3495×2550 px · 448 KB | 8 | 0 |
| 10-Role-Admin-User-Flow | ✅ valid XML · 1615 shapes · 67 connectors · 0 dangling | ✅ valid XML · 104 groups · 146 paths | ✅ 1691 KB · 1 page · 47.0×43.6 in | ✅ 4510×4186 px · 1503 KB | 67 | 0 |
| 11-Role-Inspector-User-Flow | ✅ valid XML · 1198 shapes · 49 connectors · 0 dangling | ✅ valid XML · 76 groups · 110 paths | ✅ 1392 KB · 1 page · 47.0×42.3 in | ✅ 4510×4060 px · 1153 KB | 49 | 0 |

## 9b · Automated geometry check per diagram

Checks: arrow starts/ends on its own node, arrowhead segment ≥ 8 px, no arrow through another card or phone, no collinear overlap between different arrows, no label over a card or another label, no overlapping cards, nothing outside the page.

| Diagram | Problems | Details |
|---|---|---|
| 01-Full-Application-Wireflow | 0 | — |
| 02-Module-Authentication-and-Session | 0 | — |
| 03-Module-Home-Dashboard | 0 | — |
| 04-Module-Equipment-Scan-and-Inspection | 0 | — |
| 05-Module-Inspection-Report-and-PDF | 0 | — |
| 06-Module-Corrective-Actions | 0 | — |
| 07-Module-History-and-Captured-Photos | 0 | — |
| 08-Module-Admin-Tools | 0 | — |
| 09-Module-Safety-Assistant-and-Voice-Notes | 0 | — |
| 10-Role-Admin-User-Flow | 0 | — |
| 11-Role-Inspector-User-Flow | 0 | — |

## 10 · Screen ↔ source ↔ diagrams

| Screen | Source | Diagrams |
|---|---|---|
| S00 App launch / session check | [MainActivity.kt:38](../../app/src/main/java/com/safestitch/app/MainActivity.kt#L38) | 01-Full-Application-Wireflow, 02-Module-Authentication-and-Session, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S01 Set up SafeStitch (first admin) | [AuthScreens.kt:42](../../app/src/main/java/com/safestitch/app/ui/auth/AuthScreens.kt#L42) | 01-Full-Application-Wireflow, 02-Module-Authentication-and-Session, 10-Role-Admin-User-Flow |
| S02 Sign in | [AuthScreens.kt:88](../../app/src/main/java/com/safestitch/app/ui/auth/AuthScreens.kt#L88) | 01-Full-Application-Wireflow, 02-Module-Authentication-and-Session, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S03 Home dashboard | [DashboardScreen.kt:233](../../app/src/main/java/com/safestitch/app/ui/dashboard/DashboardScreen.kt#L233) | 01-Full-Application-Wireflow, 02-Module-Authentication-and-Session, 03-Module-Home-Dashboard, 09-Module-Safety-Assistant-and-Voice-Notes, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S04 Inspect — find equipment | [FindEquipmentScreen.kt:117](../../app/src/main/java/com/safestitch/app/ui/inspect/FindEquipmentScreen.kt#L117) | 01-Full-Application-Wireflow, 04-Module-Equipment-Scan-and-Inspection, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S05 Corrective actions | [ActionsScreens.kt:111](../../app/src/main/java/com/safestitch/app/ui/actions/ActionsScreens.kt#L111) | 01-Full-Application-Wireflow, 06-Module-Corrective-Actions, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S06 Inspection history | [HistoryScreen.kt:114](../../app/src/main/java/com/safestitch/app/ui/history/HistoryScreen.kt#L114) | 01-Full-Application-Wireflow, 07-Module-History-and-Captured-Photos, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S07 Captured inspections (photo list) | [CapturedInspectionsScreen.kt:100](../../app/src/main/java/com/safestitch/app/ui/history/CapturedInspectionsScreen.kt#L100) | 01-Full-Application-Wireflow, 07-Module-History-and-Captured-Photos, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S08 Captured inspection details | [InspectionDetailsScreen.kt:295](../../app/src/main/java/com/safestitch/app/ui/history/InspectionDetailsScreen.kt#L295) | 01-Full-Application-Wireflow, 07-Module-History-and-Captured-Photos, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S09 Scan checkpoint | [ScanScreen.kt:149](../../app/src/main/java/com/safestitch/app/ui/scan/ScanScreen.kt#L149) | 01-Full-Application-Wireflow, 04-Module-Equipment-Scan-and-Inspection, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S10 New / edit equipment | [CheckpointFormScreen.kt:143](../../app/src/main/java/com/safestitch/app/ui/checkpoint/CheckpointFormScreen.kt#L143) | 01-Full-Application-Wireflow, 04-Module-Equipment-Scan-and-Inspection, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S11 Inspection checklist | [InspectionScreen.kt:81](../../app/src/main/java/com/safestitch/app/ui/inspection/InspectionScreen.kt#L81) | 01-Full-Application-Wireflow, 04-Module-Equipment-Scan-and-Inspection, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S12 Take photo | [PhotoCaptureScreen.kt:97](../../app/src/main/java/com/safestitch/app/ui/camera/PhotoCaptureScreen.kt#L97) | 01-Full-Application-Wireflow, 04-Module-Equipment-Scan-and-Inspection, 06-Module-Corrective-Actions, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S13 Inspection report | [ReportScreen.kt:153](../../app/src/main/java/com/safestitch/app/ui/report/ReportScreen.kt#L153) | 01-Full-Application-Wireflow, 05-Module-Inspection-Report-and-PDF, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S14 PDF viewer (in-app) | [PdfViewerScreen.kt:47](../../app/src/main/java/com/safestitch/app/ui/report/PdfViewerScreen.kt#L47) | 01-Full-Application-Wireflow, 05-Module-Inspection-Report-and-PDF, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S15 Corrective action detail | [ActionsScreens.kt:224](../../app/src/main/java/com/safestitch/app/ui/actions/ActionsScreens.kt#L224) | 01-Full-Application-Wireflow, 06-Module-Corrective-Actions, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S16 Settings | [SettingsScreen.kt:42](../../app/src/main/java/com/safestitch/app/ui/settings/SettingsScreen.kt#L42) | 01-Full-Application-Wireflow, 02-Module-Authentication-and-Session, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S17 Safety Assistant Chat | [GeminiChatScreen.kt:179](../../app/src/main/java/com/safestitch/app/ui/chat/GeminiChatScreen.kt#L179) | 01-Full-Application-Wireflow, 09-Module-Safety-Assistant-and-Voice-Notes, 10-Role-Admin-User-Flow, 11-Role-Inspector-User-Flow |
| S18 Admin tools | [AdminScreens.kt:82](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L82) | 01-Full-Application-Wireflow, 08-Module-Admin-Tools, 10-Role-Admin-User-Flow |
| S19 Users | [AdminScreens.kt:127](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L127) | 01-Full-Application-Wireflow, 08-Module-Admin-Tools, 10-Role-Admin-User-Flow |
| S20 Locations (4 levels) | [LocationsScreen.kt:53](../../app/src/main/java/com/safestitch/app/ui/admin/LocationsScreen.kt#L53) | 01-Full-Application-Wireflow, 08-Module-Admin-Tools, 10-Role-Admin-User-Flow |
| S21 Checklist questions editor | [ChecklistEditorScreen.kt:59](../../app/src/main/java/com/safestitch/app/ui/admin/ChecklistEditorScreen.kt#L59) | 01-Full-Application-Wireflow, 08-Module-Admin-Tools, 10-Role-Admin-User-Flow |
| S22 Sync (optional server upload) | [AdminScreens.kt:272](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L272) | 01-Full-Application-Wireflow, 08-Module-Admin-Tools, 10-Role-Admin-User-Flow |
| S23 SQL Database inspector | [AdminScreens.kt:410](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt#L410) | 01-Full-Application-Wireflow, 08-Module-Admin-Tools, 10-Role-Admin-User-Flow |
