# SafeStitch — project diagrams

Generated 2026-11-15 from commit `d797895`. Every diagram and report here is produced from one model (`generator/model.py`) whose evidence tokens are re-checked against the Kotlin source on every build.

## Start here

**Master wireflow:** `01-Full-Application-Wireflow.drawio` (editable). The same diagram is exported as `.pdf` (one large vector page; zoom in), `.svg` (vector, hover for route/source/roles) and `.png`.

- 24 screens (24 drawn) · 16 pop-ups/menus · 7 Android/system hand-offs
- 170 transitions in `NAVIGATION-MATRIX.csv`, 170 of them drawn in at least one diagram
- 71 transitions confirmed on an Android 16 emulator; the rest are traced in source (see the matrix's Verification Status column)

## Files

| File | What it is |
|---|---|
| `01-Full-Application-Wireflow.drawio` / `.pdf` / `.svg` / `.png` | **Master** — every screen and navigation path |
| `02-Module-Authentication-and-Session.drawio` / `.pdf` / `.svg` / `.png` | Module detail — Authentication, session & settings |
| `03-Module-Home-Dashboard.drawio` / `.pdf` / `.svg` / `.png` | Module detail — Home dashboard (hub) |
| `04-Module-Equipment-Scan-and-Inspection.drawio` / `.pdf` / `.svg` / `.png` | Module detail — Equipment, QR scan & inspection checklist |
| `05-Module-Inspection-Report-and-PDF.drawio` / `.pdf` / `.svg` / `.png` | Module detail — Inspection report & PDF |
| `06-Module-Corrective-Actions.drawio` / `.pdf` / `.svg` / `.png` | Module detail — Corrective actions |
| `07-Module-History-and-Captured-Photos.drawio` / `.pdf` / `.svg` / `.png` | Module detail — Inspection history & captured photos |
| `08-Module-Admin-Tools.drawio` / `.pdf` / `.svg` / `.png` | Module detail — Admin tools (admin only) |
| `09-Module-Safety-Assistant-and-Voice-Notes.drawio` / `.pdf` / `.svg` / `.png` | Module detail — Safety Assistant chat & voice notes (PARTIAL) |
| `10-Role-Admin-User-Flow.drawio` / `.pdf` / `.svg` / `.png` | Role view — Admin user flow (all 24 screens; admin-only paths in orange) |
| `11-Role-Inspector-User-Flow.drawio` / `.pdf` / `.svg` / `.png` | Role view — Inspector user flow (17 screens; admin-only areas and first-admin setup removed) |
| `SCREEN-INVENTORY.md` | Every screen: route, source file:line, roles, components, fields, buttons, dialogs, incoming/outgoing screens, states, status |
| `NAVIGATION-MATRIX.csv` | Every transition: source, action, condition, destination, role, source file:line, verification |
| `COVERAGE-REPORT.md` | Build/test/run results, the independent source re-scan, file validation, traceability tables |
| `GAPS-AND-UNVERIFIED-FLOWS.md` | Bugs, partial features, unreachable code, documentation drift, code-trace-only flows |
| `generator/` | The scripts that produced all of the above (re-run after app changes, see below) |

## Opening the diagrams on macOS

- **Editable (`.drawio`):** install draw.io Desktop (`brew install --cask drawio`, or the free app from github.com/jgraph/drawio-desktop/releases) and double-click the file, or use the *Draw.io Integration* extension (hediet.vscode-drawio) to open it inside VS Code. Every phone frame and arrow carries data: right-click › *Edit Data…* shows the route, source file:line, roles and verification status.
- **View only:** `.pdf` opens in Preview (zoom in — it is one large vector page); `.svg` opens in any browser (hover a frame or arrow for its tooltip); `.png` is a quick raster overview.

## Regenerating

```bash
cd docs/project-diagrams/generator
python3 build.py ../../.. .. runtime-results.json   # needs Google Chrome for the PDF/PNG export
```

If a screen or button changes, `build.py` stops with `EVIDENCE MISSING` and names the transition whose source token no longer matches — update `model.py`, then rebuild.

