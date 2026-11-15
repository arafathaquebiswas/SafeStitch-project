"""Rendering engine: phone wireframes, orthogonal edge routing, SVG and draw.io output.

Everything is drawn as "cells" (rectangles/ellipses with optional text), so the SVG and the
editable .drawio file share exactly the same geometry. Edges are routed with an A* search on a
10 px grid that treats every screen, dialog and note as an obstacle.
"""
import heapq
import html

FONT = "Helvetica, Arial, sans-serif"
TEAL = "#0f766e"
TEAL_L = "#ccfbf1"
INK = "#1e293b"
GREY = "#64748b"
LINE = "#cbd5e1"
ADMIN_C = "#c2410c"

STATUS_COLORS = {
    "IMPLEMENTED": ("#15803d", "#dcfce7"),
    "PARTIALLY IMPLEMENTED": ("#b45309", "#fef3c7"),
    "DECLARED BUT UNREACHABLE": ("#475569", "#e2e8f0"),
    "DOCUMENTED ONLY": ("#7c3aed", "#ede9fe"),
    "NOT VERIFIED": ("#b91c1c", "#fee2e2"),
}
EDGE_STYLES = {
    #  kind: (color, width, dash)
    "nav": (INK, 1.5, None),
    "admin": (ADMIN_C, 1.7, None),
    "popup": ("#7c3aed", 1.3, "5 3"),
    "return": ("#64748b", 1.4, "2 3"),
    "external": ("#0f766e", 1.3, "7 3 2 3"),
    "partial": ("#dc2626", 1.5, "6 3"),
    "tab": ("#0e7490", 1.5, None),
    "background": ("#0f766e", 1.2, "2 2"),
    "conditional": ("#1d4ed8", 1.5, "10 3"),
    "back": ("#64748b", 1.3, "1 3"),
    "dialog_result": ("#a21caf", 1.3, "3 2"),
    "state": ("#b45309", 1.3, "4 2"),
}

PHONE_W, PHONE_H = 230, 450
LABEL_FS = 10.0          # arrow-label font size (readable when printed)
BAND_FS = 20.0           # section heading font size
SCREEN_X, SCREEN_Y, SCREEN_W, SCREEN_H = 8, 24, 214, 394


def tw(s, size, bold=False):
    """Approximate rendered text width in px (Helvetica)."""
    w = 0.0
    for ch in str(s):
        if ch in "il.,:;'|!·":
            w += 0.28
        elif ch in "mwMW@":
            w += 0.85
        elif ch.isupper():
            w += 0.66
        elif ch == " ":
            w += 0.28
        elif ord(ch) > 0x2000:
            w += 0.9
        else:
            w += 0.53
    return w * size * (1.07 if bold else 1.0)


def wrap(s, size, width, bold=False):
    out = []
    for para in str(s).split("\n"):
        words, line = para.split(" "), ""
        for w in words:
            cand = (line + " " + w).strip()
            if tw(cand, size, bold) <= width or not line:
                line = cand
            else:
                out.append(line)
                line = w
        out.append(line)
    return out


def esc(s):
    return html.escape(str(s), quote=True)


class Cell:
    __slots__ = ("id", "x", "y", "w", "h", "text", "st", "group", "attrs", "shape")

    def __init__(self, id, x, y, w, h, text="", group=None, shape="rect", attrs=None, **st):
        self.id, self.x, self.y, self.w, self.h = id, x, y, w, h
        self.text, self.group, self.shape, self.attrs, self.st = text, group, shape, attrs, st


