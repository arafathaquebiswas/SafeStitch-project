"""SafeStitch ER diagram (draw.io) generated from Room's exported schema (app/schemas/.../2.json).
Usage: python3 -B er.py <out-dir>   -> <out-dir>/SafeStitch-ER-Diagram.drawio + preview.svg"""
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import engine as E

ROOT = os.path.abspath(os.path.join(HERE, "../../.."))
OUT = sys.argv[1]
schema = json.load(open(f"{ROOT}/app/schemas/com.safestitch.app.data.db.SafeStitchDatabase/2.json"))["database"]
T = {t["tableName"]: t for t in schema["entities"]}
TYPES = {"I": "INTEGER", "T": "TEXT", "R": "REAL", "B": "BLOB"}

POS = {  # table -> (x, y)
    "factories": (40, 230), "buildings": (40, 390), "floors": (40, 570), "areas": (40, 750),
    "template_questions": (470, 230), "checkpoints": (470, 700),
    "inspections": (900, 520),
    "checklist_items": (1330, 230), "photos": (1330, 640),
    "corrective_actions": (1760, 230), "action_events": (2190, 230),
    "users": (1760, 960), "captured_inspections": (2190, 620),
}
TW, RH, HH = 300, 19, 30
NOTES = {"captured_inspections": "Standalone: Home-scanner camera captures (no foreign keys)",
         "template_questions": "Checklist templates per equipment type — copied into checklist_items when an inspection starts",
         "users": "Accounts are stored on the phone (PBKDF2 password hashes)"}
# (parent, child, FK column, kind, card)   kind: fk = enforced FOREIGN KEY, logical = column reference without a constraint
RELS = [
    ("factories", "buildings", "factoryId", "fk", "1N"), ("buildings", "floors", "buildingId", "fk", "1N"),
    ("floors", "areas", "floorId", "fk", "1N"), ("checkpoints", "inspections", "checkpointId", "fk", "1N"),
    ("inspections", "checklist_items", "inspectionId", "fk", "1N"), ("inspections", "photos", "inspectionId", "fk", "1N"),
    ("inspections", "corrective_actions", "inspectionId", "fk", "1N"), ("checklist_items", "corrective_actions", "itemId (UNIQUE)", "fk", "11"),
    ("corrective_actions", "action_events", "actionId", "fk", "1N"),
    ("areas", "checkpoints", "areaId", "logical", "01N"), ("checklist_items", "photos", "itemId", "logical", "01N"),
    ("corrective_actions", "photos", "actionId (after-fix)", "logical", "01N"),
    ("users", "inspections", "inspectorUserId", "logical", "01N"), ("users", "checklist_items", "responsibleUserId", "logical", "01N"),
    ("users", "corrective_actions", "responsibleUserId", "logical", "01N"), ("users", "action_events", "byUserId", "logical", "01N"),
]
fk_cols = {(c, col.split(" ")[0]) for (p, c, col, k, cd) in RELS}

dg = E.Diagram("SafeStitch-ER-Diagram", "SafeStitch — Entity Relationship Diagram", 2560, 1400)
dg.add(40, 24, 1600, 46, "SafeStitch — Entity Relationship Diagram", None, fill="none", stroke="none", fs=34, fc=E.TEAL, bold=True,
       align="left", valign="middle")
dg.add(40, 74, 1900, 24, f"Room (SQLite) database, schema version {schema['version']} — {len(T)} tables, generated from "
       "app/schemas/com.safestitch.app.data.db.SafeStitchDatabase/2.json", None, fill="none", stroke="none", fs=15, fc="#334155",
       align="left", valign="middle")
dg.add(40, 102, 1900, 22, "Prepared by: Arafat Haque Biswas  |  Student ID: 24241325  |  CSE489 – Mobile App Development  |  BRAC University",
       None, fill="none", stroke="none", fs=14, fc=E.INK, bold=True, align="left", valign="middle")
dg.obstacle(30, 16, 1950, 112, 2)
leg = ["PK primary key · FK enforced foreign key (ON DELETE CASCADE) · UQ unique · ref = reference without a constraint · ? nullable",
       "Solid line = enforced FOREIGN KEY · dashed line = column reference without a database constraint",
       "Crow's foot: one ─┼ / zero-or-one ─o┼ at the parent, many ─< / zero-or-one at the child"]
dg.note(2000, 24, 520, "LEGEND", leg, color="#334155", bg="#f8fafc", size=11)

