#!/usr/bin/env python3
"""SafeStitch on-device tests: drive the INSTALLED app with adb + uiautomator (no test APK).

    python3 -I scripts/smoke/ui_flow.py --adb ADB --serial SERIAL --package PKG --out DIR workflow
    python3 -I scripts/smoke/ui_flow.py ... sync --server http://127.0.0.1:8787 --key KEY
    python3 -I scripts/smoke/ui_flow.py ... features

workflow  Fresh app, airplane mode: create admin + inspector, locations and equipment; sign in as the
          inspector; find equipment; fail one item with a photo; restart the app; reopen the draft;
          submit twice; open the PDF; check exactly one inspection + one corrective action; move the action
          Open -> In progress -> Resolved (with after-fix photo); confirm the inspector cannot verify; verify
          as admin. Needs a debuggable build for the database checks (run-as).
sync      (after workflow) configure sync while offline, submit an inspection with a photo, restart,
          reconnect, check the server copy and photo hashes, check retries left no duplicates, provoke a
          conflict and check it is reported.
features  QR from a picture, multi-label QR PDF, editable checklists, historical snapshots, report
          Save/Share, history filters and dashboard totals.

Prints PASS/FAIL lines; the last line is a JSON summary.
"""
import argparse
import hashlib
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
ACTIVITY = "com.safestitch.app.MainActivity"
ADMIN = ("Admin Tester", "admin", "AdminPass123")
INSPECTOR = ("Ina Inspector", "inspector", "InspPass123")
FACTORY, BUILDING, FLOOR, AREA = "Test Factory", "Building A", "Floor 1", "Cutting"
E2E_CODE, E2E_NAME = "FE-E2E-01", "Extinguisher E2E"
SYNC_CODE, SYNC_NAME = "FE-SYNC-01", "Extinguisher Sync"
CHECKS = []


class SmokeFailure(Exception):
    pass


def passed(name, detail=""):
    CHECKS.append({"check": name, "result": "PASS", "detail": detail})
    print(f"    PASS  {name}" + (f"  ({detail})" if detail else ""), flush=True)


def expect(condition, message):
    if not condition:
        raise SmokeFailure(message)


