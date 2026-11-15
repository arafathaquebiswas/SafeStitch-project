"""SafeStitch EXTENDED database design — ER diagram (draw.io). DESIGN PROPOSAL (schema v3), not implemented in the app.
Current tables are read from Room's exported schema (app/schemas/.../2.json); the proposed changes and new tables are defined below.
Usage: python3 -B er_ext.py <out-dir>   -> <out-dir>/SafeStitch-ER-Diagram-Extended.drawio + preview.svg"""
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import engine as E

ROOT = os.path.abspath(os.path.join(HERE, "../../.."))
OUT = sys.argv[1]
schema = json.load(open(f"{ROOT}/app/schemas/com.safestitch.app.data.db.SafeStitchDatabase/2.json"))["database"]
CUR = {t["tableName"]: t for t in schema["entities"]}
TYPES = {"I": "INTEGER", "T": "TEXT", "R": "REAL", "B": "BLOB"}
NEWC = "#6d28d9"      # proposed: new tables, new relationships, new columns / constraints
NEWBG = "#f5f3ff"

# ---------------------------------------------------------------- proposed changes to the 13 current tables
REMOVE = {"inspections": ["reportFileName", "statusTags", "syncedAt", "syncConflict"],   # -> reports, inspection_tags, sync_items
          "photos": ["syncedAt", "syncConflict"], "corrective_actions": ["syncedAt", "syncConflict"],   # -> sync_items
          "captured_inspections": ["statusTags"]}                                          # -> captured_inspection_tags
REPLACE = {("checkpoints", "type"): ("equipmentTypeId", "INTEGER", False),                 # free text -> equipment_types
           ("template_questions", "type"): ("equipmentTypeId", "INTEGER", False),
           ("users", "role"): ("roleId", "INTEGER", False)}                                # free text -> roles
INSERT_AFTER = {("checklist_items", "inspectionId"): ("templateQuestionId", "INTEGER", True)}   # which template question it came from
UPGRADE_FK = {("checkpoints", "areaId"), ("photos", "itemId"), ("photos", "actionId"), ("inspections", "inspectorUserId"),
              ("checklist_items", "responsibleUserId"), ("corrective_actions", "responsibleUserId"), ("action_events", "byUserId")}


def current_table(name):
    t = CUR[name]
    pk = set(t["primaryKey"]["columnNames"])
    uq = {c for i in t.get("indices", []) if i.get("unique") for c in i["columnNames"]}
    fks = {c for k in t.get("foreignKeys", []) for c in k["columns"]}
    cols = []
    for f in t["fields"]:
        c = f["columnName"]
        if c in REMOVE.get(name, []):
            continue
        if (name, c) in REPLACE:
            n, ty, null = REPLACE[(name, c)]
            cols.append(dict(name=n, type=ty, null=null, tags="FK", new=True))
            continue
        tags = ("PK " if c in pk else "") + ("FK " if c in fks or (name, c) in UPGRADE_FK else "") + ("UQ" if c in uq and c not in pk else "")
        cols.append(dict(name=c, type=TYPES.get(f["affinity"][0], f["affinity"]), null=not f.get("notNull"), tags=tags.strip(),
                         new=(name, c) in UPGRADE_FK))
        if (name, c) in INSERT_AFTER:
            n, ty, null = INSERT_AFTER[(name, c)]
            cols.append(dict(name=n, type=ty, null=null, tags="FK", new=True))
    return cols


