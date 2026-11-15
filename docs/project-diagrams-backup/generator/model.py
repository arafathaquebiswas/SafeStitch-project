"""Single source of truth for every SafeStitch diagram and report.

Screens, dialogs, system interactions and transitions are written down once here, each with evidence
tokens (file + exact source substring). build.py resolves every token to a line number and fails
loudly if a token is no longer present in the code, so the diagrams cannot silently drift.
"""

SRC_ROOT = "app/src/main/java/com/safestitch/app/"

# ----------------------------------------------------------------------------------------------
#  Wireframe element helpers (rendered by engine.py)
# ----------------------------------------------------------------------------------------------

def bar(title, back=True, sub=None, right=(), id=None, admin_right=False):
    return dict(t="bar", title=title, back=back, sub=sub, right=list(right), id=id, admin_right=admin_right)

def txt(s, size="s", color=None, bold=False, id=None, admin=False):
    return dict(t="txt", s=s, size=size, color=color, bold=bold, id=id, admin=admin)

def sec(s, id=None):
    return dict(t="sec", s=s, id=id)

def inp(label, value=None, id=None, admin=False):
    return dict(t="inp", label=label, value=value, id=id, admin=admin)

def sel(label, value="", id=None, admin=False):
    return dict(t="sel", label=label, value=value, id=id, admin=admin)

def btn(label, kind="primary", id=None, admin=False):
    return dict(t="btn", label=label, kind=kind, id=id, admin=admin)

def btns(*items, id=None):
    """items: (label, kind[, id[, admin]]); kinds: primary, outline, text, select, input, chip"""
    return dict(t="btns", items=[tuple(i) for i in items], id=id)

def chips(labels, sel_index=0, id=None, admin=False):
    return dict(t="chips", labels=list(labels), sel=sel_index, id=id, admin=admin)

def card(lines, id=None, chev=False, chips_=None, btns_=None, admin=False, tint=None):
    return dict(t="card", lines=list(lines), id=id, chev=chev, chips=chips_, btns=btns_, admin=admin, tint=tint)

def cam(h, label, shutter=False, frame=False, id=None):
    return dict(t="cam", h=h, label=label, shutter=shutter, frame=frame, id=id)

def kpi(*pairs, id=None):
    return dict(t="kpi", pairs=list(pairs), id=id)

def seg(labels, sel_index=-1, id=None):
    return dict(t="seg", labels=list(labels), sel=sel_index, id=id)

def switch(label, on=True, id=None, admin=False):
    return dict(t="switch", label=label, on=on, id=id, admin=admin)

def radio(opts, sel_index=0, id=None):
    return dict(t="radio", opts=list(opts), sel=sel_index, id=id)

def prog(label, frac, id=None):
    return dict(t="prog", label=label, frac=frac, id=id)

def img(h, label, id=None):
    return dict(t="img", h=h, label=label, id=id)

def bubbles(*items, id=None):
    return dict(t="bubbles", items=list(items), id=id)

def anno(s, id=None):
    return dict(t="anno", s=s, id=id)

def gap(h=4):
    return dict(t="gap", h=h)

def spinner(label=""):
    return dict(t="spinner", label=label)

def logo():
    return dict(t="logo")

def fab(label, id=None, admin=False, wide=False):
    return dict(t="fab", label=label, id=id, admin=admin, wide=wide)

def nav(active):
    return dict(t="nav", active=active)

def subbar(text, label, id=None):
    return dict(t="subbar", text=text, label=label, id=id)


# ----------------------------------------------------------------------------------------------
#  Status vocabulary
# ----------------------------------------------------------------------------------------------
IMPLEMENTED = "IMPLEMENTED"
PARTIAL = "PARTIALLY IMPLEMENTED"
UNREACHABLE = "DECLARED BUT UNREACHABLE"
DOC_ONLY = "DOCUMENTED ONLY"
NOT_VERIFIED = "NOT VERIFIED"

ALL = "Admin, Inspector"
ADMIN = "Admin"

# ----------------------------------------------------------------------------------------------
#  Screens (S..) — one per auth state rendered by MainActivity + one per NavHost route
# ----------------------------------------------------------------------------------------------
S = {}

def screen(sid, name, route, file, fn, roles, module, status, ui, states="", back="", reach=""):
    S[sid] = dict(id=sid, name=name, route=route, file=file, fn=fn, roles=roles, module=module,
                  status=status, ui=ui, states=states, back=back, reach=reach)

screen("S00", "App launch / session check", "AuthState.Loading (MainActivity)", "MainActivity.kt",
       "AuthState.Loading -> Box(", "All", "auth", IMPLEMENTED,
       [gap(70), logo(), gap(16), spinner("CircularProgressIndicator"), gap(10),
        txt("AuthRepository.refresh(): counts users and reads the remembered user id", "s", "grey"),
        gap(8), anno("No controls: decides Setup, Sign in or Home")],
       states="Loading spinner only.", back="System Back closes the app.", reach="Every cold start.")

screen("S01", "Set up SafeStitch (first admin)", "AuthState.NeedsSetup", "ui/auth/AuthScreens.kt",
       "fun SetupAdminScreen(", "First launch (no accounts)", "auth", IMPLEMENTED,
       [gap(4), logo(), txt("SafeStitch", "l", "primary", True), txt("Set up SafeStitch", "m", None, True),
        txt("Create the first admin account. Admins add inspectors, locations, equipment and checklist questions.", "s", "grey"),
        inp("Your name"), inp("Username"), inp("Password (at least 8 characters)"), inp("Repeat password"),
        txt("The two passwords do not match.", "s", "red", id="err"),
        btn("Create admin account", id="setup-create")],
       states="Inline validation errors (name, username rules, password length/match); busy label 'Creating…'.",
       back="System Back closes the app.", reach="Only when the users table is empty.")

screen("S02", "Sign in", "AuthState.SignedOut", "ui/auth/AuthScreens.kt", "fun LoginScreen(", "All", "auth", IMPLEMENTED,
       [gap(4), logo(), txt("SafeStitch", "l", "primary", True), txt("Sign in", "m", None, True),
        txt("Use the account your SafeStitch admin created for you.", "s", "grey"),
        inp("Username"), inp("Password"),
        txt("Wrong username or password.", "s", "red", id="err"),
        btn("Sign in", id="login-submit"),
        txt("Forgot your password? An admin can reset it under Admin › Users.", "s", "grey")],
       states="Errors: wrong credentials, deactivated account, 30 s lock after 5 failed attempts.",
       back="System Back closes the app.", reach="Accounts exist and nobody is remembered, or after Sign out.")

screen("S03", "Home dashboard", "DashboardRoute (start destination)", "ui/dashboard/DashboardScreen.kt",
       "fun DashboardScreen(", ALL, "home", IMPLEMENTED,
       [bar("SafeStitch", back=False, sub="Walk Admin · Admin", right=["◉"], id="account-menu"),
        card(["Live Camera & ZXing Equipment Scanner"], chips_=["ZXing active", "PASS", "FAIL", "REVIEW"]),
        cam(48, "ZXing live preview · shutter = tagged photo", shutter=True, id="live-scan"),
        card(["Scan Equipment (QR / Barcode → History)"], btns_=[("From image", "outline", "from-image")]),
        btns(("Enter or scan code", "input"), ("History", "primary", "dash-history")),
        btn("View all captured inspections (n)", "outline", id="view-captured"),
        btn("Scan Equipment (→ Inspection History)", id="dash-scan-equipment"),
        btns(("Scan for Checklist", "outline", "dash-scan"), ("Find equipment", "outline", "dash-find")),
        btn("Safety Assistant Chat (Multi-Turn)", "outline", id="dash-open-chat"),
        card(["Voice Note Transcription"], btns_=[("Record Mic", "primary", "mic")], id="voice"),
        card(["Inspection Summary Overview", "Recent Equipment Status Updates ›"], id="summary"),
        kpi(("4", "Open actions"), ("2", "Overdue"), id="kpi"),
        nav("Home")],
       states="Camera permission gate; snackbars (invalid code, no barcode in picture, photo saved); "
              "more KPIs, pass rate by building and the 'Works offline' sync card below (scroll).",
       back="Start destination: System Back closes the app.", reach="After sign-in, session restore, or Home tab.")

screen("S04", "Inspect — find equipment", "InspectRoute (bottom tab)", "ui/inspect/FindEquipmentScreen.kt",
       "fun FindEquipmentScreen(", ALL, "inspect", IMPLEMENTED,
       [bar("Inspect", back=False, right=["PDF"], id="print-labels", admin_right=True),
        btn("Scan the equipment's QR code", id="scan-button"),
        inp("⌕ Search name, code or location"),
        btns(("Factory ▾", "select"), ("Building ▾", "select")),
        txt("2 of 2 equipment", "s", "grey"),
        card(["Fire extinguisher FE-12", "FE-12 · Fire extinguisher", "PlantA › BlockB › FloorOne › Cutting"],
             btns_=[("Inspect", "primary", "inspect"), ("Edit", "text", "edit", True)], id="equipment-card"),
        card(["Electrical panel EL-3", "EL-3 · Electrical panel", "Location not set"],
             btns_=[("Inspect", "primary"), ("Edit", "text", None, True)]),
        fab("+", id="add-equipment", admin=True),
        nav("Inspect")],
       states="Empty-state text differs by role; snackbar 'Add equipment first.' when printing labels with no equipment.",
       back="Bottom-tab destination: System Back returns Home.", reach="Inspect tab, Home › Find equipment, Admin › Equipment.")

screen("S05", "Corrective actions", "ActionsRoute (bottom tab)", "ui/actions/ActionsScreens.kt",
       "fun ActionsScreen(", ALL, "actions", IMPLEMENTED,
       [bar("Corrective actions", back=False),
        chips(["To do (2)", "Open", "In progress", "Overdue", "Awaiting", "Verified", "All"], 0),
        txt("2 shown · 3 corrective actions in total", "s", "grey"),
        card(["[High] [In progress] [Overdue]", "1. Pressure gauge in the green zone?", "Gauge in red zone",
              "Fire extinguisher FE-12 (FE-12)", "Responsible: Walk Admin · Due 18 Nov"], id="action-card"),
        card(["[Medium] [Open]", "3. Safety pin and tamper seal intact?", "Responsible: Walk Inspector · Due 22 Nov"]),
        nav("Actions")],
       states="Empty state: 'No corrective actions yet…'.",
       back="Bottom-tab destination: System Back returns Home.", reach="Actions tab or Home KPI cards.")

screen("S06", "Inspection history", "HistoryRoute(equipmentCode) (bottom tab)", "ui/history/HistoryScreen.kt",
       "fun HistoryScreen(", ALL, "history", IMPLEMENTED,
       [bar("Inspection history", back=False, right=["Captured (3)"], id="captured-btn"),
        card(["Equipment Identified via QR / Barcode", "FE-12 · Fire extinguisher FE-12"], btns_=[("Show all", "text")], tint="blue"),
        card(["Captured Inspection Photos (3)", "Tap a photo for Inspection Details"], chev=True, id="captured-banner"),
        btns(("10:02 PASS", "chip", "strip"), ("11:40 FAIL", "chip"), ("12:15 REVIEW", "chip")),
        inp("⌕ Search equipment, location or inspector"),
        chips(["All", "Drafts", "Completed"], 0), chips(["Any result", "Passed", "Failed"], 0),
        chips(["7 days", "30 days", "90 days", "All time"], 3),
        btn("More filters: location, inspector, type", "text"),
        card(["Fire extinguisher FE-12   [Fail 88%]", "FE-12 · Walk Admin · Submitted"], id="history-card"),
        card(["Fire extinguisher FE-12   [Draft 3/8]", "FE-12 · Walk Inspector · Started"], id="history-draft"),
        nav("History")],
       states="Banner only when opened with an equipment code; 'Clear filters' appears when filters are not default.",
       back="Bottom-tab destination: System Back returns Home.", reach="History tab; Home scan/code/quick-chip with an equipment code.")