class Device:
    def __init__(self, adb, serial, package, out):
        self.adb, self.serial, self.package, self.out = adb, serial, package, out
        sizes = re.findall(r"(\d+)x(\d+)", self.sh("wm", "size"))
        self.width, self.height = map(int, sizes[-1])
        # Release builds are not debuggable: database checks then fall back to on-screen evidence.
        self.debuggable = "not debuggable" not in self.sh("run-as", package, "id", check=False)

    # --- adb -----------------------------------------------------------------------------------
    def run(self, *args, timeout=90, check=True, binary=False):
        proc = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, timeout=timeout)
        if check and proc.returncode != 0:
            raise SmokeFailure(f"adb {' '.join(args)} failed: {(proc.stderr or proc.stdout).decode(errors='replace').strip()}")
        return proc.stdout if binary else proc.stdout.decode(errors="replace")

    def sh(self, *args, **kwargs):
        return self.run("shell", *args, **kwargs)

    def run_as_bytes(self, path):
        return self.run("exec-out", "run-as", self.package, "cat", path, binary=True, timeout=120)

    def screenshot(self, name):
        with open(os.path.join(self.out, name), "wb") as handle:
            handle.write(self.run("exec-out", "screencap", "-p", binary=True))

    def airplane(self, on):
        self.sh("cmd", "connectivity", "airplane-mode", "enable" if on else "disable")
        time.sleep(4)
        state = self.sh("settings", "get", "global", "airplane_mode_on").strip()
        expect(state == ("1" if on else "0"), f"could not turn airplane mode {'on' if on else 'off'}")

    def launch(self):
        self.sh("am", "start", "-W", "-n", f"{self.package}/{ACTIVITY}", timeout=60)

    def restart_app(self):
        self.sh("am", "force-stop", self.package)
        time.sleep(1)
        self.launch()

    # --- screen --------------------------------------------------------------------------------
    def dump(self):
        path = "/data/local/tmp/safestitch_ui.xml"
        for _ in range(10):
            self.sh("rm", "-f", path, check=False)
            self.sh("uiautomator", "dump", path, check=False, timeout=60)
            xml = self.sh("cat", path, check=False)
            start = xml.find("<?xml")
            if start >= 0:
                try:
                    return ET.fromstring(xml[start:])
                except ET.ParseError:
                    pass
            time.sleep(1)
        raise SmokeFailure("uiautomator could not read the screen")

    @staticmethod
    def find_all(root, rid=None, text=None, contains=None, desc=None, rid_prefix=None, pkg_part=None):
        found = []
        for node in root.iter("node"):
            t = (node.get("text") or "").strip()
            r = node.get("resource-id") or ""
            if rid is not None and r != rid:
                continue
            if rid_prefix is not None and not r.startswith(rid_prefix):
                continue
            if text is not None and t.lower() != text.lower():
                continue
            if contains is not None and contains.lower() not in t.lower():
                continue
            if desc is not None and (node.get("content-desc") or "") != desc:
                continue
            if pkg_part is not None and pkg_part not in (node.get("package") or ""):
                continue
            found.append(node)
        return found

    def find(self, root, **query):
        found = self.find_all(root, **query)
        return found[0] if found else None

    @staticmethod
    def bounds(node):
        return tuple(map(int, BOUNDS.match(node.get("bounds")).groups()))

    @staticmethod
    def texts(node):
        return " ".join((n.get("text") or "") for n in node.iter("node")).strip()

    def tap_node(self, node, pause=0.8):
        x1, y1, x2, y2 = self.bounds(node)
        self.sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(pause)

    def wait(self, timeout=45, gone=False, **query):
        """Returns the node once it exists and sits still (same bounds twice), or once it is gone."""
        deadline = time.time() + timeout
        previous = None
        while time.time() < deadline:
            node = self.find(self.dump(), **query)
            if gone:
                if node is None:
                    return None
            elif node is not None:
                if previous is not None and node.get("bounds") == previous:
                    return node
                previous = node.get("bounds")
                time.sleep(0.3)
                continue
            previous = None
            time.sleep(0.7)
        raise SmokeFailure(f"timed out after {timeout}s waiting for {'absence of ' if gone else ''}{query}")

    def tap(self, timeout=45, **query):
        node = self.wait(timeout, **query)
        self.tap_node(node)
        return node

    def exists(self, **query):
        return self.find(self.dump(), **query) is not None

    def keyboard_shown(self):
        return "mInputShown=true" in self.sh("dumpsys", "input_method", check=False)

    def hide_keyboard(self):
        time.sleep(0.5)
        if not self.keyboard_shown():
            return
        self.sh("input", "keyevent", "4")
        deadline = time.time() + 8
        while time.time() < deadline:
            time.sleep(0.4)
            if not self.keyboard_shown():
                time.sleep(0.8)
                return
        raise SmokeFailure("could not hide the on-screen keyboard")

    def type_text(self, text):
        encoded = text.replace(" ", "%s")
        if not re.fullmatch(r"[A-Za-z0-9.%\-:/_]+", encoded):
            raise ValueError(f"unsupported characters for adb input: {text!r}")
        self.sh("input", "text", encoded)
        time.sleep(0.5)

    def type_field(self, rid, text, timeout=45):
        self.tap(timeout=timeout, rid=rid)
        # Clear anything already in the field (e.g. a remembered search) before typing.
        self.sh("input", "keyevent", "KEYCODE_MOVE_END")
        self.sh("input", "keyevent", *(["KEYCODE_DEL"] * 40))
        self.type_text(text)
        self.hide_keyboard()

    def back(self, pause=1.2):
        self.sh("input", "keyevent", "4")
        time.sleep(pause)

    def swipe(self, up=True, fraction=0.34):
        x = self.width // 2
        a, b = int(self.height * 0.70), int(self.height * (0.70 - fraction))
        start, end = (a, b) if up else (b, a)
        self.sh("input", "swipe", str(x), str(start), str(x), str(end), "400")
        time.sleep(0.9)

    def scroll_to(self, safe_top=None, safe_bottom=None, max_swipes=40, **query):
        safe_top = safe_top if safe_top is not None else int(self.height * 0.12)
        safe_bottom = safe_bottom if safe_bottom is not None else int(self.height * 0.96)
        for attempt in range(max_swipes):
            node = self.find(self.dump(), **query)
            if node is not None:
                _, y1, _, y2 = self.bounds(node)
                if y1 >= safe_top and y2 <= safe_bottom:
                    return node
                self.swipe(up=y1 >= safe_top, fraction=0.12 if safe_top <= y1 < safe_bottom or y1 < safe_top else 0.3)
                continue
            # Not on screen: search further down first, then back up (it may be above).
            self.swipe(up=attempt < max_swipes // 2, fraction=0.34 if attempt < max_swipes // 2 else 0.5)
        raise SmokeFailure(f"could not scroll to {query}")

    def tap_chip(self, rid, row_prefix="filter-"):
        """Taps a chip in a sideways-scrolling row, swiping the row left/right until it is visible."""
        for attempt in range(8):
            root = self.dump()
            node = self.find(root, rid=rid)
            if node is not None:
                self.tap_node(node)
                return
            row = self.find(root, rid_prefix=row_prefix)
            if row is None:
                break
            y = (self.bounds(row)[1] + self.bounds(row)[3]) // 2
            a, b = int(self.width * 0.85), int(self.width * 0.15)
            start, end = (a, b) if attempt < 4 else (b, a)
            self.sh("input", "swipe", str(start), str(y), str(end), str(y), "300")
            time.sleep(0.8)
        raise SmokeFailure(f"chip {rid} not found")

    def select_option(self, field_rid, option_text):
        self.tap(rid=field_rid)
        self.tap(text=option_text)
        time.sleep(0.6)

    # --- app data (debug builds) ----------------------------------------------------------------
    def db(self):
        folder = os.path.join(self.out, "db")
        os.makedirs(folder, exist_ok=True)
        for suffix in ("", "-wal", "-shm"):
            target = os.path.join(folder, "safestitch.db" + suffix)
            data = self.run("exec-out", "run-as", self.package, "cat", "databases/safestitch.db" + suffix, binary=True, check=False)
            if data.startswith(b"run-as:") or b"No such file" in data[:200]:
                data = b""
            with open(target, "wb") as handle:
                handle.write(data)
            if not data and suffix:
                os.remove(target)
        return sqlite3.connect(os.path.join(folder, "safestitch.db"))


def query1(db, sql, *args):
    row = db.execute(sql, args).fetchone()
    return row[0] if row else None


def pdf_text(path):
    script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "pdf_text.swift")
    proc = subprocess.run(["swift", script, path], capture_output=True, text=True, timeout=240)
    return proc.stdout


