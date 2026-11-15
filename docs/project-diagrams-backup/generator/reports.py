"""Writes SCREEN-INVENTORY.md, NAVIGATION-MATRIX.csv, COVERAGE-REPORT.md and GAPS-AND-UNVERIFIED-FLOWS.md.

It also runs the second, independent verification pass (Phase 7): it re-reads the Kotlin sources
without using model.py's evidence tokens — routes, composable registrations, NavHost navigate()/popBackStack()
call sites, screen callback parameters, dialogs and screen composables — and checks each one is represented.
"""
import csv
import json
import os
import re
import struct
import xml.etree.ElementTree as ET

FACTS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build-facts.json")


def write_all(M, OUT, ROOT, COVER, DIAGRAMS, RUNTIME, SEEN, COMMIT, TODAY, log):
    src_root = os.path.join(ROOT, M.SRC_ROOT)
    facts = json.load(open(FACTS_FILE, encoding="utf-8")) if os.path.isfile(FACTS_FILE) else {}

    def read(f):
        return open(os.path.join(src_root, f), encoding="utf-8").read()

    def nm(nid):
        if nid in M.S:
            return f"{nid} {M.S[nid]['name']}"
        if nid in M.D:
            return f"{nid} {M.D[nid]['title']} (pop-up)"
        if nid in M.X:
            return f"{nid} {M.X[nid]['title']} (Android/system)"
        return {"APP": "APP (cold start / process)", "TABS": "S03/S04/S05/S06 bottom navigation bar",
                "BACK": "BACK (previous screen via popBackStack)"}.get(nid, nid)

    def link(ref):
        f, ln = ref.rsplit(":", 1)
        return f"[{os.path.basename(f)}:{ln}](../../{f}#L{ln})"

    import extract
    for e in M.E:
        e["eid"] = f"E{e['idx'] + 1:03d}"
    ops = extract.extract(src_root, M)
    cmp_rows, not_extracted = extract.compare(ops, M)
    ex_by_edge = {}
    for o in cmp_rows:
        for eid in filter(None, o["edges"].split(",")):
            ex_by_edge.setdefault(eid, []).append(o["id"])
    with open(os.path.join(OUT, "NAVIGATION-COMPARISON.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["Op ID", "Source Screen ID", "Source Component / Action", "Destination Screen / State", "Trigger Kind", "Trigger Condition",
                    "User Role", "Code Evidence (file:line)", "Diagram Edge ID(s)", "Verification Status", "Comparison Result", "Extractor Note"])
        for o in cmp_rows:
            es = [e for e in M.E if e["eid"] in o["edges"].split(",")]
            cond = "; ".join(sorted({e["cond"] for e in es if e["cond"]})) or ("always" if es else "")
            role = o["role"] or ("; ".join(sorted({e["roles"] for e in es})) or "")
            w.writerow([o["id"], o["src"], o["comp"], o["dst"], o["kind"], cond, role, f"{M.SRC_ROOT}{o['file']}:{o['line']}", o["edges"],
                        o["verif"], o["result"], o["note"]])
    REASON = {"launch": "process start (no UI control)", "background": "background WorkManager work (no UI control)"}
    with open(os.path.join(OUT, "DIAGRAM-EDGE-EVIDENCE.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["Edge ID", "Source", "Destination", "Action", "Kind", "Roles", "Code Evidence (file:line)", "Independent Extractor Ops",
                    "Evidence Status", "Verification Status", "Shown In Diagram(s)"])
        for e in M.E:
            ex = ex_by_edge.get(e["eid"], [])
            if any(rf.endswith("MISSING") for rf in e["refs"]):
                st = "UNSUPPORTED — evidence token not found"
            elif ex:
                st = "SUPPORTED — code evidence + independent extractor"
            else:
                st = "SUPPORTED — code evidence only (" + REASON.get(e["kind"], "not detectable by the generic extractor") + ")"
            w.writerow([e["eid"], e["src"], e["dst"], e["action"], e["kind"], e["roles"], " | ".join(e["refs"]), ",".join(ex), st, e["verif"],
                        "; ".join(sorted({f"{d} ({how})" for d, how in COVER[e["idx"]]})) or "NOT DRAWN"])
    json.dump({"operations": cmp_rows, "edges_without_extracted_operation": [e["eid"] for e in not_extracted]},
              open(os.path.join(OUT, "navigation-comparison.json"), "w"), indent=1, default=str)

    # ============================================================== NAVIGATION-MATRIX.csv
    matrix_rows = []
    for e in M.E:
        rt = "; ".join(f"{r['key']}={'PASS' if r['ok'] else 'FAIL'}" for r in e["rt_pass"] + e["rt_fail"])
        diag = "; ".join(sorted({f"{d} ({how})" for d, how in COVER[e["idx"]]}))
        matrix_rows.append([nm(e["src"]), e["action"], e["cond"] or "always", nm(e["dst"]), e["roles"], " | ".join(e["refs"]),
                            e["verif"] + ("" if e["status"] == M.IMPLEMENTED else f" — {e['status']}"),
                            e["kind"], diag or "NOT DRAWN", rt, e["note"], e["eid"]])
    with open(os.path.join(OUT, "NAVIGATION-MATRIX.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["Source Screen", "Action", "Condition", "Destination Screen", "User Role", "Source File", "Verification Status",
                    "Transition Kind", "Shown In Diagram(s)", "Runtime Evidence (emulator check = result)", "Notes", "Edge ID"])
        w.writerows(matrix_rows)

    # ============================================================== SCREEN-INVENTORY.md
    def ui_parts(s):
        comps, fields, buttons, drops, chipsets = [], [], [], [], []
        for el in s["ui"]:
            t = el["t"]
            adm = " *(admin only)*" if el.get("admin") else ""
            if t == "bar":
                comps.append("top app bar" + (" with ← back" if el["back"] else "")
                             + (f"; actions: {', '.join(el['right'])}" if el["right"] else "")
                             + (" *(admin only)*" if el.get("admin_right") else ""))
            elif t == "inp":
                fields.append(el["label"] + adm)
            elif t == "sel":
                drops.append(el["label"] + adm)
            elif t == "btn":
                buttons.append(el["label"] + adm)
            elif t == "btns":
                for it in el["items"]:
                    tgt = fields if it[1] == "input" else drops if it[1] == "select" else buttons
                    tgt.append(it[0] + (" *(admin only)*" if len(it) > 3 and it[3] else ""))
            elif t == "chips":
                chipsets.append(" / ".join(el["labels"]))
            elif t == "card":
                comps.append("card: " + el["lines"][0] + adm)
                for it in el.get("btns") or []:
                    buttons.append(it[0] + (" *(admin only)*" if (len(it) > 3 and it[3]) or el.get("admin") else ""))
                if el.get("chips"):
                    chipsets.append(" / ".join(el["chips"]))
            elif t == "cam":
                comps.append("camera preview: " + el["label"])
            elif t == "fab":
                buttons.append("FAB " + el["label"] + adm)
            elif t == "nav":
                comps.append(f"bottom navigation bar (active: {el['active']})")
            elif t == "subbar":
                buttons.append(el["label"])
                comps.append("bottom submit bar: " + el["text"])
            elif t == "seg":
                comps.append("segmented answer: " + " / ".join(el["labels"]))
            elif t == "radio":
                comps.append("radio group: " + ", ".join(el["opts"]))
            elif t == "switch":
                comps.append("switch: " + el["label"])
            elif t == "kpi":
                comps.append("KPI cards")
            elif t == "img":
                comps.append("image: " + el["label"])
            elif t == "bubbles":
                comps.append("chat message list")
            elif t == "prog":
                comps.append("progress: " + el["label"])
            elif t == "spinner":
                comps.append("loading spinner")
        return comps, fields, buttons, drops, chipsets

    inv = ["# SafeStitch — screen inventory", "",
           f"Generated {TODAY} from commit `{COMMIT}` by `docs/project-diagrams/generator/` (`model.py` is the single source of truth "
           "for every diagram and report; line numbers are re-resolved from the current source on every build).", "",
           "**Scope:** 24 screens = 3 authentication states rendered directly by `MainActivity` + 21 type-safe routes registered in "
           "`SafeStitchNavHost`. Pop-ups (D01–D16) and Android/system hand-offs (X01–X07) follow the screens.", "",
           "**Status vocabulary:** IMPLEMENTED · PARTIALLY IMPLEMENTED · DECLARED BUT UNREACHABLE · DOCUMENTED ONLY · NOT VERIFIED. "
           f"✓ = reached during the Android 16 emulator walkthrough on {TODAY}.", "",
           "## Summary", "",
           "| ID | Screen | Route / state | Source | Roles | Status | Emulator |", "|---|---|---|---|---|---|---|"]
    for sid in M.SCREEN_ORDER:
        s = M.S[sid]
        inv.append(f"| {sid} | {s['name']} | `{s['route']}` | {link(s['ref'])} | {s['roles']} | {s['status']} | "
                   f"{'✓' if sid in SEEN else '—'} |")
    inv += ["", "## Screen details", ""]
    for sid in M.SCREEN_ORDER:
        s = M.S[sid]
        comps, fields, buttons, drops, chipsets = ui_parts(s)
        out_e = [e for e in M.E if e["src"] == sid and e["kind"] != "state"]
        in_e = [e for e in M.E if e["dst"] == sid and e["kind"] != "state"]
        st_e = [e for e in M.E if e["src"] == sid and e["kind"] == "state"]
        dlg = [d for d in M.D.values() if sid in d["owner"]]
        inv += [f"### {sid} — {s['name']}", "",
                f"- **Status:** {s['status']}" + (" · reached on the emulator" if sid in SEEN else " · not reached on the emulator"),
                f"- **Route / state:** `{s['route']}`",
                f"- **Source:** {link(s['ref'])}",
                f"- **Roles allowed:** {s['roles']}",
                f"- **How it is reached:** {s['reach']}",
                f"- **Back behaviour:** {s['back']}",
                "- **UI components:** " + ("; ".join(comps) or "—"),
                "- **Form fields / inputs:** " + ("; ".join(fields) or "—"),
                "- **Buttons & actions:** " + ("; ".join(buttons) or "—"),
                "- **Dropdowns / selectors:** " + ("; ".join(drops) or "—"),
                "- **Chips / tabs / filters:** " + ("; ".join(chipsets) or "—"),
                "- **Dialogs & pop-ups:** " + ("; ".join(f"{d['id']} {d['title']}" for d in dlg) or "—"),
                f"- **Loading / empty / error / success states:** {s['states'] or '—'}",
                "- **Previous screens (incoming):** " + ("; ".join(sorted({nm(e['src']) + ' — ' + e['label'] for e in in_e})) or "—"),
                "- **Next screens / targets (outgoing):** " + ("; ".join(sorted({nm(e['dst']) + ' — ' + e['label'] for e in out_e})) or "—"),
                "- **In-screen actions (no navigation):** " + ("; ".join(e["action"] for e in st_e) or "—"), ""]
    inv += ["## Pop-ups, menus and permission gates", "", "| ID | Title | Opened from | Source | Roles | Buttons |", "|---|---|---|---|---|---|"]
    for did, d in M.D.items():
        inv.append(f"| {did} | {d['title']} | {', '.join(d['owner'])} | {link(d['ref'])} | {d['roles']} | "
                   f"{' · '.join(d['buttons']) or '(menu items)'} |")
    inv += ["", "## Android / system hand-offs", "", "| ID | Interaction | Used from | Source | Status | Note |", "|---|---|---|---|---|---|"]
    for xid, x in M.X.items():
        inv.append(f"| {xid} | {x['title']} | {', '.join(x['owner'])} | {link(x['ref'])} | {x['status']} | {x['note'] or '—'} |")
    inv += ["", "## Declared but unreachable", "", "| Item | Source | Why |", "|---|---|---|"]
    for name, f, tok in M.UNREACHABLE_ITEMS:
        txt = read(f)
        ln = txt[:txt.find(tok)].count("\n") + 1
        inv.append(f"| {name} | [{f}:{ln}](../../{M.SRC_ROOT}{f}#L{ln}) | no call site anywhere in `app/src` |")
    open(os.path.join(OUT, "SCREEN-INVENTORY.md"), "w", encoding="utf-8").write("\n".join(inv) + "\n")

    # ============================================================== independent verification (Phase 7)
    routes_kt = read("ui/navigation/Routes.kt")
    navhost = read("ui/navigation/SafeStitchNavHost.kt")
    nh_lines = navhost.splitlines()
    route_classes = re.findall(r"@Serializable data (?:object|class) (\w+Route)", routes_kt)
    registered = re.findall(r"composable<(\w+)>", navhost)
    route_rows = []
    for r in route_classes:
        ln = navhost[:navhost.find(f"composable<{r}>")].count("\n") + 1 if f"composable<{r}>" in navhost else None
        sids = [sid for sid, s in M.S.items() if s["route"].startswith(r)]
        diags = sorted({d["name"] for d in DIAGRAMS for sid in sids if sid in d["screens"]})
        route_rows.append((r, ln, sids, diags))
    ma = read("MainActivity.kt")
    auth_states = re.findall(r"AuthState\.(\w+) ->", ma) + re.findall(r"is AuthState\.(\w+) ->", ma)
    nav_lines = [i + 1 for i, l in enumerate(nh_lines) if re.search(r"nav\.navigate\(|nav\.popBackStack\(\)", l)]
    ref_lines = {}
    for e in M.E:
        for r in e["refs"]:
            if r.startswith(M.SRC_ROOT + "ui/navigation/SafeStitchNavHost.kt:"):
                ref_lines.setdefault(int(r.rsplit(":", 1)[1]), []).append(e)
    nav_cov = []
    for ln in nav_lines:
        line = nh_lines[ln - 1].strip()
        nav_cov.append((ln, line, ref_lines.get(ln, []), "onBack = { nav.popBackStack() }" in line))
    cb_rows = []
    for sid, s in M.S.items():
        if s["file"] == "MainActivity.kt":
            continue
        txt = read(s["file"])
        i = txt.find(s["fn"])
        sig = txt[i:txt.find(") {", i)]
        for p in re.findall(r"\b(on[A-Z]\w*)\s*:", sig):
            wired = re.search(rf"\b{p}\s*=", navhost) is not None
            cited = p == "onBack" or any(p in ev[1] for e in M.E for ev in e["ev"])
            cb_rows.append((sid, p, wired, cited))
    dialog_hits = []
    screen_fns = []
    for dp, _, files in os.walk(os.path.join(src_root, "ui")):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            f = os.path.relpath(os.path.join(dp, fn), src_root)
            txt = read(f)
            for i, l in enumerate(txt.splitlines()):
                if re.search(r"\b(AlertDialog|DatePickerDialog|DropdownMenu)\(", l) and not l.strip().startswith("import"):
                    ids = [d["id"] for d in M.D.values() if d["file"] == f and abs(int(d["ref"].rsplit(":", 1)[1]) - (i + 1)) <= 8]
                    dialog_hits.append((f, i + 1, l.strip()[:70], ids))
            for m in re.finditer(r"\nfun (\w+Screen)\(", txt):
                name = m.group(1)
                in_model = any(s["fn"] == f"fun {name}(" for s in M.S.values())
                unreachable = any(tok == f"fun {name}(" for _, _, tok in M.UNREACHABLE_ITEMS)
                screen_fns.append((name, f, in_model, unreachable))
    # file validation (Phase 8)
    files_ok = []
    for d in DIAGRAMS:
        base = os.path.join(OUT, d["name"])
        res = {"name": d["name"]}
        try:
            root = ET.parse(base + ".drawio").getroot()
            ids = {c.get("id") for c in root.iter() if c.tag in ("mxCell", "object") and c.get("id")}
            edges_ = [(c.get("source"), c.get("target")) for c in root.iter("mxCell") if c.get("edge") == "1"]
            verts = sum(1 for c in root.iter("mxCell") if c.get("vertex") == "1")
            dangling = [e for e in edges_ if e[0] not in ids or e[1] not in ids]
            res["drawio"] = f"valid XML · {verts} shapes · {len(edges_)} connectors · {len(dangling)} dangling"
            res["drawio_ok"] = not dangling and verts > 0
        except Exception as ex:  # noqa: BLE001
            res["drawio"], res["drawio_ok"] = f"INVALID: {ex}", False
        try:
            root = ET.parse(base + ".svg").getroot()
            groups = sum(1 for _ in root.iter("{http://www.w3.org/2000/svg}g"))
            paths = sum(1 for _ in root.iter("{http://www.w3.org/2000/svg}path"))
            res["svg"] = f"valid XML · {groups} groups · {paths} paths"
            res["svg_ok"] = groups > 0
        except Exception as ex:  # noqa: BLE001
            res["svg"], res["svg_ok"] = f"INVALID: {ex}", False
        try:
            raw = open(base + ".pdf", "rb").read()
            pages = len(re.findall(rb"/Type\s*/Page[^s]", raw))
            m = re.search(rb"/MediaBox\s*\[\s*0\s+0\s+([\d.]+)\s+([\d.]+)", raw)
            res["pdf"] = f"{len(raw) // 1024} KB · {pages} page" + (f" · {float(m.group(1)) / 72:.1f}×{float(m.group(2)) / 72:.1f} in" if m else "")
            res["pdf_ok"] = raw[:5] == b"%PDF-" and pages >= 1
        except Exception as ex:  # noqa: BLE001
            res["pdf"], res["pdf_ok"] = f"MISSING: {ex}", False
        try:
            raw = open(base + ".png", "rb").read(32)
            w_, h_ = struct.unpack(">II", raw[16:24])
            res["png"] = f"{w_}×{h_} px · {os.path.getsize(base + '.png') // 1024} KB"
            res["png_ok"] = raw[:8] == b"\x89PNG\r\n\x1a\n" and w_ > 1000
        except Exception as ex:  # noqa: BLE001
            res["png"], res["png_ok"] = f"MISSING: {ex}", False
        files_ok.append(res)

    # ============================================================== numbers
    n_screens = len(M.S)
    diagrammed = sorted({s for d in DIAGRAMS for s in d["screens"]})
    master = next(d for d in DIAGRAMS if d["name"].startswith("01-"))
    n_edges = len(M.E)
    covered = [e for e in M.E if COVER[e["idx"]]]
    uncovered = [e for e in M.E if not COVER[e["idx"]]]
    rt_ver = [e for e in M.E if e["rt_pass"] and not e["rt_fail"]]
    rt_failed = [e for e in M.E if e["rt_fail"]]
    code_only = [e for e in M.E if not e["rt_pass"] and not e["rt_fail"]]
    partial = [e for e in M.E if e["status"] != M.IMPLEMENTED]
    nav_uncov = [n for n in nav_cov if not n[2] and not n[3]]
    cb_unwired = [c for c in cb_rows if not c[2]]
    cb_uncited = [c for c in cb_rows if c[2] and not c[3]]
    dlg_unmatched = [d for d in dialog_hits if not d[3]]
    fn_unaccounted = [f for f in screen_fns if not f[2] and not f[3]]
    results = RUNTIME.get("results", [])
    rt_pass = [r for r in results if r["ok"]]
    files_bad = [r["name"] for r in files_ok if not all(r.get(k + "_ok") for k in ("drawio", "svg", "pdf", "png"))]
    n_unresolved = len(uncovered) + len(nav_uncov) + len(cb_uncited) + len(dlg_unmatched) + len(fn_unaccounted) + len(files_bad)

    # ============================================================== COVERAGE-REPORT.md
    cov = ["# SafeStitch — coverage and traceability report", "",
           f"Generated {TODAY} · commit `{COMMIT}` · generator `docs/project-diagrams/generator/build.py`.", "",
           "## Final coverage metrics (independently computed)", "",
           "| Metric | Result |", "|---|---|",
           f"| Screens discovered | **{n_screens}** (21 NavHost routes + 3 auth states) |",
           f"| Screens diagrammed | **{len(diagrammed)}** |",
           f"| Navigation transitions discovered (extractor operations that leave the screen) | **{len([o for o in cmp_rows if o['kind'] != 'state'])}** |",
           f"| Transitions represented (operations matched to a drawn diagram edge) | **{len([o for o in cmp_rows if o['kind'] != 'state' and o['result'] == 'MATCHED'])}** |",
           f"| Missing arrows | **{len([o for o in cmp_rows if o['result'] == 'MISSING ARROW'])}** |",
           f"| Unsupported or incorrect arrows (evidence token not found) | **{len([e for e in M.E if any(x.endswith('MISSING') for x in e['refs'])])}** |",
           f"| Diagram edges not re-detected by the extractor (their evidence still resolves) | {len(not_extracted)} — " + (", ".join(f"{e['eid']} {e['src']}→{e['dst']}" for e in not_extracted) or "none") + " |",
           f"| Dialog transitions (open pop-up + dialog outcomes) | **{len([e for e in M.E if e['kind'] in ('popup', 'dialog_result') or e['src'] in M.D])}** |",
           f"| Runtime-verified transitions | **{len(rt_ver)}** |",
           f"| Runtime-partial transitions (some emulator checks passed, some failed) | **{len(rt_failed)}** — " + (", ".join(f"{e['eid']} {e['src']}→{e['dst']} {e['label']}" for e in rt_failed) or "none") + " |",
           f"| Code-only transitions | **{len(code_only)}** |",
           f"| Unverified transitions (no runtime pass and no extractor match) | **{len([e for e in M.E if not e['rt_pass'] and e['eid'] not in ex_by_edge])}** |",
           "| Partially implemented areas | **2** — Safety Assistant chat (S17) and voice-note transcription (Home card / chat mic) |",
           f"| Visual routing problems (automated geometry check, all diagrams) | **{sum(len(d.get('problems', [])) for d in DIAGRAMS)}** |",
           f"| Total arrows drawn across all diagrams | {sum(d['arrows'] for d in DIAGRAMS)} (master: {master['arrows']}) |", "",
           f"**Reconciliation.** The {len(M.E)} modelled transitions = {len(rt_ver)} runtime-verified + {len(rt_failed)} runtime-partial + "
           f"{len(code_only)} code-traced only. Rendered arrows are a different measure: each diagram draws its own copy of the transitions it "
           "shows (the master, both role views and the modules repeat many of them), several transitions between the same two nodes are "
           "merged into one labelled arrow, long arrows are split into a numbered connector pair (two arrows), and pop-up outcomes in the "
           "master are shown inside the pop-up trays rather than as arrows.", "",
           "## Headline numbers", "",
           "| Measure | Count |", "|---|---|",
           f"| Screens discovered in code (21 NavHost routes + 3 auth states) | **{n_screens}** |",
           f"| Screens drawn as phone frames (any diagram) | **{len(diagrammed)} / {n_screens}** |",
           f"| Screens in the master wireflow | **{len(master['screens'])} / {n_screens}** |",
           f"| Pop-ups / menus / permission gates | {len(M.D)} — all shown in the master (pop-up trays + the D01 node) |",
           f"| Android / system hand-offs | {len(M.X)} |",
           f"| Transitions traced (navigation, pop-ups, hand-offs, returns, tabs, in-screen state changes, background) | **{n_edges}** |",
           f"| … navigation / pop-up / hand-off / return / tab / background | {len([e for e in M.E if e['kind'] != 'state'])} |",
           f"| … in-screen state changes (CRUD, status changes, validation) | {len([e for e in M.E if e['kind'] == 'state'])} |",
           f"| Transitions documented in NAVIGATION-MATRIX.csv | **{len(matrix_rows)} / {n_edges}** |",
           f"| Transitions represented in at least one diagram | **{len(covered)} / {n_edges}** |",
           f"| Transitions confirmed on the emulator (every mapped check passed) | {len(rt_ver)} |",
           f"| Transitions with a failed emulator check | {len(rt_failed)} |",
           f"| Transitions verified by code trace only | {len(code_only)} |",
           f"| Partially implemented transitions | {len(partial)} |",
           f"| Independent-scan items the model does not explain (sections 3–7, 9) | **{n_unresolved}** |",
           f"| Emulator checks run | {len(results)} ({len(rt_pass)} passed, {len(results) - len(rt_pass)} failed) |", "",
           "**What the completeness claim covers:** every route class, every `composable<…>` registration, every `nav.navigate`/"
           "`popBackStack` call site in the NavHost, every `on…` callback parameter of every screen composable, every `AlertDialog` / "
           "`DatePickerDialog` / `DropdownMenu` in `ui/`, and every `…Screen` composable was re-scanned from source, independently of "
           "the model (sections 2–6). **What it does not cover:** behaviour that only exists at runtime (e.g. what a server returns), "
           "and transitions listed as code-trace-only in GAPS-AND-UNVERIFIED-FLOWS.md.", "",
           "## 1 · Build, test and run results", ""]
    for k, v in facts.get("build", {}).items():
        cov.append(f"- **{k}:** {v}")
    cov += ["", "## 2 · Route ↔ composable ↔ screen ↔ diagram", "",
            f"Route classes in `Routes.kt`: **{len(route_classes)}** · `composable<…>` registrations: **{len(registered)}** · "
            f"routes without a registration: **{len(set(route_classes) - set(registered))}** · registrations without a route class: "
            f"**{len(set(registered) - set(route_classes))}**.", "",
            f"`MainActivity` branches on: {', '.join(auth_states)} → S00 (Loading), S01 (NeedsSetup), S02 (SignedOut), NavHost/S03 (SignedIn).", "",
            "| Route class | NavHost line | Screen | Diagrams (by number) |", "|---|---|---|---|"]
    for r, ln, sids, diags in route_rows:
        cov.append(f"| `{r}` | [{ln}](../../{M.SRC_ROOT}ui/navigation/SafeStitchNavHost.kt#L{ln}) | {', '.join(sids) or '**NONE**'} | "
                   f"{', '.join(d.split('-', 1)[0] for d in diags) or '**NONE**'} |")
    cov += ["", "## 3 · NavHost navigation call sites", "",
            f"{len(nav_lines)} lines in `SafeStitchNavHost.kt` call `nav.navigate(…)` or `nav.popBackStack()`. Plain "
            "`onBack = { nav.popBackStack() }` lines are covered by the diagram convention (← returns to the previous screen). "
            f"Uncovered call sites: **{len(nav_uncov)}**.", "",
            "| Line | Call | Matrix rows citing this line |", "|---|---|---|"]
    for ln, line, es, gb in nav_cov:
        who = "; ".join(sorted({f"{e['src']}→{e['dst']} {e['label']}" for e in es})) or ("generic ← Back (convention)" if gb else "**UNCOVERED**")
        cov.append(f"| {ln} | `{line[:95].replace('|', '¦')}` | {who} |")
    cov += ["", "## 4 · Navigation callbacks declared by screen composables", "",
            f"{len(cb_rows)} `on…` callback parameters. Declared but never passed by the NavHost (stay default no-ops): **{len(cb_unwired)}**"
            + (" — " + ", ".join(f"{c[0]}.{c[1]}" for c in cb_unwired) if cb_unwired else "")
            + f". Wired but not cited by any matrix row: **{len(cb_uncited)}**"
            + (" — " + ", ".join(f"{c[0]}.{c[1]}" for c in cb_uncited) if cb_uncited else "") + ".", "",
            "| Screen | Callback | Passed by NavHost | Cited in matrix |", "|---|---|---|---|"]
    for sid, p, wired, cited in cb_rows:
        cov.append(f"| {sid} | `{p}` | {'yes' if wired else '**no** (default no-op)'} | {'yes' if cited else '**no**'} |")
    cov += ["", "## 5 · Dialogs, date pickers and dropdown menus in `ui/`", "",
            f"{len(dialog_hits)} occurrences; not matched to a pop-up node: **{len(dlg_unmatched)}**.", "",
            "| File:line | Code | Pop-up node |", "|---|---|---|"]
    for f, ln, code, ids in dialog_hits:
        cov.append(f"| [{f}:{ln}](../../{M.SRC_ROOT}{f}#L{ln}) | `{code.replace('|', '¦')}` | {', '.join(ids) or '**UNMATCHED**'} |")
    cov += ["", "## 6 · `…Screen` composables", "", "| Composable | File | Drawn | Note |", "|---|---|---|---|"]
    for name, f, in_model, unr in screen_fns:
        cov.append(f"| `{name}` | `{f}` | {'yes' if in_model else 'no'} | "
                   f"{'DECLARED BUT UNREACHABLE (no callers)' if unr else ('' if in_model else '**UNACCOUNTED**')} |")
    cov += ["", "## 7 · Transitions not drawn in any diagram", ""]
    cov += [f"- {e['src']} → {e['dst']}: {e['action']}" for e in uncovered] or [
        "- None. Every matrix row appears as an arrow, a pop-up tray entry, the bottom-tab container, a findings note or a module state note."]
    cov += ["", "## 8 · Emulator walkthrough checks", "",
            f"Device: {facts.get('emulator', 'n/a')}", "",
            "Each check waits for the destination's Compose test tag (exposed as a resource-id) or a dialog title, then saves a "
            "screenshot. Screenshots stay in the session scratchpad and are not committed.", "",
            "| Check | Result | Role | Detail |", "|---|---|---|---|"]
    for r in results:
        cov.append(f"| `{r['key']}` | {'PASS' if r['ok'] else '**FAIL**'} | {r.get('role', '')} | {r['detail'][:170].replace('|', '¦')} |")
    cov += ["", "## 9 · Generated file validation", "",
            "| Diagram | .drawio | .svg | .pdf | .png | Arrows | Layout warnings |", "|---|---|---|---|---|---|---|"]
    for res, d in zip(files_ok, DIAGRAMS):
        ok = lambda k: "✅ " if res.get(k + "_ok") else "❌ "
        cov.append(f"| {d['name']} | {ok('drawio')}{res['drawio']} | {ok('svg')}{res['svg']} | {ok('pdf')}{res['pdf']} | "
                   f"{ok('png')}{res['png']} | {d['arrows']} | {len(d['warnings'])} |")
    cov += ["", "## 9b · Automated geometry check per diagram", "",
            "Checks: arrow starts/ends on its own node, arrowhead segment ≥ 8 px, no arrow through another card or phone, no collinear "
            "overlap between different arrows, no label over a card or another label, no overlapping cards, nothing outside the page.", "",
            "| Diagram | Problems | Details |", "|---|---|---|"]
    for d in DIAGRAMS:
        pr = d.get("problems", [])
        cov.append(f"| {d['name']} | {len(pr)} | " + ("; ".join(f"{k}: {v}" for k, v in pr[:12]) + (" …" if len(pr) > 12 else "") or "—") + " |")
    cov += ["", "## 10 · Screen ↔ source ↔ diagrams", "", "| Screen | Source | Diagrams |", "|---|---|---|"]
    for sid in M.SCREEN_ORDER:
        cov.append(f"| {sid} {M.S[sid]['name']} | {link(M.S[sid]['ref'])} | "
                   f"{', '.join(d['name'] for d in DIAGRAMS if sid in d['screens'])} |")
    open(os.path.join(OUT, "COVERAGE-REPORT.md"), "w", encoding="utf-8").write("\n".join(cov) + "\n")

    # ============================================================== GAPS-AND-UNVERIFIED-FLOWS.md
    gaps = ["# SafeStitch — gaps, risks and unverified flows", "",
            f"Generated {TODAY} · commit `{COMMIT}`. Ordered by severity. The application code was not changed; "
            "these are findings for you to decide on.", ""]
    for sec_title, items in facts.get("gaps", []):
        gaps += [f"## {sec_title}", ""] + [f"- {i}" for i in items] + [""]
    gaps += ["## Transitions verified by code trace only (not exercised on the emulator)", "",
             "| From | Action | To | Why not exercised |", "|---|---|---|---|"]
    for e in code_only:
        if e["kind"] == "external":
            why = "hands off to another app, system UI or a server"
        elif e["kind"] == "state":
            why = "in-screen behaviour, code-traced"
        else:
            why = e["note"] or "not part of the scripted walkthrough"
        gaps.append(f"| {nm(e['src'])} | {e['action']} | {nm(e['dst'])} | {why} |")
    if rt_failed:
        gaps += ["", "## Emulator checks that failed", ""]
        gaps += [f"- {e['src']} → {e['dst']} {e['action']}: " + "; ".join(r["detail"] for r in e["rt_fail"]) for e in rt_failed]
    open(os.path.join(OUT, "GAPS-AND-UNVERIFIED-FLOWS.md"), "w", encoding="utf-8").write("\n".join(gaps) + "\n")

    # ============================================================== README.md (folder index)
    rd = ["# SafeStitch — project diagrams", "",
          f"Generated {TODAY} from commit `{COMMIT}`. Every diagram and report here is produced from one model "
          "(`generator/model.py`) whose evidence tokens are re-checked against the Kotlin source on every build.", "",
          "## Start here", "",
          "**Master wireflow:** `01-Full-Application-Wireflow.drawio` (editable). The same diagram is exported as `.pdf` (one large "
          "vector page; zoom in), `.svg` (vector, hover for route/source/roles) and `.png`.", "",
          f"- {n_screens} screens ({len(diagrammed)} drawn) · {len(M.D)} pop-ups/menus · {len(M.X)} Android/system hand-offs",
          f"- {n_edges} transitions in `NAVIGATION-MATRIX.csv`, {len(covered)} of them drawn in at least one diagram",
          f"- {len(rt_ver)} transitions confirmed on an Android 16 emulator; the rest are traced in source (see the matrix's "
          "Verification Status column)", "",
          "## Files", "", "| File | What it is |", "|---|---|"]
    for d in DIAGRAMS:
        kind = ("**Master** — every screen and navigation path" if d["name"].startswith("01-") else
                "**Presentation overview** — every screen, primary navigation only" if d["name"].startswith("00-") else
                "Role view — " if d["name"].startswith(("10-", "11-")) else "Module detail — ")
        if not d["name"].startswith(("00-", "01-")):
            kind += d["title"].split("—", 1)[-1].strip()
        rd.append(f"| `{d['name']}.drawio` / `.pdf` / `.svg` / `.png` | {kind} |")
    rd += ["| `SCREEN-INVENTORY.md` | Every screen: route, source file:line, roles, components, fields, buttons, dialogs, "
           "incoming/outgoing screens, states, status |",
           "| `NAVIGATION-MATRIX.csv` | Every transition: source, action, condition, destination, role, source file:line, verification |",
           "| `COVERAGE-REPORT.md` | Build/test/run results, the independent source re-scan, file validation, traceability tables |",
           "| `GAPS-AND-UNVERIFIED-FLOWS.md` | Bugs, partial features, unreachable code, documentation drift, code-trace-only flows |",
           "| `generator/` | The scripts that produced all of the above (re-run after app changes, see below) |", "",
           "## Opening the diagrams on macOS", "",
           "- **Editable (`.drawio`):** install draw.io Desktop (`brew install --cask drawio`, or the free app from "
           "github.com/jgraph/drawio-desktop/releases) and double-click the file, or use the *Draw.io Integration* extension "
           "(hediet.vscode-drawio) to open it inside VS Code. Every phone frame and arrow carries data: right-click › *Edit Data…* "
           "shows the route, source file:line, roles and verification status.",
           "- **View only:** `.pdf` opens in Preview (zoom in — it is one large vector page); `.svg` opens in any browser "
           "(hover a frame or arrow for its tooltip); `.png` is a quick raster overview.", "",
           "## Regenerating", "",
           "```bash", "cd docs/project-diagrams-backup/generator",
           "python3 -B build.py ../../.. .. runtime-results.json   # needs Google Chrome for the PDF/PNG export", "```", "",
           "If a screen or button changes, `build.py` stops with `EVIDENCE MISSING` and names the transition whose source "
           "token no longer matches — update `model.py`, then rebuild.", ""]
    open(os.path.join(OUT, "README.md"), "w", encoding="utf-8").write("\n".join(rd) + "\n")

    summary = dict(screens=n_screens, diagrammed=len(diagrammed), master_screens=len(master["screens"]), transitions=n_edges,
                   documented=len(matrix_rows), drawn=len(covered), runtime_verified=len(rt_ver), runtime_failed=len(rt_failed),
                   code_only=len(code_only), partial=len(partial), uncovered=[f"{e['src']}>{e['dst']} {e['action']}" for e in uncovered],
                   nav_uncovered=[n[0] for n in nav_uncov], cb_unwired=[f"{c[0]}.{c[1]}" for c in cb_unwired],
                   cb_uncited=[f"{c[0]}.{c[1]}" for c in cb_uncited], dialogs_unmatched=[f"{d[0]}:{d[1]}" for d in dlg_unmatched],
                   fn_unaccounted=[f[0] for f in fn_unaccounted], files_bad=files_bad, runtime_checks=len(results),
                   runtime_pass=len(rt_pass), routes=len(route_classes), registered=len(registered), unresolved=n_unresolved)
    json.dump(dict(summary, files=files_ok), open(os.path.join(OUT, "_verification-summary.json"), "w"), indent=1)
    log("VERIFICATION", json.dumps(summary))
    return summary