screen("S07", "Captured inspections (photo list)", "CapturedInspectionsRoute", "ui/history/CapturedInspectionsScreen.kt",
       "fun CapturedInspectionsScreen(", ALL, "history", IMPLEMENTED,
       [bar("Captured Inspections", sub="3 of 3 captured photos"),
        chips(["All (3)", "PASS (1)", "FAIL (1)", "NEEDS REVIEW", "FOLLOW UP"], 0),
        card(["▣  15 Nov 10:02 · IMG_FE101_gauge.jpg", "[PASS]                                         🗑"],
             chips_=["Tags:", "PASS", "FAIL", "REVIEW"], id="captured-card"),
        card(["▣  15 Nov 11:40 · IMG_EX102_corridor.jpg", "[FAIL] [FOLLOW UP]                         🗑"],
             chips_=["Tags:", "PASS", "FAIL", "REVIEW"]),
        anno("🗑 deletes immediately — no confirmation on this list")],
       states="Empty state per tag filter.",
       back="← / System Back: popBackStack.", reach="Home 'View all captured inspections', History 'Captured (n)' or banner.")

screen("S08", "Captured inspection details", "CapturedInspectionDetailRoute(inspectionId)",
       "ui/history/InspectionDetailsScreen.kt", "fun InspectionDetailsScreen(", ALL, "history", IMPLEMENTED,
       [bar("Inspection Details", sub="Capture #2 · FE-101 · 15 Nov", right=["PDF", "🗑"], id="details-delete"),
        img(56, "Zoomable full-resolution photo (− + Reset, up to 4×)"),
        card(["Equipment Details", "Link: [Auto-link] [FE-12 · Fire extinguisher]"]),
        card(["Capture & File Details", "timestamp · file · size"]),
        card(["Status Tags"], chips_=["PASS", "FAIL", "REVIEW", "FOLLOW UP"]),
        btns(("Add custom tag", "input"), ("Add tag", "primary")),
        card(["Branded PDF Export (PDFBox)", "PDF ready: capture_2.pdf"],
             btns_=[("Share PDF", "outline", "details-share"), ("Save a copy…", "outline", "details-save"), ("Open in app", "text", "details-open")]),
        btn("Export Branded PDF", id="details-export")],
       states="'Captured inspection record not found.' + [Back to list]; missing image file → generated sample canvas; export snackbars.",
       back="← / System Back: popBackStack.", reach="Captured list card, History captured strip, Home summary (photo update).")

screen("S09", "Scan checkpoint", "ScanRoute", "ui/scan/ScanScreen.kt", "fun ScanScreen(", ALL, "inspect", IMPLEMENTED,
       [bar("Scan checkpoint", right=["⚡"]),
        cam(150, "CameraX + ZXing analyzer · QR viewfinder", frame=True, id="scan-cam"),
        txt("Point the camera at the equipment's QR label. It opens the checklist automatically.", "s"),
        btn("Scan a QR code from a picture", "outline", id="scan-image"),
        inp("Or type the checkpoint code", "e.g. SS-LINE-04"),
        btn("Continue", id="manual-continue")],
       states="Camera permission gate; snackbar for unusable/no QR code (analyzer resumes after 1.5 s).",
       back="← / System Back: popBackStack. Scan is popped from the back stack when it opens the next screen.",
       reach="Home 'Scan for Checklist', Inspect 'Scan the equipment's QR code'.")

screen("S10", "New / edit equipment", "CheckpointFormRoute(code?, checkpointId)", "ui/checkpoint/CheckpointFormScreen.kt",
       "fun CheckpointFormScreen(", "Admin (+ / Edit); any role via an unknown scanned code", "inspect", IMPLEMENTED,
       [bar("New equipment", sub="or 'Edit equipment'"),
        card(["Code NEW-99 is not registered yet. Describe it once; every later scan opens its checklist."], tint="amber"),
        inp("Equipment code (on the QR label)", "read-only when scanned / editing"),
        inp("Name", "e.g. Fire extinguisher FE-12"),
        btns(("Factory ▾", "select"), ("Building ▾", "select")), btns(("Floor ▾", "select"), ("Area ▾", "select")),
        inp("Exact spot (optional)"),
        sec("Type (decides the checklist)"),
        radio(["Fire extinguisher", "Electrical panel", "Fire alarm", "Emergency exit", "Boiler"], 0),
        btn("Save and start inspection | Save equipment | Save changes", id="cp-save")],
       states="Errors: missing/too-long code, missing name, location not chosen down to area, duplicate code.",
       back="← / System Back: popBackStack.", reach="Inspect + (admin), Inspect › Edit (admin), Scan with unknown code (any role).")

screen("S11", "Inspection checklist", "InspectionRoute(inspectionId)", "ui/inspection/InspectionScreen.kt",
       "fun InspectionScreen(", ALL, "inspect", IMPLEMENTED,
       [bar("Fire extinguisher FE-12", sub="FE-12 · Fire extinguisher", right=["🗑"], id="delete-inspection"),
        card(["Inspector: Walk Admin", "Location: PlantA › … · Started 15 Nov"]),
        prog("3 of 8 answered", 0.38),
        sec("Pressure & condition"),
        card(["1. Pressure gauge in the green zone?", "Critical item"], id="item"),
        seg(["Pass", "Fail", "N/A"], 1),
        inp("Problem and corrective action (required)"),
        sel("Responsible for the fix", "Walk Admin (Admin)", id="responsible"),
        chips(["Critical", "High", "Medium", "Low"], 1),
        btns(("Due 18 Nov", "outline", "due"), ("📷 Add photo", "text", "item-photo")),
        card(["General notes & photos"], btns_=[("📷 Add general photo", "text", "general-photo")]),
        subbar("3/8 answered · 5 left", "Submit & create PDF", id="submit")],
       states="Every change auto-saves (draft survives restarts); loading spinner; error snackbars.",
       back="← / System Back: popBackStack (draft kept).",
       reach="Inspect › Inspect, Scan with known code, saved scanned equipment, History/Home draft.")

screen("S12", "Take photo", "PhotoCaptureRoute(inspectionId, itemId, actionId)", "ui/camera/PhotoCaptureScreen.kt",
       "fun PhotoCaptureScreen(", ALL, "inspect", IMPLEMENTED,
       [bar("Take photo", sub="Item 1: … | General photo | After-fix · action #1"),
        cam(300, "CameraX preview (full screen)", shutter=True, id="shutter")],
       states="Camera permission gate; capture error snackbar; spinner on the shutter while saving.",
       back="← / System Back: popBackStack (no photo saved).", reach="Inspection item/general photo, Action detail after-fix photo.")

screen("S13", "Inspection report", "ReportRoute(inspectionId)", "ui/report/ReportScreen.kt", "fun ReportScreen(",
       ALL, "report", IMPLEMENTED,
       [bar("Inspection report"),
        card(["Fire extinguisher FE-12", "FE-12 · Fire extinguisher", "Result: FAIL", "Score 88% · 7 passed · 1 failed",
              "Inspector: Walk Admin · Submitted 15 Nov"]),
        card(["PDF report", "PDF ready: SafeStitch_FE-12_….pdf · 3 pages"],
             btns_=[("Open PDF", "primary", "open-pdf"), ("Share", "outline", "share-pdf")]),
        btns(("Save a copy…", "outline", "save-pdf"), ("Other app", "text", "open-external"), ("Create again", "text", "regen")),
        card(["Corrective actions (1)", "1. Pressure gauge…  [High] [Open]"], id="report-action", chev=True),
        img(66, "Preview of page 1")],
       states="'The PDF file is missing on this phone.' + [Create PDF again]; snackbars for save/regenerate.",
       back="← / System Back: popBackStack (the inspection was popped on submit, so Back skips it).",
       reach="Inspection submit, History/Home submitted inspection, Action detail 'Open inspection report'.")

screen("S14", "PDF viewer (in-app)", "PdfViewerRoute(inspectionId)", "ui/report/PdfViewerScreen.kt",
       "fun PdfViewerScreen(", ALL, "report", IMPLEMENTED,
       [bar("SafeStitch_FE-12_….pdf", right=["⇪"], id="pdf-share"),
        img(150, "Page 1 (PdfRenderer bitmap)"), img(150, "Page 2 …")],
       states="Spinner while loading; 'The PDF could not be opened.'",
       back="← / System Back: popBackStack.", reach="Report › Open PDF.")

screen("S15", "Corrective action detail", "ActionDetailRoute(actionId)", "ui/actions/ActionsScreens.kt",
       "fun ActionDetailScreen(", ALL, "actions", IMPLEMENTED,
       [bar("Corrective action"),
        txt("[High] [In progress] [Overdue]", "s", "primary"),
        txt("1. Pressure gauge in the green zone?", "m", None, True),
        txt("Finding: Gauge in red zone · Responsible: Walk Admin · Due 18 Nov", "s"),
        card(["Fire extinguisher FE-12 (FE-12)"], btns_=[("Open inspection report", "text", "open-inspection")]),
        txt("Evidence photos ▣ ▣      After-fix photos ▣", "s", "grey"),
        card(["OPEN → [Start work]", "IN PROGRESS → note + [Mark as resolved]"], id="state-panel"),
        btn("Add after-fix photo (optional)", "outline", id="after-fix"),
        card(["RESOLVED → admin verifies; inspector sees 'Only an admin can verify this fix.'"],
             btns_=[("Verify & close", "primary", "verify", True), ("Not fixed: send back", "outline", "send-back", True)], id="resolved"),
        card(["History: Created (Open) → In progress …"]),
        card(["Admin: responsible, priority, due date"], chips_=["Critical", "High", "Medium", "Low"],
             btns_=[("Responsible ▾", "select", "admin-resp", True), ("Due 18 Nov", "outline", "admin-due", True)], admin=True, id="admin-panel")],
       states="Status-dependent controls; snackbar 'This action changed in the meantime; nothing was saved.'",
       back="← / System Back: popBackStack.", reach="Actions list card, Report corrective-action card.")

screen("S16", "Settings", "SettingsRoute", "ui/settings/SettingsScreen.kt", "fun SettingsScreen(", ALL, "account",
       IMPLEMENTED,
       [bar("Settings"),
        card(["Account", "Walk Admin (@walkadmin)", "Role: Admin"],
             btns_=[("Change my password", "outline", "change-password")]),
        btn("Sign out", "outline", id="settings-signout"),
        card(["About SafeStitch", "Version 1.0.0-debug (build 1)", "App ID com.safestitch.inspection.debug",
              "Works offline; internet only if an admin turns on sync."])],
       states="Snackbar 'Password changed.'", back="← / System Back: popBackStack.", reach="Account menu › Settings.")

screen("S17", "Safety Assistant Chat", "GeminiChatRoute", "ui/chat/GeminiChatScreen.kt", "fun GeminiChatScreen(",
       ALL, "chat", PARTIAL,
       [bar("Safety Assistant Chat", right=["Clear"]),
        card(["Specialist Role & System Instruction"], chips_=["Safety Compliance Auditor", "Corrective Action Advisor", "Rapid Field Triage"]),
        chips(["Model:", "Gemini 4 Flash (General)", "Gemini 3.1 Flash Lite"], 1),
        chips(["Summarize open corrective actions", "What should I check on FE-101?"], -1, id="quick"),
        bubbles(("m", "Hello — I'm your Safety Compliance Auditor…"), ("u", "Summarize open corrective actions"),
                ("m", "Server reply, or local fallback built from on-phone data")),
        btns(("🎙", "outline", "chat-mic"), ("Ask or use Mic…", "input"), ("Send", "primary", "chat-send")),
        anno("Server URL hard-coded to http://10.0.2.2:3000 (emulator dev host); otherwise a local fallback reply")],
       states="Typing/transcribing status lines; Clear history.",
       back="← / System Back: popBackStack.", reach="Home chat button, Account menu › Safety Assistant Chat.")

screen("S18", "Admin tools", "AdminRoute", "ui/admin/AdminScreens.kt", "fun AdminScreen(", ADMIN, "admin", IMPLEMENTED,
       [bar("Admin tools"),
        card(["Users", "Add inspectors and admins, reset passwords…"], chev=True, id="admin-users"),
        card(["Locations", "Factory › building › floor › area."], chev=True, id="admin-locations"),
        card(["Equipment", "Add, edit and print QR labels."], chev=True, id="admin-equipment"),
        card(["Checklist questions", "Add, edit, reorder, switch off…"], chev=True, id="admin-checklists"),
        card(["SQL Database", "Inspect tables, run SELECT queries"], chev=True, id="admin-sql"),
        card(["Sync", "Optional: upload when online."], chev=True, id="admin-sync")],
       back="← / System Back: popBackStack (Home).",
       reach="Account menu › Admin tools (item rendered only when user.isAdmin). No route-level guard.")