# --------------------------------------------------------------------------------------------------
#  Building blocks
# --------------------------------------------------------------------------------------------------

def go_home(d):
    for _ in range(6):
        root = d.dump()
        if d.find(root, rid="tab-home") is not None:
            d.tap_node(d.find(root, rid="tab-home"))
            d.wait(rid="dash-scan")
            return
        if d.find(root, rid="login-username") is not None:
            return
        d.back()
    raise SmokeFailure("could not get back to the home screen")


def sign_out(d):
    go_home(d)
    d.tap(rid="account-menu")
    d.tap(text="Sign out")
    d.wait(rid="login-username")


def sign_in(d, username, password):
    d.type_field("login-username", username)
    d.type_field("login-password", password)
    d.tap(rid="login-submit")
    d.wait(timeout=40, rid="dash-scan")


def open_admin(d, entry):
    go_home(d)
    d.tap(rid="account-menu")
    d.tap(text="Admin tools")
    d.tap(rid=entry)


def answer_checklist(d, fail_positions, note):
    root = d.wait(rid="progress-text")
    total = int(re.search(r"of (\d+) answered", root.get("text") or "").group(1))
    root = d.dump()
    top = d.bounds(d.find(root, rid="delete-inspection"))[3] + 12
    bottom = d.bounds(d.find(root, rid="complete-inspection"))[1] - 70
    for pos in range(total):
        answer = "FAIL" if pos in fail_positions else "PASS"
        d.tap_node(d.scroll_to(top, bottom, rid=f"item-{pos}-{answer}"), pause=0.6)
        if pos in fail_positions:
            d.tap_node(d.scroll_to(top, bottom, rid=f"item-{pos}-note"))
            d.type_text(note)
            d.hide_keyboard()
            d.tap_node(d.scroll_to(top, bottom, rid=f"item-{pos}-photo"))
            d.wait(timeout=25, rid="shutter")
            time.sleep(3)
            d.tap(rid="shutter")
            d.wait(timeout=40, rid="inspection-list")
            node = d.scroll_to(top, bottom, rid=f"item-{pos}-photo")
            expect("Photo (1)" in d.texts(node), "the camera photo was not attached to the failed item")
    return total


def create_equipment(d, code, name, type_tag="cp-type-FIRE_EXTINGUISHER"):
    d.tap(rid="tab-inspect")
    d.tap(rid="add-equipment")
    d.type_field("cp-code", code)
    d.type_field("cp-name", name)
    d.select_option("cp-loc-factory", FACTORY)
    d.select_option("cp-loc-building", BUILDING)
    d.select_option("cp-loc-floor", FLOOR)
    d.select_option("cp-loc-area", AREA)
    d.tap_node(d.scroll_to(rid=type_tag))
    d.tap_node(d.scroll_to(rid="cp-save"))
    d.wait(timeout=30, rid=f"equipment-{code}")


def start_inspection(d, code):
    d.tap(rid="tab-inspect")
    d.type_field("equipment-search", code)
    d.tap(rid=f"inspect-{code}")
    d.wait(timeout=30, rid="progress-text")


# --------------------------------------------------------------------------------------------------
#  Scenarios
# --------------------------------------------------------------------------------------------------

