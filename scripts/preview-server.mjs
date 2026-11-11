import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const PORT = 3000;
const APK_PATH = '/app/applet/app/build/outputs/apk/debug/app-debug.apk';
const AAB_PATH = '/app/applet/app/build/outputs/bundle/release/app-release.aab';

const HTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>SafeStitch — Safety & Equipment Inspection</title>
  <meta name="description" content="Offline-first safety and equipment inspection app with QR checkpoints, corrective actions, and PDF reports." />
  <script src="https://cdn.tailwindcss.com"></script>
  <style>
    :root {
      --primary: #0B6E69;
      --primary-container: #A6F0E8;
      --on-primary-container: #00201E;
      --secondary: #4A6360;
      --secondary-container: #CCE8E4;
      --surface: #F6FAF9;
      --surface-variant: #DAE5E2;
      --pass: #1B7F3B;
      --fail: #B3261E;
      --draft: #8A5100;
    }
    body {
      background-color: #E8F0EE;
      color: #171D1C;
      font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    }
    .phone-frame {
      max-width: 440px;
      margin: 0 auto;
      min-height: 100vh;
      background: #F6FAF9;
      display: flex;
      flex-direction: column;
      box-shadow: 0 10px 35px rgba(0, 32, 30, 0.12);
    }
  </style>
