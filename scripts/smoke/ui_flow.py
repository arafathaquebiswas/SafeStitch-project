#!/usr/bin/env python3
"""SafeStitch on-device smoke test: one complete inspection through PDF generation.

Drives the *installed* app with adb + uiautomator (no test APK), so it exercises exactly
the build on the device, e.g. APKs generated from the release AAB by bundletool.
Called by scripts/device-smoke-test.sh; prints a JSON summary on the last line.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
ACTIVITY = "com.safestitch.app.MainActivity"
FAIL_POSITION = 1  # this checklist item is answered FAIL (with note + photo); all others PASS


class SmokeFailure(Exception):
    pass


class Device:
    def __init__(self, adb, serial):
        self.adb = adb
        self.serial = serial
        sizes = re.findall(r"(\d+)x(\d+)", self.sh("wm", "size"))
        self.width, self.height = map(int, sizes[-1])

    def run(self, *args, timeout=60, check=True):
        proc = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        if check and proc.returncode != 0:
            raise SmokeFailure(f"adb {' '.join(args)} failed: {(proc.stderr or proc.stdout).strip()}")
        return proc.stdout

    def sh(self, *args, **kwargs):
        return self.run("shell", *args, **kwargs)

    def dump(self):
        path = "/data/local/tmp/safestitch_ui.xml"
        for _ in range(8):
            self.sh("rm", "-f", path, check=False)
            self.sh("uiautomator", "dump", path, check=False, timeout=40)
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
    def find(root, rid=None, text=None, contains=None, desc=None, pkg_part=None, clickable=None):
        for node in root.iter("node"):
            node_text = (node.get("text") or "").strip()
            if rid is not None and node.get("resource-id") != rid:
                continue
            if text is not None and node_text.lower() != text.lower():
                continue
            if contains is not None and contains.lower() not in node_text.lower():
                continue
            if desc is not None and node.get("content-desc") != desc:
                continue
            if pkg_part is not None and pkg_part not in (node.get("package") or ""):
                continue
            if clickable is not None and node.get("clickable") != ("true" if clickable else "false"):
                continue
            return node
        return None

    @staticmethod
    def bounds(node):
        return tuple(map(int, BOUNDS.match(node.get("bounds")).groups()))

    def tap_node(self, node, pause=0.7):
        x1, y1, x2, y2 = self.bounds(node)
        self.sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(pause)

    def wait(self, timeout=20, **query):
        """Returns the node once it exists AND sits still (same bounds in two dumps in a row),
        so taps never land while the layout is still moving (e.g. the keyboard closing)."""
        deadline = time.time() + timeout
        previous = None
        while time.time() < deadline:
            node = self.find(self.dump(), **query)
            if node is not None:
                if previous is not None and node.get("bounds") == previous:
                    return node
                previous = node.get("bounds")
                time.sleep(0.4)
                continue
            previous = None
            time.sleep(0.7)
        raise SmokeFailure(f"timed out after {timeout}s waiting for {query}")

    def tap(self, timeout=20, **query):
        node = self.wait(timeout, **query)
        self.tap_node(node)
        return node

    def keyboard_shown(self):
        return "mInputShown=true" in self.sh("dumpsys", "input_method", check=False)

    def hide_keyboard(self):
        """Presses Back once to close the keyboard, then waits for it to close. Never presses
        Back a second time while the keyboard is still animating (that would leave the screen)."""
        time.sleep(0.5)
        if not self.keyboard_shown():
            return
        self.sh("input", "keyevent", "4")
        deadline = time.time() + 8
        while time.time() < deadline:
            time.sleep(0.4)
            if not self.keyboard_shown():
                time.sleep(1.0)  # let the screen re-layout after the keyboard is gone
                return
        raise SmokeFailure("could not hide the on-screen keyboard")

    def type(self, text):
        encoded = text.replace(" ", "%s")
        if not re.fullmatch(r"[A-Za-z0-9.%\-]+", encoded):
            raise ValueError(f"unsupported characters for adb input: {text!r}")
        self.sh("input", "text", encoded)
        time.sleep(0.5)

    def swipe(self, up=True, fraction=0.34):
        x = self.width // 2
        a, b = int(self.height * 0.70), int(self.height * (0.70 - fraction))
        start, end = (a, b) if up else (b, a)
        self.sh("input", "swipe", str(x), str(start), str(x), str(end), "400")
        time.sleep(0.9)

    def scroll_to(self, rid, safe_top, safe_bottom, max_swipes=40):
        for _ in range(max_swipes):
            node = self.find(self.dump(), rid=rid)
            if node is not None:
                _, y1, _, y2 = self.bounds(node)
                if y1 >= safe_top and y2 <= safe_bottom:
                    return node
                if y1 < safe_top:
                    self.swipe(up=False, fraction=0.12)
                    continue
                self.swipe(up=True, fraction=0.12 if y1 < safe_bottom else 0.34)
                continue
            self.swipe(up=True)
        raise SmokeFailure(f"could not scroll to {rid}")

    def screenshot(self, path):
        with open(path, "wb") as handle:
            subprocess.run([self.adb, "-s", self.serial, "exec-out", "screencap", "-p"], stdout=handle, check=True, timeout=60)


def validate_pdf(path):
    with open(path, "rb") as handle:
        data = handle.read()
    if not data.startswith(b"%PDF-"):
        raise SmokeFailure(f"{path} is not a PDF")
    if b"%%EOF" not in data[-2048:]:
        raise SmokeFailure(f"{path} is truncated (no %%EOF)")
    pages = len(re.findall(rb"/Type\s*/Page(?![a-zA-Z])", data))
    if pages < 1:
        raise SmokeFailure(f"{path} has no pages")
    return pages, len(data)


def save_copy_via_system_picker(device, package, out_dir, report_name):
    """Uses the in-app 'Save a copy' button and Android's Save-as screen, then pulls the file."""
    device.sh("rm", "-f", f"/sdcard/Download/{report_name}", check=False)
    root = device.dump()
    top_bar = device.find(root, rid="save-pdf")
    if top_bar is None:
        device.swipe(up=True)
    device.tap(rid="save-pdf")
    deadline = time.time() + 30
    root = None
    while time.time() < deadline:
        root = device.dump()
        if device.find(root, pkg_part="documentsui") is not None:
            break
        time.sleep(1)
    else:
        raise SmokeFailure("the system Save-as screen did not open")
    save = device.find(root, text="Save", pkg_part="documentsui")
    if save is None or save.get("enabled") != "true":
        roots = device.find(root, desc="Show roots")
        if roots is not None:
            device.tap_node(roots)
            device.tap(text="Downloads", pkg_part="documentsui")
        save = device.wait(text="Save", pkg_part="documentsui")
    device.tap_node(save, pause=1.5)
    root = device.dump()
    overwrite = device.find(root, text="OK", pkg_part="documentsui")
    if overwrite is not None:
        device.tap_node(overwrite, pause=1.5)
    deadline = time.time() + 30
    listing = ""
    while time.time() < deadline:
        listing = device.sh("ls", "/sdcard/Download/", check=False)
        if report_name in listing.split():
            break
        time.sleep(1)
    else:
        raise SmokeFailure(f"saved copy {report_name} not found in /sdcard/Download ({listing.strip()!r})")
    device.wait(timeout=15, rid="report-outcome", pkg_part=package)  # back in SafeStitch
    local = os.path.join(out_dir, report_name)
    device.run("pull", f"/sdcard/Download/{report_name}", local, timeout=120)
    return local