screen("S19", "Users", "UsersRoute", "ui/admin/AdminScreens.kt", "fun UsersScreen(", ADMIN, "admin", IMPLEMENTED,
       [bar("Users"),
        card(["Walk Admin (you)", "@walkadmin · Admin · Active"], btns_=[("Reset password", "text", "reset")]),
        card(["Walk Inspector", "@walkinsp · Inspector · Active"], btns_=[("Reset password", "text"), ("Deactivate", "text", "deactivate")]),
        card(["Old Inspector", "@oldinsp · Inspector · Deactivated"], btns_=[("Reset password", "text"), ("Activate", "text")]),
        fab("+", id="add-user")],
       states="Snackbars; guards 'You cannot deactivate your own account.' / 'Keep at least one active admin.'",
       back="← / System Back: popBackStack.", reach="Admin tools › Users.")

screen("S20", "Locations (4 levels)", "LocationsRoute(level, parentId, title)", "ui/admin/LocationsScreen.kt",
       "fun LocationsScreen(", ADMIN, "admin", IMPLEMENTED,
       [bar("Locations", sub="or 'PlantA › Buildings' / '… › Floors' / '… › Areas'"),
        txt("Factory › building › floor › area. Equipment is assigned to an area. Renaming keeps past reports unchanged.", "s", "grey"),
        card(["PlantA                                     ✎   ›"], id="loc-row"),
        card(["PlantB                                     ✎   ›"]),
        anno("Areas (level 4) have no ›. No delete action exists at any level."),
        fab("+ Add factory | building | floor | area", id="add-location", wide=True)],
       states="Empty state 'No factories yet…'.", back="← / System Back: popBackStack (one level up).",
       reach="Admin tools › Locations, then row taps.")

screen("S21", "Checklist questions editor", "ChecklistEditorRoute", "ui/admin/ChecklistEditorScreen.kt",
       "fun ChecklistEditorScreen(", ADMIN, "admin", IMPLEMENTED,
       [bar("Checklist questions"),
        chips(["Fire extinguisher", "Electrical panel", "Fire alarm", "Emergency exit", "Boiler", "Sewing line (earlier)"], 0),
        txt("Changes apply to new inspections. Switch a question off instead of deleting it.", "s", "grey"),
        card(["Pressure & condition", "1. Pressure gauge in the green zone?", "Critical", "↑  ↓  ✎        In use  [on]"], id="q-card"),
        card(["Pressure & condition", "2. Hose and nozzle undamaged?", "↑  ↓  ✎        Switched off  [off]"]),
        fab("+ Add question", id="add-question", wide=True)],
       back="← / System Back: popBackStack.", reach="Admin tools › Checklist questions.")

screen("S22", "Sync (optional server upload)", "SyncRoute", "ui/admin/AdminScreens.kt", "fun SyncScreen(", ADMIN, "admin",
       IMPLEMENTED,
       [bar("Sync"),
        txt("Off by default. SafeStitch always works offline. The queue survives restarts. Passwords are never uploaded.", "s", "grey"),
        switch("Sync to server", False),
        inp("Server address", "https://sync.example.com"), inp("API key", "••••••"),
        btns(("Save", "primary", "sync-save"), ("Sync now", "outline", "sync-now")),
        card(["Status: Off", "Waiting to upload: 0 record(s), 0 photo(s)", "Device ID: 3fa2…"], tint="blue"),
        txt("Conflicts: 0 — None. The server and this phone agree.", "s"),
        btn("Re-upload everything", "text", id="resend")],
       states="URL validation error (release: https only); conflict cards [Keep server copy] [Send mine again].",
       back="← / System Back: popBackStack.", reach="Admin tools › Sync.")

screen("S23", "SQL Database inspector", "SqlDatabaseRoute", "ui/admin/AdminScreens.kt", "fun SqlDatabaseScreen(",
       ADMIN, "admin", IMPLEMENTED,
       [bar("SQL Database"),
        card(["Engine: SQLite 3 · Room v2 (safestitch.db)", "13 relational tables · 142 total rows"], tint="blue"),
        sec("Preset SQL Queries"),
        btns(("Equipment & Inspections", "outline"), ("Captured Photos", "outline")),
        btns(("Corrective Actions", "outline"), ("Factory Locations", "outline")),
        inp("Read-only SQL query (SELECT / PRAGMA)", "SELECT * FROM checkpoints"),
        btns(("Run SQL Query", "primary"), ("Refresh Counts", "outline")),
        card(["Result: 2 row(s) in 3 ms", "id | code | name …"]),
        card(["checkpoints                              2 row(s)", "id, code, name, area, type, areaId"])],
       states="Query error text (non-SELECT rejected).", back="← / System Back: popBackStack.",
       reach="Admin tools › SQL Database.")

SCREEN_ORDER = sorted(S)

# ----------------------------------------------------------------------------------------------
#  Dialogs / pop-ups (D..) and system / external interactions (X..)
# ----------------------------------------------------------------------------------------------
D = {}

def dialog(did, title, owner, file, fn, roles, lines, buttons, status=IMPLEMENTED, kind="dialog"):
    D[did] = dict(id=did, title=title, owner=owner, file=file, fn=fn, roles=roles, lines=lines,
                  buttons=buttons, status=status, kind=kind)

dialog("D01", "Account menu", ["S03"], "ui/dashboard/DashboardScreen.kt", "DropdownMenu(expanded = menuOpen", ALL,
       ["Admin tools  (admin only)", "Safety Assistant Chat", "Settings", "Sign out"], [], kind="menu")
dialog("D02", "QR labels ready", ["S04"], "ui/inspect/FindEquipmentScreen.kt", 'title = { Text("QR labels ready") }', ADMIN,
       ["n labels on A4. Print, cut, stick."], ["Open", "Share / print"])
dialog("D03", "Almost done", ["S11"], "ui/inspection/InspectionScreen.kt", 'title = { Text("Almost done") }', ALL,
       ["• items still need an answer", "• failed items need note, priority, due"], ["OK"])
dialog("D04", "Delete this inspection?", ["S11"], "ui/inspection/InspectionScreen.kt", 'title = { Text("Delete this inspection?") }', ALL,
       ["Answers, notes, photos removed."], ["Cancel", "Delete ⇒ back"])
dialog("D05", "Remove photo?", ["S11"], "ui/inspection/InspectionScreen.kt", 'title = { Text("Remove photo?") }', ALL,
       ["Tap a thumbnail; not in the PDF."], ["Keep", "Remove"])
dialog("D06", "Send back to In progress", ["S15"], "ui/actions/ActionsScreens.kt", 'title = { Text("Send back to In progress") }', ADMIN,
       ["Why is it not fixed? (required)"], ["Cancel", "Send back"])
dialog("D07", "Delete captured inspection?", ["S08"], "ui/history/InspectionDetailsScreen.kt", 'title = { Text("Delete captured inspection?") }', ALL,
       ["Removes record + photo file."], ["Cancel", "Delete ⇒ back"])
dialog("D08", "Add user", ["S19"], "ui/admin/AdminScreens.kt", 'title = { Text("Add user") }', ADMIN,
       ["Name · Username · Admin/Inspector", "Password · Repeat"], ["Cancel", "Add"])
dialog("D09", "New password for <name>", ["S19"], "ui/admin/AdminScreens.kt", 'title = { Text("New password for ${target.displayName}") }', ADMIN,
       ["New password (8+ characters)"], ["Cancel", "Save"])
dialog("D10", "Add / Rename <level>", ["S20"], "ui/admin/LocationsScreen.kt", '"Add ${level.label.lowercase()}" else "Rename ${level.label.lowercase()}"', ADMIN,
       ["<Level> name"], ["Cancel", "Save"])
dialog("D11", "Add / Edit question", ["S21"], "ui/admin/ChecklistEditorScreen.kt", '"Add question (${type.label})" else "Edit question"', ADMIN,
       ["Section · Question · ☐ Critical"], ["Cancel", "Save"])
dialog("D12", "Re-upload everything?", ["S22"], "ui/admin/AdminScreens.kt", 'title = { Text("Re-upload everything?") }', ADMIN,
       ["Server de-duplicates copies."], ["Cancel", "Re-upload"])
dialog("D13", "Change password", ["S16"], "ui/settings/SettingsScreen.kt", 'title = { Text("Change password") }', ALL,
       ["Current · New (8+) · Repeat new"], ["Cancel", "Save"])
dialog("D14", "Due-date picker", ["S11", "S15"], "ui/components/Pickers.kt", "DatePickerDialog(", ALL,
       ["Material date picker"], ["Cancel", "OK"])
dialog("D15", "Select-field dropdown", ["S04", "S06", "S10", "S11", "S15"], "ui/components/Pickers.kt", "DropdownMenu(expanded = expanded", ALL,
       ["People / inspector / type / location options"], [], kind="menu")
dialog("D16", "Camera permission gate", ["S03", "S09", "S12"], "ui/components/Common.kt", "fun CameraPermissionGate(", ALL,
       ["System prompt, asked once"], ["Allow camera", "Open app settings"], kind="gate")

X = {}

def ext(xid, title, owner, file, token, status=IMPLEMENTED, note=""):
    X[xid] = dict(id=xid, title=title, owner=owner, file=file, fn=token, status=status, note=note)

ext("X01", "System photo picker", ["S03", "S09"], "ui/scan/ScanScreen.kt", "PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)")
ext("X02", "Android share sheet", ["S08", "S13", "S14", "D02"], "util/FileSharing.kt", 'Intent.createChooser(send, "Share PDF")')
ext("X03", "External PDF app", ["S08", "S13", "D02"], "util/FileSharing.kt", "Intent(Intent.ACTION_VIEW)")
ext("X04", "System 'Save a copy'", ["S08", "S13"], "ui/report/ReportScreen.kt", 'ActivityResultContracts.CreateDocument("application/pdf")')
ext("X05", "Android app settings", ["D16"], "ui/components/Common.kt", "fun openAppSettings(")
ext("X06", "Sync server (HTTPS, WorkManager)", ["S22", "APP"], "sync/SyncManager.kt", "setRequiredNetworkType(NetworkType.CONNECTED)",
    note="Only when an admin enables sync; not exercised against a real server in this analysis.")
ext("X07", "Gemini dev endpoint :3000", ["S17", "S03"], "chat/SafetyChatService.kt", '"http://10.0.2.2:3000/api/gemini/chat"',
    status=PARTIAL, note="Emulator/debug-only host; cleartext is blocked in release builds, so the app always falls back to local text there.")

# ----------------------------------------------------------------------------------------------
#  Transitions.  kind: nav | tab | popup | return | external | state | background
#  ev: list of (file, token[, occurrence])  rt: runtime check keys from the emulator walkthrough
# ----------------------------------------------------------------------------------------------
E = []

def edge(src, dst, action, kind="nav", cond="", roles=ALL, ev=(), rt=(), el=None, label=None, status=IMPLEMENTED, note=""):
    E.append(dict(src=src, dst=dst, action=action, kind=kind, cond=cond, roles=roles, ev=list(ev), rt=list(rt),
                  el=el, label=label or action, status=status, note=note))

NH = "ui/navigation/SafeStitchNavHost.kt"
AU = "ui/auth/AuthScreens.kt"
AR = "data/AuthRepository.kt"
DB = "ui/dashboard/DashboardScreen.kt"
FE = "ui/inspect/FindEquipmentScreen.kt"
AC = "ui/actions/ActionsScreens.kt"
HI = "ui/history/HistoryScreen.kt"
CA = "ui/history/CapturedInspectionsScreen.kt"
DE = "ui/history/InspectionDetailsScreen.kt"
SC = "ui/scan/ScanScreen.kt"
CF = "ui/checkpoint/CheckpointFormScreen.kt"
IN = "ui/inspection/InspectionScreen.kt"
IV = "ui/inspection/InspectionViewModel.kt"
PC = "ui/camera/PhotoCaptureScreen.kt"
RE = "ui/report/ReportScreen.kt"
PV = "ui/report/PdfViewerScreen.kt"
SE = "ui/settings/SettingsScreen.kt"
CH = "ui/chat/GeminiChatScreen.kt"
AD = "ui/admin/AdminScreens.kt"
LO = "ui/admin/LocationsScreen.kt"
CE = "ui/admin/ChecklistEditorScreen.kt"
MA = "MainActivity.kt"
SU = "ui/dashboard/SummaryDashboardCard.kt"
CO = "ui/components/Common.kt"
IR = "data/InspectionRepository.kt"

