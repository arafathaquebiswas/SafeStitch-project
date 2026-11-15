# SafeStitch screen inventory

Source of truth: static inspection of the Kotlin/Compose implementation. `IMPLEMENTED` means the screen is registered and rendered by the app; device-only integrations are called out separately.

| ID | Screen | Route/state | Source | Roles | Status | Verified UI/workflow |
|---|---|---|---|---|---|---|
| S01 | Setup admin | `NeedsSetup` | [`AuthScreens.kt`](../../app/src/main/java/com/safestitch/app/ui/auth/AuthScreens.kt) | Public | IMPLEMENTED | Create first admin; validation; auto sign-in |
| S02 | Sign in | `SignedOut` | [`AuthScreens.kt`](../../app/src/main/java/com/safestitch/app/ui/auth/AuthScreens.kt) | Public | IMPLEMENTED | Username/password; error and lockout message |
| S03 | Home dashboard | `DashboardRoute` | [`DashboardScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/dashboard/DashboardScreen.kt) | Admin, Inspector | IMPLEMENTED | Totals; quick actions; account menu |
| S04 | Find equipment | `InspectRoute` | [`FindEquipmentScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/inspect/FindEquipmentScreen.kt) | Admin, Inspector | IMPLEMENTED | Search/filter; inspect; admin edit; print labels |
| S05 | QR scan | `ScanRoute` | [`ScanScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/scan/ScanScreen.kt) | Admin, Inspector | IMPLEMENTED | Camera; gallery image; manual code |
| S06 | Checkpoint form | `CheckpointFormRoute` | [`CheckpointFormScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/checkpoint/CheckpointFormScreen.kt) | Admin, Inspector | IMPLEMENTED | Create/edit equipment; type/location; save |
| S07 | Inspection checklist | `InspectionRoute` | [`InspectionScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/inspection/InspectionScreen.kt) | Admin, Inspector | IMPLEMENTED | Pass/fail/N/A; notes; due date; photos; complete |
| S08 | Photo capture | `PhotoCaptureRoute` | [`PhotoCaptureScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/camera/PhotoCaptureScreen.kt) | Admin, Inspector | IMPLEMENTED | General/item/after-fix camera photo |
| S09 | Inspection report | `ReportRoute` | [`ReportScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/report/ReportScreen.kt) | Admin, Inspector | IMPLEMENTED | Result; findings/actions; regenerate; share/save |
| S10 | PDF viewer | `PdfViewerRoute` | [`PdfViewerScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/report/PdfViewerScreen.kt) | Admin, Inspector | IMPLEMENTED | In-app PDF preview; share |
| S11 | Corrective actions | `ActionsRoute` | [`ActionsScreens.kt`](../../app/src/main/java/com/safestitch/app/ui/actions/ActionsScreens.kt) | Admin, Inspector | IMPLEMENTED | Filter by lifecycle status; open detail |
| S12 | Action detail | `ActionDetailRoute` | [`ActionsScreens.kt`](../../app/src/main/java/com/safestitch/app/ui/actions/ActionsScreens.kt) | Admin, Inspector | IMPLEMENTED | Start; resolve; verify admin; reopen; assign |
| S13 | Inspection history | `HistoryRoute` | [`HistoryScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/history/HistoryScreen.kt) | Admin, Inspector | IMPLEMENTED | Status/result/date/location/type/text filters |
| S14 | Captured inspections | `CapturedInspectionsRoute` | [`CapturedInspectionsScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/history/CapturedInspectionsScreen.kt) | Admin, Inspector | IMPLEMENTED | Photo capture list/filter |
| S15 | Captured detail | `CapturedInspectionDetailRoute` | [`InspectionDetailsScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/history/InspectionDetailsScreen.kt) | Admin, Inspector | IMPLEMENTED | Tags; PDF; share; delete |
| S16 | Settings | `SettingsRoute` | [`SettingsScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/settings/SettingsScreen.kt) | Admin, Inspector | IMPLEMENTED | Change password; sign out |
| S17 | Admin tools | `AdminRoute` | [`AdminScreens.kt`](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt) | Admin | IMPLEMENTED | Users; locations; checklists; sync; SQL; equipment |
| S18 | Users | `UsersRoute` | [`AdminScreens.kt`](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt) | Admin | IMPLEMENTED | Add; reset password; activate/deactivate |
| S19 | Locations | `LocationsRoute` | [`LocationsScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/admin/LocationsScreen.kt) | Admin | IMPLEMENTED | Factory > building > floor > area; rename/add |
| S20 | Checklist editor | `ChecklistEditorRoute` | [`ChecklistEditorScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/admin/ChecklistEditorScreen.kt) | Admin | IMPLEMENTED | Add/edit/reorder/disable questions |
| S21 | Sync | `SyncRoute` | [`AdminScreens.kt`](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt) | Admin | IMPLEMENTED | Optional server; queue; retry; conflicts |
| S22 | SQL inspector | `SqlDatabaseRoute` | [`AdminScreens.kt`](../../app/src/main/java/com/safestitch/app/ui/admin/AdminScreens.kt) | Admin | IMPLEMENTED | Read-only preset/custom SELECT/PRAGMA |
| S23 | Safety chat | `GeminiChatRoute` | [`GeminiChatScreen.kt`](../../app/src/main/java/com/safestitch/app/ui/chat/GeminiChatScreen.kt) | Admin, Inspector | IMPLEMENTED | Model/persona; prompts; transcription |

## Conditional and system states

- `MainActivity` gates `Loading`, `NeedsSetup`, `SignedOut`, and `SignedIn`; the first three are stateful entry states rather than navigation routes.
- `SignedIn` creates a fresh `SafeStitchNavHost` keyed by user ID.
- Completed inspections open a report; unfinished inspections open the checklist (`SafeStitchNavHost.kt:63-64`).
- Known QR codes resume/create inspections; unknown codes open the checkpoint form.
- Camera, gallery, microphone, PDF save/share, and network sync require device/environment verification beyond static source inspection.

## Roles

- `ADMIN` and `INSPECTOR` are the implemented roles (`domain/Models.kt`). Admin UI is hidden from inspectors in the dashboard and repository account mutations are admin-checked.
- Admin-only screens: Users, Locations, Checklist editor, Sync, SQL inspector, and the Admin tools hub.
- The route host itself does not independently enforce `AdminRoute`; direct/deep-link access was not device-tested. This is recorded as a verification gap rather than treated as a missing screen.
