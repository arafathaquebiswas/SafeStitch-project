"""Independent extraction of interactive operations from the Kotlin/Compose source.

This module deliberately does NOT use model.py's evidence tokens. It re-reads the sources and finds:
  * references to each screen composable's navigation callbacks (on…), resolved through SafeStitchNavHost
    to the destination route(s);
  * dialog/menu openers (assignments to the state variables that guard AlertDialog / DropdownMenu) and the
    outcomes of every dialog button (confirm, dismiss, onDismissRequest);
  * ViewModel-event navigation (Event.X -> onY(...)) inside LaunchedEffect collectors;
  * Android hand-offs (share, view, save-as, photo picker, app settings, WorkManager sync, dev endpoint)
    and auth-state changes (sign in / create admin / sign out);
  * bottom-bar tabs and MainActivity's auth-state branches.
compare() then matches each operation to the model's diagram edges and lists both directions of mismatch.
"""
import os
import re

ROUTE_TO_SCREEN = {
    "DashboardRoute": "S03", "InspectRoute": "S04", "ActionsRoute": "S05", "HistoryRoute": "S06",
    "CapturedInspectionsRoute": "S07", "CapturedInspectionDetailRoute": "S08", "ScanRoute": "S09",
    "CheckpointFormRoute": "S10", "InspectionRoute": "S11", "PhotoCaptureRoute": "S12", "ReportRoute": "S13",
    "PdfViewerRoute": "S14", "ActionDetailRoute": "S15", "SettingsRoute": "S16", "GeminiChatRoute": "S17",
    "AdminRoute": "S18", "UsersRoute": "S19", "LocationsRoute": "S20", "ChecklistEditorRoute": "S21",
    "SyncRoute": "S22", "SqlDatabaseRoute": "S23",
}
EXTERNAL = [
    (r"FileSharing\.sharePdf\(", "X02"), (r"FileSharing\.openPdf\(", "X03"), (r"(?:saveLauncher|savePdfLauncher)\.launch\(", "X04"),
    (r"(?:pickImage|pickBarcodeImage)\.launch\(", "X01"), (r"openAppSettings\(", "X05"), (r"sync\.syncNow\(\)", "X06"),
    (r"transcribeService\.(?:startMicrophoneRecording|stopAndTranscribe)|toggleMicRecording|sendPrompt\(", "X07"),
]


def balanced(s, i, open_ch="(", close_ch=")"):
    """Index just after the bracket that closes the one at s[i]."""
    depth, in_str, j = 0, False, i
    while j < len(s):
        c = s[j]
        if c == '"' and s[j - 1] != "\\":
            in_str = not in_str
        elif not in_str:
            if c == open_ch:
                depth += 1
            elif c == close_ch:
                depth -= 1
                if depth == 0:
                    return j + 1
        j += 1
    return len(s)


def line_of(s, i):
    return s.count("\n", 0, i) + 1


def label_near(lines, ln):
    """Component label: a testTag on the line or the next few, else the first string literal."""
    for k in range(ln - 1, min(ln + 6, len(lines))):
        m = re.search(r'testTag\("([^"]+)"\)', lines[k])
        if m:
            return f'testTag "{m.group(1)}"'
    for k in range(ln - 1, min(ln + 8, len(lines))):
        m = re.search(r'Text\("([^"]+)"', lines[k]) or re.search(r'"([A-Z][^"]{2,})"', lines[k])
        if m:
            return f'"{m.group(1)}"'
    return lines[ln - 1].strip()[:70]


def admin_gated(lines, ln):
    return any("isAdmin" in lines[k] for k in range(max(0, ln - 9), ln))