# ---------------------------------------------------------------- 14 new tables: (column, type, nullable, tags)
I, T = "INTEGER", "TEXT"
NEW_TABLES = {
    "equipment_types": [("id", I, 0, "PK"), ("code", T, 0, "UQ"), ("name", T, 0, ""), ("legacy", I, 0, ""),
                        ("inspectionIntervalDays", I, 1, ""), ("active", I, 0, "")],
    "roles": [("id", I, 0, "PK"), ("code", T, 0, "UQ"), ("name", T, 0, "")],
    "permissions": [("id", I, 0, "PK"), ("code", T, 0, "UQ"), ("description", T, 0, "")],
    "role_permissions": [("roleId", I, 0, "PK FK"), ("permissionId", I, 0, "PK FK")],
    "reports": [("id", I, 0, "PK"), ("inspectionId", I, 1, "FK"), ("capturedInspectionId", I, 1, "FK"), ("generatedByUserId", I, 1, "FK"),
                ("fileName", T, 0, ""), ("version", I, 0, ""), ("engine", T, 0, ""), ("sizeBytes", I, 0, ""), ("sha256", T, 0, ""),
                ("generatedAt", I, 0, ""), ("shareCount", I, 0, ""), ("lastSharedAt", I, 1, "")],
    "status_tags": [("id", I, 0, "PK"), ("code", T, 0, "UQ"), ("label", T, 0, "")],
    "inspection_tags": [("inspectionId", I, 0, "PK FK"), ("tagId", I, 0, "PK FK")],
    "captured_inspection_tags": [("capturedInspectionId", I, 0, "PK FK"), ("tagId", I, 0, "PK FK")],
    "chat_conversations": [("id", I, 0, "PK"), ("userId", I, 0, "FK"), ("persona", T, 0, ""), ("modelId", T, 0, ""), ("title", T, 1, ""),
                           ("startedAt", I, 0, ""), ("updatedAt", I, 0, "")],
    "chat_messages": [("id", I, 0, "PK"), ("conversationId", I, 0, "FK"), ("sender", T, 0, ""), ("text", T, 0, ""), ("source", T, 0, ""),
                      ("sentAt", I, 0, "")],
    "voice_notes": [("id", I, 0, "PK"), ("userId", I, 0, "FK"), ("conversationId", I, 1, "FK"), ("filePath", T, 0, ""), ("durationMs", I, 0, ""),
                    ("transcript", T, 1, ""), ("transcriptionModel", T, 1, ""), ("status", T, 0, ""), ("recordedAt", I, 0, "")],
    "notifications": [("id", I, 0, "PK"), ("userId", I, 0, "FK"), ("actionId", I, 1, "FK"), ("type", T, 0, ""), ("title", T, 0, ""),
                      ("message", T, 0, ""), ("createdAt", I, 0, ""), ("readAt", I, 1, "")],
    "sync_runs": [("id", I, 0, "PK"), ("startedAt", I, 0, ""), ("finishedAt", I, 1, ""), ("outcome", T, 0, ""), ("pushed", I, 0, ""),
                  ("conflicts", I, 0, "")],
    "sync_items": [("id", I, 0, "PK"), ("runId", I, 0, "FK"), ("entityTable", T, 0, ""), ("entityId", I, 0, ""), ("operation", T, 0, ""),
                   ("status", T, 0, ""), ("message", T, 1, ""), ("at", I, 0, "")],
}
TABLES = {n: current_table(n) for n in CUR}
TABLES.update({n: [dict(name=c, type=ty, null=bool(nl), tags=tg, new=False) for (c, ty, nl, tg) in cols] for n, cols in NEW_TABLES.items()})
assert len(TABLES) == 27

C = [40 + 450 * i for i in range(7)]  # 150 px lanes: room for FK labels between adjacent tables
POS = {
    "factories": (C[0], 200), "buildings": (C[0], 330), "floors": (C[0], 480), "areas": (C[0], 630),
    "equipment_types": (C[1], 200), "checkpoints": (C[1], 630), "status_tags": (C[1], 960), "captured_inspection_tags": (C[1], 1130),
    "captured_inspections": (C[0], 1130),
    "template_questions": (C[2], 200), "inspections": (C[2], 560), "inspection_tags": (C[2], 960),
    "checklist_items": (C[3], 200), "photos": (C[3], 600), "reports": (C[3], 880), "permissions": (C[3], 1270),
    "corrective_actions": (C[4], 200), "users": (C[4], 760), "roles": (C[4], 1060), "role_permissions": (C[4], 1230),
    "action_events": (C[5], 200), "notifications": (C[5], 470), "chat_conversations": (C[5], 760), "voice_notes": (C[5], 1010),
    "sync_runs": (C[6], 200), "sync_items": (C[6], 450), "chat_messages": (C[6], 760),
}
assert set(POS) == set(TABLES)
TW, RH, HH = 300, 19, 30
NOTES = {"template_questions": "Checklist template per equipment type; checklist_items.templateQuestionId records the source question",
         "captured_inspections": "Home-scanner camera captures; tags via captured_inspection_tags",
         "reports": "Exactly one of inspectionId / capturedInspectionId is set",
         "sync_items": "entityTable + entityId name a row in inspections, photos or corrective_actions (polymorphic — no single FK)",
         "voice_notes": "Microphone recording + transcript; linked to the chat conversation it was dictated into"}