# --- launch / auth -----------------------------------------------------------------------------
edge("APP", "S00", "Cold start", "nav", "always", "All", [(MA, "AuthState.Loading -> Box(")], label="launch")
edge("S00", "S01", "No accounts yet", "nav", "users.count() == 0", "All",
     [(MA, "AuthState.NeedsSetup -> SetupAdminScreen"), (AR, "mutableState.value = if (users.count() == 0) {")],
     ["APP>S01:no-users"], label="no accounts")
edge("S00", "S02", "Accounts exist, nobody remembered", "nav", "no remembered active user id", "All",
     [(MA, "AuthState.SignedOut -> LoginScreen"), (AR, "AuthState.SignedIn(user) else AuthState.SignedOut")],
     ["APP>S02:signed-out"], label="signed out")
edge("S00", "S03", "Session restore", "nav", "remembered user id exists and the account is active", ALL,
     [(MA, "is AuthState.SignedIn -> key(state.user.id)"), (AR, "if (user != null && user.active) AuthState.SignedIn(user)")],
     ["APP>S03:session-restore"], label="remembered user")
edge("S01", "S03", "Create admin account", "nav", "valid name, username, password ≥ 8, passwords match", "First user → Admin",
     [(AU, 'testTag("setup-create")'), (AR, "return signIn(username, password)")], ["S01>S03:create-admin"], el="setup-create",
     label="Create admin account")
edge("S01", "S01", "Validation error shown inline", "state", "invalid input / passwords differ", "All",
     [(AU, '"The two passwords do not match."')], ["S01:password-mismatch"], el="err")
edge("S02", "S03", "Sign in", "nav", "correct credentials, active account, not locked", ALL,
     [(AU, 'testTag("login-submit")'), (AR, "mutableState.value = AuthState.SignedIn(user)")], ["S02>S03:sign-in"],
     el="login-submit", label="Sign in")
edge("S02", "S02", "Sign-in rejected", "state", "wrong credentials / deactivated / 5 failures → 30 s lock", "All",
     [(AR, '"Wrong username or password."'), (AR, "LOCK_MILLIS = 30_000L")], ["S02:wrong-password"], el="err")
edge("D01", "S02", "Sign out", "nav", "", ALL, [(DB, 'testTag("menu-signout")'), (AR, "mutableState.value = AuthState.SignedOut")],
     ["D01>S02:sign-out"], label="Sign out")
edge("S16", "S02", "Sign out", "nav", "", ALL, [(SE, 'testTag("settings-signout")')], ["S16>S02:sign-out"], el="settings-signout",
     label="Sign out")

# --- bottom navigation ---------------------------------------------------------------------------
for dst, name, tok, rts in (("S03", "Home", 'Tab(DashboardRoute, "Home"', ["TAB>S03:tab-home", "BUG:home-tab-after-kpi-card",
                                                                             "BUG:home-tab-after-find-equipment"]),
                            ("S04", "Inspect", 'Tab(InspectRoute, "Inspect"', ["TAB>S04:tab-inspect", "TAB>S04:tab-inspect#inspector"]),
                            ("S05", "Actions", 'Tab(ActionsRoute, "Actions"', ["TAB>S05:tab-actions"]),
                            ("S06", "History", 'Tab(HistoryRoute(), "History"', ["TAB>S06:tab-history"])):
    edge("TABS", dst, f"Bottom bar: {name}", "tab", "bottom bar is shown only on the 4 tab screens", ALL,
         [(NH, tok), (NH, "nav.navigate(tab.route)"), (NH, "popUpTo(nav.graph.findStartDestination().id) { saveState = true }")], rts,
         label=f"{name} tab")

# --- home dashboard ------------------------------------------------------------------------------
edge("S03", "D01", "Account icon", "popup", "", ALL, [(DB, 'testTag("account-menu")')],
     ["S03>D01:account-menu", "S03>D01:account-menu#inspector"], el="account-menu", label="account icon")
edge("D01", "S18", "Admin tools", "nav", "user.isAdmin (menu item is not rendered for inspectors)", ADMIN,
     [(DB, 'modifier = Modifier.testTag("menu-admin")'), (NH, "onAdmin = { nav.navigate(AdminRoute) }")],
     ["D01>S18:admin-tools", "D01:admin-tools-visible-for-admin", "D01:admin-tools-hidden-for-inspector"], label="Admin tools")
edge("D01", "S17", "Safety Assistant Chat", "nav", "", ALL,
     [(DB, 'testTag("menu-chat")'), (NH, "onOpenChat = { nav.navigate(GeminiChatRoute) }")], ["D01>S17:menu-chat"], label="Chat")
edge("D01", "S16", "Settings", "nav", "", ALL,
     [(DB, 'testTag("menu-settings")'), (NH, "onSettings = { nav.navigate(SettingsRoute) }")], ["D01>S16:settings"], label="Settings")
edge("S03", "S06", "Live ZXing scan recognises a QR/barcode", "nav", "payload parses to an equipment code", ALL,
     [(DB, "QrCodeAnalyzer { rawText ->"), (NH, "onNavigateToEquipmentHistory = { code ->"), (NH, "nav.navigate(HistoryRoute(equipmentCode = code))")],
     [], el="live-scan",
     label="code scanned", note="Not exercised on the emulator (needs a printed code in front of the camera).")
edge("S03", "S06", "From image → photo picker → decode", "nav", "a QR/barcode is found in the picked picture", ALL,
     [(DB, 'testTag("dash-scan-equipment-image")'), (DB, "vm.scanEquipmentFromImage(")], [], el="from-image", label="from image")
edge("S03", "S06", "Quick scan tag chip", "nav", "at least one equipment exists", ALL,
     [(DB, 'testTag("dash-scan-chip-${cp.code}")')], ["S03>S06:quick-scan-chip"], el="from-image", label="quick chip")
edge("S03", "S06", "Type code → History", "nav", "code parses", ALL,
     [(DB, 'testTag("dash-scan-code-submit")')], ["S03>S06:manual-code-history", "S06:scanned-equipment-banner"], el="dash-history",
     label="History")
edge("S03", "S06", "Scan Equipment (→ Inspection History) button", "nav",
     "typed code, else the first equipment's code; with no equipment it only shows the viewfinder", ALL,
     [(DB, 'testTag("dash-scan-equipment")')], [], el="dash-scan-equipment", label="Scan Equipment button")
edge("S03", "S09", "Scan for Checklist", "nav", "", ALL, [(DB, 'testTag("dash-scan")'), (NH, "onScan = { nav.navigate(ScanRoute) }")],
     ["S03>S09:scan-for-checklist", "S03>S09:scan-for-checklist#2", "S03>S09:scan#inspector"], el="dash-scan", label="Scan for Checklist")
edge("S03", "S04", "Find equipment", "nav", "", ALL,
     [(DB, 'testTag("dash-find")'), (NH, "onFindEquipment = { nav.navigate(InspectRoute) { launchSingleTop = true } }")],
     ["S03>S04:find-equipment"], el="dash-find", label="Find equipment")
edge("S03", "S17", "Safety Assistant Chat (Multi-Turn Advisor)", "nav", "", ALL, [(DB, 'testTag("dash-open-chat")')],
     ["S03>S17:dashboard-chat-button"], el="dash-open-chat", label="Chat")
edge("S03", "S07", "View all captured inspections (n)", "nav", "", ALL,
     [(DB, 'testTag("open-captured-inspections")'), (NH, "onOpenCapturedInspections = { nav.navigate(CapturedInspectionsRoute) }")],
     ["S03>S07:view-captured"], el="view-captured", label="View all captured")
edge("S03", "S08", "Summary card: recent captured photo", "nav", "update is a captured photo", ALL,
     [(SU, "onOpenCapturedInspection(update.recordId)"), (NH, "onOpenCapturedInspectionDetail = { id -> nav.navigate(CapturedInspectionDetailRoute(id)) }")],
     [], el="summary", label="recent photo")
edge("S03", "S13", "Summary card: recent submitted checklist", "nav", "inspection completed", ALL,
     [(SU, "onOpenChecklistInspection(update.recordId, update.isCompletedChecklist)"),
      (NH, "fun openInspection(id: Long, completed: Boolean) = nav.navigate(if (completed) ReportRoute(id) else InspectionRoute(id))")],
     [], el="summary", label="recent (submitted)")
edge("S03", "S11", "Summary card: recent draft checklist", "nav", "inspection still a draft", ALL,
     [(SU, "onOpenChecklistInspection(update.recordId, update.isCompletedChecklist)")], [], el="summary", label="recent (draft)")
edge("S03", "S05", "KPI cards (Open / In progress / Overdue / Awaiting / Critical / All)", "nav", "", ALL,
     [(DB, '"kpi-open-actions", onClick = onOpenActions'), (NH, "onOpenActions = { nav.navigate(ActionsRoute) { launchSingleTop = true } }")],
     ["S03>S05:kpi-card"], el="kpi", label="KPI cards")
edge("S03", "D16", "Camera permission gate (asks once)", "popup", "camera not granted", ALL, [(DB, "CameraPermissionGate(")], [], el="live-scan",
     label="camera permission")
edge("S03", "X01", "From image", "external", "", ALL, [(DB, "pickBarcodeImage.launch(")], [], el="from-image", label="photo picker")
edge("S03", "S03", "Shutter: save tagged captured photo (create)", "state", "camera ready", ALL,
     [(DB, "vm.saveCapture(file.absolutePath, tagOnCapture)")], [], el="live-scan")
edge("S03", "S03", "Tap captured thumbnail: toggle selected tag (update)", "state", "", ALL,
     [(DB, "onClick = { vm.toggleTag(captured, selectedCaptureTag) }")], [])
edge("S03", "S03", "Capture-tag / filter chips, torch, Hide/Show viewfinder", "state", "", ALL,
     [(DB, 'testTag("filter-tag-ALL")'), (DB, 'testTag("toggle-viewfinder")')], [])
edge("S03", "X07", "Voice note: Record Mic → Stop & Transcribe", "external",
     "no RECORD_AUDIO runtime request exists; the dev server is only reachable from emulator debug builds", ALL,
     [(DB, 'testTag("dash-mic-record-btn")'), ("audio/AudioTranscriptionService.kt", '"http://10.0.2.2:3000/api/gemini/transcribe"'),
      ("audio/AudioTranscriptionService.kt", "?: buildFallbackTranscription(contextHint)")],
     ["S03:voice-note"], el="mic", label="voice note", status=PARTIAL,
     note="Without mic permission AudioRecord fails silently, a synthetic tone is sent, and a canned FE-101/EL-204/EX-102 sentence is shown.")
edge("S03", "S03", "Invalid code / no barcode in picture → snackbar", "state", "", ALL,
     [(DB, '"Unrecognized QR or barcode payload."'), (DB, '"No QR code or barcode was found in that picture."')], [])

# --- inspect tab -----------------------------------------------------------------------------------
edge("S04", "S09", "Scan the equipment's QR code", "nav", "", ALL, [(FE, 'testTag("scan-button")'), (NH, "onScan = { nav.navigate(ScanRoute) }", 2)],
     ["S04>S09:scan-button"], el="scan-button", label="Scan QR")
edge("S04", "S11", "Inspect (start or resume the draft)", "nav", "", ALL,
     [(FE, 'testTag("inspect-${cp.code}")'), (FE, "repository.startOrResumeInspection(checkpoint.id, user.displayName, user.id)"),
      (NH, "onInspectionReady = { nav.navigate(InspectionRoute(it)) }")],
     ["S04>S11:inspect", "S04>S11:inspect#inspector"], el="inspect", label="Inspect")
edge("S04", "S10", "+ Add equipment (FAB)", "nav", "user.isAdmin", ADMIN,
     [(FE, 'testTag("add-equipment")'), (NH, "onAddEquipment = { nav.navigate(CheckpointFormRoute()) }")],
     ["S04>S10:add-equipment", "S04:admin-controls-visible", "S04:admin-controls-hidden-for-inspector"], el="add-equipment", label="+ Add")
