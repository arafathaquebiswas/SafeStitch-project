#!/usr/bin/env python3
"""Builds every SafeStitch wireflow diagram (drawio/svg/pdf/png) and report from model.py.

Usage: python3 -I build.py <project_root> <out_dir> [runtime_results.json]
"""
import json
import os
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import model as M  # noqa: E402
from engine import Diagram, PHONE_W, PHONE_H, tw, wrap, EDGE_STYLES, STATUS_COLORS, INK, GREY, TEAL, ADMIN_C  # noqa: E402

ROOT = os.path.abspath(sys.argv[1])
OUT = os.path.abspath(sys.argv[2])
RUNTIME = json.load(open(sys.argv[3])) if len(sys.argv) > 3 and os.path.isfile(sys.argv[3]) else {"results": []}
SRC = os.path.join(ROOT, M.SRC_ROOT)
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
COMMIT = subprocess.run(["git", "-C", ROOT, "log", "-1", "--format=%h", "--", "app"], capture_output=True, text=True).stdout.strip() or "unknown"
TODAY = "2026-11-15"
LOG = []


def log(*a):
    LOG.append(" ".join(str(x) for x in a))
    print(*a)


# ------------------------------------------------------------------------------------------------
#  Evidence resolution
# ------------------------------------------------------------------------------------------------
_cache = {}


def src_text(f):
    if f not in _cache:
        p = os.path.join(SRC, f)
        _cache[f] = open(p, encoding="utf-8").read() if os.path.isfile(p) else None
    return _cache[f]


def resolve(ev):
    f, tok = ev[0], ev[1]
    n = ev[2] if len(ev) > 2 else 1
    s = src_text(f)
    if s is None:
        return None
    i = -1
    for _ in range(n):
        i = s.find(tok, i + 1)
        if i < 0:
            return None
    return s.count("\n", 0, i) + 1


def evref(ev):
    ln = resolve(ev)
    return f"{M.SRC_ROOT}{ev[0]}:{ln}" if ln else f"{M.SRC_ROOT}{ev[0]}:MISSING"


MISSING = []
COVER = {}
DIAGRAMS = []
for i, e in enumerate(M.E):
    e["idx"] = i
    COVER[i] = []
for e in M.E:
    e["refs"] = [evref(v) for v in e["ev"]]
    if any(r.endswith("MISSING") for r in e["refs"]):
        MISSING.append((e["src"], e["dst"], e["action"], e["refs"]))
for coll in (M.S, M.D, M.X):
    for k, v in coll.items():
        v["ref"] = evref((v["file"], v["fn"]))
        if v["ref"].endswith("MISSING"):
            MISSING.append((k, v["file"], v["fn"]))
if MISSING:
    for m in MISSING:
        log("EVIDENCE MISSING:", m)
    sys.exit("Evidence tokens no longer match the source; update model.py")

# ------------------------------------------------------------------------------------------------
#  Runtime verification
# ------------------------------------------------------------------------------------------------
RT = {}
for r in RUNTIME["results"]:
    RT.setdefault(r["key"], []).append(r)
for e in M.E:
    hits = [r for k in e["rt"] for r in RT.get(k, [])]
    e["rt_pass"] = [r for r in hits if r["ok"]]
    e["rt_fail"] = [r for r in hits if not r["ok"]]
    if e["rt_pass"] and not e["rt_fail"]:
        e["verif"] = "RUNTIME-VERIFIED (emulator) + CODE"
    elif e["rt_pass"]:
        e["verif"] = "RUNTIME-PARTIAL (some emulator checks failed) + CODE"
    elif e["rt_fail"]:
        e["verif"] = "RUNTIME-FAILED (see coverage report) + CODE"
    else:
        e["verif"] = "CODE-VERIFIED (static trace only)"
SEEN = set()
for r in RUNTIME["results"]:
    if r["ok"] and r["detail"].startswith("reached "):
        SEEN.add(r["detail"].split()[-1])
    m = re.match(r"^(S\d\d):", r["key"])
    if r["ok"] and m:
        SEEN.add(m.group(1))
if SEEN:
    SEEN.add("S00")   # every launch passes through the Loading state before Setup/Sign in/Home


def screen_seen(sid):
    return sid in SEEN


# ------------------------------------------------------------------------------------------------
#  Helpers shared by all diagrams
# ------------------------------------------------------------------------------------------------
def edge_style(es):
    if any(e["status"] != M.IMPLEMENTED for e in es):
        return "partial"
    k = es[0]["kind"]
    if k in ("return", "popup", "external", "background", "tab"):
        return k
    if all(e["roles"] == M.ADMIN for e in es):
        return "admin"
    return "nav"


def merged_label(es, limit=3):
    labs = []
    for e in es:
        if e["label"] not in labs:
            labs.append(e["label"])
    s = " / ".join(labs[:limit]) + (" / …" if len(labs) > limit else "")
    if any(e["rt_pass"] for e in es):
        s = "✓ " + s
    return s


def edge_attrs(es):
    return {"transitions": " | ".join(f"{e['action']} [{e['cond'] or 'always'}] ({e['roles']})" for e in es),
            "source_refs": " ; ".join(r for e in es for r in e["refs"]),
            "verification": " ; ".join(sorted({e["verif"] for e in es}))}


def edge_tip(es):
    return "\n".join(f"{e['src']} → {e['dst']}: {e['action']} — {e['cond'] or 'always'} — {e['roles']} — {e['refs'][0]}" for e in es)


SAT_FOR = {sid: [] for sid in M.S}
for did, d in M.D.items():
    if did == "D01":
        continue
    for o in d["owner"]:
        SAT_FOR[o].append(did)
for xid, x in M.X.items():
    for o in x["owner"]:
        if o in SAT_FOR:
            SAT_FOR[o].append(xid)
SAT_FOR["S04"] = ["D02", "X02", "X03", "D15"]   # D02's Share/Open hand-offs sit next to it
for _sid in ("S03", "S09", "S12"):
    SAT_FOR[_sid].append("X05")                 # D16 'Open app settings'
SAT_FOR["S22"] = ["D12", "X06"]
BACK_EDGES = {}
for e in M.E:
    if e["kind"] == "back":
        BACK_EDGES.setdefault(e["src"], []).append(e)


def sat_items(sid, role=None):
    items = SAT_FOR.get(sid, [])
    if role == "Inspector":
        items = [i for i in items if not (i in M.D and M.D[i]["roles"] == M.ADMIN) and not (sid == "S04" and i in ("X02", "X03"))]
    return items