</head>
<body>
  <div id="app" class="phone-frame"></div>

  <script>
    const CHECKLIST_TEMPLATES = {
      FIRE_EXTINGUISHER: [
        { section: "Fire extinguisher", question: "Extinguisher is in its marked place and mounted at the right height", critical: false },
        { section: "Fire extinguisher", question: "Pressure gauge reads in the green (charged) zone", critical: true },
        { section: "Fire extinguisher", question: "Safety pin and tamper seal are intact", critical: true },
        { section: "Fire extinguisher", question: "Service tag shows a check within the last 12 months", critical: true },
        { section: "Fire extinguisher", question: "Hose and nozzle are undamaged and unblocked", critical: false },
        { section: "Fire extinguisher", question: "Correct type for the risk (e.g. CO2 near electrical panels)", critical: false },
        { section: "Fire extinguisher", question: "Access is clear and the location sign is visible", critical: false }
      ],
      ELECTRICAL: [
        { section: "Electrical safety", question: "Panel door is closed, labelled and free of obstruction", critical: false },
        { section: "Electrical safety", question: "No exposed live parts or damaged insulation", critical: true },
        { section: "Electrical safety", question: "Circuit breakers are labelled", critical: false },
        { section: "Electrical safety", question: "No signs of overheating, burning or sparking", critical: true },
        { section: "Electrical safety", question: "Insulating rubber mat is in front of the panel", critical: false },
        { section: "Electrical safety", question: "No daisy-chained extension cords or overloaded sockets", critical: false }
      ],
      FIRE_ALARM: [
        { section: "Fire alarm", question: "Control panel shows normal status with no faults", critical: true },
        { section: "Fire alarm", question: "Manual call point is accessible, unobstructed and undamaged", critical: true },
        { section: "Fire alarm", question: "Sounders are audible in the area (last weekly test recorded)", critical: true },
        { section: "Fire alarm", question: "Smoke/heat detectors are clean, uncovered and undamaged", critical: false },
        { section: "Fire alarm", question: "Weekly test log is up to date", critical: false },
        { section: "Fire alarm", question: "Backup battery / power indicator is normal", critical: false }
      ],
      EMERGENCY_EXIT: [
        { section: "Emergency exit", question: "Exit door opens outward easily and is not locked during working hours", critical: true },
        { section: "Emergency exit", question: "Exit route is clear of materials and obstructions", critical: true },
        { section: "Emergency exit", question: "Exit sign is lit and visible", critical: true },
        { section: "Emergency exit", question: "Emergency lighting works along the route", critical: false },
        { section: "Emergency exit", question: "Stairs and handrails are in good condition", critical: false },
        { section: "Emergency exit", question: "Assembly point direction signs are posted", critical: false }
      ],
      BOILER: [
        { section: "Boiler safety", question: "Boiler is run only by a certified operator who is present", critical: true },
        { section: "Boiler safety", question: "Safety valve is tested and not tampered with", critical: true },
        { section: "Boiler safety", question: "Pressure and water-level gauges are readable and within limits", critical: true },
        { section: "Boiler safety", question: "Valid boiler inspection certificate is displayed", critical: false },
        { section: "Boiler safety", question: "No steam, water or fuel leaks", critical: false },
        { section: "Boiler safety", question: "Boiler room is ventilated and clear of combustible materials", critical: false },
        { section: "Boiler safety", question: "Daily operation log is up to date", critical: false }
      ]
    };

    const TYPE_LABELS = {
      FIRE_EXTINGUISHER: "Fire extinguisher",
      ELECTRICAL: "Electrical panel",
      FIRE_ALARM: "Fire alarm",
      EMERGENCY_EXIT: "Emergency exit",
      BOILER: "Boiler"
    };

    const state = {
      tab: 'home', // home | inspect | actions | history | scan | inspection | report | admin | newEquipment
      menuOpen: false,
      user: { displayName: 'Nadia Rahman', username: 'admin', role: 'ADMIN', roleLabel: 'Admin' },
      searchQuery: '',
      selectedTypeFilter: 'ALL',
      actionFilter: 'ALL',
      historyFilter: 'ALL',
      activeInspectionId: null,
      activeReportId: null,
      qrInput: 'FE-101',
      checkpoints: [
        { id: 1, code: 'FE-101', name: 'CO2 Extinguisher — Cutting North', type: 'FIRE_EXTINGUISHER', building: 'Dhaka Plant 1 › Main Building', area: 'Floor 1 · Cutting Room', spot: 'Near Pillar B2' },
        { id: 2, code: 'EL-204', name: 'Main Distribution Panel DB-2', type: 'ELECTRICAL', building: 'Dhaka Plant 1 › Main Building', area: 'Floor 2 · Sewing Line A', spot: 'East Wall' },
        { id: 3, code: 'EX-102', name: 'Stairwell Exit West', type: 'EMERGENCY_EXIT', building: 'Dhaka Plant 1 › Main Building', area: 'Floor 1 · Finishing', spot: 'Door W-02' },
        { id: 4, code: 'FA-301', name: 'Addressable Fire Alarm Panel', type: 'FIRE_ALARM', building: 'Dhaka Plant 1 › Annex Building', area: 'Ground Floor · Reception', spot: 'Guard Desk' },
        { id: 5, code: 'BL-001', name: 'Steam Generator Boiler #1', type: 'BOILER', building: 'Dhaka Plant 1 › Utility Block', area: 'Ground Floor · Boiler Room', spot: 'Bay 1' }
      ],
      inspections: [
        {
          id: 101,
          checkpointId: 1,
          equipmentCode: 'FE-101',
          equipmentName: 'CO2 Extinguisher — Cutting North',
          equipmentType: 'FIRE_EXTINGUISHER',
          building: 'Dhaka Plant 1 › Main Building',
          inspectorName: 'Nadia Rahman',
          startedAt: Date.now() - 86400000 * 2,
          completedAt: Date.now() - 86400000 * 2 + 600000,
          overallNote: 'Routine weekly check passed.',
          items: CHECKLIST_TEMPLATES.FIRE_EXTINGUISHER.map((q, idx) => ({
            id: idx + 1,
            ...q,
            result: 'PASS',
            note: ''
          }))
        },
        {
          id: 102,
          checkpointId: 2,
          equipmentCode: 'EL-204',
          equipmentName: 'Main Distribution Panel DB-2',
          equipmentType: 'ELECTRICAL',
          building: 'Dhaka Plant 1 › Main Building',
          inspectorName: 'Kamal Hossain',
          startedAt: Date.now() - 86400000,
          completedAt: Date.now() - 86400000 + 900000,
          overallNote: 'Rubber mat missing in front of panel DB-2; circuit 14 label faded.',
          items: CHECKLIST_TEMPLATES.ELECTRICAL.map((q, idx) => ({
            id: idx + 1,
            ...q,
            result: idx === 4 ? 'FAIL' : 'PASS',
            note: idx === 4 ? 'Insulating rubber mat was removed during floor cleaning' : '',
            priority: idx === 4 ? 'HIGH' : null
          }))
        },
        {
          id: 103,
          checkpointId: 3,
          equipmentCode: 'EX-102',
          equipmentName: 'Stairwell Exit West',
          equipmentType: 'EMERGENCY_EXIT',
          building: 'Dhaka Plant 1 › Main Building',
          inspectorName: 'Nadia Rahman',
          startedAt: Date.now() - 3600000 * 5,
          completedAt: Date.now() - 3600000 * 4,
          overallNote: 'Cartons temporarily blocking corridor near Door W-02.',
          items: CHECKLIST_TEMPLATES.EMERGENCY_EXIT.map((q, idx) => ({
            id: idx + 1,
            ...q,
            result: idx === 1 ? 'FAIL' : 'PASS',
            note: idx === 1 ? '3 fabric rolls stacked in exit corridor' : '',
            priority: idx === 1 ? 'CRITICAL' : null
          }))
        }
      ],
      actions: [
        {
          id: 501,
          inspectionId: 103,
          equipmentCode: 'EX-102',
          equipmentName: 'Stairwell Exit West',
          question: 'Exit route is clear of materials and obstructions',
          description: '3 fabric rolls stacked in exit corridor',
          priority: 'CRITICAL',
          status: 'OPEN',
          responsibleName: 'Floor Supervisor Karim',
          dueAt: Date.now() + 86400000,
          events: [
            { toStatus: 'OPEN', byName: 'Nadia Rahman', at: Date.now() - 3600000 * 4, note: 'Finding logged from inspection #103' }
          ]
        },
        {
          id: 502,
          inspectionId: 102,
          equipmentCode: 'EL-204',
          equipmentName: 'Main Distribution Panel DB-2',
          question: 'Insulating rubber mat is in front of the panel',
          description: 'Insulating rubber mat was removed during floor cleaning',
          priority: 'HIGH',
          status: 'RESOLVED',
          responsibleName: 'Maintenance Lead Rafiq',
          dueAt: Date.now() + 86400000 * 2,
          events: [
            { toStatus: 'OPEN', byName: 'Kamal Hossain', at: Date.now() - 86400000, note: 'Finding logged from inspection #102' },
            { toStatus: 'IN_PROGRESS', byName: 'Maintenance Lead Rafiq', at: Date.now() - 43200000, note: 'Ordered replacement IEC class-2 mat from store' },
            { toStatus: 'RESOLVED', byName: 'Maintenance Lead Rafiq', at: Date.now() - 7200000, note: 'Installed new insulating mat in front of DB-2' }
          ]
        }
      ],
      capturedPhotos: [
        {
          id: 901,
          timestamp: Date.now() - 900000,
          imagePath: 'photos/IMG_EL204_panel_mat.jpg',
          statusTags: ['NEEDS_REVIEW'],
          dataUrl: 'data:image/svg+xml;utf8,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="160" height="110" viewBox="0 0 160 110"><rect width="160" height="110" fill="#0F1514"/><rect x="16" y="14" width="128" height="82" rx="8" fill="none" stroke="#A6F0E8" stroke-width="2"/><text x="80" y="52" fill="#A6F0E8" font-family="monospace" font-size="11" font-weight="bold" text-anchor="middle">EL-204 PANEL</text><text x="80" y="70" fill="#CCE8E4" font-family="sans-serif" font-size="10" text-anchor="middle">NEEDS REVIEW</text></svg>')
        },
        {
          id: 902,
          timestamp: Date.now() - 3600000,
          imagePath: 'photos/IMG_EX102_corridor_obstruction.jpg',
          statusTags: ['FAIL', 'FOLLOW_UP'],
          dataUrl: 'data:image/svg+xml;utf8,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="160" height="110" viewBox="0 0 160 110"><rect width="160" height="110" fill="#25100E"/><rect x="16" y="14" width="128" height="82" rx="8" fill="none" stroke="#FFB4AB" stroke-width="2"/><text x="80" y="52" fill="#FFB4AB" font-family="monospace" font-size="11" font-weight="bold" text-anchor="middle">EX-102 EXIT</text><text x="80" y="70" fill="#FFDAD6" font-family="sans-serif" font-size="10" text-anchor="middle">FAIL · FOLLOW UP</text></svg>')
        },
        {
          id: 903,
          timestamp: Date.now() - 7200000,
          imagePath: 'photos/IMG_FE101_gauge_check.jpg',
          statusTags: ['PASS'],
          dataUrl: 'data:image/svg+xml;utf8,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="160" height="110" viewBox="0 0 160 110"><rect width="160" height="110" fill="#0B2416"/><rect x="16" y="14" width="128" height="82" rx="8" fill="none" stroke="#8AD4CC" stroke-width="2"/><text x="80" y="52" fill="#A6F0E8" font-family="monospace" font-size="11" font-weight="bold" text-anchor="middle">FE-101 GAUGE</text><text x="80" y="70" fill="#CCE8E4" font-family="sans-serif" font-size="10" text-anchor="middle">PASS</text></svg>')
        }
      ]
    };

    function computeSummary(inspection) {
      const answered = inspection.items.filter(i => i.result === 'PASS' || i.result === 'FAIL');
      const passed = inspection.items.filter(i => i.result === 'PASS').length;
      const failed = inspection.items.filter(i => i.result === 'FAIL').length;
      const criticalFailed = inspection.items.filter(i => i.result === 'FAIL' && i.critical).length;
      const scorePercent = answered.length ? Math.round((passed / answered.length) * 100) : 100;
      const outcome = !inspection.completedAt ? 'DRAFT' : (failed === 0 ? 'PASS' : 'FAIL');
      return { passed, failed, criticalFailed, scorePercent, outcome };
    }

    function formatDate(ms) {
      return new Date(ms).toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
    }

    window.setTab = (tab) => {
      state.tab = tab;
      state.menuOpen = false;
      render();
    };

    window.toggleMenu = () => {
      state.menuOpen = !state.menuOpen;
      render();
    };

    window.toggleRole = () => {
      if (state.user.role === 'ADMIN') {
        state.user = { displayName: 'Kamal Hossain', username: 'inspector', role: 'INSPECTOR', roleLabel: 'Inspector' };
      } else {
        state.user = { displayName: 'Nadia Rahman', username: 'admin', role: 'ADMIN', roleLabel: 'Admin' };
      }
      state.menuOpen = false;
      render();
    };

    window.startInspection = (checkpointId) => {
      const cp = state.checkpoints.find(c => c.id === checkpointId);
      if (!cp) return;
      const existingDraft = state.inspections.find(i => i.checkpointId === cp.id && !i.completedAt);
      if (existingDraft) {
        state.activeInspectionId = existingDraft.id;
        state.tab = 'inspection';
        render();
        return;
      }
      const template = CHECKLIST_TEMPLATES[cp.type] || CHECKLIST_TEMPLATES.FIRE_EXTINGUISHER;
      const newInsp = {
        id: Date.now(),
        checkpointId: cp.id,
        equipmentCode: cp.code,
        equipmentName: cp.name,
        equipmentType: cp.type,
        building: cp.building,
        inspectorName: state.user.displayName,
        startedAt: Date.now(),
        completedAt: null,
        overallNote: '',
        items: template.map((q, idx) => ({
          id: idx + 1,
          ...q,
          result: 'UNANSWERED',
          note: '',
          priority: q.critical ? 'CRITICAL' : 'MEDIUM'
        }))
      };
      state.inspections.unshift(newInsp);
      state.activeInspectionId = newInsp.id;
      state.tab = 'inspection';
      render();
    };

    window.scanCode = () => {
      const code = (document.getElementById('qr-input')?.value || state.qrInput).trim().toUpperCase();
      state.qrInput = code;
      const cp = state.checkpoints.find(c => c.code.toUpperCase() === code);
      if (cp) {
        window.startInspection(cp.id);
      } else {
        state.tab = 'newEquipment';
        render();
      }
    };

    window.setAnswer = (itemId, result) => {
      const insp = state.inspections.find(i => i.id === state.activeInspectionId);
      if (!insp) return;
      const item = insp.items.find(x => x.id === itemId);
      if (item) item.result = result;
      render();
    };

    window.markAllPass = () => {
      const insp = state.inspections.find(i => i.id === state.activeInspectionId);
      if (!insp) return;
      insp.items.forEach(i => { if (i.result === 'UNANSWERED') i.result = 'PASS'; });
      render();
    };

    window.updateItemNote = (itemId, val) => {
      const insp = state.inspections.find(i => i.id === state.activeInspectionId);
      if (!insp) return;
      const item = insp.items.find(x => x.id === itemId);
      if (item) item.note = val;
    };

    window.submitInspection = () => {
      const insp = state.inspections.find(i => i.id === state.activeInspectionId);
      if (!insp) return;
      insp.items.forEach(i => { if (i.result === 'UNANSWERED') i.result = 'PASS'; });
      insp.completedAt = Date.now();
      insp.items.filter(i => i.result === 'FAIL').forEach(failedItem => {
        state.actions.unshift({
          id: Date.now() + Math.floor(Math.random() * 1000),
          inspectionId: insp.id,
          equipmentCode: insp.equipmentCode,
          equipmentName: insp.equipmentName,
          question: failedItem.question,
          description: failedItem.note || 'Failed during inspection check',
          priority: failedItem.critical ? 'CRITICAL' : 'MEDIUM',
          status: 'OPEN',
          responsibleName: state.user.displayName,
          dueAt: Date.now() + (failedItem.critical ? 1 : 7) * 86400000,
          events: [
            { toStatus: 'OPEN', byName: state.user.displayName, at: Date.now(), note: 'Created automatically on inspection submission' }
          ]
        });
      });
      state.activeReportId = insp.id;
      state.tab = 'report';
      render();
    };

    window.advanceAction = (actionId, nextStatus) => {
      const act = state.actions.find(a => a.id === actionId);
      if (!act) return;
      act.status = nextStatus;
      act.events.push({
        toStatus: nextStatus,
        byName: state.user.displayName,
        at: Date.now(),
        note: nextStatus === 'IN_PROGRESS' ? 'Work started on corrective action' :
              nextStatus === 'RESOLVED' ? 'Fix completed and ready for verification' :
              'Verified and closed by Admin'
      });
      render();
    };

    window.openReport = (inspId) => {
      const insp = state.inspections.find(i => i.id === inspId);
      if (!insp) return;
      if (!insp.completedAt) {
        state.activeInspectionId = insp.id;
        state.tab = 'inspection';
      } else {
        state.activeReportId = insp.id;
        state.tab = 'report';
      }
      render();
    };

    window.saveNewEquipment = () => {
      const code = (document.getElementById('eq-code')?.value || '').trim().toUpperCase();
      const name = (document.getElementById('eq-name')?.value || '').trim();
      const type = document.getElementById('eq-type')?.value || 'FIRE_EXTINGUISHER';
      const area = (document.getElementById('eq-area')?.value || 'Floor 1 · General').trim();
      if (!code || !name) return;
      const newCp = {
        id: Date.now(),
        code,
        name,
        type,
        building: 'Dhaka Plant 1 › Main Building',
        area,
        spot: 'Marked station'
      };
      state.checkpoints.unshift(newCp);
      window.startInspection(newCp.id);
    };

    function renderTopBar() {
      return \`
        <header class="bg-[#0B6E69] text-white px-4 py-3 flex items-center justify-between sticky top-0 z-20 shadow-sm">
          <div>
            <div class="font-semibold text-lg leading-tight tracking-tight flex items-center gap-2">
              <span>SafeStitch</span>
              <span class="text-[11px] font-medium bg-[#A6F0E8] text-[#00201E] px-2 py-0.5 rounded">v1.0.0</span>
            </div>
            <div class="text-xs text-[#CCE8E4]" data-testid="signed-in-as">\${state.user.displayName} · \${state.user.roleLabel}</div>
          </div>
          <div class="flex items-center gap-1.5 relative">
            <a href="/download-apk" class="text-xs bg-white/15 hover:bg-white/25 text-white px-2.5 py-1.5 rounded-lg font-medium transition">
              APK
            </a>
            <button onclick="toggleMenu()" class="p-1.5 rounded-full hover:bg-white/15 transition" title="Account menu">
              <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
              </svg>
            </button>
            \${state.menuOpen ? \`
              <div class="absolute right-0 top-11 w-48 bg-white text-[#171D1C] rounded-xl shadow-lg border border-gray-200 py-1 z-30">
                <button onclick="setTab('admin')" class="w-full text-left px-4 py-2 text-sm hover:bg-gray-100 flex items-center justify-between">
                  <span>Admin tools</span>
                  <span class="text-xs text-[#0B6E69] font-medium">Open</span>
                </button>
                <button onclick="toggleRole()" class="w-full text-left px-4 py-2 text-sm hover:bg-gray-100">
                  Switch to \${state.user.role === 'ADMIN' ? 'Inspector' : 'Admin'}
                </button>
              </div>
            \` : ''}
          </div>
        </header>
      \`;
    }

    const STATUS_TAGS = ['NEEDS_REVIEW', 'PASS', 'FAIL', 'FOLLOW_UP'];

    window.toggleViewfinder = () => {
      state.showViewfinder = state.showViewfinder === false ? true : false;
      render();
      if (state.showViewfinder !== false) initWebCamera();
    };

    window.setCaptureTag = (tag) => {
      state.selectedCaptureTag = tag;
      render();
    };

    window.setCaptureFilterTag = (tag) => {
      state.activeFilterTag = state.activeFilterTag === tag ? null : tag;
      render();
    };

    window.togglePhotoTag = (photoId) => {
      state.capturedPhotos = state.capturedPhotos || [];
      const item = state.capturedPhotos.find(p => p.id === photoId);
      if (!item) return;
      const tag = state.selectedCaptureTag || 'NEEDS_REVIEW';
      item.statusTags = item.statusTags || ['NEEDS_REVIEW'];
      if (item.statusTags.includes(tag)) {
        item.statusTags = item.statusTags.filter(t => t !== tag);
        if (!item.statusTags.length) item.statusTags = ['NEEDS_REVIEW'];
      } else {
        item.statusTags.push(tag);
      }
      render();
    };

    window.captureViewfinderPhoto = () => {
      state.capturedPhotos = state.capturedPhotos || [];
      const activeTag = state.selectedCaptureTag || 'NEEDS_REVIEW';
      const video = document.getElementById('live-viewfinder-video');
      const canvas = document.createElement('canvas');
      canvas.width = 320;
      canvas.height = 200;
      const ctx = canvas.getContext('2d');
      if (video && video.readyState >= 2) {
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
      } else {
        ctx.fillStyle = '#0F1514';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.strokeStyle = '#A6F0E8';
        ctx.lineWidth = 3;
        ctx.strokeRect(36, 28, 248, 144);
        ctx.fillStyle = '#A6F0E8';
        ctx.font = 'bold 13px sans-serif';
        ctx.fillText('FRAME #' + (state.capturedPhotos.length + 1) + ' · ' + activeTag, 75, 96);
        ctx.fillStyle = '#CCE8E4';
        ctx.font = '11px monospace';
        ctx.fillText(new Date().toLocaleTimeString(), 118, 116);
      }
      const ts = Date.now();
      state.capturedPhotos.unshift({
        id: ts,
        timestamp: ts,
        imagePath: '/data/user/0/com.safestitch.inspection.debug/files/photos/IMG_' + ts + '.jpg',
        statusTags: [activeTag],
        dataUrl: canvas.toDataURL('image/jpeg', 0.85),
        at: ts
      });
      render();
    };

    async function initWebCamera() {
      const video = document.getElementById('live-viewfinder-video');
      if (!video || !navigator.mediaDevices?.getUserMedia) return;
      try {
        if (!window.__activeStream) {
          window.__activeStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false });
        }
        video.srcObject = window.__activeStream;
      } catch (_) {
        // Fallback simulated viewfinder remains visible when camera permission is denied in iframe
      }
    }

    function renderHome() {
      const completed = state.inspections.filter(i => i.completedAt);
      const drafts = state.inspections.filter(i => !i.completedAt);
      const summaries = completed.map(computeSummary);
      const passedCount = summaries.filter(s => s.outcome === 'PASS').length;
      const failedCount = summaries.filter(s => s.outcome === 'FAIL').length;
      const passRate = completed.length ? Math.round((passedCount / completed.length) * 100) : 0;
      const avgScore = completed.length ? Math.round(summaries.reduce((a, b) => a + b.scorePercent, 0) / completed.length) : 0;

      const openActions = state.actions.filter(a => a.status === 'OPEN').length;
      const inProgressActions = state.actions.filter(a => a.status === 'IN_PROGRESS').length;
      const awaitingVerification = state.actions.filter(a => a.status === 'RESOLVED').length;
      const criticalOpen = state.actions.filter(a => a.status !== 'VERIFIED' && (a.priority === 'CRITICAL' || a.priority === 'HIGH')).length;
      const showVf = state.showViewfinder !== false;
      const selectedCaptureTag = state.selectedCaptureTag || 'NEEDS_REVIEW';
      const activeFilterTag = state.activeFilterTag || null;
      const photos = state.capturedPhotos || [];
      const filteredPhotos = activeFilterTag
        ? photos.filter(p => (p.statusTags || []).includes(activeFilterTag))
        : photos;

      setTimeout(initWebCamera, 50);

      return \`
        <div class="p-4 space-y-3 flex-1 overflow-y-auto">
          <div class="bg-white rounded-2xl p-3 border border-[#DAE5E2] space-y-2.5 shadow-sm" data-testid="main-camera-card">
            <div class="flex items-center justify-between gap-2">
              <div>
                <div class="text-sm font-semibold text-[#171D1C]">Live Inspection Viewfinder</div>
                <div class="text-xs text-[#3F4947]">Frame safety equipment, gauges, or QR tags in real time</div>
              </div>
              <button onclick="toggleViewfinder()" class="text-xs border border-[#0B6E69] text-[#0B6E69] px-2.5 py-1 rounded-lg font-medium">
                \${showVf ? 'Hide' : 'Show'}
              </button>
            </div>
            \${showVf ? \`
              <div class="flex items-center gap-1.5 overflow-x-auto pb-0.5">
                <span class="text-[11px] text-[#3F4947] font-medium shrink-0">Capture tag:</span>
                \${STATUS_TAGS.map(tag => \`
                  <button
                    onclick="setCaptureTag('\${tag}')"
                    data-testid="capture-tag-\${tag}"
                    class="text-[11px] px-2.5 py-0.5 rounded-lg font-medium whitespace-nowrap transition \${selectedCaptureTag === tag ? 'bg-[#0B6E69] text-white' : 'bg-[#DAE5E2] text-[#3F4947]'}"
                  >
                    \${tag.replace('_', ' ')}
                  </button>
                \`).join('')}
              </div>
              <div class="relative w-full aspect-[16/10] rounded-xl overflow-hidden bg-[#0F1514] flex items-center justify-center" data-testid="main-camera-preview">
                <video id="live-viewfinder-video" autoplay playsinline muted class="absolute inset-0 w-full h-full object-cover opacity-85"></video>
                <div class="relative z-10 w-[76%] h-[68%] rounded-2xl border border-white/30 flex items-center justify-center pointer-events-none">
                  <div class="absolute -top-0.5 -left-0.5 w-6 h-6 border-t-4 border-l-4 border-[#A6F0E8] rounded-tl-lg"></div>
                  <div class="absolute -top-0.5 -right-0.5 w-6 h-6 border-t-4 border-r-4 border-[#A6F0E8] rounded-tr-lg"></div>
                  <div class="absolute -bottom-0.5 -left-0.5 w-6 h-6 border-b-4 border-l-4 border-[#A6F0E8] rounded-bl-lg"></div>
                  <div class="absolute -bottom-0.5 -right-0.5 w-6 h-6 border-b-4 border-r-4 border-[#A6F0E8] rounded-br-lg"></div>
                  <div class="w-4 h-4 relative">
                    <div class="absolute inset-x-0 top-1/2 h-[1.5px] bg-white/60"></div>
                    <div class="absolute inset-y-0 left-1/2 w-[1.5px] bg-white/60"></div>
                  </div>
                </div>
                <div class="absolute bottom-3 left-3 right-16 flex items-center z-10 pointer-events-none">
                  <span class="bg-black/60 text-white text-[11px] px-3 py-1 rounded-full truncate">
                    Align inspection item inside framing brackets
                  </span>
                </div>
                <button
                  onclick="captureViewfinderPhoto()"
                  data-testid="viewfinder-capture-fab"
                  title="Capture inspection photo"
                  class="absolute bottom-3 right-3 z-20 w-12 h-12 rounded-2xl bg-[#0B6E69] hover:bg-[#095955] active:scale-95 text-white shadow-lg flex items-center justify-center transition"
                >
                  <svg class="w-6 h-6" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z"/>
                    <path stroke-linecap="round" stroke-linejoin="round" d="M15 13a3 3 0 11-6 0 3 3 0 016 0z"/>
                  </svg>
                </button>
              </div>
            \` : ''}
            \${photos.length ? \`
              <div class="space-y-1.5 pt-1">
                <div class="flex items-center gap-1.5 overflow-x-auto pb-0.5">
                  <span class="text-xs font-medium text-[#3F4947] shrink-0">Captured frames (\${filteredPhotos.length}/\${photos.length}):</span>
                  <button
                    onclick="state.activeFilterTag = null; render();"
                    class="text-[11px] px-2 py-0.5 rounded-lg font-medium \${!activeFilterTag ? 'bg-[#0B6E69] text-white' : 'bg-[#DAE5E2] text-[#3F4947]'}"
                  >All</button>
                  \${STATUS_TAGS.map(tag => \`
                    <button
                      onclick="setCaptureFilterTag('\${tag}')"
                      class="text-[11px] px-2 py-0.5 rounded-lg font-medium whitespace-nowrap \${activeFilterTag === tag ? 'bg-[#0B6E69] text-white' : 'bg-[#DAE5E2] text-[#3F4947]'}"
                    >\${tag.replace('_', ' ')}</button>
                  \`).join('')}
                </div>
                <div class="flex gap-2.5 overflow-x-auto pb-1">
                  \${filteredPhotos.map(p => \`
                    <div onclick="togglePhotoTag(\${p.id})" class="cursor-pointer shrink-0 space-y-0.5">
                      <img src="\${p.dataUrl}" alt="Captured inspection frame" class="w-24 h-16 object-cover rounded-lg border border-[#DAE5E2]" />
                      <div class="text-[10px] font-bold text-[#0B6E69]">\${(p.statusTags || ['NEEDS_REVIEW']).join(' · ')}</div>
                      <div class="text-[10px] text-[#3F4947]">\${formatDate(p.timestamp || p.at)}</div>
                    </div>
                  \`).join('')}
                </div>
              </div>
            \` : ''}
            <button
              onclick="setTab('captured')"
              data-testid="open-captured-inspections"
              class="w-full border border-[#0B6E69] text-[#0B6E69] hover:bg-[#A6F0E8]/30 text-xs font-semibold py-2 px-3 rounded-xl transition flex items-center justify-center gap-1.5"
            >
              <span>View all captured inspections (\${photos.length})</span>
            </button>
          </div>

          <div class="grid grid-cols-2 gap-3">
            <button onclick="setTab('scan')" class="bg-[#0B6E69] hover:bg-[#095955] text-white font-medium py-2.5 px-4 rounded-xl flex items-center justify-center gap-2 shadow-sm transition">
              <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M3 7V5a2 2 0 012-2h2m10 0h2a2 2 0 012 2v2m0 10v2a2 2 0 01-2 2h-2M7 21H5a2 2 0 01-2-2v-2m4-10h2v2H7V7zm8 0h2v2h-2V7zm-8 8h2v2H7v-2zm8 0h2v2h-2v-2z"/></svg>
              <span>Scan QR</span>
            </button>
            <button onclick="setTab('inspect')" class="border border-[#0B6E69] text-[#0B6E69] hover:bg-[#A6F0E8]/30 font-medium py-2.5 px-4 rounded-xl flex items-center justify-center gap-2 transition">
              <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/></svg>
              <span>Find equipment</span>
            </button>
          </div>

          <h2 class="text-sm font-semibold text-[#3F4947] pt-1">Inspections · last 30 days</h2>
          <div class="grid grid-cols-2 gap-3">
            <div class="bg-[#DAE5E2]/60 rounded-xl p-3.5">
              <div class="text-2xl font-bold text-[#171D1C]">\${completed.length}</div>
              <div class="text-xs text-[#3F4947]">Completed</div>
            </div>
            <div class="bg-[#DAE5E2]/60 rounded-xl p-3.5">
              <div class="text-2xl font-bold text-[#1B7F3B]">\${passRate}%</div>
              <div class="text-xs text-[#3F4947]">Pass rate</div>
            </div>
            <div class="bg-[#DAE5E2]/60 rounded-xl p-3.5">
              <div class="text-2xl font-bold text-[#171D1C]">\${avgScore}%</div>
              <div class="text-xs text-[#3F4947]">Average score</div>
            </div>
            <div class="bg-[#DAE5E2]/60 rounded-xl p-3.5">
              <div class="text-2xl font-bold text-[#8A5100]">\${drafts.length}</div>
              <div class="text-xs text-[#3F4947]">Drafts in progress</div>
            </div>
            <div class="bg-[#DAE5E2]/60 rounded-xl p-3.5">
              <div class="text-2xl font-bold text-[#171D1C]">\${state.inspections.length}</div>
              <div class="text-xs text-[#3F4947]">Inspections (all time)</div>
            </div>
            <div class="bg-[#DAE5E2]/60 rounded-xl p-3.5">
              <div class="text-2xl font-bold text-[#B3261E]">\${failedCount}</div>
              <div class="text-xs text-[#3F4947]">Failed · 30 days</div>
            </div>
          </div>

          <h2 class="text-sm font-semibold text-[#3F4947] pt-1">Corrective actions</h2>
          <div class="grid grid-cols-2 gap-3">
            <button onclick="setTab('actions')" class="bg-[#DAE5E2]/60 hover:bg-[#DAE5E2] text-left rounded-xl p-3.5 transition">
              <div class="text-2xl font-bold text-[#B3261E]">\${openActions}</div>
              <div class="text-xs text-[#3F4947]">Open</div>
            </button>
            <button onclick="setTab('actions')" class="bg-[#DAE5E2]/60 hover:bg-[#DAE5E2] text-left rounded-xl p-3.5 transition">
              <div class="text-2xl font-bold text-[#8A5100]">\${inProgressActions}</div>
              <div class="text-xs text-[#3F4947]">In progress</div>
            </button>
            <button onclick="setTab('actions')" class="bg-[#DAE5E2]/60 hover:bg-[#DAE5E2] text-left rounded-xl p-3.5 transition">
              <div class="text-2xl font-bold text-[#0B6E69]">\${awaitingVerification}</div>
              <div class="text-xs text-[#3F4947]">Awaiting verification</div>
            </button>
            <button onclick="setTab('actions')" class="bg-[#DAE5E2]/60 hover:bg-[#DAE5E2] text-left rounded-xl p-3.5 transition">
              <div class="text-2xl font-bold text-[#B3261E]">\${criticalOpen}</div>
              <div class="text-xs text-[#3F4947]">Critical / high open</div>
            </button>
          </div>

          <h2 class="text-sm font-semibold text-[#3F4947] pt-1">Pass rate by building</h2>
          <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2] space-y-2">
            <div class="flex justify-between text-xs font-medium">
              <span>Dhaka Plant 1 › Main Building</span>
              <span>\${passRate}% (\${passedCount}/\${completed.length})</span>
            </div>
            <div class="w-full h-2 bg-[#DAE5E2] rounded-full overflow-hidden">
              <div class="h-full bg-[#0B6E69]" style="width: \${passRate}%"></div>
            </div>
          </div>

          <div class="bg-[#CCE8E4] text-[#051F1D] rounded-xl p-3.5 space-y-1">
            <div class="text-sm font-semibold">Works offline</div>
            <div class="text-xs leading-relaxed">Everything is stored on-device with Room SQLite. Optional WorkManager background sync can be configured under Admin › Sync.</div>
          </div>
        </div>
      \`;
    }

    function renderInspect() {
      const list = state.checkpoints.filter(c => {
        const matchesType = state.selectedTypeFilter === 'ALL' || c.type === state.selectedTypeFilter;
        const q = state.searchQuery.toLowerCase();
        const matchesQuery = !q || c.code.toLowerCase().includes(q) || c.name.toLowerCase().includes(q) || c.area.toLowerCase().includes(q);
        return matchesType && matchesQuery;
      });

      return \`
        <div class="p-4 space-y-3 flex-1 overflow-y-auto">
          <div class="flex items-center justify-between">
            <h2 class="text-base font-semibold">Find Equipment</h2>
            <button onclick="setTab('newEquipment')" class="text-xs bg-[#0B6E69] text-white px-3 py-1.5 rounded-lg font-medium">+ Add equipment</button>
          </div>
          <input
            type="text"
            placeholder="Search code, name, or floor area..."
            value="\${state.searchQuery}"
            oninput="state.searchQuery = this.value; render();"
            class="w-full px-3.5 py-2 text-sm rounded-xl border border-[#BEC9C6] bg-white focus:outline-none focus:border-[#0B6E69]"
          />
          <div class="flex gap-1.5 overflow-x-auto pb-1">
            \${['ALL', ...Object.keys(TYPE_LABELS)].map(t => \`
              <button
                onclick="state.selectedTypeFilter = '\${t}'; render();"
                class="text-xs px-3 py-1 rounded-lg whitespace-nowrap font-medium \${state.selectedTypeFilter === t ? 'bg-[#0B6E69] text-white' : 'bg-[#DAE5E2] text-[#3F4947]'}"
              >
                \${t === 'ALL' ? 'All types' : TYPE_LABELS[t]}
              </button>
            \`).join('')}
          </div>
          <div class="space-y-2.5">
            \${list.map(cp => \`
              <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2] flex items-center justify-between gap-3">
                <div class="space-y-0.5">
                  <div class="flex items-center gap-2">
                    <span class="text-xs font-mono font-bold bg-[#A6F0E8] text-[#00201E] px-2 py-0.5 rounded">\${cp.code}</span>
                    <span class="text-xs text-[#4A6360] font-medium">\${TYPE_LABELS[cp.type]}</span>
                  </div>
                  <div class="text-sm font-semibold text-[#171D1C]">\${cp.name}</div>
                  <div class="text-xs text-[#3F4947]">\${cp.building} · \${cp.area}</div>
                </div>
                <button onclick="startInspection(\${cp.id})" class="bg-[#0B6E69] hover:bg-[#095955] text-white text-xs font-semibold px-3.5 py-2 rounded-lg shrink-0">
                  Inspect
                </button>
              </div>
            \`).join('')}
          </div>
        </div>
      \`;
    }

    function renderScan() {
      return \`
        <div class="p-4 space-y-4 flex-1 overflow-y-auto">
          <div class="flex items-center justify-between">
            <h2 class="text-base font-semibold">Scan Equipment QR Tag</h2>
            <button onclick="setTab('home')" class="text-xs text-[#0B6E69] font-medium">Cancel</button>
          </div>
          <div class="bg-[#0F1514] text-white rounded-2xl p-6 text-center space-y-3 border-2 border-dashed border-[#8AD4CC]/60">
            <div class="w-16 h-16 rounded-2xl bg-[#00504B] mx-auto flex items-center justify-center">
              <svg class="w-9 h-9 text-[#A6F0E8]" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M3 7V5a2 2 0 012-2h2m10 0h2a2 2 0 012 2v2m0 10v2a2 2 0 01-2 2h-2M7 21H5a2 2 0 01-2-2v-2"/></svg>
            </div>
            <div class="text-sm font-medium">ZXing Offline QR Scanner</div>
            <div class="text-xs text-[#BEC9C6]">Select a sample checkpoint tag below or type any equipment code to launch its inspection checklist.</div>
            <div class="flex flex-wrap gap-1.5 justify-center pt-1">
              \${state.checkpoints.map(cp => \`
                <button onclick="document.getElementById('qr-input').value='\${cp.code}'; scanCode();" class="text-xs bg-[#00504B] hover:bg-[#0B6E69] text-[#A6F0E8] px-2.5 py-1 rounded font-mono">
                  \${cp.code}
                </button>
              \`).join('')}
            </div>
          </div>
          <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2] space-y-2.5">
            <label class="block text-xs font-semibold text-[#3F4947]">Manual QR / Equipment Code</label>
            <div class="flex gap-2">
              <input id="qr-input" type="text" value="\${state.qrInput}" class="flex-1 px-3 py-2 text-sm border border-[#BEC9C6] rounded-lg font-mono uppercase" placeholder="e.g. FE-101" />
              <button onclick="scanCode()" class="bg-[#0B6E69] text-white text-xs font-semibold px-4 py-2 rounded-lg">Open</button>
            </div>
          </div>
        </div>
      \`;
    }

    function renderInspection() {
      const insp = state.inspections.find(i => i.id === state.activeInspectionId);
      if (!insp) return renderHome();
      const answeredCount = insp.items.filter(i => i.result !== 'UNANSWERED').length;

      return \`
        <div class="p-4 space-y-3 flex-1 overflow-y-auto">
          <div class="flex items-center justify-between">
            <div>
              <span class="text-xs font-mono font-bold bg-[#A6F0E8] text-[#00201E] px-2 py-0.5 rounded">\${insp.equipmentCode}</span>
              <h2 class="text-base font-semibold mt-1">\${insp.equipmentName}</h2>
              <p class="text-xs text-[#3F4947]">\${TYPE_LABELS[insp.equipmentType]} · \${answeredCount}/\${insp.items.length} answered</p>
            </div>
            <button onclick="markAllPass()" class="text-xs border border-[#0B6E69] text-[#0B6E69] px-2.5 py-1.5 rounded-lg font-medium">
              Pass remaining
            </button>
          </div>

          <div class="space-y-2.5">
            \${insp.items.map(item => \`
              <div class="bg-white rounded-xl p-3.5 border \${item.result === 'FAIL' ? 'border-[#B3261E]' : 'border-[#DAE5E2]'} space-y-2.5">
                <div class="flex items-start justify-between gap-2">
                  <div class="text-sm font-medium text-[#171D1C] leading-snug">\${item.question}</div>
                  \${item.critical ? '<span class="text-[10px] font-bold uppercase bg-[#FFDDB8] text-[#2C1600] px-1.5 py-0.5 rounded shrink-0">Critical</span>' : ''}
                </div>
                <div class="grid grid-cols-3 gap-2">
                  <button onclick="setAnswer(\${item.id}, 'PASS')" class="py-1.5 rounded-lg text-xs font-semibold border \${item.result === 'PASS' ? 'bg-[#1B7F3B] text-white border-[#1B7F3B]' : 'border-[#BEC9C6] text-[#3F4947]'}">PASS</button>
                  <button onclick="setAnswer(\${item.id}, 'FAIL')" class="py-1.5 rounded-lg text-xs font-semibold border \${item.result === 'FAIL' ? 'bg-[#B3261E] text-white border-[#B3261E]' : 'border-[#BEC9C6] text-[#3F4947]'}">FAIL</button>
                  <button onclick="setAnswer(\${item.id}, 'NOT_APPLICABLE')" class="py-1.5 rounded-lg text-xs font-semibold border \${item.result === 'NOT_APPLICABLE' ? 'bg-[#5F6B69] text-white border-[#5F6B69]' : 'border-[#BEC9C6] text-[#3F4947]'}">N/A</button>
                </div>
                \${item.result === 'FAIL' ? \`
                  <input
                    type="text"
                    placeholder="Describe finding / defect (creates corrective action)..."
                    value="\${item.note || ''}"
                    oninput="updateItemNote(\${item.id}, this.value)"
                    class="w-full px-3 py-1.5 text-xs border border-[#B3261E]/50 rounded-lg bg-[#FFF8F7]"
                  />
                \` : ''}
              </div>
            \`).join('')}
          </div>

          <button onclick="submitInspection()" class="w-full bg-[#0B6E69] hover:bg-[#095955] text-white font-semibold py-3 rounded-xl shadow-sm transition">
            Submit Inspection & Generate Report
          </button>
        </div>
      \`;
    }

    function renderActions() {
      const filtered = state.actions.filter(a => state.actionFilter === 'ALL' || a.status === state.actionFilter);
      return \`
        <div class="p-4 space-y-3 flex-1 overflow-y-auto">
          <h2 class="text-base font-semibold">Corrective Actions</h2>
          <div class="flex gap-1.5 overflow-x-auto pb-1">
            \${['ALL', 'OPEN', 'IN_PROGRESS', 'RESOLVED', 'VERIFIED'].map(st => \`
              <button
                onclick="state.actionFilter = '\${st}'; render();"
                class="text-xs px-3 py-1 rounded-lg whitespace-nowrap font-medium \${state.actionFilter === st ? 'bg-[#0B6E69] text-white' : 'bg-[#DAE5E2] text-[#3F4947]'}"
              >
                \${st.replace('_', ' ')}
              </button>
            \`).join('')}
          </div>
          <div class="space-y-2.5">
            \${filtered.map(act => \`
              <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2] space-y-2">
                <div class="flex items-center justify-between">
                  <span class="text-xs font-mono font-bold bg-[#A6F0E8] text-[#00201E] px-2 py-0.5 rounded">\${act.equipmentCode}</span>
                  <span class="text-[11px] font-bold px-2 py-0.5 rounded \${act.priority === 'CRITICAL' ? 'bg-[#B3261E] text-white' : 'bg-[#FFDDB8] text-[#2C1600]'}">\${act.priority}</span>
                </div>
                <div class="text-sm font-semibold text-[#171D1C]">\${act.question}</div>
                <div class="text-xs text-[#3F4947]">Finding: \${act.description}</div>
                <div class="text-xs text-[#4A6360] flex justify-between">
                  <span>Assigned: \${act.responsibleName}</span>
                  <span class="font-medium">\${act.status.replace('_', ' ')}</span>
                </div>
                <div class="pt-1 flex gap-2">
                  \${act.status === 'OPEN' ? \`
                    <button onclick="advanceAction(\${act.id}, 'IN_PROGRESS')" class="text-xs bg-[#0B6E69] text-white px-3 py-1.5 rounded-lg font-medium">Start work</button>
                  \` : ''}
                  \${act.status === 'IN_PROGRESS' ? \`
                    <button onclick="advanceAction(\${act.id}, 'RESOLVED')" class="text-xs bg-[#0B6E69] text-white px-3 py-1.5 rounded-lg font-medium">Mark resolved</button>
                  \` : ''}
                  \${act.status === 'RESOLVED' && state.user.role === 'ADMIN' ? \`
                    <button onclick="advanceAction(\${act.id}, 'VERIFIED')" class="text-xs bg-[#1B7F3B] text-white px-3 py-1.5 rounded-lg font-medium">Verify & Close (Admin)</button>
                  \` : ''}
                </div>
              </div>
            \`).join('')}
          </div>
        </div>
      \`;
    }

    function renderHistory() {
      const photos = state.capturedPhotos || [];
      return \`
        <div class="p-4 space-y-3 flex-1 overflow-y-auto">
          <div class="flex items-center justify-between">
            <h2 class="text-base font-semibold">Inspection History</h2>
            <button onclick="setTab('captured')" class="text-xs text-[#0B6E69] font-semibold">Captured (\${photos.length})</button>
          </div>
          <div onclick="setTab('captured')" class="cursor-pointer bg-white hover:bg-gray-50 rounded-xl p-3.5 border border-[#0B6E69]/40 flex items-center justify-between gap-3">
            <div>
              <div class="text-sm font-semibold text-[#171D1C]">Captured Inspection Photos (\${photos.length})</div>
              <div class="text-xs text-[#3F4947]">Tap any captured photo below to open Inspection Details (full-res image & tag editor) or view all</div>
            </div>
            <span class="text-xs font-semibold text-[#0B6E69] shrink-0">Open →</span>
          </div>
          \${photos.length ? \`
            <div class="flex gap-2.5 overflow-x-auto pb-1" data-testid="history-captured-strip">
              \${photos.map(item => {
                const primaryTag = (item.statusTags && item.statusTags[0]) || 'NEEDS_REVIEW';
                return \`
                  <div onclick="openCapturedDetail(\${item.id})" class="cursor-pointer bg-white hover:bg-gray-50 rounded-xl p-2.5 border border-[#DAE5E2] w-48 shrink-0 space-y-1">
                    <div class="flex items-center gap-2">
                      <img src="\${item.dataUrl}" alt="Captured thumb" class="w-10 h-10 rounded-lg object-cover border border-[#DAE5E2]" />
                      <div class="min-w-0 flex-1">
                        <div class="text-xs font-semibold text-[#171D1C] truncate">\${formatDate(item.timestamp || item.at)}</div>
                        <div class="text-[10px] font-mono text-[#3F4947] truncate">\${(item.imagePath || '').split('/').pop()}</div>
                      </div>
                    </div>
                    <span class="inline-block text-[10px] font-semibold px-2 py-0.5 rounded-full bg-[#A6F0E8] text-[#00201E]">\${primaryTag.replace(/_/g, ' ')}</span>
                  </div>
                \`;
              }).join('')}
            </div>
          \` : ''}
          <div class="space-y-2.5">
            \${state.inspections.map(insp => {
              const sum = computeSummary(insp);
              const badgeColor = sum.outcome === 'PASS' ? 'bg-[#1B7F3B] text-white' :
                                 sum.outcome === 'FAIL' ? 'bg-[#B3261E] text-white' : 'bg-[#8A5100] text-white';
              return \`
                <div onclick="openReport(\${insp.id})" class="cursor-pointer bg-white hover:bg-gray-50 rounded-xl p-3.5 border border-[#DAE5E2] flex items-center justify-between">
                  <div class="space-y-0.5">
                    <div class="flex items-center gap-2">
                      <span class="text-xs font-mono font-bold bg-[#A6F0E8] text-[#00201E] px-2 py-0.5 rounded">\${insp.equipmentCode}</span>
                      <span class="text-sm font-semibold">\${insp.equipmentName}</span>
                    </div>
                    <div class="text-xs text-[#3F4947]">By \${insp.inspectorName} · \${formatDate(insp.completedAt || insp.startedAt)}</div>
                  </div>
                  <div class="text-right">
                    <span class="text-xs font-bold px-2.5 py-1 rounded \${badgeColor}">\${sum.outcome}</span>
                    <div class="text-xs text-[#3F4947] mt-1">Score: \${sum.scorePercent}%</div>
                  </div>
                </div>
              \`;
            }).join('')}
          </div>
        </div>
      \`;
    }

    window.openCapturedDetail = (photoId) => {
      state.activeCapturedId = photoId;
      state.detailZoom = 1.0;
      state.customTagDraft = '';
      state.tab = 'capturedDetail';
      render();
    };

    window.adjustDetailZoom = (delta) => {
      if (delta === 0) {
        state.detailZoom = 1.0;
      } else {
        state.detailZoom = Math.min(4.0, Math.max(1.0, (state.detailZoom || 1.0) + delta));
      }
      render();
    };

    window.toggleTagForCaptured = (photoId, tag) => {
      const item = (state.capturedPhotos || []).find(p => p.id === photoId);
      if (!item) return;
      item.statusTags = item.statusTags || ['NEEDS_REVIEW'];
      if (item.statusTags.includes(tag)) {
        item.statusTags = item.statusTags.filter(t => t !== tag);
        if (!item.statusTags.length) item.statusTags = ['NEEDS_REVIEW'];
      } else {
        item.statusTags.push(tag);
      }
      render();
    };

    window.addCustomTagToCaptured = (photoId) => {
      const inputEl = document.getElementById('custom-tag-input');
      const raw = (inputEl ? inputEl.value : state.customTagDraft || '').trim();
      if (!raw) return;
      const normalized = raw.replace(/\\s+/g, '_').replace(/,/g, '').toUpperCase();
      if (!normalized) return;
      const item = (state.capturedPhotos || []).find(p => p.id === photoId);
      if (!item) return;
      item.statusTags = item.statusTags || ['NEEDS_REVIEW'];
      if (!item.statusTags.includes(normalized)) {
        item.statusTags.push(normalized);
      }
      state.customTagDraft = '';
      render();
    };

    window.removeTagFromCaptured = (photoId, tag) => {
      const item = (state.capturedPhotos || []).find(p => p.id === photoId);
      if (!item) return;
      item.statusTags = (item.statusTags || []).filter(t => t !== tag);
      if (!item.statusTags.length) item.statusTags = ['NEEDS_REVIEW'];
      render();
    };

    window.deleteCapturedPhoto = (photoId, fromDetail = false) => {
      state.capturedPhotos = (state.capturedPhotos || []).filter(p => p.id !== photoId);
      if (fromDetail) {
        state.tab = 'captured';
      }
      render();
    };

    function renderCapturedInspections() {
      const all = state.capturedPhotos || [];
      const activeTag = state.activeFilterTag || null;
      const shown = activeTag ? all.filter(p => (p.statusTags || []).includes(activeTag)) : all;

      return \`
        <div class="p-4 space-y-3 flex-1 overflow-y-auto" data-testid="captured-inspections-list">
          <div class="flex items-center justify-between">
            <div>
              <h2 class="text-base font-semibold">Captured Inspections</h2>
              <div class="text-xs text-[#3F4947]" data-testid="captured-inspections-count">\${shown.length} of \${all.length} captured photos</div>
            </div>
            <button onclick="setTab('home')" class="text-xs text-[#0B6E69] font-semibold">← Back</button>
          </div>
          <div class="flex gap-1.5 overflow-x-auto pb-1">
            <button
              onclick="state.activeFilterTag = null; render();"
              class="text-xs px-3 py-1 rounded-lg whitespace-nowrap font-medium \${!activeTag ? 'bg-[#0B6E69] text-white' : 'bg-[#DAE5E2] text-[#3F4947]'}"
            >
              All (\${all.length})
            </button>
            \${STATUS_TAGS.map(tag => {
              const count = all.filter(p => (p.statusTags || []).includes(tag)).length;
              return \`
                <button
                  onclick="setCaptureFilterTag('\${tag}')"
                  class="text-xs px-3 py-1 rounded-lg whitespace-nowrap font-medium \${activeTag === tag ? 'bg-[#0B6E69] text-white' : 'bg-[#DAE5E2] text-[#3F4947]'}"
                >
                  \${tag.replace('_', ' ')} (\${count})
                </button>
              \`;
            }).join('')}
          </div>
          <div class="space-y-2.5">
            \${shown.map(item => \`
              <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2] space-y-2.5 hover:border-[#0B6E69] transition" data-testid="captured-inspection-\${item.id}">
                <div class="flex items-center gap-3 cursor-pointer" onclick="openCapturedDetail(\${item.id})">
                  <img src="\${item.dataUrl}" alt="Inspection thumbnail" class="w-20 h-20 rounded-xl object-cover border border-[#DAE5E2] shrink-0" />
                  <div class="flex-1 min-w-0 space-y-1">
                    <div class="text-sm font-semibold text-[#171D1C]">\${formatDate(item.timestamp || item.at)}</div>
                    <div class="text-xs text-[#3F4947] font-mono truncate">\${item.imagePath || ('photos/IMG_' + item.id + '.jpg')}</div>
                    <div class="flex flex-wrap gap-1 pt-0.5">
                      \${(item.statusTags || ['NEEDS_REVIEW']).map(tag => {
                        const chipClass = tag === 'PASS' ? 'bg-[#1B7F3B]/15 text-[#1B7F3B]' :
                                          tag === 'FAIL' ? 'bg-[#B3261E]/15 text-[#B3261E]' :
                                          'bg-[#8A5100]/15 text-[#8A5100]';
                        return \`<span class="text-[11px] font-semibold px-2.5 py-0.5 rounded-full \${chipClass}">\${tag.replace(/_/g, ' ')}</span>\`;
                      }).join('')}
                    </div>
                  </div>
                  <button onclick="event.stopPropagation(); deleteCapturedPhoto(\${item.id})" class="text-xs text-[#3F4947] hover:text-[#B3261E] p-1" title="Delete">✕</button>
                </div>
                <div class="flex items-center justify-between gap-1.5 overflow-x-auto pt-1 border-t border-gray-100">
                  <div class="flex items-center gap-1.5 overflow-x-auto">
                    <span class="text-[11px] text-[#3F4947] shrink-0">Tags:</span>
                    \${STATUS_TAGS.map(tag => {
                      const active = (item.statusTags || []).includes(tag);
                      return \`
                        <button
                          onclick="toggleTagForCaptured(\${item.id}, '\${tag}')"
                          class="text-[11px] px-2.5 py-0.5 rounded-lg font-medium whitespace-nowrap \${active ? 'bg-[#0B6E69] text-white' : 'bg-[#DAE5E2]/70 text-[#3F4947]'}"
                        >
                          \${tag.replace('_', ' ')}
                        </button>
                      \`;
                    }).join('')}
                  </div>
                  <button onclick="openCapturedDetail(\${item.id})" class="text-[11px] text-[#0B6E69] font-semibold shrink-0">Details →</button>
                </div>
              </div>
            \`).join('')}
          </div>
        </div>
      \`;
    }

    function renderCapturedDetail() {
      const item = (state.capturedPhotos || []).find(p => p.id === state.activeCapturedId) || (state.capturedPhotos || [])[0];
      if (!item) {
        return \`
          <div class="p-4 space-y-3 flex-1">
            <div class="text-sm font-semibold">Captured inspection record not found.</div>
            <button onclick="setTab('captured')" class="text-xs bg-[#0B6E69] text-white px-3 py-1.5 rounded-lg">Back to list</button>
          </div>
        \`;
      }
      const zoom = state.detailZoom || 1.0;
      const tags = item.statusTags && item.statusTags.length ? item.statusTags : ['NEEDS_REVIEW'];
      const customTags = tags.filter(t => !STATUS_TAGS.includes(t));
      const fileName = (item.imagePath || ('IMG_' + item.id + '.jpg')).split('/').pop();

      return \`
        <div class="p-4 space-y-3 flex-1 overflow-y-auto" data-testid="inspection-details-screen">
          <div class="flex items-center justify-between">
            <div>
              <h2 class="text-base font-semibold" data-testid="inspection-details-title">Inspection Details</h2>
              <div class="text-xs text-[#3F4947]">Capture #\${item.id} · \${formatDate(item.timestamp || item.at)}</div>
            </div>
            <div class="flex items-center gap-2">
              <button onclick="deleteCapturedPhoto(\${item.id}, true)" data-testid="details-delete-button" class="text-xs text-[#B3261E] border border-[#B3261E]/40 px-2.5 py-1 rounded-lg font-medium">Delete</button>
              <button onclick="setTab('captured')" class="text-xs text-[#0B6E69] font-semibold">← Back</button>
            </div>
          </div>

          <div class="bg-white rounded-xl p-3 border border-[#DAE5E2] space-y-2" data-testid="details-image-card">
            <div class="flex items-center justify-between">
              <span class="text-xs font-semibold text-[#171D1C]" data-testid="details-zoom-label">Full-Resolution Image (\${zoom.toFixed(1)}x)</span>
              <div class="flex items-center gap-1.5">
                <button onclick="adjustDetailZoom(-0.5)" data-testid="details-zoom-out" class="text-xs px-2.5 py-0.5 rounded-lg bg-[#DAE5E2] font-bold">−</button>
                <button onclick="adjustDetailZoom(0.5)" data-testid="details-zoom-in" class="text-xs px-2.5 py-0.5 rounded-lg bg-[#DAE5E2] font-bold">+</button>
                \${zoom > 1.01 ? \`<button onclick="adjustDetailZoom(0)" data-testid="details-zoom-reset" class="text-xs px-2.5 py-0.5 rounded-lg bg-[#0B6E69] text-white font-medium">Reset</button>\` : ''}
              </div>
            </div>
            <div class="w-full h-56 rounded-xl overflow-hidden bg-[#0E1716] flex items-center justify-center">
              <img src="\${item.dataUrl}" alt="Full-resolution inspection photo" style="transform: scale(\${zoom}); transition: transform 0.15s ease;" class="w-full h-full object-contain" />
            </div>
            <div class="text-[11px] text-[#3F4947]">Use + / − to zoom up to 4.0× (pinch-to-zoom supported on Android device).</div>
          </div>

          <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2] space-y-1.5" data-testid="details-metadata-card">
            <div class="text-sm font-semibold text-[#171D1C]">Capture & File Details</div>
            <div class="flex justify-between text-xs"><span class="text-[#3F4947]">Captured at</span><span class="font-medium" data-testid="details-timestamp">\${formatDate(item.timestamp || item.at)}</span></div>
            <div class="flex justify-between text-xs"><span class="text-[#3F4947]">File name</span><span class="font-mono font-medium">\${fileName}</span></div>
            <div class="flex justify-between text-xs gap-3"><span class="text-[#3F4947] shrink-0">Storage path</span><span class="font-mono text-[11px] truncate" data-testid="details-image-path">\${item.imagePath}</span></div>
            <div class="flex justify-between text-xs"><span class="text-[#3F4947]">Resolution</span><span class="font-medium">1920 × 1080 px</span></div>
          </div>

          <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2] space-y-2.5" data-testid="details-tags-card">
            <div>
              <div class="text-sm font-semibold text-[#171D1C]">Status Tags</div>
              <div class="text-xs text-[#3F4947]">Tap preset status chips to toggle tags or add a custom tag below.</div>
            </div>
            <div class="flex flex-wrap gap-1.5">
              \${tags.map(tag => {
                const chipClass = tag === 'PASS' ? 'bg-[#1B7F3B]/15 text-[#1B7F3B]' :
                                  tag === 'FAIL' ? 'bg-[#B3261E]/15 text-[#B3261E]' :
                                  'bg-[#8A5100]/15 text-[#8A5100]';
                return \`<span class="text-xs font-semibold px-2.5 py-0.5 rounded-full \${chipClass}">\${tag.replace(/_/g, ' ')}</span>\`;
              }).join('')}
            </div>
            <div class="space-y-1">
              <div class="text-[11px] font-medium text-[#3F4947]">Preset status tags</div>
              <div class="flex flex-wrap gap-1.5">
                \${STATUS_TAGS.map(tag => {
                  const active = tags.includes(tag);
                  return \`
                    <button
                      onclick="toggleTagForCaptured(\${item.id}, '\${tag}')"
                      data-testid="details-toggle-tag-\${tag}"
                      class="text-xs px-3 py-1 rounded-lg font-medium \${active ? 'bg-[#0B6E69] text-white' : 'bg-[#DAE5E2]/80 text-[#3F4947]'}"
                    >
                      \${tag.replace(/_/g, ' ')}
                    </button>
                  \`;
                }).join('')}
              </div>
            </div>
            \${customTags.length ? \`
              <div class="space-y-1">
                <div class="text-[11px] font-medium text-[#3F4947]">Custom tags (tap to remove)</div>
                <div class="flex flex-wrap gap-1.5">
                  \${customTags.map(tag => \`
                    <button onclick="removeTagFromCaptured(\${item.id}, '\${tag}')" class="text-xs px-2.5 py-1 rounded-lg bg-[#A6F0E8] text-[#00201E] font-medium">
                      \${tag.replace(/_/g, ' ')} ×
                    </button>
                  \`).join('')}
                </div>
              </div>
            \` : ''}
            <div class="flex gap-2 pt-1">
              <input
                id="custom-tag-input"
                type="text"
                placeholder="Add custom tag (e.g. LINE_4, URGENT)"
                value="\${state.customTagDraft || ''}"
                oninput="state.customTagDraft = this.value"
                data-testid="details-custom-tag-input"
                class="flex-1 px-3 py-1.5 text-xs rounded-lg border border-[#BEC9C6] focus:outline-none focus:border-[#0B6E69]"
              />
              <button
                onclick="addCustomTagToCaptured(\${item.id})"
                data-testid="details-add-tag-button"
                class="bg-[#0B6E69] text-white text-xs font-semibold px-3 py-1.5 rounded-lg"
              >
                Add tag
              </button>
            </div>
          </div>

          <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2] space-y-2.5" data-testid="details-pdf-card">
            <div>
              <div class="text-sm font-semibold text-[#171D1C]">Branded PDF Export (PDFBox)</div>
              <div class="text-xs text-[#3F4947]">Exports the inspection image, timestamp, status tags, and file metadata into a branded A4 PDF.</div>
            </div>
            \${item.exportedPdfName ? \`
              <div class="text-xs text-[#171D1C] font-medium" data-testid="details-pdf-status">
                PDF ready: \${item.exportedPdfName} (1 page · 48 KB)
              </div>
              <div class="border border-[#DAE5E2] rounded-lg p-3 bg-white space-y-2" data-testid="details-pdf-preview">
                <div class="bg-[#0B6E69] text-white px-3 py-2 rounded">
                  <div class="text-[11px] font-bold tracking-wide">SAFESTITCH | CAPTURED INSPECTION REPORT</div>
                  <div class="text-[10px] text-[#CCE8E4]">Record #C\${item.id} | Captured: \${formatDate(item.timestamp || item.at)}</div>
                </div>
                <div class="text-[10px] text-[#171D1C] space-y-0.5">
                  <div><strong>Tags:</strong> \${tags.join(', ')}</div>
                  <div><strong>File:</strong> \${fileName} (1920 × 1080 px)</div>
                </div>
                <img src="\${item.dataUrl}" alt="Embedded PDF photo" class="w-full h-28 object-contain bg-[#0E1716] rounded" />
              </div>
            \` : ''}
            <button
              onclick="item_exportPdf(\${item.id})"
              data-testid="details-export-pdf-button"
              class="w-full bg-[#0B6E69] hover:bg-[#095955] text-white text-xs font-semibold py-2.5 px-3 rounded-xl transition"
            >
              \${item.exportedPdfName ? 'Re-export Branded PDF with Latest Tags' : 'Export Branded PDF'}
            </button>
          </div>
        </div>
      \`;
    }

    window.item_exportPdf = (photoId) => {
      const item = (state.capturedPhotos || []).find(p => p.id === photoId);
      if (!item) return;
      item.exportedPdfName = 'SafeStitch_Captured_C' + item.id + '.pdf';
      render();
    };

    function renderReport() {
      const insp = state.inspections.find(i => i.id === state.activeReportId) || state.inspections[0];
      const sum = computeSummary(insp);
      return \`
        <div class="p-4 space-y-3 flex-1 overflow-y-auto">
          <div class="flex items-center justify-between">
            <button onclick="setTab('history')" class="text-xs text-[#0B6E69] font-semibold">← Back to History</button>
            <button onclick="window.print()" class="text-xs bg-[#0B6E69] text-white px-3 py-1.5 rounded-lg font-medium">Export PDF Report</button>
          </div>
          <div class="bg-white rounded-xl p-4 border border-[#DAE5E2] space-y-3">
            <div class="flex justify-between items-start border-b border-[#DAE5E2] pb-3">
              <div>
                <div class="text-xs font-mono font-bold text-[#0B6E69]">\${insp.equipmentCode} · \${TYPE_LABELS[insp.equipmentType]}</div>
                <h2 class="text-base font-bold text-[#171D1C]">\${insp.equipmentName}</h2>
                <div class="text-xs text-[#3F4947]">\${insp.building}</div>
              </div>
              <div class="text-right">
                <span class="text-xs font-bold px-2.5 py-1 rounded \${sum.outcome === 'PASS' ? 'bg-[#1B7F3B] text-white' : 'bg-[#B3261E] text-white'}">\${sum.outcome} (\${sum.scorePercent}%)</span>
                <div class="text-[11px] text-[#3F4947] mt-1">\${formatDate(insp.completedAt || insp.startedAt)}</div>
              </div>
            </div>
            <div class="space-y-1.5">
              \${insp.items.map(item => \`
                <div class="flex items-start justify-between text-xs py-1 border-b border-gray-100 last:border-0 gap-2">
                  <span class="text-[#171D1C]">\${item.question}</span>
                  <span class="font-bold shrink-0 \${item.result === 'PASS' ? 'text-[#1B7F3B]' : item.result === 'FAIL' ? 'text-[#B3261E]' : 'text-[#5F6B69]'}">\${item.result}</span>
                </div>
              \`).join('')}
            </div>
          </div>
        </div>
      \`;
    }

    function renderNewEquipment() {
      return \`
        <div class="p-4 space-y-3 flex-1 overflow-y-auto">
          <div class="flex items-center justify-between">
            <h2 class="text-base font-semibold">Register QR Checkpoint</h2>
            <button onclick="setTab('inspect')" class="text-xs text-[#0B6E69] font-medium">Cancel</button>
          </div>
          <div class="bg-white rounded-xl p-4 border border-[#DAE5E2] space-y-3">
            <div>
              <label class="block text-xs font-semibold text-[#3F4947] mb-1">Equipment Code</label>
              <input id="eq-code" type="text" value="\${state.qrInput}" class="w-full px-3 py-2 text-sm border border-[#BEC9C6] rounded-lg font-mono uppercase" />
            </div>
            <div>
              <label class="block text-xs font-semibold text-[#3F4947] mb-1">Equipment Name</label>
              <input id="eq-name" type="text" placeholder="e.g. Dry Powder Extinguisher #12" class="w-full px-3 py-2 text-sm border border-[#BEC9C6] rounded-lg" />
            </div>
            <div>
              <label class="block text-xs font-semibold text-[#3F4947] mb-1">Equipment Type</label>
              <select id="eq-type" class="w-full px-3 py-2 text-sm border border-[#BEC9C6] rounded-lg bg-white">
                \${Object.entries(TYPE_LABELS).map(([k, v]) => \`<option value="\${k}">\${v}</option>\`).join('')}
              </select>
            </div>
            <div>
              <label class="block text-xs font-semibold text-[#3F4947] mb-1">Building & Area</label>
              <input id="eq-area" type="text" value="Floor 1 · Sewing Line B" class="w-full px-3 py-2 text-sm border border-[#BEC9C6] rounded-lg" />
            </div>
            <button onclick="saveNewEquipment()" class="w-full bg-[#0B6E69] text-white font-semibold py-2.5 rounded-xl text-sm">
              Save & Start Inspection
            </button>
          </div>
        </div>
      \`;
    }

    function renderAdmin() {
      return \`
        <div class="p-4 space-y-3 flex-1 overflow-y-auto">
          <div class="flex items-center justify-between">
            <h2 class="text-base font-semibold">Admin Tools</h2>
            <button onclick="setTab('home')" class="text-xs text-[#0B6E69] font-medium">Done</button>
          </div>
          <div class="space-y-2.5">
            <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2]">
              <div class="text-sm font-semibold">Factory Hierarchy & Locations</div>
              <div class="text-xs text-[#3F4947] mt-0.5">Dhaka Plant 1 › Main Building, Annex Building, Utility Block</div>
            </div>
            <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2]">
              <div class="text-sm font-semibold">Checklist Templates</div>
              <div class="text-xs text-[#3F4947] mt-0.5">5 active equipment categories · 32 inspection questions</div>
            </div>
            <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2] flex items-center justify-between">
              <div>
                <div class="text-sm font-semibold">Android Debug APK</div>
                <div class="text-xs text-[#3F4947]">com.safestitch.inspection.debug (API 36)</div>
              </div>
              <a href="/download-apk" class="bg-[#0B6E69] text-white text-xs font-semibold px-3 py-2 rounded-lg">Download APK</a>
            </div>
            <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2] flex items-center justify-between">
              <div>
                <div class="text-sm font-semibold">Google Play Release Bundle</div>
                <div class="text-xs text-[#3F4947]">com.safestitch.inspection · Signed AAB</div>
              </div>
              <a href="/download-aab" class="bg-[#1B7F3B] text-white text-xs font-semibold px-3 py-2 rounded-lg">Download AAB</a>
            </div>
          </div>
        </div>
      \`;
    }

    function renderBottomNav() {
      const navItems = [
        { id: 'home', label: 'Home' },
        { id: 'inspect', label: 'Inspect' },
        { id: 'actions', label: 'Actions' },
        { id: 'history', label: 'History' }
      ];
      return \`
        <nav class="bg-[#DAE5E2]/80 border-t border-[#BEC9C6]/60 px-2 py-1.5 grid grid-cols-4 gap-1 sticky bottom-0">
          \${navItems.map(item => {
            const active = state.tab === item.id || (item.id === 'history' && (state.tab === 'captured' || state.tab === 'capturedDetail'));
            return \`
              <button
                onclick="setTab('\${item.id}')"
                class="py-1.5 rounded-xl flex flex-col items-center justify-center text-xs font-medium transition \${active ? 'bg-[#A6F0E8] text-[#00201E] font-semibold' : 'text-[#3F4947] hover:bg-black/5'}"
              >
                <span>\${item.label}</span>
              </button>
            \`;
          }).join('')}
        </nav>
      \`;
    }

    function render() {
      const root = document.getElementById('app');
      let body = '';
      if (state.tab === 'home') body = renderHome();
      else if (state.tab === 'inspect') body = renderInspect();
      else if (state.tab === 'scan') body = renderScan();
      else if (state.tab === 'inspection') body = renderInspection();
      else if (state.tab === 'actions') body = renderActions();
      else if (state.tab === 'history') body = renderHistory();
      else if (state.tab === 'captured') body = renderCapturedInspections();
      else if (state.tab === 'capturedDetail') body = renderCapturedDetail();
      else if (state.tab === 'report') body = renderReport();
      else if (state.tab === 'newEquipment') body = renderNewEquipment();
      else if (state.tab === 'admin') body = renderAdmin();
      root.innerHTML = renderTopBar() + body + renderBottomNav();
    }

    render();
  </script>
</body>
</html>`;

const DELIVERABLES_APK = '/app/applet/deliverables/SafeStitch-debug.apk';
const FALLBACK_BUILD_OUTPUT_APK = '/app/applet/.build-outputs/app-debug.apk';
const DELIVERABLES_AAB = '/app/applet/deliverables/SafeStitch-release.aab';
const FALLBACK_BUILD_OUTPUT_AAB = '/app/applet/.build-outputs/app-release.aab';
const DELIVERABLES_INFO = '/app/applet/deliverables/BUILD-INFO.txt';

function resolveExistingFile(candidates) {
  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) return candidate;
  }
  return null;
}