edge("S04", "S10", "Edit", "nav", "user.isAdmin", ADMIN,
     [(FE, "if (user.isAdmin) TextButton(onClick = { onEditEquipment(cp.id) })"), (NH, "onEditEquipment = { nav.navigate(CheckpointFormRoute(checkpointId = it)) }")],
     ["S04>S10:edit-equipment"], el="edit", label="Edit")
edge("S04", "D02", "PDF icon: print QR labels", "popup", "user.isAdmin and ≥ 1 equipment (else snackbar 'Add equipment first.')", ADMIN,
     [(FE, 'testTag("print-labels")'), (FE, '"Add equipment first."')], ["S04>D02:print-labels", "S04:print-labels-no-equipment"],
     el="print-labels", label="print labels")
edge("D02", "X02", "Share / print", "external", "", ADMIN, [(FE, 'FileSharing.sharePdf(context, file, "SafeStitch QR labels")')], [], label="Share / print")
edge("D02", "X03", "Open", "external", "falls back to the share sheet when no PDF app is installed", ADMIN,
     [(FE, "if (!FileSharing.openPdf(context, file)) FileSharing.sharePdf")], [], label="Open")
edge("S04", "S04", "Search, location filter", "state", "", ALL, [(FE, 'testTag("equipment-search")'), (FE, 'tagPrefix = "find"')], [])
edge("S04", "D15", "Location filter dropdowns", "popup", "locations exist", ALL, [(FE, 'tagPrefix = "find"')], [])

# --- actions --------------------------------------------------------------------------------------
edge("S05", "S15", "Tap action card", "nav", "", ALL,
     [(AC, 'testTag("action-${a.id}")'), (NH, "ActionsScreen(container = container, onOpen = { nav.navigate(ActionDetailRoute(it)) })")],
     ["S05>S15:open-action"], el="action-card", label="open action")
edge("S05", "S05", "Filter chips (To do / Open / In progress / Overdue / Awaiting / Verified / All)", "state", "", ALL,
     [(AC, 'testTag("filter-${f.name}")')], [])

# --- history ------------------------------------------------------------------------------------
edge("S06", "S07", "Captured (n) / captured-photos banner", "nav", "", ALL,
     [(HI, 'testTag("history-open-captured")'), (HI, 'testTag("history-captured-banner")'),
      (NH, "onOpenCapturedInspections = { nav.navigate(CapturedInspectionsRoute) }", 2)],
     ["S06>S07:captured-button"], el="captured-banner", label="Captured (n)")
edge("S06", "S08", "Captured strip card", "nav", "", ALL, [(HI, 'testTag("history-captured-item-${item.id}")'),
                                                          (NH, "onOpenCapturedInspectionDetail = { id -> nav.navigate(CapturedInspectionDetailRoute(id)) }", 2)],
     ["S06>S08:captured-strip-item"], el="strip", label="photo card")
edge("S06", "S13", "Inspection card (submitted)", "nav", "completedAt != null", ALL,
     [(HI, "HistoryCard(entry, onClick = { onOpen(entry.inspectionId, entry.completedAt != null) })"), (NH, "onOpen = ::openInspection")],
     ["S06>S13:open-completed"], el="history-card", label="submitted")
edge("S06", "S11", "Inspection card (draft)", "nav", "completedAt == null", ALL,
     [(HI, "HistoryCard(entry, onClick = { onOpen(entry.inspectionId, entry.completedAt != null) })")], [], el="history-draft", label="draft")
edge("S06", "S06", "Search, status/result/period chips, More filters, Clear filters, Show all", "state", "", ALL,
     [(HI, 'testTag("history-search")'), (HI, 'testTag("history-more")'), (HI, 'testTag("history-clear")')], [])
edge("S06", "D15", "More filters: location / inspector / type dropdowns", "popup", "", ALL, [(HI, 'testTag = "hist-inspector"')], [])

# --- captured photos ------------------------------------------------------------------------------
edge("S07", "S08", "Tap card / thumbnail", "nav", "", ALL,
     [(CA, "onClick = { onOpenInspection(inspection.id) }"), (NH, "onOpenInspection = { id -> nav.navigate(CapturedInspectionDetailRoute(id)) }")],
     ["S07>S08:open-captured"], el="captured-card", label="open photo")
edge("S07", "S07", "Tag filter chips; toggle tags; 🗑 delete (immediate, no confirmation)", "state", "", ALL,
     [(CA, 'testTag("toggle-tag-${inspection.id}-$tag")'), (CA, "onDelete = { vm.deleteInspection(inspection.id) }")], [])
edge("S08", "D07", "Delete icon", "popup", "", ALL, [(DE, 'testTag("details-delete-button")')], ["S08>D07:delete"], el="details-delete",
     label="🗑")
edge("D07", "BACK", "Delete → returns to the previous screen", "return", "", ALL, [(DE, "vm.deleteInspection(onDeleted = onBack)")], [],
     label="Delete ⇒ back")
edge("S08", "X02", "Share PDF", "external", "PDF exported", ALL, [(DE, 'testTag("details-share-pdf")')], [], el="details-share", label="Share PDF")
edge("S08", "X04", "Save a copy…", "external", "PDF exported", ALL, [(DE, 'testTag("details-save-pdf")')], [], el="details-save", label="Save a copy")
edge("S08", "X03", "'Open in app' (actually launches an external PDF viewer)", "external", "PDF exported", ALL,
     [(DE, 'testTag("details-open-pdf")'), (DE, '"No external PDF viewer installed."')], [], el="details-open", label="Open in app")
edge("S08", "S08", "Export Branded PDF (PDFBox), link equipment, toggle/add/remove tags, zoom", "state", "", ALL,
     [(DE, 'testTag("details-export-pdf-button")'), (DE, 'testTag("details-add-tag-button")'), (DE, 'testTag("details-zoom-in")')], [])

# --- scan / equipment form --------------------------------------------------------------------------
edge("S09", "S11", "Known code (camera, picture or typed + Continue)", "nav", "code is registered → start or resume", ALL,
     [(SC, "channel.send(Event.InspectionReady(repository.startOrResumeInspection(checkpoint.id, user.displayName, user.id)))"),
      (NH, "onInspectionReady = { id -> nav.navigate(InspectionRoute(id)) { popUpTo<ScanRoute> { inclusive = true } } }")],
     ["S09>S11:known-code"], el="manual-continue", label="known code")
edge("S09", "S10", "Unknown code", "nav", "code not registered (any role)", ALL,
     [(SC, "channel.send(Event.NewCheckpoint(code))"),
      (NH, "onNewCheckpoint = { code -> nav.navigate(CheckpointFormRoute(code = code)) { popUpTo<ScanRoute> { inclusive = true } } }")],
     ["S09>S10:unknown-code", "S09>S10:unknown-code#inspector", "S10:back-skips-scan"], el="manual-continue", label="unknown code")
edge("S09", "X01", "Scan a QR code from a picture", "external", "", ALL, [(SC, 'testTag("scan-image")')], [], el="scan-image", label="photo picker")
edge("S09", "D16", "Camera permission gate", "popup", "camera not granted", ALL, [(SC, "CameraPermissionGate(")], [], el="scan-cam")
edge("S09", "S09", "Unusable / missing QR → snackbar, analyzer resumes", "state", "", ALL,
     [(SC, '"That QR code does not contain a usable checkpoint code."')], [])
edge("S10", "S11", "Save and start inspection", "nav", "opened from an unknown scanned code", ALL,
     [(CF, "if (scannedCode != null) {"), (NH, "onInspectionReady = { id -> nav.navigate(InspectionRoute(id)) { popUpTo<CheckpointFormRoute> { inclusive = true } } }")],
     ["S10>S11:save-and-start#inspector"], el="cp-save", label="Save & start")
edge("S10", "S04", "Save equipment / Save changes → back", "return", "opened from + or Edit (admin)", ADMIN,
     [(CF, "channel.send(Event.Saved)"), (NH, "onSaved = { nav.popBackStack() }")], ["S10>S04:save-equipment"], el="cp-save", label="Save ⇒ back")
edge("S10", "S10", "Validation / duplicate-code errors", "state", "", ALL,
     [(CF, '"Give the equipment a name, for example'), (CF, "catch (e: DuplicateCheckpointCodeException)")], [])
edge("S10", "D15", "Location dropdowns (Factory › Building › Floor › Area)", "popup", "locations exist", ALL, [(CF, 'tagPrefix = "cp-loc"')], [])

# --- inspection -----------------------------------------------------------------------------------
edge("S11", "S12", "Add photo (item) / Add general photo", "nav", "", ALL,
     [(IN, 'testTag("$tag-photo")'), (IN, 'testTag("general-photo")'),
      (NH, "onTakePhoto = { itemId -> nav.navigate(PhotoCaptureRoute(route.inspectionId, itemId ?: -1L)) }")],
     ["S11>S12:item-photo", "S11:photo-attached"], el="item-photo", label="Add photo")
edge("S11", "S13", "Submit & create PDF", "nav", "all answered; each fail has a note, priority and due date", ALL,
     [(IN, 'testTag("complete-inspection")'), (IV, "val file = reportService.generateReport(inspectionId, completedAt)"),
      (NH, "onCompleted = { nav.navigate(ReportRoute(route.inspectionId)) { popUpTo<InspectionRoute> { inclusive = true } } }")],
     ["S11>S13:submit", "S11>S13:submit#inspector"], el="submit", label="Submit & create PDF")
edge("S11", "D03", "Submit with problems", "popup", "CompletionRules.problems not empty", ALL,
     [(IN, 'title = { Text("Almost done") }'), (IV, "completionProblems = problems")], ["S11>D03:submit-incomplete"], el="submit")
edge("S11", "D04", "Trash icon", "popup", "", ALL, [(IN, 'testTag("delete-inspection")')], ["S11>D04:delete"], el="delete-inspection")
edge("D04", "BACK", "Delete → back to the previous screen", "return", "", ALL, [(NH, "onDeleted = { nav.popBackStack() }")],
     ["D04>BACK:delete-confirm"], label="Delete ⇒ back")
edge("S11", "D05", "Tap a photo thumbnail", "popup", "an item or general photo exists", ALL, [(IN, "onPhotoClick = { photoToDelete = it }")], [], el="item-photo")
edge("S11", "D14", "Due date button (failed item)", "popup", "item failed", ALL,
     [(IN, 'DueDateButton(dueAt = item.dueAt, onPick = { vm.setDueDate(item, it) }, testTag = "$tag-due")')], ["S11>D14:due-date-picker"], el="due")
edge("S11", "D15", "Responsible for the fix ▾ (failed item)", "popup", "item failed", ALL, [(IN, 'label = "Responsible for the fix"')],
     ["S11>D15:responsible-select"], el="responsible")
edge("S11", "S11", "Answer Pass/Fail/N/A, notes, priority, general notes (auto-saved)", "state", "", ALL,
     [(IN, "vm.setResult(item, if (item.result == option) ItemResult.UNANSWERED else option)"), (IN, "PrioritySelector(selected = item.priority")], [])

# --- photo capture ----------------------------------------------------------------------------------
edge("S12", "S11", "Shutter → photo saved → back", "return", "opened from the inspection", ALL,
     [(PC, "PhotoCaptureViewModel.Event.Done -> onDone()"), (NH, "onDone = { nav.popBackStack() }")], ["S12>S11:shutter-saved"],
     el="shutter", label="saved ⇒ back")
edge("S12", "S15", "Shutter → after-fix photo saved → back", "return", "opened from a corrective action", ALL,
     [(PC, "repository.addAfterFixPhoto(actionId, file.name)")], ["S12>S15:shutter-saved"], el="shutter", label="after-fix ⇒ back")
edge("S12", "D16", "Camera permission gate", "popup", "camera not granted", ALL, [(PC, "CameraPermissionGate(")], [], el="shutter")

# --- report / pdf -----------------------------------------------------------------------------------
edge("S13", "S14", "Open PDF", "nav", "PDF file exists", ALL,
     [(RE, 'testTag("open-pdf")'), (NH, "onViewPdf = { nav.navigate(PdfViewerRoute(route.inspectionId)) }")], ["S13>S14:open-pdf"],
     el="open-pdf", label="Open PDF")