class Diagram:
    def __init__(self, name, title, W, H):
        self.name, self.title, self.W, self.H = name, title, W, H
        self.cells = []
        self.groups = {}
        self.nodes = {}
        self.anchors = {}
        self.edges = []
        self.obstacles = []
        self.labels = []
        self.n = 0
        self.warnings = []

    def uid(self, p="c"):
        self.n += 1
        return f"{p}{self.n}"

    def add(self, x, y, w, h, text="", group=None, shape="rect", attrs=None, id=None, **st):
        c = Cell(id or self.uid(), x, y, w, h, text, group, shape, attrs, **st)
        self.cells.append(c)
        return c

    def obstacle(self, x, y, w, h, m=8):
        self.obstacles.append((x, y, w, h, m))

    # -------------------------------------------------------------------- generic pieces
    def label(self, x, y, w, s, size=8, color=INK, bold=False, italic=False, align="left", lh=1.3):
        lines = wrap(s, size, w, bold)
        h = len(lines) * size * lh + 2
        self.add(x, y, w, h, "\n".join(lines), None, fs=size, fc=color, bold=bold, italic=italic, align=align,
                 valign="top", fill="none", stroke="none", lh=lh)
        return h

    def band(self, x, y, w, h, label, fill, stroke):
        self.add(x, y, w, h, "", None, fill=fill, stroke=stroke, sw=1, r=18, opacity=0.6)
        lw = tw(label, BAND_FS, True) + 20
        self.add(x + 16, y + 8, lw, 28, label, None, fill="none", stroke="none",
                 fs=BAND_FS, fc=stroke, bold=True, align="left", valign="middle")
        self.obstacle(x + 16, y + 8, lw, 28, 2)

    def pill(self, nid, x, y, w, h, label, fill, stroke, fc=INK, size=8, attrs=None):
        self.groups[nid] = (x, y, w, h, attrs or {})
        self.add(x, y, w, h, label, nid, id=f"{nid}__node", fill=fill, stroke=stroke, sw=1.4, r=h / 2,
                 fs=size, fc=fc, bold=True, align="center", valign="middle", attrs=attrs)
        self.nodes[nid] = dict(rect=(x, y, w, h), kind="pill", group=nid)
        self.obstacle(x, y, w, h, 6)

    # -------------------------------------------------------------------- phone wireframe
    def phone_height(self, s, role=None):
        ui = [e for e in s["ui"] if not (role == "Inspector" and e.get("admin"))]
        w0 = SCREEN_W - 12
        h = 11 + 6
        for e in ui:
            if e["t"] in ("nav", "subbar", "fab"):
                h += {"nav": 26, "subbar": 24, "fab": 0}[e["t"]] + (34 if e["t"] == "fab" and not any(x["t"] == "nav" for x in ui) else 0)
                continue
            h += self._measure(e, w0) + (0 if e["t"] in ("bar", "gap") else 3.5)
        return max(PHONE_H, int(h + SCREEN_Y + 56 - (PHONE_H - SCREEN_H - SCREEN_Y) + 8))

    def phone(self, s, x, y, role=None, runtime=False):
        sid = s["id"]
        g = sid
        ph = self.phone_height(s, role)
        self.cur_h = ph
        attrs = {"screen_id": sid, "screen_name": s["name"], "route": s["route"], "source_file": s["file"],
                 "roles": s["roles"], "status": s["status"],
                 "tooltip": f"{sid} {s['name']} | Route: {s['route']} | Source: {s['file']} | Roles: {s['roles']} | Status: {s['status']}"}
        self.groups[g] = (x, y - 40, PHONE_W, ph + 40, attrs)
        self.add(x, y - 41, 34, 18, sid, g, fill=INK, stroke="none", r=4, fs=10, fc="#fff", bold=True, align="center", valign="middle")
        name = s["name"]
        while tw(name, 13.5, True) > PHONE_W + 60 and len(name) > 6:
            name = name[:-2]
        if name != s["name"]:
            name = name.rstrip() + "…"
        self.add(x + 39, y - 42, PHONE_W + 60, 20, name, g, fill="none", stroke="none", fs=13.5, fc=INK, bold=True, align="left", valign="middle")
        route = s["route"]
        while tw(route, 8.5) > PHONE_W + 40 and len(route) > 8:
            route = route[:-2]
        if route != s["route"]:
            route = route.rstrip() + "…"
        self.add(x, y - 21, PHONE_W + 40, 13, route, g, fill="none", stroke="none", fs=8.5, fc="#475569", align="left", valign="middle")
        frame = self.add(x, y, PHONE_W, ph, "", g, id=f"{sid}__frame", fill="#e2e8f0", stroke="#475569", sw=1.6, r=26, attrs=attrs)
        self.add(x + PHONE_W / 2 - 20, y + 9, 40, 5, "", g, fill="#94a3b8", stroke="none", r=2.5)
        sc_x, sc_y = x + SCREEN_X, y + SCREEN_Y
        self.add(sc_x, sc_y, SCREEN_W, ph - (PHONE_H - SCREEN_H), "", g, fill="#ffffff", stroke="#94a3b8", sw=1, r=4)
        self.add(x + PHONE_W / 2 - 9, y + ph - 27, 18, 18, "", g, shape="ellipse", fill="none", stroke="#64748b", sw=1.3)
        col, bg = STATUS_COLORS[s["status"]]
        lab = {"IMPLEMENTED": "IMPLEMENTED", "PARTIALLY IMPLEMENTED": "PARTIAL"}.get(s["status"], s["status"])
        if runtime:
            lab += " · ✓ RUN"
        pw = tw(lab, 7.4, True) + 12
        self.add(x + PHONE_W - pw - 8, y - 7, pw, 14, lab, g, fill=bg, stroke=col, sw=0.9, r=7, fs=7.4, fc=col, bold=True,
                 align="center", valign="middle")
        self.nodes[sid] = dict(rect=(x, y, PHONE_W, ph), kind="phone")
        self.obstacle(x, y - 42, max(PHONE_W, 39 + tw(name, 13.5, True)), ph + 42, 8)
        self._screen_content(s, sc_x, sc_y, role, g, ph - (PHONE_H - SCREEN_H))
        return frame

    def _admin_mark(self, x, y, w, h, g, right_limit=None):
        self.add(x - 2, y - 1.5, w + 4, h + 3, "", g, fill="none", stroke=ADMIN_C, sw=0.9, dash="3 2", r=3)
        if right_limit is not None and x + w + 30 <= right_limit:
            tx, ty = x + w + 4, y + h / 2 - 4
        else:
            tx, ty = x + w - 24, y - 6
        self.add(tx, ty, 26, 8, "ADMIN", g, fill="#fff7ed", stroke=ADMIN_C, sw=0.6, r=2, fs=5, fc=ADMIN_C, bold=True,
                 align="center", valign="middle")

    def _screen_content(self, s, sx, sy, role, g, sh=SCREEN_H):
        sid = s["id"]
        ui = [e for e in s["ui"] if not (role == "Inspector" and e.get("admin"))]
        has_nav = any(e["t"] == "nav" for e in ui)
        has_sub = any(e["t"] == "subbar" for e in ui)
        x0, w0 = sx + 6, SCREEN_W - 12
        self.add(sx + 6, sy + 1, 40, 9, "9:41", g, fill="none", stroke="none", fs=5.8, fc=GREY, align="left", valign="middle")
        self.add(sx + SCREEN_W - 40, sy + 1, 34, 9, "▴ ▮", g, fill="none", stroke="none", fs=5.8, fc=GREY, align="right", valign="middle")
        bottom = sy + sh - (26 if has_nav else 0) - (24 if has_sub else 0) - 3
        cy = sy + 11
        omitted = []
        for e in ui:
            t = e["t"]
            if t in ("nav", "subbar", "fab"):
                continue
            h = self._measure(e, w0)
            if cy + h > bottom:
                omitted.append(e.get("id") or t)
                continue
            self._draw(e, sx, x0, cy, w0, h, g, sid, role)
            cy += h + (0 if t in ("bar", "gap") else 3.5)
        if omitted:
            self.add(sx, bottom - 9, SCREEN_W, 10, "⋮ more below (scroll)", g, fill="#f8fafc", stroke="none", fs=6, fc=GREY,
                     align="center", valign="middle", italic=True)
            self.warnings.append(f"{sid}: omitted {omitted}")
        yb = sy + sh
        for e in ui:
            if e["t"] == "nav":
                yb -= 26
                self._nav(sx, yb, e["active"], g)
        for e in ui:
            if e["t"] == "subbar":
                yb -= 24
                self.add(sx, yb, SCREEN_W, 24, "", g, fill="#f1f5f9", stroke=LINE, sw=0.8)
                self.add(sx + 6, yb + 2, 100, 20, e["text"], g, fill="none", stroke="none", fs=6.5, fc=INK, align="left", valign="middle")
                bw = tw(e["label"], 6.8, True) + 16
                self.add(sx + SCREEN_W - bw - 6, yb + 4, bw, 16, e["label"], g, fill=TEAL, stroke="none", r=8, fs=6.8, fc="#fff",
                         bold=True, align="center", valign="middle")
                if e.get("id"):
                    self.anchors[(sid, e["id"])] = yb + 12
        for e in ui:
            if e["t"] == "fab":
                if e.get("wide"):
                    fw = min(tw(e["label"], 6.8, True) + 18, SCREEN_W - 20)
                    fx, fy, fh = sx + SCREEN_W - fw - 8, yb - 30, 22
                    self.add(fx, fy, fw, fh, e["label"], g, fill=TEAL_L, stroke=TEAL, sw=1, r=8, fs=6.8, fc=TEAL, bold=True,
                             align="center", valign="middle")
                else:
                    fw = fh = 26
                    fx, fy = sx + SCREEN_W - 34, yb - 34
                    self.add(fx, fy, fw, fh, e["label"], g, fill=TEAL_L, stroke=TEAL, sw=1, r=8, fs=12, fc=TEAL, bold=True,
                             align="center", valign="middle")
                if e.get("admin"):
                    self._admin_mark(fx, fy, fw, fh, g)
                if e.get("id"):
                    self.anchors[(sid, e["id"])] = fy + fh / 2

    def _nav(self, sx, y, active, g):
        self.add(sx, y, SCREEN_W, 26, "", g, fill="#f1f5f9", stroke=LINE, sw=0.8)
        items = [("⌂", "Home"), ("▦", "Inspect"), ("☰", "Actions"), ("◷", "History")]
        iw = SCREEN_W / 4
        for i, (ic, lab) in enumerate(items):
            cx = sx + i * iw
            on = lab == active
            if on:
                self.add(cx + iw / 2 - 15, y + 2, 30, 12, "", g, fill=TEAL_L, stroke="none", r=6)
            self.add(cx, y + 2, iw, 12, ic, g, fill="none", stroke="none", fs=8, fc=INK if on else GREY, align="center",
                     valign="middle", bold=on)
            self.add(cx, y + 14, iw, 10, lab, g, fill="none", stroke="none", fs=6, fc=INK if on else GREY, align="center",
                     valign="middle", bold=on)

    TXT = {"s": 6.6, "m": 8, "l": 11}

    def _measure(self, e, w0):
        t = e["t"]
        if t == "bar":
            return 30 if e.get("sub") else 24
        if t == "txt":
            fs = self.TXT[e["size"]]
            return len(wrap(e["s"], fs, w0, e["bold"])) * fs * 1.28 + 1
        if t == "sec":
            return 11
        if t in ("inp", "sel"):
            return 20 if e.get("value") else 17
        if t == "btn":
            return 13 if e["kind"] == "text" else 17
        if t == "btns":
            return 17
        if t == "chips":
            return 13
        if t == "card":
            n = 0.0
            for i, l in enumerate(e["lines"]):
                fs = 7.2 if i == 0 else 6.3
                n += len(wrap(l, fs, w0 - 16, i == 0)) * fs * 1.28
            return 7 + n + (14 if e.get("chips") else 0) + (16 if e.get("btns") else 0)
        if t in ("cam", "img", "gap"):
            return e["h"]
        if t == "kpi":
            return 27
        if t == "seg":
            return 15
        if t == "switch":
            return 14
        if t == "radio":
            return 10.5 * len(e["opts"])
        if t == "prog":
            return 15
        if t == "bubbles":
            return sum(len(wrap(s, 6.3, 140)) * 6.3 * 1.28 + 8 for _, s in e["items"])
        if t == "anno":
            return len(wrap(e["s"], 6.2, w0 - 10)) * 6.2 * 1.28 + 6
        if t == "spinner":
            return 44
        if t == "logo":
            return 22
        return 10

    def _draw(self, e, sx, x0, y, w0, h, g, sid, role):
        t = e["t"]

        def anchor():
            if e.get("id"):
                self.anchors[(sid, e["id"])] = y + h / 2

        if t == "bar":
            self.anchors[(sid, "__bar")] = y + 10
            self.add(sx, y, SCREEN_W, h, "", g, fill="#f8fafc", stroke="none")
            self.add(sx, y + h - 0.8, SCREEN_W, 0.8, "", g, fill=LINE, stroke="none")
            tx = x0
            if e["back"]:
                self.add(x0, y + 3, 12, 14, "←", g, fill="none", stroke="none", fs=10, fc=INK, align="left", valign="middle")
                tx = x0 + 14
            rw = 0
            rights = [] if (e.get("admin_right") and role == "Inspector") else e["right"]
            for r in reversed(rights):
                bw = max(14, tw(r, 6.5, True) + 8)
                rw += bw + 3
                self.add(sx + SCREEN_W - 6 - rw, y + 4, bw, 13, r, g, fill="#fff", stroke=LINE, sw=0.8, r=3, fs=6.5, fc=INK, bold=True,
                         align="center", valign="middle")
                if e.get("admin_right"):
                    self.add(sx + SCREEN_W - 6 - rw - 2, y + 2.5, bw + 4, 16, "", g, fill="none", stroke=ADMIN_C, sw=0.9, dash="3 2", r=3)
                    self.add(sx + SCREEN_W - 6 - rw - 32, y + 7, 27, 8, "ADMIN", g, fill="#fff7ed", stroke=ADMIN_C, sw=0.6, r=2,
                             fs=5, fc=ADMIN_C, bold=True, align="center", valign="middle")
            avail = SCREEN_W - (tx - sx) - rw - 10
            title = e["title"]
            while tw(title, 9, True) > avail and len(title) > 6:
                title = title[:-2]
            if title != e["title"]:
                title = title.rstrip() + "…"
            self.add(tx, y + 3, avail, 14, title, g, fill="none", stroke="none", fs=9, fc=INK, bold=True, align="left", valign="middle")
            if e.get("sub"):
                sub = e["sub"]
                while tw(sub, 6) > SCREEN_W - (tx - sx) - 8 and len(sub) > 6:
                    sub = sub[:-2]
                self.add(tx, y + 16, SCREEN_W - (tx - sx) - 8, 10, sub, g, fill="none", stroke="none", fs=6, fc=GREY, align="left", valign="middle")
            anchor()
            return
        if t == "txt":
            fs = self.TXT[e["size"]]
            col = {"grey": GREY, "red": "#dc2626", "primary": TEAL, None: INK}[e["color"]]
            self.add(x0, y, w0, h, "\n".join(wrap(e["s"], fs, w0, e["bold"])), g, fill="none", stroke="none", fs=fs, fc=col,
                     bold=e["bold"], align="left", valign="top", lh=1.28)
            if e.get("admin"):
                self._admin_mark(x0, y, w0, h, g)
            anchor()
            return
        if t == "sec":
            self.add(x0, y, w0, h, e["s"], g, fill="none", stroke="none", fs=7.5, fc=TEAL, bold=True, align="left", valign="middle")
            return
        if t in ("inp", "sel"):
            self.add(x0, y, w0, h, "", g, fill="#fff", stroke="#94a3b8", sw=0.9, r=3)
            if e.get("value"):
                self.add(x0 + 4, y + 1, w0 - 8, 8, e["label"], g, fill="none", stroke="none", fs=5.6, fc=GREY, align="left", valign="middle")
                self.add(x0 + 4, y + 9, w0 - 16, 10, e["value"], g, fill="none", stroke="none", fs=6.8, fc=INK, align="left", valign="middle")
            else:
                self.add(x0 + 4, y, w0 - 16, h, e["label"], g, fill="none", stroke="none", fs=6.6, fc=GREY, align="left", valign="middle")
            if t == "sel":
                self.add(x0 + w0 - 12, y, 10, h, "▾", g, fill="none", stroke="none", fs=7, fc=GREY, align="center", valign="middle")
            if e.get("admin"):
                self._admin_mark(x0, y, w0, h, g)
            anchor()
            return
        if t == "btn":
            self._button(x0, y, w0, h, e["label"], e["kind"], g)
            if e.get("admin"):
                self._admin_mark(x0, y, w0, h, g)
            anchor()
            return
        if t == "btns":
            self._btn_row(x0, y, w0, h, e["items"], g, sid, role)
            anchor()
            return
        if t == "chips":
            self._chips(x0, y, w0, e["labels"], e["sel"], g)
            if e.get("id"):
                self.anchors[(sid, e["id"])] = y + 6
            return
        if t == "card":
            fill = {"blue": "#e0f2fe", "amber": "#fef3c7", None: "#f1f5f9"}[e.get("tint")]
            self.add(x0, y, w0, h, "", g, fill=fill, stroke="#e2e8f0", sw=0.8, r=5)
            cy = y + 3.5
            for i, l in enumerate(e["lines"]):
                fs = 7.2 if i == 0 else 6.3
                lines = wrap(l, fs, w0 - 16, i == 0)
                lh = len(lines) * fs * 1.28
                self.add(x0 + 5, cy, w0 - 14, lh, "\n".join(lines), g, fill="none", stroke="none", fs=fs, fc=INK if i == 0 else "#475569",
                         bold=i == 0, align="left", valign="top", lh=1.28)
                cy += lh
            if e.get("chips"):
                self._chips(x0 + 5, cy + 1, w0 - 10, e["chips"], -1, g)
                cy += 14
            if e.get("btns"):
                self._btn_row(x0 + 5, cy + 1, w0 - 10, 14, e["btns"], g, sid, role, compact=True, parent_admin=bool(e.get("admin")))
            if e.get("chev"):
                self.add(x0 + w0 - 12, y, 10, h, "›", g, fill="none", stroke="none", fs=11, fc=GREY, align="center", valign="middle")
            if e.get("admin"):
                self.add(x0 - 2, y - 1.5, w0 + 4, h + 3, "", g, fill="none", stroke=ADMIN_C, sw=0.9, dash="3 2", r=3)
                self.add(x0 + w0 - 30, y + 2, 28, 8, "ADMIN", g, fill="#fff7ed", stroke=ADMIN_C, sw=0.6, r=2, fs=5, fc=ADMIN_C,
                         bold=True, align="center", valign="middle")
            anchor()
            return
        if t == "cam":
            self.add(x0, y, w0, h, "", g, fill="#0f172a", stroke="none", r=5)
            if e.get("frame"):
                sq = min(h, w0) * 0.55
                self.add(x0 + w0 / 2 - sq / 2, y + h / 2 - sq / 2, sq, sq, "", g, fill="none", stroke="#fff", sw=1.6, r=8)
            self.add(x0 + 4, y + h - 16, w0 - 44, 13, e["label"], g, fill="#334155", stroke="none", r=6, fs=5.8, fc="#fff",
                     align="center", valign="middle")
            if e.get("shutter"):
                rr = 9 if h < 100 else 16
                yy = y + h - 2 * rr - 4 if h < 100 else y + h - 2 * rr - 26
                self.add(x0 + w0 - 2 * rr - 6 if h < 100 else x0 + w0 / 2 - rr, yy, 2 * rr, 2 * rr, "◉", g,
                         shape="ellipse", fill=TEAL, stroke="#fff", sw=1.2, fs=rr, fc="#fff", align="center", valign="middle")
            anchor()
            return
        if t == "kpi":
            n = len(e["pairs"])
            bw = (w0 - 4 * (n - 1)) / n
            for i, (v, lab) in enumerate(e["pairs"]):
                bx = x0 + i * (bw + 4)
                self.add(bx, y, bw, h, "", g, fill="#f8fafc", stroke="#e2e8f0", sw=0.8, r=4)
                self.add(bx + 5, y + 2, bw - 8, 13, v, g, fill="none", stroke="none", fs=10, fc=INK, bold=True, align="left", valign="middle")
                self.add(bx + 5, y + 15, bw - 8, 10, lab, g, fill="none", stroke="none", fs=6, fc=GREY, align="left", valign="middle")
            anchor()
            return
        if t == "seg":
            n = len(e["labels"])
            bw = w0 / n
            for i, lab in enumerate(e["labels"]):
                on = i == e["sel"]
                self.add(x0 + i * bw, y, bw, h, lab, g, fill="#fee2e2" if on else "#fff", stroke="#94a3b8", sw=0.8,
                         r=7 if i in (0, n - 1) else 0, fs=6.8, fc="#b91c1c" if on else INK, bold=on, align="center", valign="middle")
            anchor()
            return
        if t == "switch":
            self.add(x0, y, w0 - 30, h, e["label"], g, fill="none", stroke="none", fs=7.5, fc=INK, bold=True, align="left", valign="middle")
            on = e["on"]
            self.add(x0 + w0 - 24, y + 2, 22, 11, "", g, fill=TEAL if on else "#cbd5e1", stroke="none", r=5.5)
            self.add(x0 + w0 - (13 if on else 23), y + 3, 9, 9, "", g, shape="ellipse", fill="#fff", stroke="none")
            return
        if t == "radio":
            for i, o in enumerate(e["opts"]):
                yy = y + i * 10.5
                self.add(x0 + 2, yy + 1.5, 7, 7, "", g, shape="ellipse", fill=TEAL if i == e["sel"] else "#fff", stroke=TEAL, sw=0.9)
                self.add(x0 + 13, yy, w0 - 14, 10, o, g, fill="none", stroke="none", fs=6.6, fc=INK, align="left", valign="middle")
            return
        if t == "prog":
            self.add(x0, y, w0, 9, e["label"], g, fill="none", stroke="none", fs=7, fc=INK, bold=True, align="left", valign="middle")
            self.add(x0, y + 10, w0, 3.5, "", g, fill="#e2e8f0", stroke="none", r=1.7)
            self.add(x0, y + 10, w0 * e["frac"], 3.5, "", g, fill=TEAL, stroke="none", r=1.7)
            return
        if t == "img":
            self.add(x0, y, w0, h, e["label"], g, fill="#e2e8f0", stroke="#cbd5e1", sw=0.8, r=4, fs=6.4, fc=GREY, align="center",
                     valign="middle", italic=True)
            anchor()
            return
        if t == "bubbles":
            cy = y
            for who, s in e["items"]:
                lines = wrap(s, 6.3, 140)
                bh = len(lines) * 6.3 * 1.28 + 5
                bw = min(150, max(tw(l, 6.3) for l in lines) + 12)
                bx = x0 if who == "m" else x0 + w0 - bw
                self.add(bx, cy, bw, bh, "\n".join(lines), g, fill="#f1f5f9" if who == "m" else TEAL_L, stroke="none", r=6, fs=6.3,
                         fc=INK, align="left", valign="middle", lh=1.28, pad=5)
                cy += bh + 3
            return
        if t == "anno":
            lines = wrap(e["s"], 6.2, w0 - 10)
            self.add(x0, y, w0, h, "\n".join(lines), g, fill="#fef9c3", stroke="#eab308", sw=0.8, r=3, fs=6.2, fc="#713f12",
                     italic=True, align="left", valign="middle", lh=1.28, pad=4)
            return
        if t == "spinner":
            self.add(x0 + w0 / 2 - 12, y + 2, 24, 24, "", g, shape="ellipse", fill="none", stroke=TEAL, sw=2.5, dash="28 10")
            self.add(x0, y + 30, w0, 10, e["label"], g, fill="none", stroke="none", fs=6.3, fc=GREY, align="center", valign="middle")
            return
        if t == "logo":
            self.add(x0, y + 1, 20, 20, "⛨", g, fill=TEAL, stroke="none", r=5, fs=11, fc="#fff", align="center", valign="middle")
            return

    def _button(self, x, y, w, h, label, kind, g):
        if kind == "primary":
            self.add(x, y, w, h, label, g, fill=TEAL, stroke="none", r=h / 2, fs=6.8, fc="#fff", bold=True, align="center", valign="middle")
        elif kind == "outline":
            self.add(x, y, w, h, label, g, fill="#fff", stroke=TEAL, sw=0.9, r=h / 2, fs=6.8, fc=TEAL, bold=True, align="center", valign="middle")
        elif kind == "text":
            self.add(x, y, w, h, label, g, fill="none", stroke="none", fs=6.8, fc=TEAL, bold=True, align="left", valign="middle")
        elif kind in ("input", "select"):
            self.add(x, y, w, h, label + (" ▾" if kind == "select" and "▾" not in label else ""), g, fill="#fff", stroke="#94a3b8",
                     sw=0.8, r=3, fs=6.3, fc=GREY, align="left", valign="middle", pad=4)
        elif kind == "chip":
            self.add(x, y, w, h, label, g, fill="#f1f5f9", stroke="#cbd5e1", sw=0.8, r=4, fs=6.2, fc=INK, align="center", valign="middle")

    def _btn_row(self, x, y, w, h, items, g, sid, role, compact=False, parent_admin=False):
        items = [i for i in items if not (role == "Inspector" and len(i) > 3 and i[3])]
        if not items:
            return
        gapw = 4
        weights = [max(tw(i[0], 6.8, True) + 14, 28) for i in items]
        if compact:
            cx = x
            for it, wt in zip(items, weights):
                wt = min(wt, w - (cx - x))
                if wt < 12:
                    break
                self._button(cx, y, wt, h, it[0], it[1], g)
                if len(it) > 3 and it[3] and not parent_admin:
                    self._admin_mark(cx, y, wt, h, g, right_limit=x + w)
                if len(it) > 2 and it[2]:
                    self.anchors[(sid, it[2])] = y + h / 2
                cx += wt + gapw
            return
        scale = (w - gapw * (len(items) - 1)) / sum(weights)
        cx = x
        for it, wt in zip(items, weights):
            bw = wt * scale
            self._button(cx, y, bw, h, it[0], it[1], g)
            if len(it) > 3 and it[3] and not parent_admin:
                self._admin_mark(cx, y, bw, h, g)
            if len(it) > 2 and it[2]:
                self.anchors[(sid, it[2])] = y + h / 2
            cx += bw + gapw

    def _chips(self, x, y, w, labels, sel, g):
        cx = x
        for i, lab in enumerate(labels):
            cw = tw(lab, 6) + 10
            if cx + cw > x + w - 2:
                self.add(cx, y, 12, 11, "…", g, fill="none", stroke="none", fs=7, fc=GREY, align="center", valign="middle")
                break
            on = i == sel
            self.add(cx, y, cw, 11, lab, g, fill=TEAL_L if on else "#fff", stroke=TEAL if on else "#94a3b8", sw=0.7, r=5.5,
                     fs=6, fc=INK, bold=on, align="center", valign="middle")
            cx += cw + 3

    # -------------------------------------------------------------------- dialogs / system nodes
    def dialog_node(self, d, x, y, w=150, big=False, key=None):
        did = d["id"]
        kind = d.get("kind", "dialog")
        fs_t, fs_b = (8.4, 7.2) if big else (7.4, 6.4)
        tag = {"dialog": "POP-UP", "menu": "MENU", "gate": "PERMISSION"}[kind]
        lines_t = wrap(d["title"], fs_t, w - 40 - tw(tag, 5.4, True), True)
        body = []
        for l in d["lines"]:
            body += wrap(l, fs_b, w - 12)
        th = len(lines_t) * fs_t * 1.25
        blines = wrap("   ".join(d["buttons"]), fs_b, w - 12, True) if d["buttons"] else []
        h = 6 + max(th, 11) + 3 + len(body) * fs_b * 1.28 + (len(blines) * fs_b * 1.3 + 6 if blines else 4)
        col = {"dialog": "#7c3aed", "menu": "#7c3aed", "gate": "#b45309"}[kind]
        bg = {"dialog": "#f5f3ff", "menu": "#faf5ff", "gate": "#fffbeb"}[kind]
        attrs = {"dialog_id": did, "title": d["title"], "source_file": d["file"], "roles": d["roles"], "status": d["status"],
                 "tooltip": f"{did} {d['title']} | Source: {d['file']} | Roles: {d['roles']}"}
        g = key or f"{did}@{int(x)}_{int(y)}"
        self.groups[g] = (x, y, w, h, attrs)
        self.add(x, y, w, h, "", g, id=f"{g}__node", fill=bg, stroke=col, sw=1.2, r=7, dash="4 2", attrs=attrs)
        self.add(x + 5, y + 4, 22, 10, did, g, fill=col, stroke="none", r=3, fs=6.3, fc="#fff", bold=True, align="center", valign="middle")
        tgw = tw(tag, 5.4, True) + 6
        self.add(x + w - tgw - 4, y + 4, tgw, 9, tag, g, fill="#fff", stroke=col, sw=0.6, r=2, fs=5.4, fc=col, bold=True,
                 align="center", valign="middle")
        cy = y + 4
        self.add(x + 31, cy, w - 37 - tgw, th + 2, "\n".join(lines_t), g, fill="none", stroke="none", fs=fs_t, fc=INK,
                 bold=True, align="left", valign="top", lh=1.25)
        cy += max(th, 11) + 3
        if body:
            bh = len(body) * fs_b * 1.28
            self.add(x + 6, cy, w - 12, bh, "\n".join(body), g, fill="none", stroke="none", fs=fs_b, fc="#475569", align="left",
                     valign="top", lh=1.28)
            cy += bh + 2
        if blines:
            self.add(x + 6, cy, w - 12, len(blines) * fs_b * 1.3 + 2, "\n".join(blines), g, fill="none", stroke="none", fs=fs_b, fc=TEAL,
                     bold=True, align="right", valign="top", lh=1.3)
        self.nodes[g] = dict(rect=(x, y, w, h), kind="dialog", group=g)
        self.nodes.setdefault(did, dict(rect=(x, y, w, h), kind="dialog", group=g))
        self.obstacle(x, y, w, h, 6)
        return h

    def ext_node(self, xd, x, y, w=150, key=None):
        xid = xd["id"]
        partial = xd["status"] != "IMPLEMENTED"
        col = "#dc2626" if partial else "#0f766e"
        bg = "#fef2f2" if partial else "#f0fdfa"
        h = 26
        attrs = {"system_id": xid, "title": xd["title"], "source_file": xd["file"], "status": xd["status"],
                 "tooltip": f"{xid} {xd['title']} | Source: {xd['file']} | Status: {xd['status']}" + (f" | {xd['note']}" if xd["note"] else "")}
        g = key or f"{xid}@{int(x)}_{int(y)}"
        self.groups[g] = (x, y, w, h, attrs)
        self.add(x, y, w, h, "", g, id=f"{g}__node", fill=bg, stroke=col, sw=1.2, r=4, attrs=attrs, dash="8 3 2 3")
        self.add(x + 4, y + 8, 22, 10, xid, g, fill=col, stroke="none", r=3, fs=6.3, fc="#fff", bold=True, align="center", valign="middle")
        lines = wrap(xd["title"], 6.8, w - 66, True)
        self.add(x + 29, y + 1, w - 64, h - 2, "\n".join(lines[:2]), g, fill="none", stroke="none", fs=6.8, fc=INK, bold=True,
                 align="left", valign="middle", lh=1.2)
        tag = "SYSTEM" if not partial else "PARTIAL"
        self.add(x + w - 35, y + 8, 32, 9, tag, g, fill="#fff", stroke=col, sw=0.6, r=2, fs=5.2, fc=col, bold=True, align="center", valign="middle")
        self.nodes[g] = dict(rect=(x, y, w, h), kind="ext", group=g)
        self.nodes.setdefault(xid, dict(rect=(x, y, w, h), kind="ext", group=g))
        self.obstacle(x, y, w, h, 6)
        return h

    def ref_node(self, nid, x, y, label, sub, w=160):
        h = 34
        g = f"ref_{nid}@{int(x)}_{int(y)}"
        self.groups[g] = (x, y, w, h, {"reference_to": nid, "tooltip": f"Off-diagram reference to {nid}: {label}"})
        self.add(x, y, w, h, "", g, id=f"{g}__node", fill="#ffffff", stroke="#334155", sw=1.1, r=17, dash="3 2")
        self.add(x + 10, y + 3, w - 18, 14, "↗ " + label, g, fill="none", stroke="none", fs=7.6, fc=INK, bold=True, align="left", valign="middle")
        self.add(x + 10, y + 17, w - 18, 12, sub, g, fill="none", stroke="none", fs=6.4, fc=GREY, align="left", valign="middle", italic=True)
        self.nodes[f"ref:{nid}"] = dict(rect=(x, y, w, h), kind="ref", group=g)
        self.obstacle(x, y, w, h, 6)

    def note(self, x, y, w, title, lines, color="#334155", bg="#f8fafc", size=7.2):
        body = []
        for l in lines:
            body += wrap(l, size, w - 14)
        h = 22 + len(body) * size * 1.32 + 6
        g = self.uid("note")
        self.groups[g] = (x, y, w, h, {})
        self.add(x, y, w, h, "", g, fill=bg, stroke=color, sw=1, r=6)
        self.add(x + 7, y + 4, w - 14, 14, title, g, fill="none", stroke="none", fs=size + 1.6, fc=color, bold=True, align="left", valign="middle")
        self.add(x + 7, y + 20, w - 14, h - 24, "\n".join(body), g, fill="none", stroke="none", fs=size, fc=INK, align="left", valign="top",
                 lh=1.32)
        self.obstacle(x, y, w, h, 6)
        return h

    # -------------------------------------------------------------------- routing
    def route(self, edges, G=10):
        W, H = int(self.W), int(self.H)
        nx, ny = W // G + 2, H // G + 2
        blocked = bytearray(nx * ny)
        for (x, y, w, h, m) in self.obstacles:
            i0, i1 = max(0, int((x - m) // G)), min(nx - 1, int((x + w + m) // G))
            j0, j1 = max(0, int((y - m) // G)), min(ny - 1, int((y + h + m) // G))
            for j in range(j0, j1 + 1):
                base = j * nx
                for i in range(i0, i1 + 1):
                    blocked[base + i] = 1
        useH = bytearray(nx * ny)
        useV = bytearray(nx * ny)
        arrivals = {}
        DIRS = ((1, 0), (0, 1), (-1, 0), (0, -1))

        def free(i, j):
            return 0 <= i < nx and 0 <= j < ny and not blocked[j * nx + i]

        for e in edges:
            if e["src"] == e["dst"]:
                self._self_loop(e)
                continue
            sn, dn = self.nodes[e["src"]], self.nodes[e["dst"]]
            sx, sy, sw, sh = sn["rect"]
            dx, dy, dw, dh = dn["rect"]
            s_top = 40 if sn["kind"] == "phone" else 0
            m = 9 if sn["kind"] == "phone" else 7
            starts = []
            ay = e.get("ay")
            for side in (e.get("sides") or ("R", "L", "B", "T")):
                if side in ("R", "L"):
                    ys = [ay] if ay is not None else [sy + sh * f for f in (0.45, 0.3, 0.6)]
                    for yy in ys:
                        j = int(yy // G)
                        i = int((sx + sw + m) // G) + 1 if side == "R" else int((sx - m) // G) - 1
                        if free(i, j):
                            starts.append((i, j, 0 if side == "R" else 2, (sx + sw if side == "R" else sx, j * G + G / 2)))
                            break
                elif ay is None:
                    i = int((sx + sw / 2) // G)
                    if side == "B":
                        j = int((sy + sh + m) // G) + 1
                        if free(i, j):
                            starts.append((i, j, 1, (i * G + G / 2, sy + sh)))
                    else:
                        j = int((sy - s_top - m) // G) - 1
                        if free(i, j):
                            starts.append((i, j, 3, (i * G + G / 2, sy - s_top)))
            if not starts:
                self.warnings.append(f"no free start for {e['src']}->{e['dst']}")
                continue
            dm = 9 if dn["kind"] == "phone" else 7
            top_extra = 40 if dn["kind"] == "phone" else 0
            goals = {}
            for side in (e.get("dsides") or ("L", "T", "R", "B")):
                if side in ("L", "R"):
                    i = int((dx - dm) // G) - 1 if side == "L" else int((dx + dw + dm) // G) + 1
                    lo, hi = dy + min(30, dh * 0.25), dy + dh - min(30, dh * 0.25)
                    for j in range(int(lo // G), int(hi // G) + 1):
                        if free(i, j):
                            goals[(i, j)] = (0 if side == "L" else 2, (dx if side == "L" else dx + dw, j * G + G / 2))
                elif side == "T":
                    j = int((dy - top_extra - dm) // G) - 1
                    for i in range(int((dx + min(24, dw * 0.2)) // G), int((dx + dw - min(24, dw * 0.2)) // G) + 1):
                        if free(i, j):
                            goals[(i, j)] = (1, (i * G + G / 2, dy - top_extra))
                else:
                    j = int((dy + dh + dm) // G) + 1
                    for i in range(int((dx + min(24, dw * 0.2)) // G), int((dx + dw - min(24, dw * 0.2)) // G) + 1):
                        if free(i, j):
                            goals[(i, j)] = (3, (i * G + G / 2, dy + dh))
            if not goals:
                self.warnings.append(f"no free goal for {e['src']}->{e['dst']}")
                continue
            gx0, gy0 = (dx - dm) / G, (dy - top_extra - dm) / G
            gx1, gy1 = (dx + dw + dm) / G, (dy + dh + dm) / G

            def hfun(i, j):
                hx = gx0 - i if i < gx0 else (i - gx1 if i > gx1 else 0)
                hy = gy0 - j if j < gy0 else (j - gy1 if j > gy1 else 0)
                return hx + hy

            TURN, SAME, CROSS = 6.0, 9.0, 1.5
            openh, best, prev = [], {}, {}
            for (i, j, d, pt) in starts:
                st = (i, j, d)
                best[st] = 0.0
                prev[st] = ("start", pt)
                heapq.heappush(openh, (hfun(i, j), 0.0, st))
            found = None
            expanded = 0
            while openh:
                f, gcost, st = heapq.heappop(openh)
                if gcost > best.get(st, 1e18) + 1e-9:
                    continue
                i, j, d = st
                expanded += 1
                if (i, j) in goals:
                    found = st
                    break
                if expanded > 500000:
                    break
                for nd, (di, dj) in enumerate(DIRS):
                    if (nd - d) % 4 == 2:
                        continue
                    ni, nj = i + di, j + dj
                    if not free(ni, nj):
                        continue
                    k = nj * nx + ni
                    c = 1.0 + (TURN if nd != d else 0.0)
                    if di:
                        c += SAME * useH[k] + CROSS * useV[k]
                    else:
                        c += SAME * useV[k] + CROSS * useH[k]
                    if (ni, nj) in goals:
                        need = goals[(ni, nj)][0]
                        c += 0 if nd == need else TURN
                        c += 4 * arrivals.get((e["dst"], ni, nj), 0)
                    ns = (ni, nj, nd)
                    ng = gcost + c
                    if ng < best.get(ns, 1e18):
                        best[ns] = ng
                        prev[ns] = st
                        heapq.heappush(openh, (ng + hfun(ni, nj), ng, ns))
            if not found:
                self.warnings.append(f"route failed {e['src']}->{e['dst']}")
                continue
            st = found
            cells = []
            while True:
                p = prev[st]
                cells.append(st)
                if p[0] == "start":
                    start_pt = p[1]
                    break
                st = p
            cells.reverse()
            last = (cells[-1][0], cells[-1][1])
            end_pt = goals[last][1]
            arrivals[(e["dst"],) + last] = arrivals.get((e["dst"],) + last, 0) + 1
            for a, b in zip(cells, cells[1:]):
                for c in (a, b):
                    k = c[1] * nx + c[0]
                    if a[0] != b[0]:
                        useH[k] = 1
                    else:
                        useV[k] = 1
            centers = [(c[0] * G + G / 2, c[1] * G + G / 2) for c in cells]
            pts = [start_pt, centers[0]]
            for a, b, c in zip(centers, centers[1:], centers[2:]):
                if (abs(a[0] - b[0]) < 0.01) != (abs(b[0] - c[0]) < 0.01):
                    pts.append(b)
            pts += [centers[-1], end_pt]
            self.edges.append(dict(e, points=_simplify(pts)))

    def _self_loop(self, e):
        x, y, w, h = self.nodes[e["src"]]["rect"]
        ay = e.get("ay") or (y + h * 0.4)
        y2 = min(ay + 70, y + h - 30)
        self.edges.append(dict(e, points=[(x, ay), (x - 30, ay), (x - 30, y2), (x, y2)], self_loop=True))

    # -------------------------------------------------------------------- labels
    def place_labels(self):
        node_rects = [(x - 2, y - 2, w + 4, h + 4) for (x, y, w, h, m) in self.obstacles]
        for ed in self.edges:
            lab = ed.get("label")
            if not lab:
                ed.setdefault("label_box", None)
                continue
            lines = wrap(lab, LABEL_FS, 150, True)
            lw = max(tw(l, LABEL_FS, True) for l in lines) + 12
            lh = len(lines) * LABEL_FS * 1.25 + 5
            segs = sorted(zip(ed["points"], ed["points"][1:]), key=lambda s: -(abs(s[0][0] - s[1][0]) + abs(s[0][1] - s[1][1])))
            chosen = None
            if ed.get("self_loop"):
                (a, b) = ed["points"][1], ed["points"][2]
                chosen = (a[0] - lw - 4, (a[1] + b[1]) / 2 - lh / 2, lw, lh)
            for off in (0, 1, -1, 2, -2):
                for (a, b) in segs:
                    horiz = abs(a[1] - b[1]) < 0.01
                    for t in (0.5, 0.35, 0.65, 0.22, 0.78, 0.1, 0.9):
                        px, py = a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
                        if horiz:
                            py += off * (lh / 2 + 4)
                        else:
                            px += off * (lw / 2 + 4)
                        box = (px - lw / 2, py - lh / 2, lw, lh)
                        if not any(_overlap(box, r) for r in node_rects) and not any(_overlap(box, r) for r in self.labels):
                            chosen = box
                            break
                    if chosen:
                        break
                if chosen:
                    break
            if not chosen and ed.get("pair"):
                # connector pair: put the label on the other half (source → badge) when this half has no room
                mate = next((m for m in self.edges if m is not ed and m.get("pair") == ed["pair"]), None)
                if mate is not None:
                    for (a, b) in sorted(zip(mate["points"], mate["points"][1:]), key=lambda s: -(abs(s[0][0] - s[1][0]) + abs(s[0][1] - s[1][1]))):
                        for t in (0.5, 0.3, 0.7):
                            for off in (0, 1, -1):
                                px, py = a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
                                if abs(a[1] - b[1]) < 0.01:
                                    py += off * (lh / 2 + 4)
                                else:
                                    px += off * (lw / 2 + 4)
                                box = (px - lw / 2, py - lh / 2, lw, lh)
                                if not any(_overlap(box, r) for r in node_rects) and not any(_overlap(box, r) for r in self.labels):
                                    self.labels.append(box)
                                    mate["label_box"] = (box, lines)
                                    ed["label_box"] = None
                                    break
                            if mate.get("label_box"):
                                break
                        if mate.get("label_box"):
                            break
                if mate is not None and mate.get("label_box"):
                    continue
            if not chosen:
                a, b = segs[0]
                px, py = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
                chosen = (px - lw / 2, py - lh / 2, lw, lh)
                self.warnings.append(f"label overlap: {ed['src']}->{ed['dst']} '{lab}'")
            self.labels.append(chosen)
            ed["label_box"] = (chosen, lines)

    # -------------------------------------------------------------------- geometry validation
    def validate(self):
        """Automated layout checks; returns a list of (kind, detail) problems."""
        probs = []
        obs = [(x, y, w, h) for (x, y, w, h, m) in self.obstacles]

        def on_border(pt, r, tol=2.0):
            x, y, w, h = r
            px, py = pt
            inside_x = x - tol <= px <= x + w + tol
            inside_y = y - tol <= py <= y + h + tol
            near = (abs(px - x) <= tol or abs(px - x - w) <= tol or abs(py - y) <= tol or abs(py - y - h) <= tol)
            return inside_x and inside_y and near

        def seg_hits(a, b, r, shrink=3):
            x, y, w, h = r[0] + shrink, r[1] + shrink, r[2] - 2 * shrink, r[3] - 2 * shrink
            if w <= 0 or h <= 0:
                return False
            if abs(a[1] - b[1]) < 0.01:
                y0 = a[1]
                x0, x1 = sorted((a[0], b[0]))
                return y < y0 < y + h and x0 < x + w and x1 > x
            x0 = a[0]
            y0, y1 = sorted((a[1], b[1]))
            return x < x0 < x + w and y0 < y + h and y1 > y

        segs_all = []
        for k, ed in enumerate(self.edges):
            pts = ed["points"]
            if ed.get("to_note"):
                continue
            srect = self.nodes[ed["src"]]["rect"]
            drect = self.nodes[ed["dst"]]["rect"]
            s_top = 40 if self.nodes[ed["src"]]["kind"] == "phone" else 0
            d_top = 40 if self.nodes[ed["dst"]]["kind"] == "phone" else 0
            sr = (srect[0], srect[1] - s_top, srect[2], srect[3] + s_top)
            dr = (drect[0], drect[1] - d_top, drect[2], drect[3] + d_top)
            name = f"{ed['src']}→{ed['dst']}"
            if not on_border(pts[0], sr) and not on_border(pts[0], srect):
                probs.append(("detached start", name))
            if not on_border(pts[-1], dr) and not on_border(pts[-1], drect):
                probs.append(("detached end (arrow stops in empty space)", name))
            last = abs(pts[-1][0] - pts[-2][0]) + abs(pts[-1][1] - pts[-2][1])
            if last < 8 and not ed.get("self_loop"):
                probs.append(("arrowhead segment shorter than 8 px", name))
            for a, b in zip(pts, pts[1:]):
                if abs(a[0] - b[0]) > 0.01 and abs(a[1] - b[1]) > 0.01:
                    probs.append(("diagonal segment", name))
                for r in obs:
                    if _contains(sr, r) or _contains(dr, r) or _contains(r, srect) or _contains(r, drect):
                        continue
                    if seg_hits(a, b, r):
                        probs.append(("arrow crosses a card / phone", f"{name} through box at {int(r[0])},{int(r[1])}"))
                        break
                segs_all.append((k, a, b))
        # collinear overlaps between different arrows
        for i in range(len(segs_all)):
            k1, a1, b1 = segs_all[i]
            for j in range(i + 1, len(segs_all)):
                k2, a2, b2 = segs_all[j]
                if k1 == k2:
                    continue
                if abs(a1[1] - b1[1]) < 0.01 and abs(a2[1] - b2[1]) < 0.01 and abs(a1[1] - a2[1]) < 1:
                    lo, hi = max(min(a1[0], b1[0]), min(a2[0], b2[0])), min(max(a1[0], b1[0]), max(a2[0], b2[0]))
                    if hi - lo > 24 and self.edges[k1]["dst"] != self.edges[k2]["dst"] and self.edges[k1]["src"] != self.edges[k2]["src"]:
                        probs.append(("parallel arrows overlap", f"{self.edges[k1]['src']}→{self.edges[k1]['dst']} / {self.edges[k2]['src']}→{self.edges[k2]['dst']}"))
                elif abs(a1[0] - b1[0]) < 0.01 and abs(a2[0] - b2[0]) < 0.01 and abs(a1[0] - a2[0]) < 1:
                    lo, hi = max(min(a1[1], b1[1]), min(a2[1], b2[1])), min(max(a1[1], b1[1]), max(a2[1], b2[1]))
                    if hi - lo > 24 and self.edges[k1]["dst"] != self.edges[k2]["dst"] and self.edges[k1]["src"] != self.edges[k2]["src"]:
                        probs.append(("parallel arrows overlap", f"{self.edges[k1]['src']}→{self.edges[k1]['dst']} / {self.edges[k2]['src']}→{self.edges[k2]['dst']}"))
        # labels
        for k, ed in enumerate(self.edges):
            lb = ed.get("label_box")
            if not lb:
                continue
            box = lb[0]
            for r in obs:
                if _overlap((box[0] + 1, box[1] + 1, box[2] - 2, box[3] - 2), r):
                    probs.append(("label overlaps a card / phone", f"{ed['src']}→{ed['dst']} '{ed.get('label')}'"))
                    break
            for k2, ed2 in enumerate(self.edges):
                if k2 > k and ed2.get("label_box") and _overlap((box[0] + 1, box[1] + 1, box[2] - 2, box[3] - 2), ed2["label_box"][0]):
                    probs.append(("labels overlap", f"'{ed.get('label')}' / '{ed2.get('label')}'"))
        # cards overlapping each other (ignore containers that hold them)
        for i in range(len(obs)):
            for j in range(i + 1, len(obs)):
                a, b = obs[i], obs[j]
                if _overlap((a[0] + 1, a[1] + 1, a[2] - 2, a[3] - 2), (b[0] + 1, b[1] + 1, b[2] - 2, b[3] - 2)) and not (_contains(a, b) or _contains(b, a)):
                    probs.append(("cards overlap", f"{int(a[0])},{int(a[1])} / {int(b[0])},{int(b[1])}"))
        # text that runs outside the drawing
        for c in self.cells:
            if c.x + c.w > self.W + 1 or c.y + c.h > self.H + 1 or c.x < -1 or c.y < -1:
                probs.append(("content clipped at the page edge", f"{c.text[:30]!r} at {int(c.x)},{int(c.y)}"))
        return probs

    # -------------------------------------------------------------------- output
    def svg(self):
        out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.W}" height="{self.H}" viewBox="0 0 {self.W} {self.H}" '
               f'font-family="{FONT}">', f"<title>{esc(self.title)}</title>", "<defs>"]
        for k, (col, w, dash) in EDGE_STYLES.items():
            out.append(f'<marker id="ah-{k}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" '
                       f'orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{col}"/></marker>')
        out.append("</defs>")
        out.append(f'<rect x="0" y="0" width="{self.W}" height="{self.H}" fill="#ffffff"/>')
        open_group = None
        for c in self.cells:
            if c.group != open_group:
                if open_group is not None:
                    out.append("</g>")
                open_group = c.group
                if c.group is not None:
                    tip = self.groups.get(c.group, (0, 0, 0, 0, {}))[4].get("tooltip")
                    out.append(f'<g id="{esc(_xid(c.group))}">' + (f"<title>{esc(tip)}</title>" if tip else ""))
            out.append(_svg_cell(c))
        if open_group is not None:
            out.append("</g>")
        for ed in self.edges:
            col, w, dash = EDGE_STYLES[ed["style"]]
            d = "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in ed["points"])
            out.append(f'<path d="{d}" fill="none" stroke="#ffffff" stroke-width="{w + 2.6}" stroke-linejoin="round" opacity="0.9"/>')
            out.append(f'<path d="{d}" fill="none" stroke="{col}" stroke-width="{w}" stroke-linejoin="round"'
                       + (f' stroke-dasharray="{dash}"' if dash else "")
                       + f' marker-end="url(#ah-{ed["style"]})"><title>{esc(ed.get("tip", ""))}</title></path>')
        for ed in self.edges:
            if not ed.get("label_box"):
                continue
            (x, y, w, h), lines = ed["label_box"]
            col = EDGE_STYLES[ed["style"]][0]
            out.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" rx="3" fill="#ffffff" fill-opacity="0.95" '
                       f'stroke="{col}" stroke-width="0.6"/>')
            for k, l in enumerate(lines):
                ty = y + 2.5 + LABEL_FS * 1.25 * (k + 1) - 2.2
                out.append(f'<text x="{x + w / 2:.1f}" y="{ty:.1f}" font-size="{LABEL_FS}" text-anchor="middle" fill="{col}" '
                           f'font-weight="bold">{esc(l)}</text>')
        out.append("</svg>")
        return "\n".join(out)

    def drawio(self):
        rows = ['<mxfile host="SafeStitch wireflow generator" agent="docs/project-diagrams/generator" version="24.7.17">',
                f'<diagram id="{esc(_xid(self.name))}" name="{esc(self.title)}">',
                f'<mxGraphModel dx="1400" dy="900" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" '
                f'page="1" pageScale="1" pageWidth="{int(self.W)}" pageHeight="{int(self.H)}" background="#ffffff" math="0" shadow="0">',
                "<root>", '<mxCell id="0"/>', '<mxCell id="1" parent="0"/>']
        group_ids, emitted = {}, set()
        for c in self.cells:
            parent, ox, oy = "1", 0, 0
            if c.group is not None:
                if c.group not in emitted:
                    gx, gy, gw, gh, attrs = self.groups[c.group]
                    gid = "g_" + _xid(c.group)
                    group_ids[c.group] = (gid, gx, gy)
                    rows.append(f'<mxCell id="{gid}" value="" style="group" vertex="1" connectable="0" parent="1">'
                                f'<mxGeometry x="{gx:.1f}" y="{gy:.1f}" width="{gw:.1f}" height="{gh:.1f}" as="geometry"/></mxCell>')
                    emitted.add(c.group)
                parent, ox, oy = group_ids[c.group]
            style = _drawio_style(c)
            value = esc("<br>".join(esc(l) for l in c.text.split("\n"))) if c.text else ""
            geo = f'<mxGeometry x="{c.x - ox:.1f}" y="{c.y - oy:.1f}" width="{max(c.w, 0.5):.1f}" height="{max(c.h, 0.5):.1f}" as="geometry"/>'
            cid = _xid(c.id)
            if c.attrs:
                at = " ".join(f'{_xid(k)}="{esc(v)}"' for k, v in c.attrs.items())
                rows.append(f'<object id="{cid}" label="{value}" {at}><mxCell style="{style}" vertex="1" parent="{parent}">{geo}</mxCell></object>')
            else:
                rows.append(f'<mxCell id="{cid}" value="{value}" style="{style}" vertex="1" parent="{parent}">{geo}</mxCell>')
        for n, ed in enumerate(self.edges):
            col, w, dash = EDGE_STYLES[ed["style"]]
            pts = ed["points"]
            style = (f"edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;endArrow=block;endFill=1;endSize=5;strokeColor={col};strokeWidth={w};"
                     f"fontSize={LABEL_FS};fontColor={col};fontStyle=1;labelBackgroundColor=#ffffff;labelBorderColor={col};")
            if dash:
                style += f"dashed=1;dashPattern={dash};"
            sxr, syr, swr, shr = self.nodes[ed["src"]]["rect"]
            dxr, dyr, dwr, dhr = self.nodes[ed["dst"]]["rect"]
            ex, ey = (pts[0][0] - sxr) / swr, (pts[0][1] - syr) / shr
            nx_, ny_ = (pts[-1][0] - dxr) / dwr, (pts[-1][1] - dyr) / dhr
            if self.nodes[ed["dst"]]["kind"] == "phone" and pts[-1][1] < dyr:
                ny_ = 0.0   # arrows entering the title area attach to the frame's top edge in draw.io
            if self.nodes[ed["src"]]["kind"] == "phone" and pts[0][1] < syr:
                ey = 0.0
            style += (f"exitX={min(max(ex, 0), 1):.3f};exitY={min(max(ey, 0), 1):.3f};exitDx=0;exitDy=0;exitPerimeter=0;"
                      f"entryX={min(max(nx_, 0), 1):.3f};entryY={min(max(ny_, 0), 1):.3f};entryDx=0;entryDy=0;entryPerimeter=0;")
            label = esc("<br>".join(esc(l) for l in ed["label_box"][1])) if ed.get("label_box") else ""
            at = " ".join(f'{_xid(k)}="{esc(v)}"' for k, v in ed.get("attrs", {}).items())
            mid = "".join(f'<mxPoint x="{x:.1f}" y="{y:.1f}"/>' for x, y in pts[1:-1])
            rows.append(f'<object id="edge_{n}" label="{label}" {at}><mxCell style="{style}" edge="1" parent="1" '
                        f'source="{_xid(self._cell_for(ed["src"]))}" target="{_xid(self._cell_for(ed["dst"]))}">'
                        f'<mxGeometry relative="1" as="geometry"><mxPoint x="{pts[0][0]:.1f}" y="{pts[0][1]:.1f}" as="sourcePoint"/>'
                        f'<mxPoint x="{pts[-1][0]:.1f}" y="{pts[-1][1]:.1f}" as="targetPoint"/><Array as="points">{mid}</Array>'
                        f'</mxGeometry></mxCell></object>')
        rows += ["</root>", "</mxGraphModel>", "</diagram>", "</mxfile>"]
        return "\n".join(rows)

    def _cell_for(self, nid):
        n = self.nodes[nid]
        if n["kind"] == "phone":
            return f"{nid}__frame"
        return f"{n['group']}__node"


def _xid(s):
    return "".join(ch if ch.isalnum() or ch in "_-" else "_" for ch in str(s))


def _contains(outer, inner):
    return (outer[0] <= inner[0] + 0.5 and outer[1] <= inner[1] + 0.5 and outer[0] + outer[2] >= inner[0] + inner[2] - 0.5
            and outer[1] + outer[3] >= inner[1] + inner[3] - 0.5)


def _overlap(a, b):
    return not (a[0] + a[2] <= b[0] or b[0] + b[2] <= a[0] or a[1] + a[3] <= b[1] or b[1] + b[3] <= a[1])


def _simplify(pts):
    out = [pts[0]]
    for p in pts[1:]:
        if abs(p[0] - out[-1][0]) < 0.01 and abs(p[1] - out[-1][1]) < 0.01:
            continue
        out.append(p)
    res = [out[0]]
    for i in range(1, len(out) - 1):
        a, b, c = res[-1], out[i], out[i + 1]
        if (abs(a[0] - b[0]) < 0.01 and abs(b[0] - c[0]) < 0.01) or (abs(a[1] - b[1]) < 0.01 and abs(b[1] - c[1]) < 0.01):
            continue
        res.append(b)
    res.append(out[-1])
    return res


def _svg_cell(c):
    st = c.st
    fill, stroke = st.get("fill", "#fff"), st.get("stroke", "none")
    sw, r, dash, op = st.get("sw", 1), st.get("r", 0), st.get("dash"), st.get("opacity")
    parts = []
    common = (f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}"' + (f' stroke-dasharray="{dash}"' if dash else "")
              + (f' opacity="{op}"' if op is not None else ""))
    if not (fill == "none" and stroke == "none"):
        if c.shape == "ellipse":
            parts.append(f'<ellipse cx="{c.x + c.w / 2:.1f}" cy="{c.y + c.h / 2:.1f}" rx="{c.w / 2:.1f}" ry="{c.h / 2:.1f}" {common}/>')
        else:
            parts.append(f'<rect x="{c.x:.1f}" y="{c.y:.1f}" width="{c.w:.1f}" height="{c.h:.1f}" rx="{min(r, c.w / 2, c.h / 2):.1f}" {common}/>')
    if c.text:
        fs, lh = st.get("fs", 8), st.get("lh", 1.25)
        lines = c.text.split("\n")
        align, pad = st.get("align", "left"), st.get("pad", 0)
        if align == "left":
            tx, anchor = c.x + pad, "start"
        elif align == "right":
            tx, anchor = c.x + c.w - pad, "end"
        else:
            tx, anchor = c.x + c.w / 2, "middle"
        total = len(lines) * fs * lh
        y0 = c.y + fs * 0.95 if st.get("valign") == "top" else c.y + (c.h - total) / 2 + fs * 0.92 + (fs * lh - fs) / 2
        weight = ' font-weight="bold"' if st.get("bold") else ""
        ital = ' font-style="italic"' if st.get("italic") else ""
        spans = "".join(f'<tspan x="{tx:.1f}" y="{y0 + k * fs * lh:.1f}">{esc(l)}</tspan>' for k, l in enumerate(lines))
        parts.append(f'<text font-size="{fs}" fill="{st.get("fc", INK)}" text-anchor="{anchor}"{weight}{ital}>{spans}</text>')
    return "".join(parts)


def _drawio_style(c):
    st = c.st
    s = ["ellipse"] if c.shape == "ellipse" else []
    s.append("html=1;whiteSpace=wrap")
    fill, stroke = st.get("fill", "#fff"), st.get("stroke", "none")
    s.append(f"fillColor={fill}")
    s.append(f"strokeColor={stroke}")
    s.append(f"strokeWidth={st.get('sw', 1)}")
    r = st.get("r", 0)
    if r and c.shape != "ellipse":
        s.append(f"rounded=1;absoluteArcSize=1;arcSize={2 * min(r, c.w / 2, c.h / 2):.1f}")
    if st.get("dash"):
        s.append(f"dashed=1;dashPattern={st['dash']}")
    if st.get("opacity") is not None:
        s.append(f"opacity={int(st['opacity'] * 100)}")
    if c.text:
        fstyle = (1 if st.get("bold") else 0) + (2 if st.get("italic") else 0)
        s.append(f"fontSize={st.get('fs', 8)};fontColor={st.get('fc', INK)};fontStyle={fstyle};fontFamily=Helvetica")
        s.append(f"align={st.get('align', 'left')};verticalAlign={st.get('valign', 'middle')}")
        s.append(f"spacing=0;spacingLeft={st.get('pad', 0)};spacingRight={st.get('pad', 0)}")
    return ";".join(s) + ";"