# (parent, child, FK column, kind, card)   kind: fk = enforced today (v2), new = proposed FOREIGN KEY
RELS = [
    ("factories", "buildings", "factoryId", "fk", "1N"), ("buildings", "floors", "buildingId", "fk", "1N"),
    ("floors", "areas", "floorId", "fk", "1N"), ("checkpoints", "inspections", "checkpointId", "fk", "1N"),
    ("inspections", "checklist_items", "inspectionId", "fk", "1N"), ("inspections", "photos", "inspectionId", "fk", "1N"),
    ("inspections", "corrective_actions", "inspectionId", "fk", "1N"), ("checklist_items", "corrective_actions", "itemId (UNIQUE)", "fk", "11"),
    ("corrective_actions", "action_events", "actionId", "fk", "1N"),
    # unenforced id columns in v2 -> proposed real foreign keys (ON DELETE SET NULL)
    ("areas", "checkpoints", "areaId", "new", "01N"), ("checklist_items", "photos", "itemId", "new", "01N"),
    ("corrective_actions", "photos", "actionId (after-fix)", "new", "01N"),
    ("users", "inspections", "inspectorUserId", "new", "01N"), ("users", "checklist_items", "responsibleUserId", "new", "01N"),
    ("users", "corrective_actions", "responsibleUserId", "new", "01N"), ("users", "action_events", "byUserId", "new", "01N"),
    # new tables
    ("equipment_types", "checkpoints", "equipmentTypeId", "new", "1N"), ("equipment_types", "template_questions", "equipmentTypeId", "new", "1N"),
    ("template_questions", "checklist_items", "templateQuestionId", "new", "01N"),
    ("roles", "users", "roleId", "new", "1N"), ("roles", "role_permissions", "roleId", "new", "1N"),
    ("permissions", "role_permissions", "permissionId", "new", "1N"),
    ("inspections", "reports", "inspectionId", "new", "01N"), ("captured_inspections", "reports", "capturedInspectionId", "new", "01N"),
    ("users", "reports", "generatedByUserId", "new", "01N"),
    ("status_tags", "inspection_tags", "tagId", "new", "1N"), ("inspections", "inspection_tags", "inspectionId", "new", "1N"),
    ("status_tags", "captured_inspection_tags", "tagId", "new", "1N"),
    ("captured_inspections", "captured_inspection_tags", "capturedInspectionId", "new", "1N"),
    ("users", "chat_conversations", "userId", "new", "1N"), ("chat_conversations", "chat_messages", "conversationId", "new", "1N"),
    ("users", "voice_notes", "userId", "new", "1N"), ("chat_conversations", "voice_notes", "conversationId", "new", "01N"),
    ("users", "notifications", "userId", "new", "1N"), ("corrective_actions", "notifications", "actionId", "new", "01N"),
    ("sync_runs", "sync_items", "runId", "new", "1N"),
]
# every FK column drawn must exist as an FK row in the child table
for (p, c, col, k, cd) in RELS:
    cname = col.split(" ")[0]
    assert any(r["name"] == cname and "FK" in r["tags"] for r in TABLES[c]), (p, c, col)
n_new = len(NEW_TABLES)

W0, H0 = C[6] + 300 + 60, 1520
dg = E.Diagram("SafeStitch-ER-Diagram-Extended", "SafeStitch — Extended Database Design (ER Diagram)", W0, H0)
dg.add(40, 24, 1700, 46, "SafeStitch — Extended Database Design (ER Diagram)", None, fill="none", stroke="none", fs=34, fc=E.TEAL,
       bold=True, align="left", valign="middle")
dg.add(40, 74, 2000, 24, f"Proposed schema v3: {len(TABLES)} tables ({len(CUR)} current + {n_new} new) and {len(RELS)} relationships. "
       f"Design proposal — the app currently runs schema v{schema['version']} (app/schemas/…/2.json).", None, fill="none", stroke="none",
       fs=15, fc="#334155", align="left", valign="middle")
dg.add(40, 102, 2000, 22, "Prepared by: Arafat Haque Biswas  |  Student ID: 24241325  |  CSE489 – Mobile App Development  |  BRAC University",
       None, fill="none", stroke="none", fs=14, fc=E.INK, bold=True, align="left", valign="middle")
dg.obstacle(30, 16, 2020, 112, 2)
leg = ["Teal header = table in the current app (v2) · violet header = NEW table",
       "Violet-tinted row = new column, or an existing id column that becomes a real FOREIGN KEY",
       "Dark line = foreign key enforced today · violet line = proposed foreign key",
       "PK primary key · FK foreign key · UQ unique · ? nullable",
       "Crow's foot: one ─┼ / zero-or-one ─o┼ at the parent, many ─< / zero-or-one at the child"]
