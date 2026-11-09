#!/usr/bin/env python3
"""SafeStitch reference sync server, for LOCAL TESTING ONLY (schema 2).

    python3 scripts/sync-test-server.py --key test-key-123 --data build/sync-server [--fail-first 1]

Contract (see app/src/main/java/com/safestitch/app/sync/SyncPayload.kt):
  POST /api/v1/sync                       JSON records; "Authorization: Bearer <key>"
       -> {"results": [{"type", "local_id", "status": created|unchanged|updated|conflict, "message"?}]}
  PUT  /api/v1/photos/<device>/<photo_id> raw JPEG; header X-SafeStitch-Sha256
       -> 201 created | 200 unchanged (same bytes) | 409 conflict (different bytes) | 400 corrupt upload

Records are keyed by (device_id, type, local_id). An exact re-send is "unchanged" (never duplicated);
a higher revision replaces the stored copy; anything else is refused as a conflict and the stored copy kept.

Test helpers (same API key required):
  GET  /test/summary                      counts, keys, revisions, photo hashes
  POST /test/bump?device=..&type=..&local_id=..   make the stored copy "newer" to provoke a conflict
  --fail-first N                          store the first N record uploads but answer 503, like a
                                          connection lost after the server saved the data.

From the Android emulator this computer is http://10.0.2.2:<port>. Bound to 127.0.0.1 only.
"""
import argparse
import hashlib
import json
import os
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

LOCK = threading.Lock()


class Store:
    def __init__(self, root):
        self.root = root
        os.makedirs(os.path.join(root, "photos"), exist_ok=True)
        self.state_path = os.path.join(root, "state.json")
        self.state = {"records": {}, "photos": {}, "stats": {"record_requests": 0, "created": 0, "unchanged": 0, "updated": 0, "conflict": 0, "photo_uploads": 0, "photos_unchanged": 0, "photo_conflicts": 0, "dropped_responses": 0}}
        if os.path.exists(self.state_path):
            with open(self.state_path, encoding="utf-8") as handle:
                self.state = json.load(handle)

    def save(self):
        tmp = self.state_path + ".part"
        with open(tmp, "w", encoding="utf-8") as handle:
            json.dump(self.state, handle, indent=1, sort_keys=True)
        os.replace(tmp, self.state_path)

    def put_record(self, device, kind, record):
        key = f"{device}/{kind}/{record['local_id']}"
        canonical = json.dumps(record, sort_keys=True)
        stored = self.state["records"].get(key)
        if stored is None:
            status, message = "created", None
        elif stored["canonical"] == canonical:
            status, message = "unchanged", None
        elif int(record.get("revision", 0)) > int(stored["revision"]):
            status, message = "updated", None
        else:
            status = "conflict"
            message = (f"The server already has a different copy of this {kind.replace('_', ' ')} "
                       f"(server revision {stored['revision']}, this phone sent {record.get('revision')}). The server copy was kept.")
        if status in ("created", "updated"):
            self.state["records"][key] = {"revision": int(record.get("revision", 0)), "canonical": canonical, "record": record}
        self.state["stats"][status] += 1
        return {"type": kind, "local_id": record["local_id"], "status": status, **({"message": message} if message else {})}