edge("S13", "S15", "Corrective-action card", "nav", "inspection has failed items", ALL,
     [(RE, 'testTag("report-action-${action.id}")'), (NH, "onOpenAction = { nav.navigate(ActionDetailRoute(it)) }")],
     ["S13>S15:open-action", "S13>S15:open-action#inspector"], el="report-action", label="action card")
edge("S13", "X02", "Share", "external", "", ALL, [(RE, 'testTag("share-pdf")')], [], el="share-pdf", label="Share")
edge("S13", "X04", "Save a copy…", "external", "", ALL, [(RE, 'testTag("save-pdf")')], [], el="save-pdf", label="Save a copy")
edge("S13", "X03", "Other app", "external", "snackbar when no PDF app is installed", ALL, [(RE, 'testTag("open-external")')], [], el="open-external",
     label="Other app")
edge("S13", "S13", "Create again / Create PDF again (regenerate)", "state", "", ALL, [(RE, 'testTag("regenerate-pdf")')], [])
edge("S14", "X02", "Share icon", "external", "", ALL, [(PV, 'FileSharing.sharePdf(context, f, "SafeStitch report")')], [], el="pdf-share", label="Share")

# --- corrective action detail ---------------------------------------------------------------------
edge("S15", "S13", "Open inspection report", "nav", "", ALL,
     [(AC, 'testTag("open-inspection")'), (NH, "onOpenInspection = { nav.navigate(ReportRoute(it)) }")], ["S15>S13:open-inspection-report"],
     el="open-inspection", label="Open inspection report")
edge("S15", "S12", "Add after-fix photo", "nav", "status In progress", ALL,
     [(AC, 'testTag("after-fix-photo")'),
      (NH, "onAddAfterFixPhoto = { inspectionId, actionId -> nav.navigate(PhotoCaptureRoute(inspectionId, actionId = actionId)) }")],
     ["S15>S12:after-fix-photo"], el="after-fix", label="after-fix photo")
edge("S15", "D06", "Not fixed: send back", "popup", "status Resolved and user.isAdmin", ADMIN, [(AC, 'testTag("reopen-action")')],
     ["S15>D06:send-back"], el="send-back")
edge("D06", "S15", "Send back → status In progress", "state", "comment required", ADMIN,
     [(AC, "container.repository.sendBackAction(current.action.id, user, sendBackComment)")], [], label="Send back")
edge("S15", "S15", "Start work: Open → In progress", "state", "status Open", ALL, [(AC, 'testTag("start-action")')], ["S15:status-open", "S15:start-work"])
edge("S15", "S15", "Mark as resolved: In progress → Resolved", "state", "status In progress; note required", ALL,
     [(AC, 'testTag("resolve-action")')], ["S15:resolve"])
edge("S15", "S15", "Verify & close: Resolved → Verified", "state", "status Resolved and user.isAdmin", ADMIN,
     [(AC, 'testTag("verify-action")'), (IR, "requireAdmin(admin)")], ["S15:verify"])
edge("S15", "S15", "Inspector at Resolved: 'Only an admin can verify this fix.'", "state", "status Resolved, inspector", "Inspector",
     [(AC, 'testTag("verify-locked")')], ["S15:verify-locked-for-inspector"])
edge("S15", "D14", "Admin: due date", "popup", "user.isAdmin and not Verified", ADMIN, [(AC, 'testTag = "action-due"')], [], el="admin-due")
edge("S15", "D15", "Admin: responsible person", "popup", "user.isAdmin and not Verified", ADMIN, [(AC, 'testTag = "action-responsible-select"')], [],
     el="admin-resp")
edge("S15", "S15", "Admin: change priority (resets the due date)", "state", "user.isAdmin and not Verified", ADMIN, [(AC, 'tagPrefix = "action-priority"')], [])

# --- settings / chat ------------------------------------------------------------------------------
edge("S16", "D13", "Change my password", "popup", "", ALL, [(SE, 'testTag("change-password")')], ["S16>D13:change-password"],
     el="change-password")
edge("D13", "S16", "Save → 'Password changed.'", "state", "current password correct, new ≥ 8 and repeated", ALL,
     [(SE, "container.auth.changePassword(user, current, new)")], [], label="Save")
edge("S17", "X07", "Send / quick prompt", "external", "server reachable (emulator + local dev server), else local fallback reply", ALL,
     [(CH, 'testTag("chat-send")'), ("chat/SafetyChatService.kt", "?: buildContextualReply(")], ["S17:send-quick-prompt"], el="chat-send",
     label="send prompt", status=PARTIAL)
edge("S17", "S17", "Persona / model chips, quick prompts, mic, Clear", "state", "", ALL,
     [(CH, 'testTag("chat-role-${persona.id}")'), (CH, 'testTag("chat-clear")'), (CH, 'testTag("chat-mic-record")')], [])

# --- admin ----------------------------------------------------------------------------------------
for dst, name, tag, cb, rt, elid in (
        ("S19", "Users", '"admin-users", onUsers', "onUsers = { nav.navigate(UsersRoute) }", "S18>S19:users", "admin-users"),
        ("S20", "Locations", '"admin-locations", onLocations', "onLocations = { nav.navigate(LocationsRoute()) }", "S18>S20:locations", "admin-locations"),
        ("S04", "Equipment", '"admin-equipment", onEquipment', "onEquipment = { nav.navigate(InspectRoute) { launchSingleTop = true } }", "S18>S04:equipment", "admin-equipment"),
        ("S21", "Checklist questions", '"admin-checklists", onChecklists', "onChecklists = { nav.navigate(ChecklistEditorRoute) }", "S18>S21:checklists", "admin-checklists"),
        ("S23", "SQL Database", '"admin-sql-database", onSqlDatabase', "onSqlDatabase = { nav.navigate(SqlDatabaseRoute) }", "S18>S23:sql-database", "admin-sql"),
        ("S22", "Sync", '"admin-sync", onSync', "onSync = { nav.navigate(SyncRoute) }", "S18>S22:sync", "admin-sync")):
    edge("S18", dst, name, "nav", "", ADMIN, [(AD, tag), (NH, cb)], [rt], el=elid, label=name)

edge("S19", "D08", "+ (FAB)", "popup", "", ADMIN, [(AD, 'testTag("add-user")')], ["S19>D08:add-user", "D08>S19:add-user-save"], el="add-user")
edge("S19", "D09", "Reset password", "popup", "", ADMIN, [(AD, 'TextButton(onClick = { resetting = user }) { Text("Reset password") }')],
     ["S19>D09:reset-password"], el="reset")
edge("S19", "S19", "Activate / Deactivate (not yourself; keep ≥ 1 active admin)", "state", "", ADMIN,
     [(AD, "container.auth.setActive(admin, user.id, !user.active)"), (AR, '"Keep at least one active admin."')], [])
edge("S20", "S20", "Tap row → next level (Factory → Building → Floor → Area)", "nav", "level < Area", ADMIN,
     [(LO, "onClick = { if (child != null) onOpenChild(child, node.id,"),
      (NH, "onOpenChild = { childLevel, nodeId, nodeName -> nav.navigate(LocationsRoute(childLevel.ordinal, nodeId, nodeName)) }")],
     ["S20>S20:open-child-L1", "S20>S20:open-child-L2", "S20>S20:open-child-L3"], el="loc-row", label="row › next level")
edge("S20", "D10", "Add <level> (FAB) / ✎ rename", "popup", "", ADMIN, [(LO, 'testTag("add-location")'), (LO, "IconButton(onClick = { editing = node })")],
     ["S20>D10:add-location-L0", "S20>D10:add-location-L1", "S20>D10:add-location-L2", "S20>D10:add-location-L3"], el="add-location")
edge("S21", "D11", "Add question (FAB) / ✎ edit", "popup", "", ADMIN, [(CE, 'testTag("add-question")'), (CE, 'testTag("question-$index-edit")')],
     ["S21>D11:add-question"], el="add-question")
edge("S21", "S21", "Type chips, move ↑/↓, In use switch", "state", "", ADMIN,
     [(CE, "container.catalog.moveQuestion(q, up = true)"), (CE, 'testTag("question-$index-active")')], [])
edge("S22", "D12", "Re-upload everything", "popup", "sync enabled", ADMIN, [(AD, 'testTag("sync-resend-all")')], [], el="resend")
edge("S22", "X06", "Save (on) / Sync now", "external", "sync enabled; WorkManager waits for a network", ADMIN,
     [(AD, 'testTag("sync-now")'), ("sync/SyncManager.kt", "fun syncNow() = enqueue(ExistingWorkPolicy.REPLACE)")],
     ["S22:sync-now-disabled-when-off"], el="sync-now", label="Sync now")
edge("S22", "S22", "Conflicts: Keep server copy / Send mine again", "state", "conflicts exist", ADMIN,
     [(AD, "container.repository.resolveConflict(conflict, sendMine = false)")], [])
edge("S23", "S23", "Preset queries, Run SQL Query, Refresh Counts, table card → SELECT *", "state", "", ADMIN,
     [(AD, 'testTag("sql-preset-${preset.id}")'), (AD, 'testTag("sql-run-query")'), (AD, 'testTag("sql-table-${table.tableName}")')], [])

# --- permission gate / background ----------------------------------------------------------------
edge("D16", "X05", "Open app settings", "external", "permission denied once", ALL, [(CO, 'Text("Open app settings")')], [], label="app settings")
edge("APP", "X06", "App start / any syncable change → requestSync()", "background", "sync enabled + network", ALL,
     [("SafeStitchApp.kt", "container.syncManager.requestSync()"), ("AppContainer.kt", "repo.onSyncableChange = { syncManager.requestSync() }")], [],
     label="background sync")

# ----------------------------------------------------------------------------------------------
#  Declared-but-unreachable code (for the gaps report)
# ----------------------------------------------------------------------------------------------
UNREACHABLE_ITEMS = [
    ("InspectionListScreen (alias of CapturedInspectionsScreen)", CA, "fun InspectionListScreen("),
    ("CapturedInspectionDetailsScreen (alias of InspectionDetailsScreen)", DE, "fun CapturedInspectionDetailsScreen("),
    ("ChatScreen (alias of GeminiChatScreen)", CH, "fun ChatScreen("),
    ("DatabaseSeed.seedSampleData (sample factory, FE-101… equipment and inspections)", "data/db/DatabaseSeed.kt", "fun seedSampleData("),
]

# ----------------------------------------------------------------------------------------------
#  Audit additions (independent extractor, 2026-11-15): dialog outcomes, stack-dependent Back,
#  permission results and the camera shutter. Kinds: dialog_result (result of a pop-up button),
#  back (popBackStack to whatever screen is underneath — not a fixed destination).
# ----------------------------------------------------------------------------------------------
SC_ = SC
SE_ = SE

# dialog outcomes ---------------------------------------------------------------------------------
edge("D01", "S03", "Dismiss menu (tap outside / Back)", "dialog_result", "", ALL, [(DB, "DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false })")],
     [], label="dismiss")
edge("D02", "S04", "Dismiss (tap outside / Back)", "dialog_result", "", ADMIN, [(FE, "onDismissRequest = { vm.labels = null }")], [], label="dismiss")
edge("D03", "S11", "OK / dismiss → fix the listed problems", "dialog_result", "", ALL,
     [(IN, "confirmButton = { TextButton(onClick = vm::dismissProblems) { Text(\"OK\") } }")], [], label="OK")
edge("D04", "S11", "Cancel / dismiss (inspection kept)", "dialog_result", "", ALL,
     [(IN, 'dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }')], [], label="Cancel")
edge("D05", "S11", "Remove → photo deleted (not in the PDF)", "dialog_result", "", ALL,
     [(IN, "vm.deletePhoto(photo.id)")], [], label="Remove")
edge("D05", "S11", "Keep / dismiss", "dialog_result", "", ALL, [(IN, 'TextButton(onClick = { photoToDelete = null }) { Text("Keep") }')], [], label="Keep")
edge("D06", "S15", "Cancel / dismiss (stays Resolved)", "dialog_result", "", ADMIN,
     [(AC, 'dismissButton = { TextButton(onClick = { sendBackOpen = false }) { Text("Cancel") } }')], [], label="Cancel")