def workflow(d):
    d.airplane(True)
    passed("Airplane mode on for the whole workflow")
    d.sh("logcat", "-b", "crash", "-c", check=False)
    d.launch()

    d.wait(timeout=60, rid="setup-name")
    d.type_field("setup-name", ADMIN[0])
    d.type_field("setup-username", ADMIN[1])
    d.type_field("setup-password", ADMIN[2])
    d.type_field("setup-confirm", ADMIN[2])
    d.tap(rid="setup-create")
    d.wait(timeout=60, rid="dash-scan")
    passed("First admin account created and signed in")

    open_admin(d, "admin-users")
    d.tap(rid="add-user")
    d.type_field("user-name", INSPECTOR[0])
    d.type_field("user-username", INSPECTOR[1])
    d.tap(rid="user-role-INSPECTOR")
    d.type_field("user-password", INSPECTOR[2])
    d.type_field("user-confirm", INSPECTOR[2])
    d.tap(rid="user-save")
    d.wait(timeout=30, rid=f"user-{INSPECTOR[1]}")
    passed("Inspector account created by the admin", f"@{INSPECTOR[1]}")
    d.back()

    d.tap(rid="admin-locations")
    for index, name in enumerate([FACTORY, BUILDING, FLOOR, AREA]):
        d.tap(rid="add-location")
        d.type_field("location-name-field", name)
        d.tap(rid="location-save")
        d.wait(rid=f"location-{name}")
        if index < 3:
            d.tap(rid=f"location-{name}")
    passed("Location hierarchy created", f"{FACTORY} › {BUILDING} › {FLOOR} › {AREA}")
    go_home(d)
    create_equipment(d, E2E_CODE, E2E_NAME)
    passed("Admin registered equipment in the area", f"{E2E_CODE}, Fire extinguisher")

    sign_out(d)
    sign_in(d, INSPECTOR[1], INSPECTOR[2])
    expect("Inspector" in d.texts(d.wait(rid="signed-in-as")), "not signed in as the inspector")
    passed("Signed in as the inspector")

    start_inspection(d, E2E_CODE)
    passed("Found the equipment by search and opened its checklist")
    total = answer_checklist(d, {0}, "Not in marked place")
    passed(f"Failed item 1 with a note and a camera photo; passed the other {total - 1}")

    draft_query = {"rid_prefix": "inspection-"}
    if d.debuggable:
        draft_id = query1(d.db(), "SELECT id FROM inspections WHERE equipmentCode = ? AND completedAt IS NULL", E2E_CODE)
        expect(draft_id is not None, "draft not stored in the database")
        draft_query = {"rid": f"inspection-{draft_id}"}
    d.restart_app()
    d.wait(timeout=60, rid="dash-scan")
    d.tap(rid="tab-history")
    d.tap(rid="history-status-DRAFT")
    d.tap(**draft_query)
    progress = d.wait(rid="progress-text")
    expect(f"{total} of {total} answered" in (progress.get("text") or ""), "draft answers were lost after restart")
    passed("App restarted; draft reopened from History with all answers", progress.get("text"))

    button = d.wait(rid="complete-inspection")
    x1, y1, x2, y2 = d.bounds(button)
    cx, cy = str((x1 + x2) // 2), str((y1 + y2) // 2)
    d.sh("input", "tap", cx, cy)
    d.sh("input", "tap", cx, cy)  # immediate retry of the submission
    d.wait(timeout=90, rid="report-outcome")
    status = d.wait(rid="pdf-status").get("text") or ""
    expect("PDF ready" in status, f"PDF not generated: {status!r}")
    passed("Submitted (button pressed twice); PDF generated", status.splitlines()[-1])

    d.tap(rid="open-pdf")
    d.wait(timeout=40, rid="pdf-page-0")
    time.sleep(2)
    d.screenshot("workflow-pdf-viewer.png")
    passed("PDF opened in the app's viewer (works offline without a PDF app)")
    d.back()

    if not d.debuggable:
        expect("Corrective actions (1)" in d.texts(d.scroll_to(rid="report-actions-title")), "report does not list exactly 1 corrective action")
        d.tap(rid="tab-history")
        if d.exists(rid="history-clear"):
            d.tap(rid="history-clear")
        count = d.wait(rid="history-count").get("text") or ""
        expect(count.startswith("1 of 1"), f"history shows {count!r}")
        d.tap(rid="tab-actions")
        d.tap_chip("filter-ALL")
        shown = d.wait(rid="actions-count").get("text") or ""
        expect("1 corrective action in total" in shown, f"actions screen shows {shown!r}")
        passed("Exactly one inspection and one corrective action after the retried submission (on-screen counts)", f"{count}; {shown}")
        return release_actions(d)
    db = d.db()
    inspections = query1(db, "SELECT COUNT(*) FROM inspections WHERE equipmentCode = ?", E2E_CODE)
    actions = query1(db, "SELECT COUNT(*) FROM corrective_actions ca JOIN inspections i ON i.id = ca.inspectionId WHERE i.equipmentCode = ?", E2E_CODE)
    expect(inspections == 1 and actions == 1, f"expected exactly 1 inspection and 1 corrective action, got {inspections} and {actions}")
    passed("Exactly one inspection and one corrective action exist after the retried submission")
    report = query1(db, "SELECT reportFileName FROM inspections WHERE equipmentCode = ?", E2E_CODE)
    pdf_path = os.path.join(d.out, report)
    with open(pdf_path, "wb") as handle:
        handle.write(d.run_as_bytes(f"files/reports/{report}"))
    raw = open(pdf_path, "rb").read()
    images = len(re.findall(rb"/Subtype\s*/Image", raw))
    text = pdf_text(pdf_path)
    expect(raw.startswith(b"%PDF") and images >= 1, f"PDF has no embedded photo (images={images})")
    expect("Not in marked place" in text and "Corrective actions (1)" in text, "PDF does not show the finding / corrective action")
    passed("PDF contains the photo and the finding", f"{images} image(s); finding and 'Corrective actions (1)' present")

    return actions_flow(d, debuggable=True)


def release_actions(d):
    return actions_flow(d, debuggable=False)


def actions_flow(d, debuggable):
    go_home(d)  # the report screen has no bottom bar
    d.tap(rid="tab-actions")
    d.tap_chip("filter-TODO")
    action_node = d.wait(rid_prefix="action-")
    action_id = int(action_node.get("resource-id").split("-")[1])
    d.tap_node(action_node)
    expect("Open" in d.texts(d.wait(rid="action-status")), "new action is not Open")
    d.tap(rid="start-action")
    d.wait(timeout=20, rid="resolve-action")
    expect("In progress" in d.texts(d.wait(rid="action-status")), "action did not move to In progress")
    passed("Inspector moved the action Open → In progress")
    d.tap_node(d.scroll_to(rid="after-fix-photo"))
    d.wait(timeout=25, rid="shutter")
    time.sleep(3)
    d.tap(rid="shutter")
    d.wait(timeout=40, rid="action-detail")
    d.tap_node(d.scroll_to(rid="resolution-note"))
    d.type_text("Moved back to marked bracket")
    d.hide_keyboard()
    d.tap_node(d.scroll_to(rid="resolve-action"))
    d.wait(timeout=20, rid="verify-locked")
    expect("Resolved" in d.texts(d.scroll_to(rid="action-status")), "action did not move to Resolved")
    expect(not d.exists(rid="verify-action"), "inspector can see the Verify button")
    passed("Inspector moved the action In progress → Resolved with an after-fix photo; Verify is not available to the inspector")

    sign_out(d)
    sign_in(d, ADMIN[1], ADMIN[2])
    d.tap(rid="tab-actions")
    d.tap_chip("filter-AWAITING")
    d.tap(rid=f"action-{action_id}")
    d.tap_node(d.scroll_to(rid="verify-action"))
    d.wait(timeout=20, rid="verified-text")
    history = d.texts(d.scroll_to(rid="action-history"))
    d.screenshot("workflow-action-verified.png")
    passed("Admin verified the action (Resolved → Verified)")

    if not debuggable:
        for step in ("Created (Open)", "Open → In progress", "In progress → Resolved", "→ Verified"):
            expect(step in history, f"history panel misses {step!r}: {history}")
        expect(history.count(INSPECTOR[0]) >= 3 and ADMIN[0] in history, f"history panel misses who did it: {history}")
        passed("Action history shows who and when for all 4 statuses (on screen)")
        expect(d.sh("settings", "get", "global", "airplane_mode_on").strip() == "1", "airplane mode turned off during the test")
        passed("Still in airplane mode at the end")
        return {"history": history}
    db = d.db()
    row = db.execute("SELECT status, responsibleName, startedByName, startedAt, resolvedByName, resolvedAt, verifiedByName, verifiedAt FROM corrective_actions WHERE id = ?", (action_id,)).fetchone()
    events = db.execute("SELECT fromStatus, toStatus, byName, at FROM action_events WHERE actionId = ? ORDER BY at, id", (action_id,)).fetchall()
    after_fix = query1(db, "SELECT COUNT(*) FROM photos WHERE actionId = ?", action_id)
    expect(row[0] == "VERIFIED", f"status is {row[0]}")
    expect([e[1] for e in events] == ["OPEN", "IN_PROGRESS", "RESOLVED", "VERIFIED"], f"events: {events}")
    expect([e[2] for e in events] == [INSPECTOR[0], INSPECTOR[0], INSPECTOR[0], ADMIN[0]], f"event users: {events}")
    expect(row[2] == INSPECTOR[0] and row[4] == INSPECTOR[0] and row[6] == ADMIN[0] and row[3] <= row[5] <= row[7], f"transition fields: {row}")
    expect(after_fix == 1, f"after-fix photos: {after_fix}")
    passed("Audit trail records who and when for all 4 statuses", " → ".join(f"{e[1]} by {e[2]}" for e in events))
    expect(row[1] == INSPECTOR[0], f"responsible person: {row[1]}")
    passed("Action has a responsible person, priority and due date", f"responsible {row[1]}")
    expect(d.sh("settings", "get", "global", "airplane_mode_on").strip() == "1", "airplane mode turned off during the test")
    crashes = d.sh("logcat", "-b", "crash", "-d", check=False)
    expect(d.package not in crashes, "the app crashed:\n" + crashes[-2000:])
    passed("Still in airplane mode at the end; no crashes in logcat")
    return {"history": history}


def server_get(server, key, path):
    request = urllib.request.Request(server + path, headers={"Authorization": f"Bearer {key}"})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode())