def extract(src_root, M):
    rd = lambda f: open(os.path.join(src_root, f), encoding="utf-8").read()
    nav = rd("ui/navigation/SafeStitchNavHost.kt")
    ops = []

    def add(src, comp, dst, kind, f, ln, role=None, note=""):
        ops.append(dict(src=src, comp=comp, dst=dst, kind=kind, file=f, line=ln, role=role, note=note))

    # ---- MainActivity auth branches and auth-state changes
    ma = rd("MainActivity.kt")
    for m in re.finditer(r"AuthState\.(\w+) -> (\w+)\(", ma):
        tgt = {"SetupAdminScreen": "S01", "LoginScreen": "S02"}.get(m.group(2))
        if tgt:
            add("S00", f"AuthState.{m.group(1)}", tgt, "auth-branch", "MainActivity.kt", line_of(ma, m.start()))
    m = re.search(r"is AuthState\.SignedIn -> .*SafeStitchNavHost", ma)
    if m:
        add("S00", "AuthState.SignedIn", "S03", "auth-branch", "MainActivity.kt", line_of(ma, m.start()))
    au = rd("ui/auth/AuthScreens.kt")
    for pat, src in ((r"auth\.createFirstAdmin\(", "S01"), (r"auth\.signIn\(", "S02")):
        mm = re.search(pat, au)
        add(src, label_near(au.splitlines(), line_of(au, mm.start())), "S03", "auth", "ui/auth/AuthScreens.kt", line_of(au, mm.start()))
    # ---- bottom bar
    for m in re.finditer(r'Tab\((\w+?)(?:\(\))?, "(\w+)"', nav):
        add("TABS", f'tab "{m.group(2)}"', ROUTE_TO_SCREEN[m.group(1)], "tab", "ui/navigation/SafeStitchNavHost.kt", line_of(nav, m.start()))

    # ---- NavHost wiring: screen fn -> {callback: ([destinations], line)}
    wiring = {}
    for m in re.finditer(r"composable<(\w+)>", nav):
        body_start = nav.find("{", m.end())
        body_end = balanced(nav, body_start, "{", "}")
        body = nav[body_start:body_end]
        fm = re.search(r"\b(\w+Screen)\(", body)
        if not fm:
            continue
        call_start = body.find("(", fm.start())
        call = body[call_start:balanced(body, call_start)]
        cbs = {}
        for cm in re.finditer(r"\b(on[A-Z]\w*)\s*=\s*", call):
            vs = cm.end()
            val = call[vs:balanced(call, vs, "{", "}")] if call[vs] == "{" else re.match(r"[\w:]+", call[vs:]).group(0)
            dsts = ["S13", "S11"] if val == "::openInspection" else []
            dsts += [ROUTE_TO_SCREEN[r] for r in re.findall(r"nav\.navigate\((\w+Route)", val)]
            if "popBackStack" in val:
                dsts.append("BACK")
            cbs[cm.group(1)] = (dsts, line_of(nav, body_start + call_start + cm.start()))
        wiring[fm.group(1)] = cbs

    screen_by_fn = {s["fn"][4:-1]: sid for sid, s in M.S.items() if s["fn"].startswith("fun ")}
    files = sorted({s["file"] for s in M.S.values() if s["file"].startswith("ui/")})
    dialog_ids_by_file = {}
    for did, d in M.D.items():
        dialog_ids_by_file.setdefault(d["file"], []).append((did, int(d["ref"].rsplit(":", 1)[1])))
    for f in files:
        txt = rd(f)
        lines = txt.splitlines()
        tops = [(m.group(1), line_of(txt, m.start() + 1)) for m in re.finditer(r"\n(?:private )?fun (\w+)\(", txt)]

        def owner(ln):
            o = None
            for name, l0 in tops:
                if l0 <= ln and name in screen_by_fn:
                    o = screen_by_fn[name]
                elif l0 <= ln and name in ("InspectionListScreen", "CapturedInspectionDetailsScreen", "ChatScreen"):
                    o = None
            return o

        dblocks = []
        for m in re.finditer(r"\b(AlertDialog|DropdownMenu|DatePickerDialog)\(", txt):
            a, b = m.start(), balanced(txt, m.end() - 1)
            rest = txt[b:b + 3].lstrip()
            if rest.startswith("{"):
                b = balanced(txt, txt.index("{", b), "{", "}")
            l0, l1 = line_of(txt, a), line_of(txt, b)
            cands = dialog_ids_by_file.get(f, [])
            did = min(cands, key=lambda t: abs(t[1] - l0)) if cands else ("?", -99)
            did = did[0] if abs(did[1] - l0) <= 8 else "?"
            pre = "\n".join(lines[max(0, l0 - 12):l0])
            gv = {g for t in re.findall(r"if \((?:vm\.)?(\w+)(?:\.isNotEmpty\(\))?\)|(\w+)\?\.let|if \((\w+) \|\| (\w+) != null\)", pre) for g in t if g}
            em = re.search(r"expanded\s*=\s*(\w+)", txt[a:a + 80])
            if em:
                gv.add(em.group(1))
            dblocks.append(dict(id=did, l0=l0, l1=l1, guards=gv))

        def dialog_at(ln):
            return next((d for d in dblocks if d["l0"] <= ln <= d["l1"]), None)

        # dialogs guarded by ViewModel state: find VM methods that assign the guard, then handlers calling them
        vm_src = txt
        vmf = f.replace("Screen.kt", "ViewModel.kt")
        if os.path.isfile(os.path.join(src_root, vmf)):
            vm_src += rd(vmf)
        for db in dblocks:
            for g in list(db["guards"]):
                for mm in re.finditer(r"fun (\w+)\([^)]*\)[^{=]*[{=]", vm_src):
                    body = vm_src[mm.end():mm.end() + 1600]
                    nxt = re.search(r"\n    fun ", body)
                    body = body[:nxt.start()] if nxt else body
                    if re.search(rf"\b{g}\s*=\s*(?!emptyList|null|false)", body):
                        for hm in re.finditer(rf"vm::{mm.group(1)}\b|vm\.{mm.group(1)}\(", txt):
                            ln = line_of(txt, hm.start())
                            if owner(ln) and not dialog_at(ln):
                                add(owner(ln), label_near(lines, ln), db["id"], "open-dialog", f, ln, note=f"via vm.{mm.group(1)}() setting {g}")
        # shared pop-up components used by this screen
        for pat, did in ((r"CameraPermissionGate\(", "D16"), (r"\bSelectField\(", "D15"), (r"\bLocationSelector\(", "D15"), (r"\bDueDateButton\(", "D14")):
            for mm in re.finditer(pat, txt):
                ln = line_of(txt, mm.start())
                if owner(ln):
                    add(owner(ln), label_near(lines, ln), did, "open-dialog", f, ln, role="Admin" if admin_gated(lines, ln) else None)
        # navigation callback references
        for fn_name, sid in screen_by_fn.items():
            fm = re.search(rf"\nfun {fn_name}\(", txt)
            if not fm:
                continue
            sig_end = txt.find(") {", fm.start())
            params = re.findall(r"\b(on[A-Z]\w*)\s*:", txt[fm.start():sig_end])
            cb = wiring.get(fn_name, {})
            for p in params:
                for rm in re.finditer(rf"\b{p}\b(?!\s*[:=])", txt[sig_end:]):
                    ln = line_of(txt, sig_end + rm.start())
                    if owner(ln) != sid:
                        continue
                    dsts, nh_ln = cb.get(p, ([], None))
                    d = dialog_at(ln)
                    src = d["id"] if d and d["id"] != "?" else sid
                    for dst in dsts or ["UNWIRED"]:
                        add(src, label_near(lines, ln), dst, "nav-callback", f, ln, role="Admin" if admin_gated(lines, ln) else None,
                            note=f"{p} → NavHost:{nh_ln}" if dsts else f"{p} is not passed by the NavHost")
        # click handlers
        for m in re.finditer(r"\b(onClick|onCheckedChange|onSelect|onPick|onGo|onDismissRequest|onPhotoClick|onToggleTag|onDelete)\s*=\s*", txt):
            ln = line_of(txt, m.start())
            sid = owner(ln)
            if not sid:
                continue
            vs = m.end()
            val = txt[vs:balanced(txt, vs, "{", "}")] if txt[vs] == "{" else re.match(r"[\w:.]+", txt[vs:]).group(0)
            d = dialog_at(ln)
            src = d["id"] if d and d["id"] != "?" else sid
            comp = "dismiss (outside tap / Back)" if m.group(1) == "onDismissRequest" else label_near(lines, ln)
            role = "Admin" if admin_gated(lines, ln) else None
            hit = False
            for pat, x in EXTERNAL:
                if re.search(pat, val):
                    add(src, comp, x, "external", f, ln, role)
                    hit = True
            if "auth.signOut()" in val:
                add(src, comp, "S02", "auth", f, ln, role)
                hit = True
            for db in dblocks:
                if db is d or db["id"] == "?":
                    continue
                for g in db["guards"]:
                    if re.search(rf"\b{g}\s*=\s*(?!false\b|null\b)[\w!]", val):
                        add(src, comp, db["id"], "open-dialog", f, ln, role)
                        hit = True
            if d is not None and d["id"] != "?" and not hit:
                if re.search(r"onDeleted = onBack|vm\.delete\(\)", val):
                    add(src, comp, "BACK", "dialog-result", f, ln, role)
                elif not re.search(r"\bon[A-Z]\w*\(", val):
                    add(src, comp, sid, "dialog-result", f, ln, role)
                hit = True
            if not hit and m.group(1) != "onDismissRequest":
                if re.fullmatch(r"on[A-Z]\w*", val) or (re.search(r"\bon[A-Z]\w*\(", val) and not re.search(r"\b(vm|container|scope|chatViewModel|sync)\b", val)):
                    continue   # pure forwarding to a navigation callback, counted by the callback scan
                add(src, comp, sid, "state", f, ln, role)
        # ViewModel events → callbacks
        for m in re.finditer(r"(?:is )?(\w+ViewModel)\.Event\.(\w+)\s*->\s*(on\w+)\(", txt):
            ln = line_of(txt, m.start())
            sid = owner(ln)
            fn_name = next((n for n, s in screen_by_fn.items() if s == sid), None)
            dsts, nh_ln = wiring.get(fn_name, {}).get(m.group(3), ([], None))
            for dst in dsts or ["UNWIRED"]:
                add(sid, f"event {m.group(2)}", dst, "event", f, ln, note=f"{m.group(3)} → NavHost:{nh_ln}")
    for f in ("SafeStitchApp.kt", "AppContainer.kt"):
        t = rd(f)
        for mm in re.finditer(r"requestSync\(\)", t):
            add("APP", f"{f}: requestSync()", "X06", "background", f, line_of(t, mm.start()))
    add("APP", "process start → MainActivity", "S00", "launch", "MainActivity.kt", line_of(ma, ma.find("AuthState.Loading")))
    su = rd("ui/dashboard/SummaryDashboardCard.kt")
    mm = re.search(r"onOpenCapturedInspection\(update\.recordId\)", su)
    add("S03", "summary card: recent captured photo", "S08", "nav-callback", "ui/dashboard/SummaryDashboardCard.kt", line_of(su, mm.start()))
    mm = re.search(r"onOpenChecklistInspection\(update\.recordId", su)
    for dst in ("S13", "S11"):
        add("S03", "summary card: recent checklist", dst, "nav-callback", "ui/dashboard/SummaryDashboardCard.kt", line_of(su, mm.start()))
    co = rd("ui/components/Common.kt")
    mm = re.search(r'Text\("Open app settings"\)', co)
    add("D16", '"Open app settings"', "X05", "external", "ui/components/Common.kt", line_of(co, mm.start()))
    mm = re.search(r"RequestPermission\(\)\) \{", co)
    add("D16", "permission result (granted = it)", "OWNER", "dialog-result", "ui/components/Common.kt", line_of(co, mm.start()))
    pk = rd("ui/components/Pickers.kt")
    mm = re.search(r"DatePickerDialog\(", pk)
    add("D14", "OK / Cancel", "OWNER", "dialog-result", "ui/components/Pickers.kt", line_of(pk, mm.start()))
    mm = re.search(r"DropdownMenuItem\(", pk)
    add("D15", "option selected", "OWNER", "dialog-result", "ui/components/Pickers.kt", line_of(pk, mm.start()))
    seen, uniq = set(), []
    for o in ops:
        k = (o["src"], o["dst"], o["kind"], o["file"], o["line"])
        if k not in seen:
            seen.add(k)
            uniq.append(o)
    for i, o in enumerate(uniq):
        o["id"] = f"OP{i + 1:03d}"
    return uniq


def compare(ops, M):
    """Matches each extracted operation to diagram edges; returns (rows, edges without an extracted operation)."""
    by_pair = {}
    for e in M.E:
        by_pair.setdefault((e["src"], e["dst"]), []).append(e)
    rows, matched = [], set()
    for o in ops:
        src, dst = o["src"], o["dst"]
        if dst == "OWNER":
            cands = [e for e in M.E if e["src"] == src and e["kind"] == "dialog_result"]
        elif dst == "BACK":
            cands = [e for e in M.E if e["src"] == src and (e["dst"] == "BACK" or e["kind"] in ("return", "back"))]
        else:
            cands = by_pair.get((src, dst), [])
        if dst == "UNWIRED":
            result = "UNWIRED CALLBACK (no destination in code)"
        elif cands:
            result = "MATCHED"
            matched.update(e["idx"] for e in cands)
        elif o["kind"] == "state":
            result = "STATE CHANGE — NOT ANNOTATED"
        else:
            result = "MISSING ARROW"
        rows.append(dict(o, edges=",".join(e["eid"] for e in cands), result=result,
                         verif="; ".join(sorted({e["verif"] for e in cands}))))
    unsupported = [e for e in M.E if e["idx"] not in matched]
    return rows, unsupported