for name, (x, y) in POS.items():
    t = T[name]
    pk = set(t["primaryKey"]["columnNames"])
    uq = {c for i in t.get("indices", []) if i.get("unique") for c in i["columnNames"]}
    fks = {c for k in t.get("foreignKeys", []) for c in k["columns"]}
    rows = t["fields"]
    h = HH + RH * len(rows) + 6
    g = name
    attrs = {"table": name, "columns": str(len(rows)), "tooltip": f"{name} — {len(rows)} columns"}
    dg.groups[g] = (x, y, TW, h, attrs)
    dg.add(x, y, TW, h, "", g, id=f"{g}__node", fill="#ffffff", stroke="#0f766e", sw=1.6, r=6, attrs=attrs)
    dg.add(x, y, TW, HH, name, g, fill="#0f766e", stroke="none", r=6, fs=14, fc="#ffffff", bold=True, align="center", valign="middle")
    for i, f in enumerate(rows):
        c = f["columnName"]
        tags = ("PK " if c in pk else "") + ("FK " if c in fks else "") + ("UQ" if c in uq and c not in pk else "")
        logical = (name, c) in fk_cols and c not in fks
        ry = y + HH + 3 + i * RH
        dg.add(x + 6, ry, 52, RH, tags.strip() or ("ref" if logical else ""), g, fill="none", stroke="none", fs=10,
               fc="#b45309" if "PK" in tags else ("#1d4ed8" if (tags or logical) else E.GREY), bold=True, align="left", valign="middle")
        dg.add(x + 58, ry, TW - 64, RH, f"{c}{'' if f.get('notNull') else ' ?'}", g, fill="none", stroke="none", fs=11.5,
               fc=E.INK, bold=c in pk, italic=logical, align="left", valign="middle")
        dg.add(x + TW - 90, ry, 84, RH, TYPES.get(f["affinity"][0], f["affinity"]), g, fill="none", stroke="none", fs=10, fc=E.GREY,
               align="right", valign="middle")
    dg.nodes[name] = dict(rect=(x, y, TW, h), kind="table", group=g)
    dg.obstacle(x, y, TW, h, 6)  # < engine port offset (7) so R/L exits stay free
    if name in NOTES:
        dg.add(x, y + h + 8, TW, 34, NOTES[name], None, fill="#fefce8", stroke="#eab308", sw=0.8, r=4, fs=10, fc="#713f12", italic=True,
               align="left", valign="middle", pad=6)
        dg.obstacle(x, y + h + 8, TW, 34, 6)

E.EDGE_STYLES["er_fk"] = ("#1e293b", 1.6, None)
E.EDGE_STYLES["er_logical"] = ("#64748b", 1.4, "7 4")
FORCE = {("checklist_items", "corrective_actions"): (("R",), ("L",))}  # side-by-side tables: straight line
edges = [dict(src=p, dst=c, style="er_fk" if k == "fk" else "er_logical", label=col, card=cd,
              sides=FORCE.get((p, c), (("R", "L", "B", "T"),))[0], dsides=FORCE.get((p, c), (0, ("L", "T", "R", "B")))[1])
         for (p, c, col, k, cd) in RELS]
dg.route(edges)
# Labels: also keep clear of every OTHER edge's line, so no crossing hides under a label.
def seg_rects(ed, pad=3):
    return [(min(a[0], b[0]) - pad, min(a[1], b[1]) - pad, abs(a[0] - b[0]) + 2 * pad, abs(a[1] - b[1]) + 2 * pad)
            for a, b in zip(ed["points"], ed["points"][1:])]
all_edges, placed = dg.edges, []
for ed in all_edges:
    others = [r for e2 in all_edges if e2 is not ed for r in seg_rects(e2)]
    dg.labels, dg.edges = placed + others, [ed]
    dg.place_labels()
    placed.append(dg.labels[-1])
dg.edges, dg.labels = all_edges, placed
probs = dg.validate()
dg.H = max(y + h for (x, y, w, h, m) in dg.obstacles) + 60
svg = dg.svg()
xml = dg.drawio()
# draw.io crow's-foot notation per relationship
parts = xml.split('<object id="edge_')
for i in range(1, len(parts)):
    card = dg.edges[i - 1]["card"]
    start, end = {"1N": ("ERmandOne", "ERmany"), "11": ("ERmandOne", "ERzeroToOne"), "01N": ("ERzeroToOne", "ERmany")}[card]
    parts[i] = parts[i].replace("endArrow=block;endFill=1;endSize=5;",
                                f"startArrow={start};endArrow={end};startFill=0;endFill=0;startSize=12;endSize=12;", 1)
xml = '<object id="edge_'.join(parts)
open(os.path.join(OUT, "SafeStitch-ER-Diagram.drawio"), "w", encoding="utf-8").write(xml)
open(os.path.join(OUT, "preview.svg"), "w", encoding="utf-8").write(svg)
print("tables", len(POS), "relationships", len(dg.edges), "of", len(RELS), "problems", probs, "warnings", dg.warnings)