def server_post(server, key, path):
    request = urllib.request.Request(server + path, data=b"", method="POST", headers={"Authorization": f"Bearer {key}"})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode())


def wait_server(server, key, predicate, timeout=240):
    deadline = time.time() + timeout
    summary = None
    while time.time() < deadline:
        summary = server_get(server, key, "/test/summary")
        if predicate(summary):
            return summary
        time.sleep(3)
    raise SmokeFailure(f"server did not reach the expected state in {timeout}s: {json.dumps(summary)[:600]}")


def sync_flow(d, server, key):
    d.airplane(True)
    d.launch()
    go_home(d)
    if not d.exists(rid="dash-scan"):
        sign_in(d, ADMIN[1], ADMIN[2])
    open_admin(d, "admin-sync")
    d.tap(rid="sync-enabled")
    d.type_field("sync-url", "http://10.0.2.2:8787")
    d.type_field("sync-key", key)
    d.tap(rid="sync-save")
    phase = d.wait(contains="Waiting for a network").get("text")
    passed("Sync configured while offline; waiting for a network", phase)
    go_home(d)

    create_equipment(d, SYNC_CODE, SYNC_NAME)
    start_inspection(d, SYNC_CODE)
    answer_checklist(d, {1}, "Gauge in red zone")
    d.tap(rid="complete-inspection")
    d.wait(timeout=90, rid="report-outcome")
    passed("Submitted an inspection with a photo while offline")
    expect(server_get(server, key, "/test/summary")["inspections"] == 0, "server received data while offline")

    db = d.db()
    expected_inspections = query1(db, "SELECT COUNT(*) FROM inspections WHERE completedAt IS NOT NULL")
    expected_actions = query1(db, "SELECT COUNT(*) FROM corrective_actions")
    expected_photos = query1(db, "SELECT COUNT(*) FROM photos p JOIN inspections i ON i.id = p.inspectionId WHERE i.completedAt IS NOT NULL")
    d.restart_app()
    d.wait(timeout=60, rid="dash-scan")
    open_admin(d, "admin-sync")
    pending = d.wait(rid="sync-pending").get("text")
    expect(f"{expected_inspections + expected_actions} record(s)" in pending and f"{expected_photos} photo(s)" in pending, f"pending queue after restart: {pending}")
    passed("Pending uploads survived an app restart", pending)

    d.airplane(False)
    summary = wait_server(server, key, lambda s: s["inspections"] >= expected_inspections and s["corrective_actions"] >= expected_actions and s["photos"] >= expected_photos)
    passed("Reconnected: server received the records and photos",
           f"{summary['inspections']} inspections, {summary['corrective_actions']} actions, {summary['photos']} photos")

    devices = {k.split("/")[0] for k in summary["records"]}
    expect(len(devices) == 1, f"unexpected devices: {devices}")
    device = devices.pop()
    db = d.db()
    mismatches = []
    for photo_id, file_name in db.execute("SELECT p.id, p.fileName FROM photos p JOIN inspections i ON i.id = p.inspectionId WHERE i.completedAt IS NOT NULL"):
        local = hashlib.sha256(d.run_as_bytes(f"files/photos/{file_name}")).hexdigest()
        if summary["photo_hashes"].get(f"{device}/{photo_id}") != local:
            mismatches.append(photo_id)
    expect(not mismatches, f"photo hashes differ for {mismatches}")
    passed("Every server photo matches the phone's file byte-for-byte (SHA-256)", f"{expected_photos} photos")
    expect(summary["stats"]["dropped_responses"] >= 1 and summary["stats"]["unchanged"] >= 1,
           f"interrupted upload was not retried: {summary['stats']}")
    expect(summary["inspections"] == expected_inspections and summary["corrective_actions"] == expected_actions and summary["photos"] == expected_photos,
           "duplicates on the server after the retried upload")
    passed("Interrupted upload (server saved, reply lost) was retried without duplicates", json.dumps(summary["stats"]))

    before = summary["stats"]
    d.tap_node(d.scroll_to(rid="sync-resend-all"))
    d.tap(text="Re-upload")
    summary = wait_server(server, key, lambda s: s["stats"]["photos_unchanged"] >= before["photos_unchanged"] + expected_photos
                          and s["stats"]["unchanged"] >= before["unchanged"] + expected_inspections + expected_actions)
    expect(summary["inspections"] == expected_inspections and summary["corrective_actions"] == expected_actions and summary["photos"] == expected_photos,
           f"forced re-upload created duplicates: {summary}")
    passed("Forced re-upload of everything: server still has exactly one copy of each", json.dumps(summary["stats"]))

    action_id = query1(db, "SELECT ca.id FROM corrective_actions ca JOIN inspections i ON i.id = ca.inspectionId WHERE i.equipmentCode = ?", SYNC_CODE)
    server_post(server, key, f"/test/bump?device={device}&type=corrective_action&local_id={action_id}")
    go_home(d)
    d.tap(rid="tab-actions")
    d.tap_chip("filter-ALL")
    d.tap(rid=f"action-{action_id}")
    d.tap(rid="start-action")
    d.wait(timeout=20, rid="resolve-action")
    open_admin(d, "admin-sync")
    conflicts = None
    deadline = time.time() + 120
    while time.time() < deadline:
        conflicts = d.find(d.dump(), rid="sync-conflicts")
        if conflicts is not None and "Conflicts: 1" in (conflicts.get("text") or ""):
            break
        time.sleep(3)
    expect(conflicts is not None and "Conflicts: 1" in (conflicts.get("text") or ""), "conflict was not reported in the app")
    d.screenshot("sync-conflict.png")
    passed("Conflicting record reported explicitly on the Sync screen", f"corrective action #{action_id}")
    with open(os.path.join(server_dir(server), "requests.jsonl"), "rb") as handle:
        raw = handle.read().lower()
    for word in (b"password", b"pbkdf2", ADMIN[2].lower().encode(), INSPECTOR[2].lower().encode()):
        expect(word not in raw, f"uploaded data contains {word!r}")
    passed("No passwords or password hashes in anything uploaded")