edge("D07", "S08", "Cancel / dismiss (record kept)", "dialog_result", "", ALL, [(DE, "onDismissRequest = { confirmDelete = false }")], [], label="Cancel")
edge("D08", "S19", "Add → user created (snackbar); errors stay in the dialog", "dialog_result", "valid name, username, password ≥ 8, passwords match", ADMIN,
     [(AD, "error = container.auth.createUser(admin, name, username, role, password)")], [], label="Add")
edge("D08", "S19", "Cancel / dismiss", "dialog_result", "", ADMIN, [(AD, "dismissButton = { TextButton(onClick = { adding = false }, enabled = !busy) { Text(\"Cancel\") } }")], [],
     label="Cancel")
edge("D09", "S19", "Save → password reset (snackbar) / Cancel", "dialog_result", "new password ≥ 8", ADMIN,
     [(AD, "error = container.auth.resetPassword(admin, target.id, password)")], [], label="Save / Cancel")
edge("D10", "S20", "Save → location added or renamed / Cancel", "dialog_result", "name not blank", ADMIN,
     [(LO, "container.catalog.addLocation(level, parentId, name) else container.catalog.renameLocation(level, node.id, name)")], [],
     label="Save / Cancel")
edge("D11", "S21", "Save → question added or edited / Cancel", "dialog_result", "section and question not blank", ADMIN,
     [(CE, "container.catalog.addQuestion(type, section, text, critical)")], [], label="Save / Cancel")
edge("D12", "X06", "Re-upload → reset sync state + sync now", "external", "sync enabled", ADMIN,
     [(AD, "container.repository.resetSyncState()"), (AD, "sync.syncNow()", 2)], [], label="Re-upload")
edge("D12", "S22", "Cancel / dismiss", "dialog_result", "", ADMIN, [(AD, "onDismissRequest = { confirmResend = false }")], [], label="Cancel")
edge("D13", "S16", "Cancel / dismiss", "dialog_result", "", ALL, [(SE_, "dismissButton = { TextButton(onClick = { changing = false }) { Text(\"Cancel\") } }")], [],
     label="Cancel")
for _o in ("S11", "S15"):
    edge("D14", _o, "OK → due date set / Cancel", "dialog_result", "", ALL if _o == "S11" else ADMIN,
         [("ui/components/Pickers.kt", "DatePickerDialog(")], [], label="OK / Cancel")
for _o in ("S04", "S06", "S10", "S11", "S15"):
    edge("D15", _o, "Option selected → field updated", "dialog_result", "", ADMIN if _o == "S15" else ALL,
         [("ui/components/Pickers.kt", "DropdownMenuItem(")], [], label="option selected")
for _o in ("S03", "S09", "S12"):
    edge("D16", _o, "Permission granted → camera preview shown", "dialog_result", "user allows the camera", ALL,
         [("ui/components/Common.kt", "launcher.launch(Manifest.permission.CAMERA)")], [], label="granted")

# stack-dependent Back (← in the app bar, or the system Back gesture) --------------------------------
_BACK = {
    "S07": (CA, "BackButton(onBack = onBack)"), "S08": (DE, "BackButton(onBack = onBack)"),
    "S09": (SC_, "navigationIcon = { BackButton(onBack) }"), "S10": (CF, "navigationIcon = { BackButton(onBack) }"),
    "S11": (IN, "navigationIcon = { BackButton(onBack) }"), "S12": (PC, "navigationIcon = { BackButton(onDone) }"),
    "S13": (RE, "navigationIcon = { BackButton(onBack) }"), "S14": (PV, "navigationIcon = { BackButton(onBack) }"),
    "S15": (AC, 'TopAppBar(title = { Text("Corrective action") }, navigationIcon = { BackButton(onBack) })'),
    "S16": (SE_, 'TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) })'),
    "S17": (CH, "navigationIcon = { BackButton(onBack) }"),
    "S18": (AD, 'TopAppBar(title = { Text("Admin tools") }, navigationIcon = { BackButton(onBack) })'),
    "S19": (AD, 'TopAppBar(title = { Text("Users") }, navigationIcon = { BackButton(onBack) })'),
    "S20": (LO, "navigationIcon = { BackButton(onBack) }"), "S21": (CE, "navigationIcon = { BackButton(onBack) }"),
    "S22": (AD, 'TopAppBar(title = { Text("Sync") }, navigationIcon = { BackButton(onBack) })'),
    "S23": (AD, "navigationIcon = { BackButton(onBack) }", 4),
}
for _sid, _ev in _BACK.items():
    edge(_sid, "BACK", "← / system Back → previous screen on the back stack", "back", "destination depends on how the screen was opened",
         (ADMIN if _sid in ("S18", "S19", "S20", "S21", "S22", "S23") else ALL), [_ev], [], label="← back", el=None)
edge("S08", "BACK", "'Back to list' (record not found state)", "back", "captured record no longer exists", ALL, [(DE, 'Text("Back to list")')], [],
     label="Back to list")

# remaining state changes found by the extractor ------------------------------------------------------
edge("S12", "S12", "Shutter: capture + save photo (spinner); error → snackbar, stays", "state", "camera ready", ALL,
     [(PC, "imageCapture.takePicture(")], [])

# ----------------------------------------------------------------------------------------------
#  UI refinements after comparing every frame with the emulator screenshots (2026-11-15).
#  Long screens are drawn full length (taller frames) instead of being cut off.
# ----------------------------------------------------------------------------------------------
S["S03"]["ui"] = [
    bar("SafeStitch", back=False, sub="Walk Admin · Admin", right=["◉"], id="account-menu"),
    card(["Live Camera & ZXing Equipment Scanner", "Auto-identifies equipment via QR or barcode and opens its inspection history"],
         chips_=["ZXing QR / Barcode Active", "Capture tag:", "NEEDS REVIEW", "PASS", "FAIL"]),
    cam(64, "ZXing live: point at QR / barcode, or tap shutter", shutter=True, id="live-scan"),
    card(["Scan Equipment (QR / Barcode → History)", "Supports QR_CODE, CODE_128, CODE_39, EAN_13 & DATA_MATRIX via ZXing."],
         btns_=[("From image", "outline", "from-image")]),
    chips(["Quick scan tag:", "FE-12 · Fire extinguisher FE-12"], -1, id="quick-chip"),
    btns(("Enter or scan QR / barcode (e.g. FE-101)", "input"), ("History", "primary", "dash-history")),
    chips(["Captured frames (3/3):", "All", "NEEDS REVIEW", "PASS", "FAIL"], 1),
    btns(("▣ NEEDS REVIEW", "chip"), ("▣ FAIL · FOLLOW UP", "chip"), ("▣ PASS", "chip")),
    btn("View all captured inspections (3)", "outline", id="view-captured"),
    btn("Scan Equipment (QR / Barcode → Inspection History)", id="dash-scan-equipment"),
    btns(("Scan for Checklist", "outline", "dash-scan"), ("Find equipment", "outline", "dash-find")),
    btn("Safety Assistant Chat (Multi-Turn Advisor)", "outline", id="dash-open-chat"),
    card(["Voice Note Transcription", "Record microphone audio & transcribe via gemini-3.5-transcribe"], btns_=[("Record Mic", "primary", "mic")], id="voice"),
    inp("Transcribed Audio (gemini-3.5-transcribe)", "Field safety voice note: checked FE-101 …"),
    card(["Inspection Summary Overview      [2 Pending Reports]", "1 checklists · 3 camera captures",
          "4 Total Performed · 2 Passed · 2 Pending Reports", "Status chart · Recent Equipment Status Updates ›"], id="summary"),
    sec("Inspections · last 30 days"),
    kpi(("1", "Completed"), ("100%", "Pass rate")), kpi(("85%", "Average score"), ("0", "Drafts in progress")),
    kpi(("1", "Inspections (all time)"), ("0", "Failed · 30 days")),
    sec("Corrective actions (cards open Actions)"),
    kpi(("0", "Open"), ("0", "In progress"), id="kpi"), kpi(("0", "Overdue"), ("1", "Awaiting verification")),
    kpi(("0", "Critical / high not verified"), ("1", "All corrective actions")), kpi(("0", "Verified · 30 days"), ("—", "Fixed on time")),
    sec("Pass rate by building"), card(["BlockB                               100%  (1/1)", "████████████████████"]),
    card(["Works offline", "Everything is stored on this phone. An admin can turn on optional sync under Admin › Sync."], tint="blue"),
    nav("Home")]
S["S04"]["ui"] = [
    bar("Inspect", back=False, right=["PDF"], id="print-labels", admin_right=True),
    btn("Scan the equipment's QR code", id="scan-button"),
    inp("⌕ Search name, code or location"),
    sel("Factory", "All"),
    txt("1 of 1 equipment", "s", "grey"),
    card(["Fire extinguisher FE-12", "FE-12 · Fire extinguisher", "PlantA › BlockB › FloorOne › Cutting"],
         btns_=[("Inspect", "primary", "inspect"), ("Edit", "text", "edit", True)], id="equipment-card"),
    anno("Empty list: 'No equipment yet…' — admins are told to add locations + equipment, inspectors to scan a QR label"),
    fab("+", id="add-equipment", admin=True), nav("Inspect")]
S["S05"]["ui"] = [
    bar("Corrective actions", back=False),
    chips(["To do (1)", "Open (0)", "In progress (1)", "Overdue (0)", "Awaiting verification", "Verified", "All"], 0),
    txt("1 shown · 2 corrective actions in total", "s", "grey"),
    card(["[Medium] [In progress]", "1. Extinguisher is in its marked place and mounted at the right height", "Gauge in red zone",
          "Fire extinguisher FE-12 (FE-12)", "PlantA › BlockB › FloorOne › Cutting", "Responsible: Walk Admin · Due Nov 22, 2026"], id="action-card"),
    anno("A filter with no matches shows only the count line — no explanation (e.g. 'To do (0)' after everything is verified)"),
    anno("No actions at all: 'No corrective actions yet. Every failed checklist item creates one when the inspection is submitted.'"),
    nav("Actions")]
S["S06"]["ui"] = [
    bar("Inspection history", back=False, right=["Captured (3)"], id="captured-btn"),
    card(["Equipment Identified via QR / Barcode", "FE-12 · Fire extinguisher FE-12", "Fire extinguisher · Cutting"],
         btns_=[("Show all", "text")], tint="blue"),
    card(["📷 Captured Inspection Photos (3)", "Tap any captured photo below to open Inspection Details (full-res image & tag editor) or view all"],
         chev=True, id="captured-banner"),
    btns(("Nov 15 5:06 · NEEDS REVIEW", "chip", "strip"), ("Nov 15 4:21 · FAIL", "chip")),
    inp("⌕ Search equipment, location or inspector"),
    chips(["All", "Drafts", "Completed"], 0), chips(["Any result", "Passed", "Failed"], 0),
    chips(["7 days", "30 days", "90 days", "All time"], 3),
    btn("More filters: location, inspector, type", "text"),
    btns(("Factory ▾ All", "select"), ("Inspector ▾ All", "select"), ("Type ▾ All", "select")),
    txt("1 of 1 inspection", "s", "grey"),
    card(["Fire extinguisher FE-12            [PASS 85%]", "FE-12 · Walk Admin", "PlantA › BlockB › FloorOne › Cutting",
          "Submitted Nov 15, 2026, 6:49 AM"], id="history-card"),
    card(["Fire extinguisher FE-12            [Draft 3/7]", "FE-12 · Walk Inspector · Started …"], id="history-draft"),
    nav("History")]
S["S07"]["ui"] = [
    bar("Captured Inspections", sub="3 of 3 captured photos"),
    chips(["All (3)", "NEEDS REVIEW (1)", "PASS (1)", "FAIL (1)", "FOLLOW UP (1)"], 0),
    card(["▣  Nov 15, 2026, 5:06 AM            🗑", "IMG_EL204_panel_mat.jpg", "[NEEDS REVIEW]"],
         chips_=["Tags:", "NEEDS REVIEW", "PASS", "FAIL", "FOLLOW UP"], id="captured-card"),
    card(["▣  Nov 15, 2026, 4:21 AM            🗑", "IMG_EX102_corridor_obstruction.jpg", "[FAIL] [FOLLOW UP]"],
         chips_=["Tags:", "NEEDS REVIEW", "PASS", "FAIL", "FOLLOW UP"]),
    card(["▣  Nov 15, 2026, 3:21 AM            🗑", "IMG_FE101_gauge_check.jpg", "[PASS]"], chips_=["Tags:", "NEEDS REVIEW", "PASS", "FAIL"]),
    anno("🗑 deletes the record and photo immediately — no confirmation on this list"),
    anno("Empty: 'No captured inspection photos yet' / '… with tag X'")]