def make_handler(api_key, store, fail_first):
    remaining_failures = [fail_first]

    class Handler(BaseHTTPRequestHandler):
        def _reply(self, code, payload):
            body = json.dumps(payload).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorised(self):
            if self.headers.get("Authorization") != f"Bearer {api_key}":
                self._reply(401, {"error": "bad api key"})
                return False
            return True

        def _body(self):
            return self.rfile.read(int(self.headers.get("Content-Length") or 0))

        def do_GET(self):
            if not self._authorised():
                return
            if urlparse(self.path).path != "/test/summary":
                return self._reply(404, {"error": "not found"})
            with LOCK:
                recs = store.state["records"]
                self._reply(200, {
                    "inspections": sum(1 for k in recs if "/inspection/" in k),
                    "corrective_actions": sum(1 for k in recs if "/corrective_action/" in k),
                    "photos": len(store.state["photos"]),
                    "stats": store.state["stats"],
                    "records": {k: {"revision": v["revision"], "status": v["record"].get("status"), "result": v["record"].get("result")} for k, v in recs.items()},
                    "photo_hashes": {k: v["sha256"] for k, v in store.state["photos"].items()},
                })

        def do_POST(self):
            if not self._authorised():
                return
            url = urlparse(self.path)
            if url.path == "/test/bump":
                q = {k: v[0] for k, v in parse_qs(url.query).items()}
                key = f"{q['device']}/{q['type']}/{q['local_id']}"
                with LOCK:
                    if key not in store.state["records"]:
                        return self._reply(404, {"error": f"no record {key}"})
                    store.state["records"][key]["revision"] = 9_000_000_000_000_000
                    store.state["records"][key]["canonical"] = "bumped-by-test"
                    store.save()
                return self._reply(200, {"bumped": key})
            if url.path != "/api/v1/sync":
                return self._reply(404, {"error": "not found"})
            raw = self._body()
            try:
                data = json.loads(raw.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                return self._reply(400, {"error": "invalid json"})
            with LOCK:
                with open(os.path.join(store.root, "requests.jsonl"), "ab") as log:
                    log.write(raw + b"\n")
                device = data.get("device_id", "?")
                results = [store.put_record(device, "inspection", r) for r in data.get("inspections", [])]
                results += [store.put_record(device, "corrective_action", r) for r in data.get("corrective_actions", [])]
                store.state["stats"]["record_requests"] += 1
                drop = remaining_failures[0] > 0
                if drop:
                    remaining_failures[0] -= 1
                    store.state["stats"]["dropped_responses"] += 1
                store.save()
            summary = " ".join(f"{r['type'][:4]}#{r['local_id']}:{r['status']}" for r in results)
            print(f"[sync] device={device[:8]} {summary}" + ("  -> answering 503 (simulated lost response)" if drop else ""), flush=True)
            if drop:
                return self._reply(503, {"error": "simulated interruption after saving"})
            self._reply(200, {"results": results})

        def do_PUT(self):
            if not self._authorised():
                return
            match = re.fullmatch(r"/api/v1/photos/([A-Za-z0-9-]{1,64})/(\d{1,12})", urlparse(self.path).path)
            if not match:
                return self._reply(404, {"error": "not found"})
            device, photo_id = match.groups()
            body = self._body()
            sha = hashlib.sha256(body).hexdigest()
            if sha != (self.headers.get("X-SafeStitch-Sha256") or "").lower():
                return self._reply(400, {"error": "corrupt upload: SHA-256 mismatch"})
            key = f"{device}/{photo_id}"
            with LOCK:
                stored = store.state["photos"].get(key)
                if stored is not None:
                    if stored["sha256"] == sha:
                        store.state["stats"]["photos_unchanged"] += 1
                        store.save()
                        return self._reply(200, {"status": "unchanged"})
                    store.state["stats"]["photo_conflicts"] += 1
                    store.save()
                    return self._reply(409, {"status": "conflict", "message": "different photo already stored"})
                folder = os.path.join(store.root, "photos", device)
                os.makedirs(folder, exist_ok=True)
                with open(os.path.join(folder, f"{photo_id}.jpg"), "wb") as handle:
                    handle.write(body)
                store.state["photos"][key] = {
                    "sha256": sha,
                    "bytes": len(body),
                    "inspection": self.headers.get("X-SafeStitch-Inspection"),
                    "action": self.headers.get("X-SafeStitch-Action"),
                    "kind": self.headers.get("X-SafeStitch-Kind"),
                }
                store.state["stats"]["photo_uploads"] += 1
                store.save()
            print(f"[photo] {key} {len(body)} bytes sha256={sha[:12]}… kind={self.headers.get('X-SafeStitch-Kind')}", flush=True)
            self._reply(201, {"status": "created"})

        def log_message(self, fmt, *args):
            pass

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--key", required=True, help="API key the app must send")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--data", default="sync-server-data", help="folder for state.json, requests.jsonl and photos/")
    parser.add_argument("--fail-first", type=int, default=0, help="answer 503 to the first N record uploads (after saving them)")
    args = parser.parse_args()
    store = Store(args.data)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(args.key, store, args.fail_first))
    print(f"SafeStitch sync test server on http://127.0.0.1:{args.port} (emulator: http://10.0.2.2:{args.port}); data in {args.data}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