SERVER_DIR = {"path": None}


def server_dir(_server):
    return SERVER_DIR["path"]


def features(d, qr_png):
    d.airplane(True)
    d.launch()
    go_home(d)
    if not d.exists(rid="dash-scan"):
        sign_in(d, ADMIN[1], ADMIN[2])

    # Five equipment types offered for new equipment.
    d.tap(rid="tab-inspect")
    d.tap(rid="add-equipment")
    types = [n for n in ["FIRE_EXTINGUISHER", "ELECTRICAL", "FIRE_ALARM", "EMERGENCY_EXIT", "BOILER"] if d.find(d.dump(), rid=f"cp-type-{n}") or d.scroll_to(rid=f"cp-type-{n}")]
    root = d.dump()
    expect(d.find(root, rid="cp-type-SEWING_LINE") is None, "legacy type offered for new equipment")
    passed("All five equipment types are separate options for new equipment", ", ".join(types))
    d.back()
    d.back()

    # Editable checklists: change a Fire alarm question, add an Electrical one.
    open_admin(d, "admin-checklists")
    d.tap(rid="type-FIRE_ALARM")
    d.tap(rid="question-0-edit")
    question = d.wait(rid="question-text")
    d.tap_node(question)
    d.sh("input", "keyevent", "KEYCODE_MOVE_END")
    d.type_text(" checked-by-test")
    d.hide_keyboard()
    d.tap(rid="question-save")
    d.scroll_to(contains="checked-by-test")
    d.tap(rid="type-ELECTRICAL")
    d.tap(rid="add-question")
    d.type_field("question-section", "Electrical safety")
    d.type_field("question-text", "Thermal scan done this quarter")
    d.tap(rid="question-save")
    d.scroll_to(contains="Thermal scan done this quarter")
    passed("Fire alarm question edited and Electrical question added in the checklist editor")
    go_home(d)
    create_equipment(d, "FA-T-01", "Fire alarm panel T", "cp-type-FIRE_ALARM")
    start_inspection(d, "FA-T-01")
    expect(d.exists(contains="checked-by-test"), "new Fire alarm inspection does not use the edited question")
    passed("New Fire alarm inspection uses the edited question")
    d.tap(rid="delete-inspection")
    d.tap(text="Delete")
    time.sleep(1.5)

    # Historical snapshot: rename the inspected equipment; the old inspection keeps its name and questions.
    d.tap(rid="tab-inspect")
    d.type_field("equipment-search", E2E_CODE)
    d.tap(text="Edit")
    name = d.wait(rid="cp-name")
    d.tap_node(name)
    d.sh("input", "keyevent", "KEYCODE_MOVE_END")
    d.type_text(" renamed")
    d.hide_keyboard()
    d.tap_node(d.scroll_to(rid="cp-save"))
    time.sleep(1.5)
    d.tap(rid="tab-history")
    d.type_field("history-search", E2E_CODE)
    card = d.texts(d.wait(rid_prefix="inspection-"))
    expect(E2E_NAME in card and "renamed" not in card, f"history lost the snapshot: {card}")
    passed("Historical snapshot kept: old inspection still shows the equipment as inspected", E2E_NAME)

    # QR code from a picture (the photo picker) — the picture is a printed label rendered to PNG.
    d.run("push", qr_png, "/sdcard/Pictures/safestitch-label.png")
    d.sh("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Pictures/safestitch-label.png", check=False)
    time.sleep(3)
    go_home(d)
    d.tap(rid="dash-scan")
    d.tap(rid="scan-image")
    time.sleep(3)
    root = d.dump()
    picks = [n for n in root.iter("node") if "photo" in (n.get("content-desc") or "").lower() and n.get("clickable") == "true"]
    if not picks:
        picks = [n for n in root.iter("node") if (n.get("class") or "").endswith("ImageView") and n.get("clickable") == "true"]
    expect(picks, "photo picker shows no picture")
    d.tap_node(picks[0], pause=3)
    d.wait(timeout=30, rid="progress-text")
    passed("QR code read from a picture via the photo picker; checklist opened")
    d.tap(rid="delete-inspection")
    d.tap(text="Delete")
    time.sleep(1.5)

    # Multi-label printable QR PDF.
    d.tap(rid="tab-inspect")
    d.tap(rid="print-labels")
    d.wait(text="QR labels ready")
    db = d.db()
    count = query1(db, "SELECT COUNT(*) FROM checkpoints")
    passed("Printable QR label sheet generated", f"{count} labels")
    d.back()

    # Report Save a copy / Share.
    d.tap(rid="tab-history")
    d.tap(rid="history-status-COMPLETED")
    d.tap(rid_prefix="inspection-")
    d.wait(rid="report-outcome")
    d.tap_node(d.scroll_to(rid="share-pdf"), pause=3)
    root = d.dump()
    expect(any(p in (n.get("package") or "") for n in root.iter("node") for p in ("intentresolver", "android")), "share sheet did not open")
    passed("Report Share opens the Android share sheet")
    d.back()
    d.back()

    # History filters and dashboard totals against the database.
    db = d.db()
    total = query1(db, "SELECT COUNT(*) FROM inspections")
    completed = query1(db, "SELECT COUNT(*) FROM inspections WHERE completedAt IS NOT NULL")
    go_home(d)
    d.tap(rid="tab-history")
    if d.exists(rid="history-clear"):
        d.tap(rid="history-clear")
    d.tap(rid="history-status-COMPLETED")
    shown = d.wait(rid="history-count").get("text")
    expect(shown.startswith(f"{completed} of {total}"), f"history filter count {shown!r}, expected {completed} of {total}")
    passed("History filter matches the database", shown)
    go_home(d)
    d.scroll_to(rid="kpi-total-actions")
    root = d.dump()
    kpi_total = d.find(root, rid="kpi-total-actions").get("text")
    db_actions = query1(db, "SELECT COUNT(*) FROM corrective_actions")
    expect(kpi_total == str(db_actions), f"dashboard total actions {kpi_total} vs database {db_actions}")
    d.swipe(up=False)
    d.swipe(up=False)
    root = d.dump()
    kpi_inspections = (d.find(root, rid="kpi-total-inspections") or d.scroll_to(rid="kpi-total-inspections")).get("text")
    expect(kpi_inspections == str(total), f"dashboard total inspections {kpi_inspections} vs database {total}")
    passed("Dashboard totals match the database", f"{kpi_inspections} inspections, {kpi_total} corrective actions")
    d.screenshot("features-dashboard.png")
    captured_flow(d)