def run(args):
    device = Device(args.adb, args.serial)
    checks = []

    def passed(name, detail=""):
        checks.append({"check": name, "result": "PASS", "detail": detail})
        print(f"    PASS  {name}" + (f"  ({detail})" if detail else ""), flush=True)

    code = args.code
    device.sh("logcat", "-b", "crash", "-c", check=False)
    device.sh("am", "force-stop", args.package, check=False)
    device.sh("am", "start", "-W", "-n", f"{args.package}/{ACTIVITY}")
    device.wait(timeout=40, rid="scan-fab")
    passed("App launches (home screen shown)")

    device.tap(rid="scan-fab")
    device.wait(rid="manual-code")
    deadline = time.time() + 15
    while True:
        root = device.dump()
        # CameraX draws the preview into a TextureView/SurfaceView inside the Compose interop holder.
        if any(n.get("class") in ("android.view.TextureView", "android.view.SurfaceView") for n in root.iter("node")):
            passed("QR scanner opens with live camera preview")
            break
        if time.time() > deadline:
            raise SmokeFailure("camera preview not shown on the scan screen (camera permission?)")
        time.sleep(1)
    device.tap(rid="manual-code")
    device.type(code)
    device.hide_keyboard()
    device.tap(rid="manual-continue")

    device.wait(rid="cp-name")
    passed("Unknown checkpoint code opens the 'New checkpoint' form", code)
    device.tap(rid="cp-name")
    device.type("Smoke test fire point")
    device.hide_keyboard()
    device.tap(rid="cp-area")
    device.type("Floor 1")
    device.hide_keyboard()
    device.tap(rid="cp-type-FIRE_SAFETY")
    device.tap(rid="cp-save")

    device.wait(timeout=20, rid="inspector-name")
    passed("Checkpoint saved offline; inspection checklist opened")
    inspector = device.find(device.dump(), rid="inspector-name")
    if not (inspector.get("text") or "").strip():  # pre-filled from Settings after the first inspection
        device.tap_node(inspector)
        device.type("Smoke Tester")
        device.hide_keyboard()

    root = device.dump()
    progress = device.find(root, rid="progress-text")
    match = re.search(r"of (\d+) answered", progress.get("text") if progress is not None else "")
    if not match:
        raise SmokeFailure("could not read the checklist size")
    total = int(match.group(1))
    safe_top = device.bounds(device.find(root, rid="delete-inspection"))[3] + 12
    safe_bottom = device.bounds(device.find(root, rid="complete-inspection"))[1] - 70

    for position in range(total):
        answer = "FAIL" if position == FAIL_POSITION else "PASS"
        device.tap_node(device.scroll_to(f"item-{position}-{answer}", safe_top, safe_bottom), pause=0.5)
        if position == FAIL_POSITION:
            device.tap_node(device.scroll_to(f"item-{position}-note", safe_top, safe_bottom))
            device.type("Seal broken. Replaced by maintenance.")
            device.hide_keyboard()
            device.tap_node(device.scroll_to(f"item-{position}-photo", safe_top, safe_bottom))
            device.wait(timeout=20, rid="shutter")
            time.sleep(3)  # let the camera settle
            device.tap(rid="shutter")
            device.wait(timeout=40, rid="inspection-list")
            node = device.scroll_to(f"item-{position}-photo", safe_top, safe_bottom)
            if device.find(node, contains="Photo (1)") is None:
                raise SmokeFailure("the captured photo was not attached to the checklist item")
            passed("Camera photo captured and attached to the failed item")
    root = device.dump()
    progress = device.find(root, rid="progress-text")
    if progress is None:
        for _ in range(12):
            device.swipe(up=False)
        progress = device.wait(rid="progress-text")
    passed(f"All {total} checklist items answered (1 FAIL with note, {total - 1} PASS)")

    device.tap(rid="complete-inspection")
    outcome = device.wait(timeout=90, rid="report-outcome")
    status = device.wait(rid="pdf-status")
    status_text = status.get("text") or ""
    if "PDF ready" not in status_text or "page" not in status_text:
        raise SmokeFailure(f"PDF was not generated: {status_text!r}")
    report_name = re.search(r"PDF ready: (\S+\.pdf)", status_text).group(1)
    passed("Inspection completed and PDF report generated", f"{outcome.get('text')}; {status_text.splitlines()[-1]}")
    device.screenshot(os.path.join(args.out, "report-screen.png"))

    for _ in range(6):
        if device.find(device.dump(), rid="pdf-preview") is not None:
            break
        device.swipe(up=True)
    else:
        raise SmokeFailure("PDF preview not shown")
    passed("App renders page 1 of its own PDF (in-app preview)")
    device.screenshot(os.path.join(args.out, "report-preview.png"))

    for _ in range(6):
        device.swipe(up=False)
    local_pdf = save_copy_via_system_picker(device, args.package, args.out, report_name)
    pages, size = validate_pdf(local_pdf)
    passed("PDF saved with the system Save-as screen and pulled to the Mac", f"{os.path.basename(local_pdf)}, {pages} pages, {size // 1024} KB")

    airplane = device.sh("settings", "get", "global", "airplane_mode_on", check=False).strip()
    if airplane != "1":
        raise SmokeFailure("airplane mode was not on for the whole test")
    passed("Airplane mode still on at the end of the test")

    crashes = device.sh("logcat", "-b", "crash", "-d", check=False)
    if args.package in crashes:
        raise SmokeFailure("the app crashed during the test:\n" + crashes[-2000:])
    passed("No crashes recorded in logcat")
    return checks, local_pdf


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--code", default=f"SS-SMOKE-{int(time.time())}")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    try:
        checks, pdf = run(args)
    except (SmokeFailure, subprocess.TimeoutExpired) as error:
        try:
            Device(args.adb, args.serial).screenshot(os.path.join(args.out, "failure.png"))
        except Exception:  # noqa: BLE001 - best effort only
            pass
        print(f"    FAIL  {error}", flush=True)
        print(json.dumps({"result": "FAIL", "error": str(error)}))
        return 1
    print(json.dumps({"result": "PASS", "checks": checks, "pdf": pdf}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