def dialog_height(d, w, big=False):
    fs_t, fs_b = (8.4, 7.2) if big else (7.4, 6.4)
    tag = {"dialog": "POP-UP", "menu": "MENU", "gate": "PERMISSION"}[d["kind"]]
    lt = wrap(d["title"], fs_t, w - 40 - tw(tag, 5.4, True), True)
    body = sum((wrap(l, fs_b, w - 12) for l in d["lines"]), [])
    btn_lines = len(wrap("   ".join(d["buttons"]), fs_b, w - 12, True)) if d["buttons"] else 0
    return 6 + max(len(lt) * fs_t * 1.25, 11) + 3 + len(body) * fs_b * 1.28 + (btn_lines * fs_b * 1.3 + 6 if d["buttons"] else 4)


def tray_height(items, width=304):
    if not items:
        return 0
    colw = (width - 18) / 2
    hs = [dialog_height(M.D[i], colw) if i in M.D else 26 for i in items]
    rows = [max(hs[k:k + 2]) for k in range(0, len(hs), 2)]
    return 16 + sum(rows) + 6 * len(rows) + 2


def tray(dg, sid, px, py, ph, items, width=304):
    """Pop-ups and Android hand-offs owned by a screen, attached under its phone frame (master/role views)."""
    if not items:
        return 0
    for e in M.E:
        if (e["src"] == sid and e["dst"] in items) or (e["src"] in items and (e["dst"] in items or e["dst"] in ("BACK", sid))):
            COVER[e["idx"]].append((dg.name, "pop-up tray"))
    cx = px + PHONE_W / 2
    tx, ty = cx - width / 2, py + ph + 16
    colw = (width - 18) / 2
    hs = [dialog_height(M.D[i], colw) if i in M.D else 26 for i in items]
    rows = [items[k:k + 2] for k in range(0, len(items), 2)]
    rh = [max(hs[k:k + 2]) for k in range(0, len(items), 2)]
    th = 16 + sum(rh) + 6 * len(rows) + 2
    g = dg.uid("tray")
    dg.groups[g] = (tx, ty, width, th, {"tooltip": f"Pop-ups and Android hand-offs opened from {sid}; outcomes are on each card"})
    dg.add(tx, ty, width, th, "", g, fill="#fafafa", stroke="#a1a1aa", sw=0.9, r=8, dash="3 3")
    dg.add(tx + 8, ty + 2, width - 16, 12, f"{sid} · pop-ups (with outcomes) & Android hand-offs", g, fill="none", stroke="none",
           fs=6.6, fc=GREY, bold=True, align="left", valign="middle")
    dg.add(cx - 0.7, py + ph, 1.4, 16, "", g, fill="#7c3aed", stroke="none")
    yy = ty + 16
    for row, h in zip(rows, rh):
        for k, it in enumerate(row):
            x = tx + 6 + k * (colw + 6)
            if it in M.D:
                dg.dialog_node(M.D[it], x, yy, w=colw, key=f"{it}@{sid}")
            else:
                dg.ext_node(M.X[it], x, yy, w=colw, key=f"{it}@{sid}")
        yy += h + 6
    dg.obstacles.append((tx, ty, width, th, 6))
    return th


def back_stub(dg, sid, px, py, role=None):
    """'↩ previous screen' stub left of the frame: Back pops to whatever is underneath (not a fixed screen)."""
    es = [e for e in BACK_EDGES.get(sid, []) if not (role == "Inspector" and e["roles"] == M.ADMIN)]
    if not es:
        return
    ybar = dg.anchors.get((sid, "__bar"), py + 36)
    bw, bh = 96, 30
    bx, by = px - 40 - bw, ybar - bh / 2
    key = f"BACK@{sid}"
    dg.groups[key] = (bx, by, bw, bh, {"tooltip": "Back / ← : NavController.popBackStack() — returns to the screen underneath"})
    dg.add(bx, by, bw, bh, "↩ previous screen\n(back stack)", key, id=f"{key}__node", fill="#f8fafc", stroke="#64748b", sw=1,
           r=15, dash="2 2", fs=7, fc="#475569", bold=True, align="center", valign="middle", lh=1.2)
    dg.nodes[key] = dict(rect=(bx, by, bw, bh), kind="pill", group=key)
    dg.obstacle(bx, by, bw, bh, 4)
    lab = "← back" + (" / Back to list" if len(es) > 1 else "")
    dg.edges.append(dict(src=sid, dst=key, style="back", label="", points=[(px, ybar), (bx + bw, ybar)], members=es,
                         attrs=edge_attrs(es), tip=edge_tip(es), fixed=True, cap=lab))


def finish(dg, edges, out_base, scale=1.0):
    def dist(e):
        a, b = dg.nodes[e["src"]]["rect"], dg.nodes[e["dst"]]["rect"]
        return abs(a[0] - b[0]) + abs(a[1] - b[1])
    edges.sort(key=dist)
    dg.route(edges)
    dg.place_labels()
    probs = dg.validate()
    for ed in dg.edges:
        for m in ed.get("members", []):
            COVER[m["idx"]].append((dg.name, "arrow"))
    DIAGRAMS.append(dict(name=dg.name, title=dg.title, W=dg.W, H=dg.H, arrows=len(dg.edges), warnings=list(dg.warnings),
                         problems=probs, screens=sorted(k for k, v in dg.nodes.items() if v["kind"] == "phone")))
    for w in dg.warnings:
        log(f"  [{dg.name}] {w}")
    for p in probs:
        log(f"  [{dg.name}] VISUAL {p[0]}: {p[1]}")
    with open(out_base + ".svg", "w", encoding="utf-8") as fh:
        fh.write(dg.svg())
    with open(out_base + ".drawio", "w", encoding="utf-8") as fh:
        fh.write(dg.drawio())
    export(out_base, dg.W, dg.H, scale)
    return dg


def edge_style(es):
    if any(e["status"] != M.IMPLEMENTED for e in es):
        return "partial"
    k = es[0]["kind"]
    if k in ("return", "popup", "external", "background", "tab", "back", "dialog_result"):
        return k
    if all(e["roles"] == M.ADMIN for e in es):
        return "admin"
    if all(e["cond"] for e in es):
        return "conditional"
    return "nav"


def screen_edges(dg, kinds, role=None):
    groups = {}
    for e in M.E:
        if e["kind"] not in kinds or e["src"] == "TABS" or e["dst"] == "BACK":
            continue
        if e["kind"] == "popup" and e["dst"] != "D01":
            continue
        if e["src"] not in dg.nodes or e["dst"] not in dg.nodes:
            continue
        if role == "Inspector" and e["roles"] == M.ADMIN:
            continue
        groups.setdefault((e["src"], e["dst"], edge_style([e])), []).append(e)
    out = []
    for (s_, d_, st), es in groups.items():
        el = next((e["el"] for e in es if e["el"] and (s_, e["el"]) in dg.anchors), None)
        ay = dg.anchors.get((s_, el)) if el else None
        out.append(dict(src=s_, dst=d_, style=edge_style(es), label=merged_label(es), ay=ay, attrs=edge_attrs(es), tip=edge_tip(es),
                        sides=("R", "L") if ay is not None else None, members=es))
    return out