dg.note(W0 - 640, 20, 600, "LEGEND", leg, color="#334155", bg="#f8fafc", size=11)

for name, (x, y) in POS.items():
    rows = TABLES[name]
    is_new = name in NEW_TABLES
    head = NEWC if is_new else "#0f766e"
    h = HH + RH * len(rows) + 6
    g = name
    attrs = {"table": name, "columns": str(len(rows)), "status": "new" if is_new else "current",
             "tooltip": f"{name} — {len(rows)} columns{' (NEW)' if is_new else ''}"}
    dg.groups[g] = (x, y, TW, h, attrs)
    dg.add(x, y, TW, h, "", g, id=f"{g}__node", fill="#ffffff", stroke=head, sw=1.6, r=6, attrs=attrs)
    dg.add(x, y, TW, HH, name, g, fill=head, stroke="none", r=6, fs=14, fc="#ffffff", bold=True, align="center", valign="middle")
    if is_new:
        dg.add(x + TW - 50, y + 7, 42, 16, "NEW", g, fill="#ede9fe", stroke="none", r=8, fs=9, fc=NEWC, bold=True, align="center",
               valign="middle")
    for i, r in enumerate(rows):
        ry = y + HH + 3 + i * RH
        if r["new"]:
            dg.add(x + 2, ry, TW - 4, RH, "", g, fill=NEWBG, stroke="none")
        pk = "PK" in r["tags"]
        dg.add(x + 6, ry, 52, RH, r["tags"], g, fill="none", stroke="none", fs=10,
               fc="#b45309" if pk else (NEWC if (r["new"] or is_new) else "#1d4ed8"), bold=True, align="left", valign="middle")
        dg.add(x + 58, ry, TW - 64, RH, f"{r['name']}{' ?' if r['null'] else ''}", g, fill="none", stroke="none", fs=11.5,
               fc=NEWC if r["new"] else E.INK, bold=pk or r["new"], align="left", valign="middle")
        dg.add(x + TW - 90, ry, 84, RH, r["type"], g, fill="none", stroke="none", fs=10, fc=E.GREY, align="right", valign="middle")
    dg.nodes[name] = dict(rect=(x, y, TW, h), kind="table", group=g)
    dg.obstacle(x, y, TW, h, 6)  # < engine port offset (7) so R/L exits stay free
    if name in NOTES:
        nl = E.wrap(NOTES[name], 10, TW - 20)
        nh = len(nl) * 13 + 12
        dg.add(x, y + h + 8, TW, nh, "\n".join(nl), None, fill="#fefce8", stroke="#eab308", sw=0.8, r=4, fs=10, fc="#713f12",
               italic=True, align="left", valign="middle", pad=6, lh=1.3)
        dg.obstacle(x, y + h + 8, TW, nh, 6)

chg = ["checkpoints.type, template_questions.type (free text) → equipmentTypeId → equipment_types",
       "users.role (free text) → roleId → roles; admin rights listed in permissions via role_permissions",
       "inspections.reportFileName → reports (every generated / regenerated PDF, who made it, how often shared)",
       "statusTags (comma-separated text) on inspections and captured_inspections → status_tags + junction tables",
       "syncedAt / syncConflict on inspections, photos, corrective_actions → sync_runs + sync_items",
       "7 id columns without a constraint (areaId, itemId, actionId, 4 user ids) → real FOREIGN KEYs, ON DELETE SET NULL",
       "New data not stored today: chat conversations + messages, voice notes, overdue-action notifications"]
dg.note(C[6], 980, TW, "CHANGES FROM v2", chg, color=NEWC, bg=NEWBG, size=10.5)

E.EDGE_STYLES["er_fk"] = ("#1e293b", 1.6, None)
E.EDGE_STYLES["er_new"] = (NEWC, 1.5, None)
FORCE = {("checklist_items", "corrective_actions"): (("R",), ("L",))}
edges = [dict(src=p, dst=c, style="er_fk" if k == "fk" else "er_new", label=col, card=cd,
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
open(os.path.join(OUT, "SafeStitch-ER-Diagram-Extended.drawio"), "w", encoding="utf-8").write(xml)
open(os.path.join(OUT, "preview.svg"), "w", encoding="utf-8").write(svg)
print("tables", len(TABLES), "relationships", len(dg.edges), "of", len(RELS), "problems", probs, "warnings", dg.warnings)