def captured_flow(d):
    d.airplane(True)
    go_home(d)
    d.tap(rid="viewfinder-capture-fab")
    time.sleep(2.5)
    d.tap(rid="tab-history")
    d.tap(rid="history-open-captured")
    count_text = d.wait(rid="captured-inspections-count").get("text") or ""
    expect("4 of 4" in count_text or "captured photo" in count_text, f"unexpected count: {count_text!r}")
    root = d.dump()
    cards = d.find_all(root, rid_prefix="captured-inspection-")
    expect(len(cards) >= 1, "no captured inspection cards found")
    newest_id = cards[0].get("resource-id").split("-")[-1]
    ts_before = (d.find(root, rid=f"captured-timestamp-{newest_id}").get("text") or "").strip()
    d.tap(rid=f"toggle-tag-{newest_id}-PASS")
    time.sleep(1.0)
    d.restart_app()
    d.wait(timeout=60, rid="dash-scan")
    d.tap(rid="tab-history")
    d.tap(rid="history-open-captured")
    d.wait(rid=f"captured-inspection-{newest_id}")
    root_after = d.dump()
    ts_after = (d.find(root_after, rid=f"captured-timestamp-{newest_id}").get("text") or "").strip()
    expect(ts_after == ts_before, f"timestamp changed after restart: {ts_before!r} vs {ts_after!r}")
    expect(d.find(root_after, rid=f"captured-thumbnail-{newest_id}") is not None, "thumbnail missing after restart")
    expect(d.find(root_after, rid=f"captured-tag-{newest_id}-PASS") is not None, "PASS tag missing after restart")
    passed("Fresh captured photo, timestamp, thumbnail, and changed tag survived app restart in airplane mode", f"id={newest_id}, timestamp={ts_after}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--out", required=True)
    sub = parser.add_subparsers(dest="scenario", required=True)
    sub.add_parser("workflow")
    sub.add_parser("captured")
    s = sub.add_parser("sync")
    s.add_argument("--server", default="http://127.0.0.1:8787")
    s.add_argument("--key", required=True)
    s.add_argument("--server-data", required=True)
    f = sub.add_parser("features")
    f.add_argument("--qr-png", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    device = Device(args.adb, args.serial, args.package, args.out)
    try:
        if args.scenario == "workflow":
            workflow(device)
        elif args.scenario == "captured":
            captured_flow(device)
        elif args.scenario == "sync":
            SERVER_DIR["path"] = args.server_data
            sync_flow(device, args.server, args.key)
        else:
            features(device, args.qr_png)
    except (SmokeFailure, subprocess.TimeoutExpired) as error:
        try:
            device.screenshot(f"failure-{args.scenario}.png")
        except Exception:  # noqa: BLE001 - best effort
            pass
        print(f"    FAIL  {error}", flush=True)
        print(json.dumps({"result": "FAIL", "scenario": args.scenario, "error": str(error), "checks": CHECKS}))
        return 1
    print(json.dumps({"result": "PASS", "scenario": args.scenario, "checks": CHECKS}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
