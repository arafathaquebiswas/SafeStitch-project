# Diagram generator

Produces every file in `docs/project-diagrams/` from one model, so the diagrams and reports cannot disagree with each other.

| File | Role |
|---|---|
| `model.py` | Screens (with wireframe content), pop-ups, Android hand-offs and every transition, each with evidence tokens (`file`, exact source substring) |
| `engine.py` | Phone-frame wireframe renderer, A* orthogonal edge router, SVG and draw.io writers (same geometry in both) |
| `build.py` | Layouts for the master, module and role diagrams; resolves evidence to `file:line`; exports PDF/PNG with headless Chrome |
| `reports.py` | Writes the inventory, navigation matrix, coverage report, gaps report and README; runs the independent source re-scan and file validation |
| `build-facts.json` | Build/test/emulator results and findings recorded during the analysis (edit after re-testing) |
| `runtime-results.json` | Results of the emulator walkthrough (`key`, `ok`, `detail`, `role`), used for the ✓ marks |

```bash
# from this folder
python3 build.py ../../.. .. runtime-results.json
```

Requirements: Python 3.9+ and Google Chrome (for the PDF and PNG export; `.drawio` and `.svg` need nothing else).
Chrome runs headless with a throw-away profile and only loads the local HTML page that wraps the SVG.

When the app changes, `build.py` stops with `EVIDENCE MISSING` for every transition whose source token no longer
exists. Update the matching `edge(...)` or `screen(...)` entry in `model.py` and rebuild. New routes, dialogs, screen
composables or navigation callbacks that the model does not cover are listed in `COVERAGE-REPORT.md`, sections 2–6.