const server = http.createServer((req, res) => {
  const urlPath = (req.url || '/').split('?')[0];

  if (urlPath === '/download-apk' || urlPath === '/deliverables/SafeStitch-debug.apk') {
    const target = resolveExistingFile([DELIVERABLES_APK, APK_PATH, FALLBACK_BUILD_OUTPUT_APK]);
    if (target) {
      const stat = fs.statSync(target);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': 'attachment; filename="SafeStitch-debug.apk"'
      });
      if (req.method === 'HEAD') {
        res.end();
        return;
      }
      fs.createReadStream(target).pipe(res);
      return;
    }
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('APK not found');
    return;
  }

  if (urlPath === '/download-aab' || urlPath === '/deliverables/SafeStitch-release.aab') {
    const target = resolveExistingFile([DELIVERABLES_AAB, AAB_PATH, FALLBACK_BUILD_OUTPUT_AAB]);
    if (target) {
      const stat = fs.statSync(target);
      res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': stat.size,
        'Content-Disposition': 'attachment; filename="SafeStitch-release.aab"'
      });
      if (req.method === 'HEAD') {
        res.end();
        return;
      }
      fs.createReadStream(target).pipe(res);
      return;
    }
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Release AAB not found');
    return;
  }

  if (urlPath === '/deliverables/BUILD-INFO.txt') {
    if (fs.existsSync(DELIVERABLES_INFO)) {
      const stat = fs.statSync(DELIVERABLES_INFO);
      res.writeHead(200, {
        'Content-Type': 'text/plain; charset=utf-8',
        'Content-Length': stat.size
      });
      if (req.method === 'HEAD') {
        res.end();
        return;
      }
      fs.createReadStream(DELIVERABLES_INFO).pipe(res);
      return;
    }
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('BUILD-INFO.txt not found');
    return;
  }

  if (urlPath === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok' }));
    return;
  }

  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(HTML);
});

function killOldPreviewServer() {
  for (const entry of fs.readdirSync('/proc')) {
    if (!/^\d+$/.test(entry)) continue;
    const pid = Number(entry);
    if (pid === process.pid) continue;
    try {
      const cmdline = fs.readFileSync('/proc/' + pid + '/cmdline', 'utf8');
      if (cmdline.includes('preview-server.mjs')) {
        process.kill(pid, 'SIGKILL');
      }
    } catch (_) {}
  }
}

killOldPreviewServer();

try {
  if (fs.existsSync('/var/www/assets')) {
    fs.writeFileSync('/var/www/assets/warmup.html', HTML, 'utf8');
  }
} catch (_) {}

setTimeout(() => {
  server.listen(PORT, '0.0.0.0', () => {
    console.log('SafeStitch preview server listening on http://0.0.0.0:' + PORT);
  });
}, 200);