def legend(dg, x, y, w=560, extra=None):
    g = dg.uid("legend")
    items = [("nav", "Navigation (tap → new screen)"), ("conditional", "Conditional navigation (label = condition)"),
             ("admin", "Admin-only navigation"), ("return", "Return after save / photo (popBackStack to the opener)"),
             ("back", "← / system Back → previous screen on the stack"), ("popup", "Opens a pop-up / menu / permission prompt"),
             ("dialog_result", "Dialog outcome (OK / Cancel / Save / Delete …)"), ("state", "In-screen state change (annotation box)"),
             ("external", "Hands off to Android / another app / server"), ("background", "Background work (WorkManager)"),
             ("partial", "Partially implemented path"), ("tab", "Bottom-bar tab switch")]
    rows = (len(items) + 1) // 2
    notes = ["✓ in an arrow label, or '✓ RUN' on a screen = exercised in the Android 16 emulator walkthrough; everything else is traced in source.",
             "ADMIN dashed outline inside a frame = control rendered only when user.isAdmin. Dashed tray under a frame = that screen's pop-ups and hand-offs.",
             "Every screen with ← (and the system Back gesture) returns to the previous screen via NavController.popBackStack(); only non-obvious returns are drawn.",
             extra or "Hover a frame or arrow in the .drawio/.svg to see its route, source file:line, roles and verification status."]
    nl = [wrap(n, 7.6, w - 28) for n in notes]
    H = 40 + rows * 18 + 26 + sum(len(l) * 7.6 * 1.3 + 4 for l in nl) + 8
    dg.groups[g] = (x, y, w, H, {})
    dg.add(x, y, w, H, "", g, fill="#ffffff", stroke="#334155", sw=1.2, r=8)
    dg.add(x + 12, y + 6, w - 24, 18, "LEGEND", g, fill="none", stroke="none", fs=12, fc=INK, bold=True, align="left", valign="middle")
    for i, (k, lab) in enumerate(items):
        col, sw, dash = EDGE_STYLES[k]
        cxx = x + 14 + (i % 2) * (w / 2)
        cyy = y + 34 + (i // 2) * 18
        dg.add(cxx, cyy + 6, 34, 0.1, "", g, fill="none", stroke=col, sw=sw + 0.6, dash=dash)
        dg.add(cxx + 31, cyy + 1, 8, 10, "▶", g, fill="none", stroke="none", fs=7, fc=col, align="center", valign="middle")
        dg.add(cxx + 44, cyy, w / 2 - 60, 12, lab, g, fill="none", stroke="none", fs=8, fc=INK, align="left", valign="middle")
    yy = y + 34 + rows * 18 + 4
    bx = x + 14
    for st, (col, bg) in STATUS_COLORS.items():
        lab = st.replace("PARTIALLY IMPLEMENTED", "PARTIAL").replace("DECLARED BUT UNREACHABLE", "UNREACHABLE")
        bw = tw(lab, 6.8, True) + 12
        dg.add(bx, yy, bw, 14, lab, g, fill=bg, stroke=col, sw=0.8, r=7, fs=6.8, fc=col, bold=True, align="center", valign="middle")
        bx += bw + 8
    yy += 22
    for lines in nl:
        dg.add(x + 14, yy, w - 28, len(lines) * 7.6 * 1.3 + 2, "\n".join(lines), g, fill="none", stroke="none", fs=7.6, fc="#334155",
               align="left", valign="top", lh=1.3)
        yy += len(lines) * 7.6 * 1.3 + 4
    dg.obstacles.append((x, y, w, H, 6))
    return H


def header(dg, title, sub, stats):
    dg.add(60, 24, dg.W - 120, 40, title, None, fill="none", stroke="none", fs=30, fc=INK, bold=True, align="left", valign="middle")
    dg.add(60, 68, dg.W - 120, 18, sub, None, fill="none", stroke="none", fs=12, fc="#334155", align="left", valign="middle")
    dg.add(60, 90, dg.W - 120, 18, stats, None, fill="none", stroke="none", fs=11, fc=TEAL, bold=True, align="left", valign="middle")




def export(base, W, H, scale):
    tmp = tempfile.mkdtemp(prefix="wf-export-", dir=os.environ.get("WF_TMP") or None)
    html_p = os.path.join(tmp, "page.html")
    svg = open(base + ".svg", encoding="utf-8").read()
    with open(html_p, "w", encoding="utf-8") as fh:
        fh.write(f"<!doctype html><html><head><meta charset='utf-8'><style>@page{{size:{W}px {H}px;margin:0}}"
                 f"html,body{{margin:0;padding:0;background:#fff}}svg{{display:block}}</style></head><body>{svg}</body></html>")
    prof = os.path.join(tmp, "profile")
    common = [CHROME, "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check", f"--user-data-dir={prof}",
              "--disable-extensions", "--hide-scrollbars", "--disable-background-networking", "--disable-sync"]
    subprocess.run(common + ["--no-pdf-header-footer", f"--print-to-pdf={base}.pdf", "file://" + html_p], capture_output=True, timeout=300)
    subprocess.run(common + [f"--window-size={int(W)},{int(H)}", f"--force-device-scale-factor={scale}", f"--screenshot={base}.png",
                             "file://" + html_p], capture_output=True, timeout=300)




# ------------------------------------------------------------------------------------------------
#  MASTER WIREFLOW (+ the two role views reuse its layout)
# ------------------------------------------------------------------------------------------------
PX, OX, OY = 440, 70, 300
MASTER_POS = {
    "S00": (0, 0), "S01": (1, 0), "S02": (1, 1),
    "S03": (2, 0), "S04": (2, 1), "S05": (2, 2), "S06": (2, 3),
    "S16": (4, 0), "S17": (5, 0), "S18": (6, 0), "S19": (7, 0), "S20": (8, 0), "S21": (9, 0),
    "S09": (3, 1), "S10": (4, 1), "S11": (5, 1), "S12": (6, 1), "S13": (7, 1), "S14": (8, 1), "S22": (9, 1),
    "S15": (6, 2), "S23": (9, 2),
    "S07": (3, 3), "S08": (4, 3),
}
BANDS = [
    ("AUTHENTICATION & SESSION — detail: 02", 0, 0, 1, 1, "#f1f5f9", "#475569"),
    ("ACCOUNT MENU · SETTINGS · ASSISTANT — detail: 02, 03, 09", 3, 0, 5, 0, "#faf5ff", "#7e22ce"),
    ("ADMIN TOOLS (admin only) — detail: 08", 6, 0, 9, 0, "#fff7ed", ADMIN_C),
    ("ADMIN TOOLS (cont.) — 08", 9, 1, 9, 2, "#fff7ed", ADMIN_C),
    ("EQUIPMENT · SCAN · INSPECTION · REPORT & PDF — detail: 04, 05", 3, 1, 8, 1, "#f0fdf4", "#15803d"),
    ("CORRECTIVE ACTIONS — detail: 06", 3, 2, 6, 2, "#fefce8", "#a16207"),
    ("HISTORY & CAPTURED PHOTOS — detail: 07", 3, 3, 4, 3, "#eff6ff", "#1d4ed8"),
]


def master_rows(screens, role):
    tmp = Diagram("tmp", "", 10, 10)
    need = {}
    for sid in screens:
        c, r = MASTER_POS[sid]
        ph = tmp.phone_height(M.S[sid], role)
        th = tray_height(sat_items(sid, role))
        need[r] = max(need.get(r, 0), 118 + ph + (16 + th if th else 0) + 60)
    need[2] = max(need.get(2, 0), 420)
    need[3] = max(need.get(3, 0), 900)
    ys = [OY]
    for r in range(4):
        ys.append(ys[-1] + max(need.get(r, 0), 700))
    return ys


def build_master(role=None, name="01-Full-Application-Wireflow", title="SafeStitch — Full Application Wireflow", scale=1.0):
    screens = [s for s in M.SCREEN_ORDER if role != "Inspector" or (M.S[s]["roles"] != M.ADMIN and s != "S01")]
    title = title.replace("{n}", str(len(screens)))
    ROWY = master_rows(screens, role)
    W, H = OX + 10 * PX + 40, ROWY[4] + 20
    dg = Diagram(name, title, W, H)
    cellxy = lambda c, r: (OX + c * PX + 50, ROWY[r] + 118)
    n_nav = len([e for e in M.E if e["kind"] != "state"])
    n_rt = len([e for e in M.E if e["rt_pass"]])
    sub = ("Kotlin · Jetpack Compose (Material 3) · type-safe Navigation-Compose: 21 routes in one NavHost + 3 auth states in MainActivity · "
           "Room/SQLite · CameraX + ZXing · PDF (PdfDocument + PDFBox) · offline-first with optional WorkManager sync · roles: Admin, Inspector")
    stats = (f"{len(screens)} screens · {len(M.D)} pop-ups/menus · {len(M.X)} Android/system hand-offs · {len(M.E)} traced transitions "
             f"({n_nav} navigation / back / pop-up / dialog-outcome / hand-off + {len(M.E) - n_nav} in-screen state changes) · "
             f"{n_rt} confirmed on the emulator · source commit {COMMIT} · generated {TODAY}")
    if role:
        stats = f"ROLE VIEW — {role.upper()}: " + stats
    header(dg, title, sub, stats)
    for (lab, c0, r0, c1, r1, fill, stroke) in BANDS:
        if role == "Inspector" and "ADMIN" in lab:
            continue
        dg.band(OX + c0 * PX + 8, ROWY[r0] + 6, (c1 - c0 + 1) * PX - 16, ROWY[r1 + 1] - ROWY[r0] - 16, lab, fill, stroke)
    tx, ty = OX + 2 * PX + 8, ROWY[0] + 6
    dg.add(tx, ty, PX - 16, ROWY[4] - ROWY[0] - 16, "", None, fill="#ecfeff", stroke="#0e7490", sw=2, r=18, dash="8 4", opacity=0.9)
    dg.add(tx + 12, ty + 6, PX - 40, 16, "BOTTOM NAVIGATION — 4 TABS", None, fill="none", stroke="none", fs=11.5, fc="#0e7490",
           bold=True, align="left", valign="middle")
    dg.add(tx + 12, ty + 24, PX - 40, 40, "Home · Inspect · Actions · History switch to each other from these four screens "
           "(popUpTo Home, saveState/restoreState). ⚠ Bug: a tab screen opened from a Home KPI card or 'Find equipment' is restored "
           "by the Home tab instead of the dashboard (System Back fixes it).", None,
           fill="none", stroke="none", fs=7.2, fc="#155e75", align="left", valign="top", lh=1.3)
    dg.obstacle(tx + 12, ty + 4, PX - 40, 62, 2)
    for e in M.E:
        if e["kind"] == "tab":
            COVER[e["idx"]].append((name, "tab container"))
        if e["kind"] == "background":
            COVER[e["idx"]].append((name, "findings note"))
    sx0, sy0 = cellxy(0, 0)
    dg.pill("APP", sx0 + 40, ROWY[0] - 66, 150, 28, "▶ START: app launch", "#0f172a", "#0f172a", fc="#ffffff", size=9,
            attrs={"tooltip": "SafeStitchApp.onCreate → MainActivity.onCreate (single activity)"})
    for sid in screens:
        c, r = MASTER_POS[sid]
        px, py = cellxy(c, r)
        dg.phone(M.S[sid], px, py, role=role, runtime=screen_seen(sid))
        tray(dg, sid, px, py, dg.nodes[sid]["rect"][3], sat_items(sid, role))
        back_stub(dg, sid, px, py, role)
    mx, my = cellxy(3, 0)
    dg.dialog_node(M.D["D01"], mx + 5, my + 110, w=210, big=True, key="D01")
    if role == "Inspector":
        dg.add(mx + 5, my + 215, 210, 30, "INSPECTOR: the 'Admin tools' item is not rendered (if (user.isAdmin))", None, fill="#fee2e2",
               stroke="#dc2626", sw=0.8, r=4, fs=7.2, fc="#991b1b", bold=True, align="center", valign="middle")
        dg.obstacles.append((mx + 5, my + 215, 210, 30, 6))
    lx, ly = OX + 5 * PX + 30, ROWY[3] + 40
    lh = legend(dg, lx, ly, w=640, extra=("Role view: admin-only screens, arrows and controls are removed; red notes mark where an "
                                          "inspector is stopped." if role == "Inspector" else None))
    if role != "Inspector":
        state_machine(dg, OX + 7 * PX - 20, ROWY[2] + 60, w=780)
    nh = notes_master(dg, lx, ly + lh + 20, role, w=640)
    ix = OX + 5 * PX + 700
    ih = screen_index(dg, ix, ly, W - ix - 60, screens)
    if role:
        access_panel(dg, ix, ly + ih + 24, W - ix - 60, role)
    edges = screen_edges(dg, ("nav", "return", "popup"), role=role)
    edges.append(dict(src="APP", dst="S00", style="nav", label="", ay=None, members=[M.E[0]], attrs={"transitions": "cold start"},
                      tip="cold start", sides=("B",), dsides=("T",)))
    return finish(dg, edges, os.path.join(OUT, name), scale=scale)


ACCESS_ROWS = [
    ("Open Admin tools (S18–S23)", ("ui/dashboard/DashboardScreen.kt", "if (user.isAdmin) {"), None, "none"),
    ("Add / reset / (de)activate users (S19)", None, ("data/AuthRepository.kt", 'if (!admin.isAdmin) return "Only an admin can add users."'), "none"),
    ("Add / rename locations (S20)", None, None, "none"),
    ("Edit checklist questions (S21)", None, None, "none"),
    ("Configure sync / re-upload (S22)", None, None, "none"),
    ("SQL inspector, read-only SELECT/PRAGMA (S23)", None, None, "none"),
    ("Add / edit equipment via + / Edit (S04 → S10)", ("ui/inspect/FindEquipmentScreen.kt", "if (user.isAdmin) {", 2), None, "none"),
    ("Register equipment by scanning an unknown code", None, None, "none — open to every role"),
    ("Print QR labels (S04 → D02)", ("ui/inspect/FindEquipmentScreen.kt", "if (user.isAdmin) {"), None, "n/a"),
    ("Verify / send back / reassign / reschedule action (S15)", ("ui/actions/ActionsScreens.kt", "ActionStatus.RESOLVED -> if (user.isAdmin) {"),
     ("data/InspectionRepository.kt", "requireAdmin(admin)"), "n/a"),
    ("Start work / mark resolved (S15)", None, None, "n/a — any signed-in user by design"),
]


def access_panel(dg, x, y, w, role):
    g = dg.uid("access")
    rh = 17
    H = 52 + rh * (len(ACCESS_ROWS) + 1) + 10
    dg.groups[g] = (x, y, w, H, {"tooltip": "UI visibility vs data-layer enforcement, from the source"})
    dg.add(x, y, w, H, "", g, fill="#ffffff", stroke=ADMIN_C, sw=1.2, r=8)
    dg.add(x + 12, y + 6, w - 24, 20, "ACCESS CONTROL — intended (UI) vs enforced (data layer)", g, fill="none", stroke="none", fs=12,
           fc=ADMIN_C, bold=True, align="left", valign="middle")
    dg.add(x + 12, y + 26, w - 24, 16, "No NavHost route has a guard; ✗ = the action relies on the UI hiding it (an access-control concern).",
           g, fill="none", stroke="none", fs=7.8, fc="#7c2d12", align="left", valign="middle")
    cols = [("Capability", 0.34), ("UI gate", 0.25), ("Data-layer check", 0.29), ("Route guard", 0.12)]
    yy = y + 46
    cx = x + 12
    for lab, f in cols:
        dg.add(cx, yy, (w - 24) * f, rh, lab, g, fill="#fff7ed", stroke="none", fs=7.8, fc=INK, bold=True, align="left", valign="middle", pad=3)
        cx += (w - 24) * f
    for k, (cap, ui, data, guard) in enumerate(ACCESS_ROWS):
        yy += rh
        if ui:
            uis = "isAdmin · " + evref(ui).replace(M.SRC_ROOT, "").split("/")[-1]
        elif "Start work" in cap:
            uis = "none — shown to every role"
        elif "scanning" in cap:
            uis = "none — Scan › unknown code, any role"
        else:
            uis = "reachable only via Admin tools"
        ds = ("✓ " + evref(data).replace(M.SRC_ROOT, "").split("/")[-1]) if data else ("✗ none" if "any signed-in" not in guard else "— (by design)")
        if "by design" in guard or "every role" in guard:
            ds = "— (by design)" if "any signed-in" in guard else "✗ none (any role)"
        cx = x + 12
        for (lab, f), v in zip(cols, (cap, uis, ds, guard)):
            cw = (w - 24) * f
            while tw(v, 7.5) > cw - 6 and len(v) > 4:
                v = v[:-2]
            col = "#15803d" if v.startswith("✓") else ("#b91c1c" if v.startswith("✗") else INK)
            dg.add(cx, yy, cw, rh, v, g, fill="#ffffff" if k % 2 else "#fffaf5", stroke="none", fs=7.5, fc=col, bold=v[:1] in "✓✗",
                   align="left", valign="middle", pad=3)
            cx += cw
    dg.obstacles.append((x, y, w, H, 6))
    return H


def state_machine(dg, x, y, w=860):
    h = 250
    g = dg.uid("sm")
    dg.groups[g] = (x, y, w, h, {"tooltip": "Corrective-action status transitions (InspectionRepository.transition)"})
    dg.add(x, y, w, h, "", g, fill="#fffbeb", stroke="#a16207", sw=1.2, r=10)
    dg.add(x + 14, y + 6, w - 28, 18, "CORRECTIVE-ACTION LIFECYCLE — S15 status changes (one action per failed item, created on Submit in S11)",
           g, fill="none", stroke="none", fs=10.5, fc="#a16207", bold=True, align="left", valign="middle")
    states = [("OPEN", "#fee2e2", "#b91c1c"), ("IN PROGRESS", "#ffedd5", "#c2410c"), ("RESOLVED (awaiting verification)", "#e0f2fe", "#0369a1"),
              ("VERIFIED", "#dcfce7", "#15803d")]
    bw, gapx = 160, (w - 48 - 4 * 160) / 3
    pos = []
    for i, (lab, bg, col) in enumerate(states):
        bx = x + 24 + i * (bw + gapx)
        dg.add(bx, y + 72, bw, 40, lab, g, fill=bg, stroke=col, sw=1.4, r=20, fs=8.4, fc=col, bold=True, align="center", valign="middle")
        pos.append(bx)
    for i, (lab, who, col) in enumerate([("Start work", "any role", "#334155"), ("Mark as resolved + note", "any role", "#334155"),
                                         ("Verify & close", "ADMIN only", ADMIN_C)]):
        x1, x2 = pos[i] + bw, pos[i + 1]
        dg.add(x1 + 2, y + 91.3, x2 - x1 - 10, 1.4, "", g, fill=col, stroke="none")
        dg.add(x2 - 10, y + 86, 9, 11, "▶", g, fill="none", stroke="none", fs=8, fc=col, align="center", valign="middle")
        dg.add(x1 - 20, y + 46, x2 - x1 + 40, 24, f"{lab}\n({who})", g, fill="none", stroke="none", fs=7.4, fc=col, bold=True,
               align="center", valign="middle", lh=1.2)
    xi, xr = pos[1] + bw / 2, pos[2] + bw / 2
    dg.add(xi - 0.7, y + 112, 1.4, 34, "", g, fill=ADMIN_C, stroke="none")
    dg.add(xr - 0.7, y + 112, 1.4, 34, "", g, fill=ADMIN_C, stroke="none")
    dg.add(xi, y + 145, xr - xi, 1.4, "", g, fill=ADMIN_C, stroke="none")
    dg.add(xi - 5, y + 111, 10, 9, "▲", g, fill="none", stroke="none", fs=8, fc=ADMIN_C, align="center", valign="middle")
    dg.add(xi, y + 148, xr - xi, 14, "Not fixed: send back + comment (D06, ADMIN only)", g, fill="none", stroke="none", fs=7.4,
           fc=ADMIN_C, bold=True, align="center", valign="middle")
    lines = ["Each step writes an ActionEventEntity (who / when / note), listed under History on S15.",
             "Until VERIFIED, admins can also reassign the responsible person, change priority (resets the due date) or pick a due date (D14/D15).",
             "Inspectors at RESOLVED see 'Only an admin can verify this fix.' — enforced in the UI and in InspectionRepository.requireAdmin()."]
    body = sum((wrap(l, 7.8, w - 48) for l in lines), [])
    dg.add(x + 24, y + 172, w - 48, len(body) * 7.8 * 1.35 + 4, "\n".join(body), g, fill="none", stroke="none", fs=7.8, fc="#334155",
           align="left", valign="top", lh=1.35)
    dg.obstacles.append((x, y, w, h, 6))


def notes_master(dg, x, y, role, w=640):  # returns height
    lines = [
        "Role checks live in the UI (if (user.isAdmin) …) and in repository guards for users and corrective-action verify/reassign; "
        "NavHost routes themselves have no guard — admin screens are reachable only through the Account menu item.",
        "Any role may register new equipment: Scan → unknown code → 'New equipment' (S10) → 'Save and start inspection'.",
        "Scan (S09) pops itself on success and Submit pops the checklist (S11), so Back from the next screen skips them.",
        "Partial: Safety Assistant Chat and the Home voice note call a hard-coded http://10.0.2.2:3000 dev server (emulator only; "
        "cleartext is blocked in release) and otherwise show locally generated/canned text; RECORD_AUDIO is never requested at runtime.",
        "Not drawn as screens: InspectionListScreen, CapturedInspectionDetailsScreen and ChatScreen aliases (no callers); "
        "DatabaseSeed.seedSampleData (never called). See GAPS-AND-UNVERIFIED-FLOWS.md.",
    ]
    if role == "Inspector":
        lines = [
            "Inspectors never see: Account menu › Admin tools, Inspect + / Edit / print-labels, S15 'Verify & close', 'Not fixed: send back' "
            "and the admin reassign/priority/due-date panel.",
            "Inspectors CAN: inspect, photograph, submit, start/resolve corrective actions, browse history and captured photos, "
            "use Settings and the Safety Assistant, and register new equipment by scanning an unknown code.",
            "Admin routes have no NavHost guard; an inspector simply has no UI entry point to them.",
        ]
    return dg.note(x, y, w, "KEY FINDINGS FROM THE CODE" if role != "Inspector" else "INSPECTOR ACCESS SUMMARY",
            ["• " + l for l in lines], color="#1e293b", bg="#f8fafc", size=9.2)




def screen_index(dg, x, y, w, screens):
    """Traceability table drawn inside the diagram: ID → route → source → roles → status."""
    g = dg.uid("index")
    rows = [M.S[sid] for sid in screens]
    rh = 17
    H = 34 + rh * (len(rows) + 1) + 8
    dg.groups[g] = (x, y, w, H, {"tooltip": "Screen index (see SCREEN-INVENTORY.md for full details)"})
    dg.add(x, y, w, H, "", g, fill="#ffffff", stroke="#334155", sw=1.2, r=8)
    dg.add(x + 12, y + 6, w - 24, 20, "SCREEN INDEX — every diagram frame ↔ route ↔ source file", g, fill="none", stroke="none", fs=12,
           fc=INK, bold=True, align="left", valign="middle")
    cols = [("ID", 0.05), ("Screen", 0.25), ("Route / state", 0.27), ("Source (app/src/main/java/com/safestitch/app/…)", 0.28),
            ("Roles", 0.07), ("Status", 0.08)]
    cx = x + 12
    yy = y + 32
    for lab, f in cols:
        dg.add(cx, yy, (w - 24) * f, rh, lab, g, fill="#f1f5f9", stroke="none", fs=7.8, fc=INK, bold=True, align="left", valign="middle", pad=3)
        cx += (w - 24) * f
    for k, sc in enumerate(rows):
        yy += rh
        cx = x + 12
        line = sc["ref"].replace(M.SRC_ROOT, "")
        roles = "Admin" if sc["roles"] == M.ADMIN else ("All" if sc["roles"] in (M.ALL, "All") else "see note")
        st = "PARTIAL" if sc["status"] == M.PARTIAL else sc["status"]
        vals = [sc["id"], sc["name"] + (" ✓" if screen_seen(sc["id"]) else ""), sc["route"], line, roles, st]
        for (lab, f), v in zip(cols, vals):
            cw = (w - 24) * f
            while tw(v, 7.6) > cw - 6 and len(v) > 4:
                v = v[:-2]
            dg.add(cx, yy, cw, rh, v, g, fill="#ffffff" if k % 2 else "#fafafa", stroke="none", fs=7.6,
                   fc=(STATUS_COLORS[sc["status"]][0] if lab == "Status" else INK), bold=lab in ("ID", "Status"), align="left",
                   valign="middle", pad=3)
            cx += cw
    dg.obstacles.append((x, y, w, H, 6))
    return H



REF_SUB = {"S00": "Auth & session (02)", "S01": "Auth & session (02)", "S02": "Auth & session (02)", "S03": "Home dashboard (03)",
           "S04": "Equipment & inspection (04)", "S09": "Equipment & inspection (04)", "S10": "Equipment & inspection (04)",
           "S11": "Equipment & inspection (04)", "S12": "Equipment & inspection (04)", "S13": "Report & PDF (05)",
           "S14": "Report & PDF (05)", "S05": "Corrective actions (06)", "S15": "Corrective actions (06)", "S06": "History & captured (07)",
           "S07": "History & captured (07)", "S08": "History & captured (07)", "S16": "Auth & session (02)",
           "S17": "Safety assistant (09)", "S18": "Admin tools (08)", "S19": "Admin tools (08)", "S20": "Admin tools (08)",
           "S21": "Admin tools (08)", "S22": "Admin tools (08)", "S23": "Admin tools (08)", "D01": "Home dashboard (03)",
           "BACK": "NavController.popBackStack()", "APP": "cold start", "TABS": "bottom navigation bar"}
REF_LABEL = {"BACK": "previous screen", "APP": "App launch", "TABS": "Bottom-bar tab"}

MODULES = [
    dict(name="02-Module-Authentication-and-Session", title="Module 02 — Authentication, session & settings",
         blurb="MainActivity renders one of three auth states before the NavHost exists: Loading → Setup (no accounts) / Sign in / Home "
               "(remembered user). Sign out (Account menu or Settings) returns to Sign in; a different user gets a fresh navigation stack "
               "(key(user.id)). Passwords are PBKDF2-hashed on the phone; 5 wrong attempts lock sign-in for 30 s.",
         core=["S00", "S01", "S02", "S16", "D01"], context=["S03"],
         pos={"S00": (0, 0), "S01": (1, 0), "S02": (1, 1), "S03": (2, 0), "S16": (3, 1)}, d01=(3, 0), pills={"APP": (0, 0)}, cols=5, rows=2),
    dict(name="03-Module-Home-Dashboard", title="Module 03 — Home dashboard (hub)",
         blurb="The start destination. Live ZXing camera scan, picture/manual code and quick chips all open Inspection history filtered by "
               "equipment; buttons open Scan/Find/Chat/Captured photos; the summary card and KPI cards deep-link into checklists, reports, "
               "photos and corrective actions. The account icon opens the Account menu.",
         core=["S03", "D01"], context=[], pos={"S03": (0, 0)}, d01=(1, 0), cols=3, rows=1),
    dict(name="04-Module-Equipment-Scan-and-Inspection", title="Module 04 — Equipment, QR scan & inspection checklist",
         blurb="Find or scan equipment, register unknown codes (any role, via scan) or add/edit equipment (admin), then run the checklist. "
               "Every answer auto-saves; Submit validates (D03), writes the PDF and creates one corrective action per failed item.",
         core=["S04", "S09", "S10", "S11", "S12"], context=[],
         pos={"S04": (0, 0), "S09": (1, 0), "S10": (2, 0), "S11": (3, 0), "S12": (4, 0)}, cols=5, rows=1),
    dict(name="05-Module-Inspection-Report-and-PDF", title="Module 05 — Inspection report & PDF",
         blurb="The report shows result, score and corrective actions; the PDF can be viewed in-app (PdfRenderer), shared, saved via the "
               "system document picker, opened in another app or regenerated with the latest corrective-action status.",
         core=["S13", "S14"], context=[], pos={"S13": (0, 0), "S14": (1, 0)}, cols=2, rows=1),
    dict(name="06-Module-Corrective-Actions", title="Module 06 — Corrective actions",
         blurb="One action per failed checklist item. Anyone signed in can start work, add after-fix photos and mark it resolved; "
               "only an admin can verify, send back, reassign, reprioritise or re-date it.",
         core=["S05", "S15"], context=["S12"], pos={"S05": (0, 0), "S15": (1, 0), "S12": (2, 0)}, cols=3, rows=1, state_machine=True),
    dict(name="07-Module-History-and-Captured-Photos", title="Module 07 — Inspection history & captured photos",
         blurb="History filters checklist inspections (status, result, period, location, inspector, type, text) and links to captured "
               "camera photos, whose detail screen edits tags, links equipment and exports a branded PDFBox report.",
         core=["S06", "S07", "S08"], context=[], pos={"S06": (0, 0), "S07": (1, 0), "S08": (2, 0)}, cols=3, rows=1),
    dict(name="08-Module-Admin-Tools", title="Module 08 — Admin tools (admin only)",
         blurb="Reached only from Account menu › Admin tools (rendered when user.isAdmin). Users, the 4-level location tree, the checklist "
               "question editor, optional HTTPS sync and a read-only SQL inspector; 'Equipment' jumps to the Inspect tab.",
         core=["S18", "S19", "S20", "S21", "S22", "S23"], context=[],
         pos={"S18": (0, 0), "S19": (1, 0), "S20": (2, 0), "S21": (3, 0), "S22": (1, 1), "S23": (2, 1)}, cols=4, rows=2),
    dict(name="09-Module-Safety-Assistant-and-Voice-Notes", title="Module 09 — Safety Assistant chat & voice notes (PARTIAL)",
         blurb="Both features post to a hard-coded http://10.0.2.2:3000 dev server (the Mac, as seen from an emulator; cleartext is blocked "
               "in release builds). Without it the chat answers from a local rules-based fallback and the voice note returns a canned "
               "sentence. The app never requests RECORD_AUDIO at runtime, so on a real phone the microphone records nothing.",
         core=["S17"], context=["S03"], pos={"S03": (0, 0), "S17": (1, 0)}, cols=2, rows=1),
]



MPX, MOX, MOY = 790, 420, 150


def side_sats(dg, sid, px, py, items, w=210):
    """Module view: pop-ups / hand-offs in a spaced column right of the phone so each trigger gets its own arrow."""
    x = px + PHONE_W + 140
    y = py + 8
    for it in items:
        if it in M.D:
            trig = [e["action"] for e in M.E if e["src"] == sid and e["dst"] == it]
            d = dict(M.D[it], lines=[f"← {t}" for t in trig] + M.D[it]["lines"])
            h = dg.dialog_node(d, x, y, w=w, big=True, key=f"{it}@{sid}")
        else:
            h = dg.ext_node(M.X[it], x, y, w=w, key=f"{it}@{sid}")
        y += h + 30
    return y - py


def side_height(sid, w=210):
    h = 8
    for it in SAT_FOR.get(sid, []):
        if it in M.D:
            trig = [e["action"] for e in M.E if e["src"] == sid and e["dst"] == it]
            h += dialog_height(dict(M.D[it], lines=[f"← {t}" for t in trig] + M.D[it]["lines"]), w, big=True) + 30
        else:
            h += 26 + 30
    return h


def state_lines(sid, core):
    es = [e for e in M.E if e["kind"] in ("state", "background") and (
        e["src"] == sid or (e["kind"] == "background" and sid == "S22"))]
    out = [("✓ " if e["rt_pass"] else "• ") + e["action"] + (f"  [{e['cond']}]" if e["cond"] else "") +
           (" — ADMIN" if e["roles"] == M.ADMIN else "") for e in es]
    return es, out


def resolve_src(dg, e):
    s_ = e["src"]
    if s_ in dg.nodes and dg.nodes[s_]["kind"] in ("phone", "pill"):
        return s_
    if s_ == "D01" and "D01" in dg.groups:
        return "D01"
    if s_ in M.D or s_ in M.X:
        if e["kind"] == "dialog_result" and e["dst"] in M.S:
            k = f"{s_}@{e['dst']}"          # an outcome only leaves the copy of the dialog that belongs to its own screen
            return k if k in dg.nodes else None
        owners = list((M.D.get(s_) or M.X.get(s_))["owner"])
        for o in owners:
            if f"{s_}@{o}" in dg.nodes:
                return f"{s_}@{o}"
    return None


def resolve_dst(dg, e, src_key):
    d_ = e["dst"]
    if d_ in dg.nodes and dg.nodes[d_]["kind"] in ("phone", "pill"):
        return d_
    if d_ == "D01" and "D01" in dg.groups:
        return "D01"
    owner = src_key.split("@")[1] if src_key and "@" in src_key else (src_key if src_key in M.S else None)
    if d_ == "BACK" and owner and f"BACK@{owner}" in dg.nodes:
        return f"BACK@{owner}"
    if (d_ in M.D or d_ in M.X) and owner and f"{d_}@{owner}" in dg.nodes:
        return f"{d_}@{owner}"
    return None


def build_module(md):
    cols, rows = md["cols"], md["rows"]
    core = set(md["core"])
    tmp = Diagram("tmp", "", 10, 10)
    note_w = PHONE_W + 140 + 210
    need = {}
    for sid, (c, r) in md["pos"].items():
        ph = tmp.phone_height(M.S[sid])
        _, sl = state_lines(sid, core)
        nh = (22 + len(sum((wrap(l, 8, note_w - 14) for l in sl), [])) * 8 * 1.32 + 6 + 40) if sl else 0
        need[r] = max(need.get(r, 0), 60 + max(ph, side_height(sid)) + nh + 80)
    ROWY = [MOY + 110]
    for r in range(rows):
        ROWY.append(ROWY[-1] + max(need.get(r, 0), 600))
    W = MOX + cols * MPX + 330
    H = ROWY[rows] + (300 if md.get("state_machine") else 0) + 380
    dg = Diagram(md["name"], md["title"], W, H)
    header(dg, md["title"], "", "")
    lines = wrap(md["blurb"], 11, W - 120)
    dg.add(60, 66, W - 120, len(lines) * 11 * 1.35 + 4, "\n".join(lines), None, fill="none", stroke="none", fs=11, fc="#334155",
           align="left", valign="top", lh=1.35)
    dg.obstacle(60, 66, W - 120, len(lines) * 11 * 1.35 + 4, 2)
    state_edges = []
    for sid, (c, r) in md["pos"].items():
        px, py = MOX + c * MPX + 60, ROWY[r] + 50
        dg.phone(M.S[sid], px, py, runtime=screen_seen(sid))
        ph = dg.nodes[sid]["rect"][3]
        sh = side_sats(dg, sid, px, py, SAT_FOR.get(sid, []))
        back_stub(dg, sid, px, py)
        es, sl = state_lines(sid, core)
        if sl and (sid in core or sid in md["pos"]):
            ny = py + max(ph, sh) + 34
            dg.note(px, ny, note_w, f"{sid} — in-screen actions & state changes (no navigation)", sl, color="#b45309", bg="#fffbeb", size=8)
            dg.edges.append(dict(src=sid, dst=sid, style="state", label="", members=es, attrs=edge_attrs(es), tip=edge_tip(es),
                                 points=[(px + PHONE_W / 2, py + ph), (px + PHONE_W / 2, ny)], fixed=True, to_note=True))
            for e in es:
                COVER[e["idx"]].append((md["name"], "state annotation"))
    if "d01" in md:
        c, r = md["d01"]
        dg.dialog_node(M.D["D01"], MOX + c * MPX + 60, ROWY[r] + 150, w=230, big=True, key="D01")
    for pid, (c, r) in md.get("pills", {}).items():
        dg.pill(pid, MOX + c * MPX + 100, ROWY[r] - 60, 150, 28, "▶ START: app launch", "#0f172a", "#0f172a", fc="#ffffff", size=9)
    groups, refs_in, refs_out = {}, [], []
    for e in M.E:
        if e["kind"] in ("state", "background", "back", "tab") and not (e["kind"] == "tab" and e["dst"] in core):
            continue
        src = resolve_src(dg, e)
        dst = resolve_dst(dg, e, src) if src else None
        core_src = e["src"] in core or (e["src"] in M.D and src and "@" in src and src.split("@")[1] in core)
        if src and dst:
            if not (core_src or e["dst"] in core or (e["src"] in M.D) or e["src"] == "D01"):
                continue
        elif src and not dst:
            if not core_src or not (e["dst"] in M.S or e["dst"] in ("BACK", "D01")):
                continue
            dst = f"out:{e['dst']}"
            if dst not in refs_out:
                refs_out.append(dst)
        elif not src and e["dst"] in core and (e["src"] in M.S or e["src"] in ("APP", "TABS", "D01")):
            src = f"in:{e['src']}"
            if src not in refs_in:
                refs_in.append(src)
            dst = e["dst"] if e["dst"] in dg.nodes else resolve_dst(dg, e, None)
            if not dst:
                continue
        else:
            continue
        groups.setdefault((src, dst, edge_style([e])), []).append(e)
    yy = ROWY[0] + 40
    for rid in refs_in:
        nid = rid[3:]
        lab = REF_LABEL.get(nid, f"{nid} {M.S[nid]['name']}" if nid in M.S else (f"{nid} Account menu" if nid == "D01" else nid))
        dg.ref_node(rid, 60, yy, "from " + lab, REF_SUB.get(nid, ""), w=210)
        dg.nodes[rid] = dg.nodes.pop(f"ref:{rid}")
        yy += 50
    yy = ROWY[0] + 40
    for rid in refs_out:
        nid = rid[4:]
        lab = REF_LABEL.get(nid, f"{nid} {M.S[nid]['name']}" if nid in M.S else (f"{nid} Account menu" if nid == "D01" else nid))
        dg.ref_node(rid, W - 290, yy, "to " + lab, REF_SUB.get(nid, ""), w=240)
        dg.nodes[rid] = dg.nodes.pop(f"ref:{rid}")
        yy += 50
    edges = []
    for (s_, d_, st), es in groups.items():
        if s_ not in dg.nodes or d_ not in dg.nodes:
            continue
        el = next((e["el"] for e in es if e["el"] and (s_, e["el"]) in dg.anchors), None)
        ay = dg.anchors.get((s_, el)) if el else None
        lab = merged_label(es, 2)
        if "@" in d_ and d_.split("@")[0] in M.D and d_.split("@")[1] == s_:
            lab = "✓" if any(e["rt_pass"] for e in es) else ""          # trigger text is printed inside the dialog card
        ed = dict(src=s_, dst=d_, style=edge_style(es), label=lab, ay=ay, attrs=edge_attrs(es), tip=edge_tip(es),
                  sides=("R", "L") if ay is not None else None, members=es)
        if "@" in s_ and (d_ == s_.split("@")[1]):
            ed["sides"], ed["dsides"] = ("L",), ("R",)                  # dialog outcome returns to its own screen
        edges.append(ed)
    ny = ROWY[rows] + 10
    if md.get("state_machine"):
        state_machine(dg, MOX, ny, w=min(W - MOX - 340, 1000))
        ny += 290
    lg_h = legend(dg, W - 700, ny, w=640)
    dg.H = ny + lg_h + 40
    return finish(dg, edges, os.path.join(OUT, md["name"]), scale=1.5)


def build_roles():
    build_master(role="Admin", name="10-Role-Admin-User-Flow", title="SafeStitch — Admin user flow (all {n} screens; admin-only paths in orange)")
    build_master(role="Inspector", name="11-Role-Inspector-User-Flow",
                 title="SafeStitch — Inspector user flow ({n} screens; admin-only areas and first-admin setup removed)")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    only = os.environ.get("ONLY", "")
    if not only or only == "master":
        build_master()
    if not only or only == "modules":
        for md in MODULES:
            build_module(md)
    if not only or only == "roles":
        build_roles()
    if not only:
        import reports
        reports.write_all(M, OUT, ROOT, COVER, DIAGRAMS, RUNTIME, SEEN, COMMIT, TODAY, log)
    with open(os.path.join(OUT, "_build-log.txt"), "w") as fh:
        fh.write("\n".join(LOG))
    log("done")