S["S08"]["ui"] = [
    bar("Inspection Details", sub="Capture #3 · FE-12 · Nov 15, 2026, 5:06 AM", right=["PDF", "🗑"], id="details-delete"),
    card(["Full-Resolution Image (1.0x)                [−] [+] [Reset]"]),
    img(84, "photo — or a generated placeholder when the file is missing"),
    txt("Pinch with two fingers or use + / − to inspect details up to 4.0× zoom.", "s", "grey"),
    card(["Equipment Details                      [Auto-linked]", "Equipment · Equipment code · Category · Location · Inspector",
          "Link to registered equipment (included in PDFBox report):"], chips_=["Auto-link", "FE-12 · Fire extinguisher FE-12"]),
    card(["Capture & File Details", "Captured at · File name · Size · Resolution"]),
    card(["Status Tags", "Tap preset status chips to toggle tags or add a custom tag below. Changes save immediately offline."],
         chips_=["NEEDS REVIEW", "PASS", "FAIL", "FOLLOW UP"]),
    txt("Custom tags (tap to remove):  LINE_4 ×", "s"),
    btns(("Add custom tag (e.g. LINE_4, URGENT)", "input"), ("Add tag", "primary")),
    card(["Branded PDF Export (PDFBox)", "Exports the image, timestamp, equipment details and status tags into a branded A4 PDF.",
          "PDF ready: capture_3.pdf"],
         btns_=[("Share PDF", "outline", "details-share"), ("Save a copy…", "outline", "details-save"), ("Open in app", "text", "details-open")]),
    btn("Export Branded PDF", id="details-export"),
    img(54, "Exported PDF Preview (Page 1)"),
    anno("Record missing: 'Captured inspection record not found.' + [Back to list]")]
S["S10"]["ui"] = [
    bar("New equipment", sub="or 'Edit equipment'"),
    card(["Code NEW-99 is not registered yet. Describe it once; every later scan opens its checklist."], tint="amber"),
    inp("Equipment code (on the QR label)", "read-only when scanned / editing"),
    inp("Name", "e.g. Fire extinguisher FE-12"),
    sec("Location"),
    sel("Factory", "Not set"),
    btns(("Building ▾", "select"), ("Floor ▾", "select"), ("Area ▾", "select")),
    anno("Building / Floor / Area appear after the level above is chosen"),
    inp("Exact spot (optional)", "e.g. next to stair B"),
    sec("Type (decides the checklist)"),
    radio(["Fire extinguisher", "Electrical panel", "Fire alarm", "Emergency exit", "Boiler"], 0),
    txt("Enter the code printed on (or planned for) its QR label.", "s", "red"),
    btn("Save and start inspection | Save equipment | Save changes", id="cp-save")]
S["S11"]["ui"] = [
    bar("Fire extinguisher FE-12", sub="FE-12 · Fire extinguisher", right=["🗑"], id="delete-inspection"),
    card(["Inspector: Walk Admin", "Location: PlantA › BlockB › FloorOne › Cutting", "Started Nov 15, 2026, 6:46 AM"]),
    prog("0 of 7 answered", 0.0), chips(["0 pass", "0 fail", "0 N/A"], -1),
    sec("Fire extinguisher"),
    card(["1. Extinguisher is in its marked place and mounted at the right height"], id="item"),
    seg(["Pass", "Fail", "N/A"], 1),
    inp("Problem and corrective action (required)", "Gauge in red zone"),
    sel("Responsible for the fix", "Walk Admin", id="responsible"),
    txt("Finding priority", "s", None, True), chips(["Critical", "High", "Medium", "Low"], 2),
    btns(("Due Nov 22, 2026", "outline", "due"), ("📷 Photo (1)", "text", "item-photo")),
    img(24, "▣ photo thumbnail — tap → 'Remove photo?'"),
    card(["2. Pressure gauge reads in the green (charged) zone", "Critical item"]),
    seg(["Pass", "Fail", "N/A"], -1),
    btns(("📷 Add photo", "text"), ("Add note", "text")),
    txt("… items 3–7 (safety pin, service tag, hose, …)", "s", "grey"),
    card(["General notes & photos"], btns_=[("📷 Add general photo", "text", "general-photo")]),
    inp("General notes (optional)"),
    subbar("0/7 answered · 7 left", "Submit & create PDF", id="submit")]
S["S13"]["ui"] = [
    bar("Inspection report"),
    card(["Fire extinguisher FE-12", "FE-12 · Fire extinguisher", "PlantA › BlockB › FloorOne › Cutting", "Result: PASS",
          "Score 85% · 6 passed · 1 failed · 0 N/A", "Inspector: Walk Admin", "Submitted Nov 15, 2026, 6:49 AM"]),
    card(["PDF report", "PDF ready: SafeStitch_FE-12_20261115-0649_R1.pdf", "2 pages · 160 KB"],
         btns_=[("Open PDF", "primary", "open-pdf"), ("Share", "outline", "share-pdf")]),
    btns(("Save a copy…", "outline", "save-pdf"), ("Other app", "text", "open-external"), ("Create again", "text", "regen")),
    card(["Corrective actions (1)", "1. Extinguisher is in its marked place …", "[Medium] [Open]  Due Nov 22, 2026"], id="report-action", chev=True),
    sec("Preview of page 1"), img(70, "PDF page 1 (rendered with PdfRenderer)"),
    anno("File missing: 'The PDF file is missing on this phone.' + [Create PDF again]")]
S["S14"]["ui"] = [bar("SafeStitch_FE-12_20261115-…", right=["⇪"], id="pdf-share"),
                  img(150, "Page 1 (PdfRenderer bitmap)"), img(150, "Page 2 — Photos")]
S["S15"]["ui"] = [
    bar("Corrective action"),
    chips(["Medium", "In progress", "Overdue (if late)"], -1),
    txt("1. Extinguisher is in its marked place and mounted at the right height", "m", None, True),
    txt("Finding: Gauge in red zone", "s"), txt("Responsible: Walk Admin", "s"), txt("Due Nov 22, 2026", "s"),
    card(["Fire extinguisher FE-12 (FE-12)", "PlantA › BlockB › FloorOne › Cutting"], btns_=[("Open inspection report", "text", "open-inspection")]),
    txt("Evidence photos  ▣        After-fix photos (1)  ▣", "s", "grey"),
    sec("OPEN"), btn("Start work", id="start"),
    sec("IN PROGRESS"), btn("Add after-fix photo (optional)", "outline", id="after-fix"), inp("What was done to fix it?"),
    btn("Mark as resolved", id="resolve"),
    sec("RESOLVED"), txt("Fix reported: Recharged extinguisher", "s"),
    btns(("Verify & close", "primary", "verify", True), ("Not fixed: send back", "outline", "send-back", True)),
    txt("Inspector instead sees: 'Only an admin can verify this fix.'", "s", "grey"),
    sec("VERIFIED"), txt("Closed: verified by Walk Admin on Nov 15, 2026", "s", "primary"),
    card(["History", "• Created (Open) · Walk Admin · Nov 15 6:49", "• Open → In progress · …", "• In progress → Resolved – awaiting verification"]),
    card(["Admin: responsible person, priority and due date"], admin=True, id="admin-panel"),
    sel("Responsible person", "Walk Admin", id="admin-resp", admin=True),
    chips(["Critical", "High", "Medium", "Low"], 2, admin=True),
    btn("Due Nov 22, 2026", "outline", id="admin-due", admin=True)]
S["S20"]["ui"] = [
    bar("Locations", sub="or 'PlantA › Buildings' … 'PlantA › BlockB › FloorOne › Areas'"),
    txt("Factory › building › floor › area. Equipment is assigned to an area. Renaming keeps past inspection reports unchanged.", "s", "grey"),
    card(["PlantA                                              ✎    ›"], id="loc-row"),
    card(["PlantB                                              ✎    ›"]),
    anno("Areas (4th level) show ✎ only — no ›. There is no delete at any level."),
    anno("Empty level: 'No factories yet. Tap \"Add factory\".'"),
    fab("+ Add factory | building | floor | area", id="add-location", wide=True)]
S["S21"]["ui"] = [
    bar("Checklist questions"),
    chips(["Fire extinguisher", "Electrical panel", "Fire alarm", "Emergency exit", "Boiler", "Sewing line (earlier)"], 0),
    txt("Changes apply to new inspections. Inspections already started or submitted keep the questions they had. "
        "Switch a question off instead of deleting it.", "s", "grey"),
    card(["Fire extinguisher", "1. Extinguisher is in its marked place and mounted at the right height", "↑   ↓   ✎          In use   [on]"], id="q-card"),
    card(["Fire extinguisher", "2. Pressure gauge reads in the green (charged) zone", "Critical", "↑   ↓   ✎          In use   [on]"]),
    card(["Fire extinguisher", "5. Hose and nozzle are undamaged and unblocked", "↑   ↓   ✎          Switched off   [off]"]),
    fab("+ Add question", id="add-question", wide=True)]
S["S22"]["ui"] = [
    bar("Sync"),
    txt("Off by default. SafeStitch always works offline. When sync is on, submitted inspections, corrective-action changes and "
        "photos are uploaded to your server; offline they wait in a queue that survives restarts. Passwords are never uploaded.", "s", "grey"),
    switch("Sync to server", False),
    inp("Server address", "https://sync.example.com"), inp("API key", "••••••"),
    txt("Test build: http://10.0.2.2 and http://localhost allowed. Release builds require https.", "s", "grey"),
    btns(("Save", "primary", "sync-save"), ("Sync now", "outline", "sync-now")),
    card(["Status: Sync is off", "Waiting to upload: 0 record(s), 0 photo(s)", "Device ID: 52b88ea7…"], tint="blue"),
    txt("Conflicts: 0 — None. The server and this phone agree.", "s"),
    anno("Conflict cards (when present): [Keep server copy] [Send mine again]"),
    btn("Re-upload everything", "text", id="resend")]
S["S23"]["ui"] = [
    bar("SQL Database"),
    card(["Engine: SQLite 3 · Room v2 (safestitch.db)", "13 relational tables · 72 total rows persisted on device"], tint="blue"),
    sec("Preset SQL Queries"),
    btns(("Equipment & Inspections", "outline"), ("Captured Photos", "outline")),
    btns(("Corrective Actions", "outline"), ("Factory Locations", "outline")),
    inp("Read-only SQL query (SELECT / PRAGMA)", "SELECT c.code, c.name, … FROM checkpoints c …"),
    btns(("Run SQL Query", "primary"), ("Refresh Counts", "outline")),
    card(["Result: 0 row(s) in 3 ms", "code | name | type | inspections", "No rows returned."]),
    sec("Relational Tables (13)"),
    card(["action_events                                   0 row(s)", "id, actionId, fromStatus, toStatus, byName, …"]),
    card(["areas                                               1 row(s)", "id, floorId, name"])]

# dialog buttons now state their outcome (shown in the pop-up trays and module dialog cards)
_OUT = {
    "D02": ["Open ⇒ PDF app", "Share / print ⇒ share sheet"], "D03": ["OK ⇒ back to the checklist"],
    "D04": ["Cancel ⇒ stays", "Delete ⇒ previous screen"], "D05": ["Keep", "Remove ⇒ photo deleted"],
    "D06": ["Cancel", "Send back ⇒ In progress"], "D07": ["Cancel", "Delete ⇒ previous screen"],
    "D08": ["Cancel", "Add ⇒ user created"], "D09": ["Cancel", "Save ⇒ password reset"],
    "D10": ["Cancel", "Save ⇒ added / renamed"], "D11": ["Cancel", "Save ⇒ question saved"],
    "D12": ["Cancel", "Re-upload ⇒ sync queued"], "D13": ["Cancel", "Save ⇒ password changed"],
    "D14": ["Cancel", "OK ⇒ due date set"], "D16": ["Allow camera ⇒ preview", "Open app settings"],
}
for _k, _v in _OUT.items():
    D[_k]["buttons"] = _v
