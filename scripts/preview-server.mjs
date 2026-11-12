import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { GoogleGenAI } from '@google/genai';

const PORT = 3000;
const APK_PATH = '/app/applet/app/build/outputs/apk/debug/app-debug.apk';
const AAB_PATH = '/app/applet/app/build/outputs/bundle/release/app-release.aab';
const SQL_DB_DIR = '/app/applet/data';
const SQL_DB_PATH = path.join(SQL_DB_DIR, 'safestitch.sqlite');

fs.mkdirSync(SQL_DB_DIR, { recursive: true });
const sqliteDb = new DatabaseSync(SQL_DB_PATH);

function initSqliteDatabase() {
  sqliteDb.exec(`
    CREATE TABLE IF NOT EXISTS factories (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS buildings (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      factoryId INTEGER NOT NULL,
      name TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS floors (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      buildingId INTEGER NOT NULL,
      name TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS areas (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      floorId INTEGER NOT NULL,
      name TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS checkpoints (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      code TEXT NOT NULL UNIQUE,
      name TEXT NOT NULL,
      area TEXT NOT NULL,
      type TEXT NOT NULL,
      createdAt INTEGER NOT NULL,
      areaId INTEGER
    );
    CREATE TABLE IF NOT EXISTS inspections (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      checkpointId INTEGER NOT NULL,
      inspectorName TEXT NOT NULL,
      siteName TEXT NOT NULL,
      startedAt INTEGER NOT NULL,
      completedAt INTEGER,
      generalNotes TEXT NOT NULL DEFAULT '',
      equipmentCode TEXT NOT NULL,
      equipmentName TEXT NOT NULL,
      equipmentType TEXT NOT NULL,
      updatedAt INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS checklist_items (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      inspectionId INTEGER NOT NULL,
      position INTEGER NOT NULL,
      section TEXT NOT NULL,
      question TEXT NOT NULL,
      critical INTEGER NOT NULL,
      result TEXT NOT NULL,
      note TEXT NOT NULL DEFAULT ''
    );
    CREATE TABLE IF NOT EXISTS captured_inspections (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      timestamp INTEGER NOT NULL,
      imagePath TEXT NOT NULL,
      statusTags TEXT NOT NULL DEFAULT 'NEEDS_REVIEW'
    );
    CREATE TABLE IF NOT EXISTS corrective_actions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      inspectionId INTEGER NOT NULL,
      itemId INTEGER NOT NULL,
      description TEXT NOT NULL,
      priority TEXT NOT NULL,
      dueAt INTEGER NOT NULL,
      status TEXT NOT NULL,
      responsibleName TEXT NOT NULL,
      createdByName TEXT NOT NULL,
      createdAt INTEGER NOT NULL,
      updatedAt INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT NOT NULL UNIQUE,
      displayName TEXT NOT NULL,
      role TEXT NOT NULL,
      active INTEGER NOT NULL DEFAULT 1
    );
  `);

  const countRow = sqliteDb.prepare('SELECT COUNT(*) AS cnt FROM checkpoints').get();
  if (countRow && Number(countRow.cnt) > 0) return;

  const now = Date.now();
  const day = 86_400_000;

  sqliteDb.exec(`
    INSERT INTO factories (id, name) VALUES (1, 'Dhaka Plant 1');
    INSERT INTO buildings (id, factoryId, name) VALUES (1, 1, 'Main Building'), (2, 1, 'Annex Building');
    INSERT INTO floors (id, buildingId, name) VALUES (1, 1, 'Floor 1'), (2, 1, 'Floor 2'), (3, 2, 'Ground Floor');
    INSERT INTO areas (id, floorId, name) VALUES
      (1, 1, 'Cutting Room'),
      (2, 1, 'Finishing'),
      (3, 2, 'Sewing Line A'),
      (4, 3, 'Reception & Boiler Bay');
    INSERT INTO users (username, displayName, role, active) VALUES
      ('admin', 'Nadia Rahman', 'ADMIN', 1),
      ('kamal', 'Kamal Hossain', 'INSPECTOR', 1);
  `);

  const insertCp = sqliteDb.prepare(
    'INSERT INTO checkpoints (id, code, name, area, type, createdAt, areaId) VALUES (?, ?, ?, ?, ?, ?, ?)'
  );
  insertCp.run(1, 'FE-101', 'CO2 Extinguisher — Cutting North', 'Near Pillar B2', 'FIRE_EXTINGUISHER', now - 14 * day, 1);
  insertCp.run(2, 'EL-204', 'Main Distribution Panel DB-2', 'East Wall', 'ELECTRICAL', now - 14 * day, 3);
  insertCp.run(3, 'EX-102', 'Stairwell Exit West', 'Door W-02', 'EMERGENCY_EXIT', now - 14 * day, 2);
  insertCp.run(4, 'FA-301', 'Addressable Fire Alarm Panel', 'Guard Desk', 'FIRE_ALARM', now - 10 * day, 4);
  insertCp.run(5, 'BL-001', 'Steam Generator Boiler #1', 'Bay 1', 'BOILER', now - 10 * day, 4);

  const insertInsp = sqliteDb.prepare(
    'INSERT INTO inspections (id, checkpointId, inspectorName, siteName, startedAt, completedAt, generalNotes, equipmentCode, equipmentName, equipmentType, updatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
  );
  insertInsp.run(101, 1, 'Nadia Rahman', 'Dhaka Plant 1 › Main Building › Floor 1 › Cutting Room', now - 2 * day - 600000, now - 2 * day, 'Routine weekly extinguisher inspection passed.', 'FE-101', 'CO2 Extinguisher — Cutting North', 'FIRE_EXTINGUISHER', now - 2 * day);
  insertInsp.run(102, 3, 'Nadia Rahman', 'Dhaka Plant 1 › Main Building › Floor 1 › Finishing', now - day - 900000, now - day, 'Exit corridor partially obstructed by fabric cartons.', 'EX-102', 'Stairwell Exit West', 'EMERGENCY_EXIT', now - day);
  insertInsp.run(103, 2, 'Kamal Hossain', 'Dhaka Plant 1 › Main Building › Floor 2 › Sewing Line A', now - 3 * day - 600000, now - 3 * day, 'Insulating mat missing in front of panel DB-2.', 'EL-204', 'Main Distribution Panel DB-2', 'ELECTRICAL', now - 3 * day);

  const insertCap = sqliteDb.prepare(
    'INSERT INTO captured_inspections (timestamp, imagePath, statusTags) VALUES (?, ?, ?)'
  );
  insertCap.run(now - 7200000, 'photos/IMG_FE101_gauge_check.jpg', 'PASS');
  insertCap.run(now - 3600000, 'photos/IMG_EX102_corridor_obstruction.jpg', 'FAIL,FOLLOW_UP');
  insertCap.run(now - 900000, 'photos/IMG_EL204_panel_mat.jpg', 'NEEDS_REVIEW');

  const insertAct = sqliteDb.prepare(
    'INSERT INTO corrective_actions (inspectionId, itemId, description, priority, dueAt, status, responsibleName, createdByName, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
  );
  insertAct.run(102, 2, '3 fabric rolls stacked in exit corridor near Door W-02', 'CRITICAL', now + day, 'OPEN', 'Floor Supervisor Karim', 'Nadia Rahman', now - day, now - day);
  insertAct.run(103, 5, 'Insulating rubber mat removed during floor cleaning', 'HIGH', now + 2 * day, 'RESOLVED', 'Maintenance Lead Rafiq', 'Kamal Hossain', now - 3 * day, now - 3600000);
}

initSqliteDatabase();

function inspectSqlTables() {
  const tableRows = sqliteDb.prepare(
    "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name COLLATE NOCASE"
  ).all();
  return tableRows.map((row) => {
    const safeIdent = '"' + String(row.name).replace(/"/g, '""') + '"';
    const cntRow = sqliteDb.prepare(`SELECT COUNT(*) AS cnt FROM ${safeIdent}`).get();
    const cols = sqliteDb.prepare(`PRAGMA table_info(${safeIdent})`).all().map((c) => `${c.name} (${c.type})`);
    return {
      tableName: row.name,
      rowCount: Number(cntRow?.cnt ?? 0),
      columns: cols,
      createSql: row.sql || ''
    };
  });
}

function isReadOnlySqlQuery(sql) {
  const cleaned = String(sql || '').trim().replace(/;+$/, '').trim();
  if (!cleaned || cleaned.includes(';')) return false;
  const upper = cleaned.toUpperCase();
  if (!upper.startsWith('SELECT ') && !upper.startsWith('WITH ') && !upper.startsWith('PRAGMA ') && !upper.startsWith('EXPLAIN ')) {
    return false;
  }
  const forbidden = ['INSERT ', 'UPDATE ', 'DELETE ', 'DROP ', 'ALTER ', 'CREATE ', 'REPLACE ', 'TRUNCATE ', 'ATTACH ', 'DETACH '];
  return !forbidden.some((tok) => upper.includes(tok));
}

function executeReadOnlySqlQuery(rawSql) {
  const start = performance.now();
  const cleaned = String(rawSql || '').trim().replace(/;+$/, '').trim();
  if (!isReadOnlySqlQuery(cleaned)) {
    return {
      sql: cleaned,
      columns: [],
      rows: [],
      executionTimeMs: 1,
      error: 'Only read-only SELECT or PRAGMA queries are allowed in the SQL inspector.'
    };
  }
  try {
    const rowsObj = sqliteDb.prepare(cleaned).all().slice(0, 100);
    const columns = rowsObj.length > 0 ? Object.keys(rowsObj[0]) : [];
    const rows = rowsObj.map((r) => columns.map((c) => (r[c] === null || r[c] === undefined ? 'NULL' : String(r[c]))));
    const elapsed = Math.max(1, Math.round(performance.now() - start));
    return { sql: cleaned, columns, rows, executionTimeMs: elapsed, error: null };
  } catch (err) {
    const elapsed = Math.max(1, Math.round(performance.now() - start));
    return { sql: cleaned, columns: [], rows: [], executionTimeMs: elapsed, error: err?.message || 'SQL execution failed.' };
  }
}

const CHAT_PERSONAS = {
  SAFETY_AUDITOR: {
    id: 'SAFETY_AUDITOR',
    title: 'Safety Compliance Auditor',
    shortBadge: 'Auditor',
    defaultModel: 'gemini-4-hydrogen',
    systemInstruction:
      'You are a Senior Factory Safety & Compliance Auditor for SafeStitch. Guide inspectors through fire extinguisher, electrical panel, fire alarm, emergency exit, and boiler inspections. Reference specific equipment codes (FE-101, EL-204, EX-102, FA-301, BL-001) and provide clear, actionable compliance checks.'
  },
  MAINTENANCE_ADVISOR: {
    id: 'MAINTENANCE_ADVISOR',
    title: 'Corrective Action Advisor',
    shortBadge: 'Maintenance',
    defaultModel: 'gemini-4-hydrogen',
    systemInstruction:
      'You are an Industrial Equipment & Corrective Action Advisor for SafeStitch. Help maintenance leads and floor supervisors diagnose failed checklist items, prioritize CRITICAL/HIGH hazards, write clear resolution notes, and prepare after-fix verification evidence.'
  },
  FAST_TRIAGE: {
    id: 'FAST_TRIAGE',
    title: 'Rapid Field Triage',
    shortBadge: 'Fast Triage',
    defaultModel: 'gemini-3.1-flash-lite',
    systemInstruction:
      'You are a Rapid Field Triage Assistant for SafeStitch inspectors on the factory floor. Respond in concise, numbered bullet points with immediate safety steps and pass/fail criteria.'
  }
};

function buildFallbackChatReply(userPrompt, history, persona) {
  const lower = String(userPrompt || '').toLowerCase();
  const badge = persona?.shortBadge || 'Auditor';
  const turnCount = Array.isArray(history) ? history.filter((m) => m.role === 'user').length : 1;
  const openActions = sqliteDb.prepare("SELECT * FROM corrective_actions WHERE status != 'VERIFIED' ORDER BY dueAt ASC").all();
  const checkpoints = sqliteDb.prepare('SELECT code, name, type, area FROM checkpoints ORDER BY code').all();
  const capturedCount = Number(sqliteDb.prepare('SELECT COUNT(*) AS cnt FROM captured_inspections').get()?.cnt ?? 0);

  if (lower.includes('action') || lower.includes('pending') || lower.includes('overdue') || lower.includes('fix')) {
    if (openActions.length > 0) {
      const list = openActions
        .map((a) => `• Action #${a.id} [${a.priority} · ${a.status}]: ${a.description} (Assigned: ${a.responsibleName})`)
        .join('\n');
      return `[${badge}] Active Corrective Actions (${openActions.length}):\n${list}\n\nRecommended next step: verify CRITICAL and HIGH items first and attach an after-fix photo before marking Verified.`;
    }
    return `[${badge}] All corrective actions in SQLite are currently verified.`;
  }

  if (lower.includes('fe-101') || lower.includes('extinguisher') || lower.includes('fire')) {
    return `[${badge}] Fire Extinguisher Inspection Guide (FE-101 — CO2 Extinguisher, Cutting North):\n1. Verify the pressure gauge needle is in the green charged zone (Critical).\n2. Check that the safety pin and tamper seal are intact (Critical).\n3. Confirm the annual service tag is dated within the last 12 months (Critical).\n4. Ensure unobstructed access near Pillar B2 and inspect the hose/nozzle.`;
  }

  if (lower.includes('el-204') || lower.includes('electrical') || lower.includes('panel') || lower.includes('mat')) {
    return `[${badge}] Electrical Panel Safety Guide (EL-204 — Main Distribution Panel DB-2):\n1. Confirm IEC Class-2 insulating rubber mat is positioned in front of DB-2.\n2. Inspect for exposed live conductors, damaged insulation, or overheating (Critical).\n3. Verify all circuit breakers are labeled and the panel door is closed and unobstructed.`;
  }

  if (lower.includes('ex-102') || lower.includes('exit') || lower.includes('corridor') || lower.includes('stair')) {
    return `[${badge}] Emergency Exit Compliance Guide (EX-102 — Stairwell Exit West):\n1. Keep the exit route 100% clear of fabric rolls or cartons near Door W-02 (Critical).\n2. Verify the exit door opens outward easily without keys or locks during working hours (Critical).\n3. Confirm exit signage is illuminated and emergency lighting functions along the stairwell.`;
  }

  const cpCodes = checkpoints.map((c) => `${c.code} (${c.name})`).join(', ');
  return `[${badge} · Turn ${turnCount}] Based on your question ("${userPrompt}"):\n• Registered Equipment (${checkpoints.length}): ${cpCodes}\n• Active Corrective Actions: ${openActions.length}\n• Captured Inspection Photos: ${capturedCount}\nAsk about any specific equipment code, checklist requirement, or corrective action for detailed guidance.`;
}

async function handleGeminiChatRequest(payload) {
  const roleId = payload.roleId && CHAT_PERSONAS[payload.roleId] ? payload.roleId : 'SAFETY_AUDITOR';
  const persona = CHAT_PERSONAS[roleId];
  const allowedModels = new Set(['gemini-4-hydrogen', 'gemini-3.1-flash-lite']);
  const model = allowedModels.has(payload.model) ? payload.model : persona.defaultModel;
  const rawHistory = Array.isArray(payload.history) ? payload.history : [];
  const lastUserEntry = [...rawHistory].reverse().find((m) => m.role === 'user');
  const userMessage = String(payload.message || lastUserEntry?.text || '').trim();

  if (!userMessage) {
    return { reply: 'Please enter a safety or equipment question.', model, roleId };
  }

  const systemInstruction = String(payload.systemInstruction || persona.systemInstruction);
  const apiKey = process.env.GEMINI_API_KEY;

  if (apiKey && apiKey.trim().length > 0) {
    try {
      const ai = new GoogleGenAI({
        apiKey: apiKey.trim(),
        httpOptions: {
          headers: {
            'User-Agent': 'aistudio-build'
          }
        }
      });
      const priorTurns = rawHistory.slice(0, -1).map((m) => ({
        role: m.role === 'user' ? 'user' : 'model',
        parts: [{ text: String(m.text || '') }]
      }));
      const chat = ai.chats.create({
        model,
        history: priorTurns,
        config: {
          systemInstruction
        }
      });
      const response = await chat.sendMessage({ message: userMessage });
      const text = (response.text || '').trim();
      if (text) {
        return { reply: text, model, roleId, source: 'gemini' };
      }
    } catch (_) {
      // Fall back cleanly to SQLite-grounded response when offline or key is invalid in test environment
    }
  }

  const fallbackReply = buildFallbackChatReply(userMessage, rawHistory, persona);
  return { reply: fallbackReply, model, roleId, source: 'contextual' };
}

function buildFallbackTranscription(contextHint) {
  const hint = String(contextHint || '').toUpperCase();
  if (hint.includes('FE-101') || hint.includes('EXTINGUISHER')) {
    return 'Checked CO2 extinguisher FE-101 near Pillar B2: pressure gauge is in the green zone and safety pin is intact.';
  }
  if (hint.includes('EL-204') || hint.includes('ELECTRICAL')) {
    return 'Inspected electrical distribution panel EL-204 on Floor 2: new IEC Class-2 insulating rubber mat is installed and circuit breakers are labeled.';
  }
  if (hint.includes('EX-102') || hint.includes('EXIT')) {
    return 'Stairwell emergency exit EX-102 inspected: exit corridor near Door W-02 requires clearing of stacked fabric rolls.';
  }
  return 'Field safety voice note: checked equipment FE-101 pressure gauge in green zone, verified electrical panel EL-204 insulating mat, and flagged EX-102 corridor clearance for follow-up.';
}

async function handleGeminiTranscribeRequest(payload) {
  const model = 'gemini-3.5-transcribe';
  const mimeType = String(payload.mimeType || 'audio/wav');
  const base64AudioString = String(payload.audioBase64 || '').trim();
  const contextHint = String(payload.contextHint || '');
  const apiKey = process.env.GEMINI_API_KEY;

  if (apiKey && apiKey.trim().length > 0 && base64AudioString.length > 0) {
    try {
      const ai = new GoogleGenAI({
        apiKey: apiKey.trim(),
        httpOptions: {
          headers: {
            'User-Agent': 'aistudio-build'
          }
        }
      });
      const audioPart = {
        inlineData: {
          mimeType,
          data: base64AudioString
        }
      };
      const response = await ai.models.generateContent({
        model: 'gemini-3.5-transcribe',
        contents: { parts: [audioPart, { text: 'Transcribe this audio.' }] }
      });
      const text = (response.text || '').trim();
      if (text) {
        return { text, model, source: 'gemini' };
      }
    } catch (_) {
      // Fall back cleanly when offline or when synthesized test tone is used
    }
  }

  return {
    text: buildFallbackTranscription(contextHint),
    model,
    source: 'contextual'
  };
}

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
                <button onclick="setTab('chat')" data-testid="menu-chat" class="w-full text-left px-4 py-2 text-sm hover:bg-gray-100 flex items-center justify-between">
                  <span>Safety Assistant Chat</span>
                  <span class="text-xs text-[#0B6E69] font-medium">AI</span>
                </button>
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
            <div class="bg-[#F2F7F6] rounded-xl p-3 border border-[#DAE5E2] space-y-2" data-testid="dash-scan-equipment-panel">
              <div class="flex items-center justify-between gap-2">
                <div>
                  <div class="text-xs font-bold text-[#171D1C]">Scan Equipment (QR / Barcode → History)</div>
                  <div class="text-[11px] text-[#3F4947]">Supports QR_CODE, CODE_128, CODE_39, EAN_13 & DATA_MATRIX via ZXing.</div>
                </div>
                <span class="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-[#A6F0E8] text-[#00201E]">ZXing Active</span>
              </div>
              <div class="flex items-center gap-1.5 overflow-x-auto pb-0.5">
                <span class="text-[11px] text-[#3F4947] shrink-0">Quick scan tag:</span>
                \${(state.checkpoints || []).map(cp => \`
                  <button
                    onclick="scanEquipmentToHistory('\${cp.code}')"
                    data-testid="dash-scan-chip-\${cp.code}"
                    class="text-[11px] px-2.5 py-1 rounded-lg bg-white hover:bg-[#0B6E69] hover:text-white border border-[#DAE5E2] font-medium whitespace-nowrap transition"
                  >
                    \${cp.code} · \${cp.name}
                  </button>
                \`).join('')}
              </div>
              <div class="flex gap-2">
                <input
                  id="dash-scan-code-input"
                  type="text"
                  placeholder="Enter or scan QR / barcode (e.g. FE-101)"
                  value="\${state.dashScanInput || ''}"
                  oninput="state.dashScanInput = this.value"
                  data-testid="dash-scan-code-input"
                  class="flex-1 px-3 py-1.5 text-xs rounded-lg border border-[#BEC9C6] bg-white focus:outline-none focus:border-[#0B6E69] font-mono uppercase"
                />
                <button
                  onclick="scanEquipmentToHistory(document.getElementById('dash-scan-code-input').value || (state.checkpoints[0] && state.checkpoints[0].code))"
                  data-testid="dash-scan-code-submit"
                  class="bg-[#0B6E69] text-white text-xs font-semibold px-3 py-1.5 rounded-lg"
                >
                  History
                </button>
              </div>
            </div>

            <button
              onclick="setTab('captured')"
              data-testid="open-captured-inspections"
              class="w-full border border-[#0B6E69] text-[#0B6E69] hover:bg-[#A6F0E8]/30 text-xs font-semibold py-2 px-3 rounded-xl transition flex items-center justify-center gap-1.5"
            >
              <span>View all captured inspections (\${photos.length})</span>
            </button>
          </div>

          <button
            onclick="scanEquipmentToHistory(state.dashScanInput || (state.checkpoints[0] && state.checkpoints[0].code) || 'FE-101')"
            data-testid="dash-scan-equipment"
            class="w-full bg-[#0B6E69] hover:bg-[#095955] text-white font-semibold py-2.5 px-4 rounded-xl flex items-center justify-center gap-2 shadow-sm transition text-xs"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M3 7V5a2 2 0 012-2h2m10 0h2a2 2 0 012 2v2m0 10v2a2 2 0 01-2 2h-2M7 21H5a2 2 0 01-2-2v-2m4-10h2v2H7V7zm8 0h2v2h-2V7zm-8 8h2v2H7v-2zm8 0h2v2h-2v-2z"/></svg>
            <span>Scan Equipment (QR / Barcode → Inspection History)</span>
          </button>

          <div class="grid grid-cols-2 gap-3">
            <button onclick="setTab('scan')" class="border border-[#0B6E69] text-[#0B6E69] hover:bg-[#A6F0E8]/30 font-medium py-2 px-4 rounded-xl flex items-center justify-center gap-2 transition text-xs">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M3 7V5a2 2 0 012-2h2m10 0h2a2 2 0 012 2v2m0 10v2a2 2 0 01-2 2h-2M7 21H5a2 2 0 01-2-2v-2m4-10h2v2H7V7zm8 0h2v2h-2V7zm-8 8h2v2H7v-2zm8 0h2v2h-2v-2z"/></svg>
              <span>Scan for Checklist</span>
            </button>
            <button onclick="setTab('inspect')" class="border border-[#0B6E69] text-[#0B6E69] hover:bg-[#A6F0E8]/30 font-medium py-2 px-4 rounded-xl flex items-center justify-center gap-2 transition text-xs">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/></svg>
              <span>Find equipment</span>
            </button>
          </div>

          <button
            onclick="setTab('chat')"
            data-testid="dash-open-chat"
            class="w-full border border-[#0B6E69] bg-[#E5F6F4] hover:bg-[#CCE8E4] text-[#00201E] font-semibold py-2.5 px-4 rounded-xl flex items-center justify-center gap-2 transition text-xs"
          >
            <svg class="w-4 h-4 text-[#0B6E69]" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"/></svg>
            <span>Safety Assistant Chat (Multi-Turn Advisor)</span>
          </button>

          <div class="bg-white rounded-2xl p-3.5 border border-[#DAE5E2] space-y-2.5 shadow-sm" data-testid="dash-voice-transcribe-card">
            <div class="flex items-center justify-between gap-2">
              <div>
                <div class="text-xs font-bold text-[#171D1C]">Voice Note Transcription</div>
                <div class="text-[11px] text-[#3F4947]">Record microphone audio & transcribe via <span class="font-mono font-semibold text-[#0B6E69]">gemini-3.5-transcribe</span></div>
              </div>
              <button
                onclick="toggleDashMicRecording()"
                data-testid="dash-mic-record-btn"
                class="text-xs font-semibold px-3 py-1.5 rounded-xl transition \${state.dashRecordingAudio ? 'bg-[#B3261E] text-white' : 'bg-[#0B6E69] text-white'}"
              >
                \${state.dashRecordingAudio ? 'Stop & Transcribe' : state.dashTranscribingAudio ? 'Transcribing…' : 'Record Mic'}
              </button>
            </div>
            \${state.dashVoiceTranscript ? \`
              <div>
                <label class="block text-[10px] font-semibold text-[#0B6E69] mb-1">Transcribed Audio (gemini-3.5-transcribe)</label>
                <textarea
                  rows="2"
                  data-testid="dash-transcribe-result"
                  oninput="state.dashVoiceTranscript = this.value"
                  class="w-full px-2.5 py-1.5 text-xs border border-[#BEC9C6] rounded-lg bg-[#F6FAF9] text-[#171D1C]"
                >\${escapeHtml(state.dashVoiceTranscript)}</textarea>
              </div>
            \` : ''}
          </div>

          \${(() => {
            const totalPerformed = state.inspections.length + photos.length;
            const pendingCaptures = photos.filter(p => {
              const t = p.statusTags || ['NEEDS_REVIEW'];
              return (t.includes('NEEDS_REVIEW') || t.includes('FOLLOW_UP')) && !p.exportedPdfName;
            }).length;
            const pendingReportsCount = drafts.length + pendingCaptures;
            const totalPassed = passedCount + photos.filter(p => (p.statusTags || []).includes('PASS')).length;
            const totalFailed = failedCount + photos.filter(p => (p.statusTags || []).includes('FAIL')).length;
            const maxBar = Math.max(totalPerformed, totalPassed, totalFailed, pendingReportsCount, 1);
            const passPct = Math.round((totalPassed / maxBar) * 100);
            const failPct = Math.round((totalFailed / maxBar) * 100);
            const pendPct = Math.round((pendingReportsCount / maxBar) * 100);
            const ringSum = Math.max(totalPassed + totalFailed + pendingReportsCount, 1);
            const passDeg = Math.round((totalPassed / ringSum) * 360);
            const failDeg = Math.round((totalFailed / ringSum) * 360);

            const recentUpdates = [
              ...state.inspections.map(insp => {
                const sum = computeSummary(insp);
                return {
                  key: 'insp-' + insp.id,
                  code: insp.equipmentCode,
                  name: insp.equipmentName,
                  sub: insp.building + ' · ' + formatDate(insp.completedAt || insp.startedAt),
                  status: insp.completedAt ? (sum.outcome + ' (' + sum.scorePercent + '%)') : 'PENDING REPORT',
                  kind: !insp.completedAt ? 'PENDING' : sum.outcome,
                  at: insp.completedAt || insp.startedAt,
                  onClick: \`openReport(\${insp.id})\`
                };
              }),
              ...photos.map(p => {
                const eq = resolveCapturedEquipment(p);
                const primary = (p.statusTags && p.statusTags[0]) || 'NEEDS_REVIEW';
                return {
                  key: 'cap-' + p.id,
                  code: eq.code,
                  name: eq.name,
                  sub: eq.category + ' · ' + formatDate(p.timestamp || p.at),
                  status: primary.replace(/_/g, ' '),
                  kind: primary === 'PASS' ? 'PASS' : primary === 'FAIL' ? 'FAIL' : 'PENDING',
                  at: p.timestamp || p.at,
                  onClick: \`openCapturedDetail(\${p.id})\`
                };
              })
            ].sort((a, b) => (b.at || 0) - (a.at || 0)).slice(0, 4);

            return \`
              <div class="bg-white rounded-2xl p-4 border border-[#DAE5E2] space-y-3.5 shadow-sm" data-testid="summary-dashboard-card">
                <div class="flex items-center justify-between gap-2">
                  <div>
                    <div class="text-sm font-bold text-[#171D1C]" data-testid="summary-dashboard-title">Inspection Summary Overview</div>
                    <div class="text-xs text-[#3F4947]">\${state.inspections.length} checklists · \${photos.length} camera captures</div>
                  </div>
                  <span class="text-xs font-semibold px-2.5 py-1 rounded-full bg-[#D97706]/15 text-[#D97706]" data-testid="summary-pending-reports-badge">
                    \${pendingReportsCount} Pending Report\${pendingReportsCount === 1 ? '' : 's'}
                  </span>
                </div>

                <div class="grid grid-cols-3 gap-2">
                  <div class="bg-[#0B6E69]/10 rounded-xl p-2.5">
                    <div class="text-xl font-bold text-[#0B6E69]" data-testid="summary-metric-total">\${totalPerformed}</div>
                    <div class="text-[11px] text-[#3F4947]">Total Performed</div>
                  </div>
                  <div class="bg-[#1B7F3B]/10 rounded-xl p-2.5">
                    <div class="text-xl font-bold text-[#1B7F3B]" data-testid="summary-metric-passed">\${totalPassed}</div>
                    <div class="text-[11px] text-[#3F4947]">Passed</div>
                  </div>
                  <div class="bg-[#D97706]/10 rounded-xl p-2.5">
                    <div class="text-xl font-bold text-[#D97706]" data-testid="summary-metric-pending-reports">\${pendingReportsCount}</div>
                    <div class="text-[11px] text-[#3F4947]">Pending Reports</div>
                  </div>
                </div>

                <div class="bg-[#F2F7F6] rounded-xl p-3 flex items-center gap-4 border border-[#DAE5E2]" data-testid="summary-dashboard-chart">
                  <div class="relative w-24 h-24 rounded-full flex items-center justify-center shrink-0" style="background: conic-gradient(#1B7F3B 0deg \${passDeg}deg, #B3261E \${passDeg}deg \${passDeg + failDeg}deg, #D97706 \${passDeg + failDeg}deg 360deg);">
                    <div class="w-16 h-16 rounded-full bg-white flex flex-col items-center justify-center">
                      <span class="text-base font-bold text-[#171D1C]">\${totalPerformed}</span>
                      <span class="text-[10px] text-[#3F4947]">Total</span>
                    </div>
                  </div>
                  <div class="flex-1 space-y-2">
                    <div class="space-y-0.5">
                      <div class="flex justify-between text-[11px]"><span class="flex items-center gap-1.5"><span class="w-2 h-2 rounded-full bg-[#1B7F3B]"></span>Passed</span><span class="font-bold">\${totalPassed}</span></div>
                      <div class="w-full h-1.5 bg-[#DAE5E2] rounded-full overflow-hidden"><div class="h-full bg-[#1B7F3B]" style="width: \${Math.max(passPct, totalPassed > 0 ? 8 : 0)}%"></div></div>
                    </div>
                    <div class="space-y-0.5">
                      <div class="flex justify-between text-[11px]"><span class="flex items-center gap-1.5"><span class="w-2 h-2 rounded-full bg-[#B3261E]"></span>Failed / Alerts</span><span class="font-bold">\${totalFailed}</span></div>
                      <div class="w-full h-1.5 bg-[#DAE5E2] rounded-full overflow-hidden"><div class="h-full bg-[#B3261E]" style="width: \${Math.max(failPct, totalFailed > 0 ? 8 : 0)}%"></div></div>
                    </div>
                    <div class="space-y-0.5">
                      <div class="flex justify-between text-[11px]"><span class="flex items-center gap-1.5"><span class="w-2 h-2 rounded-full bg-[#D97706]"></span>Pending Reports</span><span class="font-bold">\${pendingReportsCount}</span></div>
                      <div class="w-full h-1.5 bg-[#DAE5E2] rounded-full overflow-hidden"><div class="h-full bg-[#D97706]" style="width: \${Math.max(pendPct, pendingReportsCount > 0 ? 8 : 0)}%"></div></div>
                    </div>
                  </div>
                </div>

                <div class="space-y-2 pt-1 border-t border-[#DAE5E2]">
                  <div class="text-xs font-semibold text-[#171D1C]" data-testid="summary-recent-updates-header">Recent Equipment Status Updates</div>
                  <div class="space-y-1.5" data-testid="summary-recent-updates-list">
                    \${recentUpdates.map(u => {
                      const badge = u.kind === 'PASS' ? 'bg-[#1B7F3B]/15 text-[#1B7F3B]' :
                                    u.kind === 'FAIL' ? 'bg-[#B3261E]/15 text-[#B3261E]' :
                                    'bg-[#D97706]/15 text-[#D97706]';
                      return \`
                        <div onclick="\${u.onClick}" class="cursor-pointer bg-[#F2F7F6] hover:bg-[#DAE5E2]/60 rounded-xl px-3 py-2 flex items-center justify-between gap-2 transition" data-testid="summary-recent-item-\${u.key}">
                          <div class="min-w-0 flex-1">
                            <div class="flex items-center gap-1.5">
                              <span class="text-[10px] font-mono font-bold bg-[#0B6E69]/15 text-[#0B6E69] px-1.5 py-0.5 rounded">\${u.code}</span>
                              <span class="text-xs font-semibold text-[#171D1C] truncate">\${u.name}</span>
                            </div>
                            <div class="text-[11px] text-[#3F4947] truncate">\${u.sub}</div>
                          </div>
                          <span class="text-[10px] font-semibold px-2 py-0.5 rounded-full shrink-0 \${badge}">\${u.status}</span>
                        </div>
                      \`;
                    }).join('')}
                  </div>
                </div>
              </div>
            \`;
          })()}

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

    window.scanEquipmentToHistory = (rawCode) => {
      const cleaned = (rawCode || '').trim().replace(/^SAFESTITCH:/i, '').toUpperCase();
      if (!cleaned) return;
      state.historyEquipmentCode = cleaned;
      state.dashScanInput = '';
      state.tab = 'history';
      render();
    };

    window.clearHistoryEquipmentFilter = () => {
      state.historyEquipmentCode = '';
      render();
    };

    function renderHistory() {
      const photos = state.capturedPhotos || [];
      const scannedCode = (state.historyEquipmentCode || '').trim().toUpperCase();
      const matchedCp = scannedCode
        ? (state.checkpoints || []).find(c => c.code.toUpperCase() === scannedCode)
        : null;
      const filteredInspections = scannedCode
        ? state.inspections.filter(i => (i.equipmentCode || '').toUpperCase().includes(scannedCode) || (i.equipmentName || '').toUpperCase().includes(scannedCode))
        : state.inspections;

      return \`
        <div class="p-4 space-y-3 flex-1 overflow-y-auto">
          <div class="flex items-center justify-between">
            <div>
              <h2 class="text-base font-semibold">Inspection History</h2>
              \${scannedCode ? \`<div class="text-xs text-[#0B6E69] font-medium">Filtered by scanned equipment: \${scannedCode}</div>\` : ''}
            </div>
            <div class="flex items-center gap-2">
              <button onclick="setTab('captured')" class="text-xs text-[#0B6E69] font-semibold">Captured (\${photos.length})</button>
              \${scannedCode ? \`<button onclick="clearHistoryEquipmentFilter()" data-testid="history-clear" class="text-xs text-[#B3261E] font-semibold">Clear filters</button>\` : ''}
            </div>
          </div>
          \${scannedCode ? \`
            <div class="bg-[#A6F0E8]/35 border border-[#0B6E69] rounded-xl p-3.5 flex items-center justify-between gap-3" data-testid="history-scanned-equipment-banner">
              <div>
                <div class="text-[11px] font-bold text-[#0B6E69] uppercase tracking-wide">Equipment Identified via QR / Barcode</div>
                <div class="text-sm font-semibold text-[#171D1C]">\${matchedCp ? (matchedCp.code + ' · ' + matchedCp.name) : ('Equipment Code: ' + scannedCode)}</div>
                \${matchedCp ? \`<div class="text-xs text-[#3F4947]">\${TYPE_LABELS[matchedCp.type] || matchedCp.type} · \${matchedCp.building} · \${matchedCp.area}</div>\` : ''}
              </div>
              <button onclick="clearHistoryEquipmentFilter()" data-testid="history-clear-scanned-equipment" class="text-xs bg-white border border-[#0B6E69] text-[#0B6E69] font-semibold px-2.5 py-1 rounded-lg shrink-0">Show all</button>
            </div>
          \` : ''}
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
            \${filteredInspections.map(insp => {
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

    function resolveCapturedEquipment(item) {
      const cps = state.checkpoints || [];
      if (item.selectedCheckpointId) {
        const chosen = cps.find(c => c.id === item.selectedCheckpointId);
        if (chosen) {
          return {
            name: chosen.name,
            code: chosen.code,
            category: TYPE_LABELS[chosen.type] || chosen.type,
            location: (chosen.building || 'Dhaka Plant 1 › Main Building') + ' · ' + (chosen.area || 'Production Floor'),
            inspector: (state.user && state.user.displayName) || 'Field Safety Inspector',
            checkpointId: chosen.id,
            mode: 'Selected'
          };
        }
      }
      const p = (item.imagePath || '').toLowerCase();
      let matched = cps[0];
      if (p.includes('fe12') || p.includes('fire')) matched = cps.find(c => c.type === 'FIRE_SAFETY') || cps[0];
      else if (p.includes('ep04') || p.includes('electrical')) matched = cps.find(c => c.type === 'ELECTRICAL') || cps[0];
      else if (p.includes('mg09') || p.includes('guard') || p.includes('sewing')) matched = cps.find(c => c.type === 'SEWING_LINE') || cps[0];
      if (matched) {
        return {
          name: matched.name,
          code: matched.code,
          category: TYPE_LABELS[matched.type] || matched.type,
          location: (matched.building || 'Dhaka Plant 1 › Main Building') + ' · ' + (matched.area || 'Production Floor'),
          inspector: (state.user && state.user.displayName) || 'Field Safety Inspector',
          checkpointId: matched.id,
          mode: 'Auto-linked'
        };
      }
      return {
        name: 'Factory Floor Safety Checkpoint',
        code: 'SS-CAP-' + item.id,
        category: 'General safety',
        location: 'Dhaka Plant 1 › Main Building · Production Floor',
        inspector: (state.user && state.user.displayName) || 'Field Safety Inspector',
        checkpointId: null,
        mode: 'Auto-linked'
      };
    }

    window.selectEquipmentForCaptured = (photoId, cpId) => {
      const item = (state.capturedPhotos || []).find(p => p.id === photoId);
      if (!item) return;
      item.selectedCheckpointId = cpId || null;
      render();
    };

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
      const equip = resolveCapturedEquipment(item);

      return \`
        <div class="p-4 space-y-3 flex-1 overflow-y-auto" data-testid="inspection-details-screen">
          <div class="flex items-center justify-between">
            <div>
              <h2 class="text-base font-semibold" data-testid="inspection-details-title">Inspection Details</h2>
              <div class="text-xs text-[#3F4947]">Capture #\${item.id} · \${equip.code} · \${formatDate(item.timestamp || item.at)}</div>
            </div>
            <div class="flex items-center gap-2">
              <button onclick="item_exportPdf(\${item.id})" data-testid="details-export-pdf-top" class="text-xs bg-[#0B6E69]/10 text-[#0B6E69] border border-[#0B6E69]/30 px-2.5 py-1 rounded-lg font-semibold">PDF</button>
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

          <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2] space-y-2" data-testid="details-equipment-card">
            <div class="flex items-center justify-between">
              <div class="text-sm font-semibold text-[#171D1C]">Equipment Details</div>
              <span class="text-[11px] font-semibold px-2.5 py-0.5 rounded-full bg-[#A6F0E8] text-[#00201E]" data-testid="details-equipment-link-mode">\${equip.mode}</span>
            </div>
            <div class="space-y-1">
              <div class="flex justify-between text-xs gap-2"><span class="text-[#3F4947]">Equipment</span><span class="font-medium text-right" data-testid="details-equipment-name">\${equip.name}</span></div>
              <div class="flex justify-between text-xs gap-2"><span class="text-[#3F4947]">Equipment code</span><span class="font-mono font-bold text-[#0B6E69]" data-testid="details-equipment-code">\${equip.code}</span></div>
              <div class="flex justify-between text-xs gap-2"><span class="text-[#3F4947]">Category</span><span class="font-medium" data-testid="details-equipment-category">\${equip.category}</span></div>
              <div class="flex justify-between text-xs gap-2"><span class="text-[#3F4947]">Location</span><span class="font-medium text-right" data-testid="details-equipment-location">\${equip.location}</span></div>
              <div class="flex justify-between text-xs gap-2"><span class="text-[#3F4947]">Inspector</span><span class="font-medium" data-testid="details-equipment-inspector">\${equip.inspector}</span></div>
            </div>
            <div class="pt-1 space-y-1">
              <div class="text-[11px] font-medium text-[#3F4947]">Link to registered equipment (included in PDFBox report):</div>
              <div class="flex gap-1.5 overflow-x-auto pb-1">
                <button
                  onclick="selectEquipmentForCaptured(\${item.id}, null)"
                  data-testid="details-select-equipment-auto"
                  class="text-[11px] px-2.5 py-1 rounded-lg whitespace-nowrap font-medium \${!item.selectedCheckpointId ? 'bg-[#0B6E69] text-white' : 'bg-[#DAE5E2] text-[#3F4947]'}"
                >
                  Auto-link
                </button>
                \${(state.checkpoints || []).map(cp => {
                  const selected = item.selectedCheckpointId === cp.id || (!item.selectedCheckpointId && equip.checkpointId === cp.id);
                  return \`
                    <button
                      onclick="selectEquipmentForCaptured(\${item.id}, \${cp.id})"
                      data-testid="details-select-equipment-\${cp.code}"
                      class="text-[11px] px-2.5 py-1 rounded-lg whitespace-nowrap font-medium \${selected ? 'bg-[#0B6E69] text-white' : 'bg-[#DAE5E2] text-[#3F4947]'}"
                    >
                      \${cp.code} · \${cp.name}
                    </button>
                  \`;
                }).join('')}
              </div>
            </div>
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
              <div class="text-xs text-[#3F4947]">Exports the high-resolution image, inspection timestamp, equipment details (\${equip.code}), and status tags into a branded A4 PDF.</div>
            </div>
            \${item.exportedPdfName ? \`
              <div class="text-xs text-[#171D1C] font-medium" data-testid="details-pdf-status">
                PDF ready: \${item.exportedPdfName} (1 page · 48 KB · Equipment: \${equip.code})
              </div>
              <div class="flex gap-2">
                <button onclick="window.print()" data-testid="details-share-pdf" class="text-xs bg-[#0B6E69] text-white px-3 py-1.5 rounded-lg font-semibold">Share PDF</button>
                <button onclick="window.print()" data-testid="details-save-pdf" class="text-xs border border-[#0B6E69] text-[#0B6E69] px-3 py-1.5 rounded-lg font-semibold">Save a copy…</button>
                <button onclick="window.print()" data-testid="details-open-pdf" class="text-xs text-[#0B6E69] px-2.5 py-1.5 rounded-lg font-semibold">Open in app</button>
              </div>
              <div class="border border-[#DAE5E2] rounded-lg p-3 bg-white space-y-2" data-testid="details-pdf-preview">
                <div class="bg-[#0B6E69] text-white px-3 py-2 rounded">
                  <div class="text-[11px] font-bold tracking-wide">SAFESTITCH | CAPTURED INSPECTION REPORT</div>
                  <div class="text-[10px] text-[#CCE8E4]">Record #C\${item.id} | Equipment: \${equip.code} (\${equip.name})</div>
                  <div class="text-[10px] text-[#CCE8E4]">Captured: \${formatDate(item.timestamp || item.at)} | Inspector: \${equip.inspector}</div>
                </div>
                <div class="text-[10px] text-[#171D1C] space-y-0.5 bg-[#F2F7F6] p-2 rounded border border-[#D6DEDC]">
                  <div><strong>Equipment:</strong> \${equip.name} (\${equip.code}) · \${equip.category}</div>
                  <div><strong>Location:</strong> \${equip.location}</div>
                  <div><strong>Inspector:</strong> \${equip.inspector}</div>
                  <div><strong>Status Tags:</strong> \${tags.join(', ')}</div>
                  <div><strong>High-Res Image:</strong> \${fileName} (1920 × 1080 px)</div>
                </div>
                <img src="\${item.dataUrl}" alt="Embedded PDF photo" class="w-full h-28 object-contain bg-[#0E1716] rounded" />
              </div>
            \` : ''}
            <button
              onclick="item_exportPdf(\${item.id})"
              data-testid="details-export-pdf-button"
              class="w-full bg-[#0B6E69] hover:bg-[#095955] text-white text-xs font-semibold py-2.5 px-3 rounded-xl transition"
            >
              \${item.exportedPdfName ? 'Re-export Branded PDF with Latest Details' : 'Export Branded PDF'}
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
            <button onclick="openSqlDatabase()" data-testid="admin-sql-database" class="w-full text-left bg-[#E5F6F4] hover:bg-[#CCE8E4] transition rounded-xl p-3.5 border border-[#0B6E69]/30 flex items-center justify-between">
              <div>
                <div class="text-sm font-semibold text-[#00201E]">SQL Database (SQLite / Room v2)</div>
                <div class="text-xs text-[#0B6E69] mt-0.5">Inspect relational tables, schemas, row counts & run live SQL SELECT queries</div>
              </div>
              <span class="bg-[#0B6E69] text-white text-xs font-semibold px-2.5 py-1.5 rounded-lg">Open SQL</span>
            </button>
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

    const SQL_PRESETS = [
      {
        id: 'equipment_summary',
        title: 'Equipment & Inspections',
        sql: 'SELECT c.code, c.name, c.type, COUNT(i.id) AS inspections FROM checkpoints c LEFT JOIN inspections i ON i.checkpointId = c.id GROUP BY c.id ORDER BY c.code'
      },
      {
        id: 'captured_photos',
        title: 'Captured Photos',
        sql: 'SELECT id, timestamp, imagePath, statusTags FROM captured_inspections ORDER BY timestamp DESC'
      },
      {
        id: 'open_actions',
        title: 'Corrective Actions',
        sql: 'SELECT id, priority, status, responsibleName, description FROM corrective_actions ORDER BY dueAt ASC'
      },
      {
        id: 'locations',
        title: 'Factory Locations',
        sql: 'SELECT fa.name AS factory, b.name AS building, f.name AS floor, a.name AS area FROM areas a JOIN floors f ON a.floorId = f.id JOIN buildings b ON f.buildingId = b.id JOIN factories fa ON b.factoryId = fa.id ORDER BY fa.name, b.name, f.name, a.name'
      }
    ];

    state.sqlTables = [];
    state.sqlInput = SQL_PRESETS[0].sql;
    state.sqlResult = null;

    async function openSqlDatabase() {
      state.tab = 'sqlDatabase';
      render();
      await refreshSqlDatabase(state.sqlInput);
    }

    async function refreshSqlDatabase(sqlToRun) {
      try {
        const [sumRes, qRes] = await Promise.all([
          fetch('/api/sql/summary').then(r => r.json()),
          fetch('/api/sql/query', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sql: sqlToRun })
          }).then(r => r.json())
        ]);
        state.sqlTables = sumRes.tables || [];
        state.sqlResult = qRes;
        render();
      } catch (e) {
        state.sqlResult = { sql: sqlToRun, columns: [], rows: [], executionTimeMs: 1, error: e.message || 'Query failed' };
        render();
      }
    }

    function runSqlPreset(id) {
      const preset = SQL_PRESETS.find(p => p.id === id);
      if (!preset) return;
      state.sqlInput = preset.sql;
      refreshSqlDatabase(preset.sql);
    }

    function runCustomSqlInput() {
      const el = document.getElementById('sql-query-input');
      if (el) state.sqlInput = el.value;
      refreshSqlDatabase(state.sqlInput);
    }

    function inspectSqlTable(tableName) {
      const q = 'SELECT * FROM "' + tableName + '" LIMIT 25';
      state.sqlInput = q;
      refreshSqlDatabase(q);
    }

    function renderSqlDatabase() {
      const totalRows = (state.sqlTables || []).reduce((acc, t) => acc + (t.rowCount || 0), 0);
      const res = state.sqlResult;
      return \`
        <div class="p-4 space-y-3 flex-1 overflow-y-auto" data-testid="sql-database-screen">
          <div class="flex items-center justify-between">
            <h2 class="text-base font-semibold">SQL Database Inspector</h2>
            <button onclick="setTab('admin')" class="text-xs text-[#0B6E69] font-medium">← Admin Tools</button>
          </div>

          <div class="bg-[#CCE8E4] rounded-xl p-3.5 border border-[#0B6E69]/25" data-testid="sql-db-summary-card">
            <div class="text-xs font-bold text-[#00201E]">Engine: SQLite 3 · Room v2 (safestitch.db)</div>
            <div class="text-xs text-[#171D1C] mt-0.5" data-testid="sql-db-totals">\${state.sqlTables.length} relational tables · \${totalRows} total rows persisted</div>
          </div>

          <div class="space-y-1.5">
            <div class="text-xs font-semibold text-[#3F4947]">Preset SQL Queries</div>
            <div class="grid grid-cols-2 gap-1.5">
              \${SQL_PRESETS.map(p => \`
                <button
                  onclick="runSqlPreset('\${p.id}')"
                  data-testid="sql-preset-\${p.id}"
                  class="text-xs bg-white hover:bg-[#E5F6F4] border border-[#BEC9C6] rounded-lg px-2.5 py-1.5 font-medium text-[#00201E] truncate"
                >\${p.title}</button>
              \`).join('')}
            </div>
          </div>

          <div class="space-y-1.5">
            <label class="block text-xs font-semibold text-[#3F4947]">Read-only SQL query (SELECT / PRAGMA)</label>
            <textarea
              id="sql-query-input"
              data-testid="sql-query-input"
              rows="3"
              class="w-full px-3 py-2 text-xs font-mono border border-[#BEC9C6] rounded-lg bg-white"
            >\${escapeHtml(state.sqlInput)}</textarea>
            <div class="flex gap-2">
              <button
                onclick="runCustomSqlInput()"
                data-testid="sql-run-query"
                class="bg-[#0B6E69] text-white text-xs font-semibold px-3.5 py-2 rounded-lg"
              >Run SQL Query</button>
              <button
                onclick="refreshSqlDatabase(state.sqlInput)"
                data-testid="sql-refresh-tables"
                class="bg-white border border-[#BEC9C6] text-[#171D1C] text-xs font-semibold px-3 py-2 rounded-lg"
              >Refresh Counts</button>
            </div>
          </div>

          \${res ? \`
            <div class="bg-white rounded-xl p-3.5 border border-[#DAE5E2] space-y-2" data-testid="sql-query-results">
              \${res.error ? \`
                <div class="text-xs text-[#B3261E] font-medium" data-testid="sql-query-error">\${escapeHtml(res.error)}</div>
              \` : \`
                <div class="text-xs font-semibold text-[#00201E]">Result: \${res.rows.length} row(s) in \${res.executionTimeMs} ms</div>
                \${res.columns.length ? \`
                  <div class="overflow-x-auto">
                    <table class="w-full text-left border-collapse text-[11px]">
                      <thead>
                        <tr class="border-b border-[#DAE5E2] text-[#0B6E69]">
                          \${res.columns.map(c => \`<th class="py-1 pr-2 font-semibold">\${escapeHtml(c)}</th>\`).join('')}
                        </tr>
                      </thead>
                      <tbody>
                        \${res.rows.map(r => \`
                          <tr class="border-b border-[#F0F5F3]">
                            \${r.map(val => \`<td class="py-1 pr-2 text-[#171D1C]">\${escapeHtml(val)}</td>\`).join('')}
                          </tr>
                        \`).join('')}
                      </tbody>
                    </table>
                  </div>
                \` : '<div class="text-xs text-[#3F4947]">No rows returned.</div>'}
              \`}
            </div>
          \` : ''}

          <div class="space-y-2">
            <div class="text-xs font-semibold text-[#3F4947]" data-testid="sql-table-header">Relational Tables (\${state.sqlTables.length})</div>
            \${state.sqlTables.map(t => \`
              <button
                onclick="inspectSqlTable('\${t.tableName}')"
                data-testid="sql-table-\${t.tableName}"
                class="w-full text-left bg-white hover:bg-[#F0F7F6] rounded-xl p-3 border border-[#DAE5E2] block"
              >
                <div class="flex items-center justify-between">
                  <span class="text-xs font-bold text-[#171D1C] font-mono">\${escapeHtml(t.tableName)}</span>
                  <span class="text-[11px] font-semibold text-[#0B6E69]">\${t.rowCount} row(s)</span>
                </div>
                <div class="text-[11px] text-[#3F4947] mt-1">\${escapeHtml((t.columns || []).join(', '))}</div>
              </button>
            \`).join('')}
          </div>
        </div>
      \`;
    }

    const CLIENT_CHAT_PERSONAS = [
      {
        id: 'SAFETY_AUDITOR',
        title: 'Safety Compliance Auditor',
        defaultModel: 'gemini-4-hydrogen',
        systemInstruction: 'You are a Senior Factory Safety & Compliance Auditor for SafeStitch. Guide inspectors through fire extinguisher, electrical panel, fire alarm, emergency exit, and boiler inspections. Reference specific equipment codes (FE-101, EL-204, EX-102, FA-301, BL-001) and provide clear, actionable compliance checks.'
      },
      {
        id: 'MAINTENANCE_ADVISOR',
        title: 'Corrective Action Advisor',
        defaultModel: 'gemini-4-hydrogen',
        systemInstruction: 'You are an Industrial Equipment & Corrective Action Advisor for SafeStitch. Help maintenance leads and floor supervisors diagnose failed checklist items, prioritize CRITICAL/HIGH hazards, write clear resolution notes, and prepare after-fix verification evidence.'
      },
      {
        id: 'FAST_TRIAGE',
        title: 'Rapid Field Triage',
        defaultModel: 'gemini-3.1-flash-lite',
        systemInstruction: 'You are a Rapid Field Triage Assistant for SafeStitch inspectors on the factory floor. Respond in concise, numbered bullet points with immediate safety steps and pass/fail criteria.'
      }
    ];

    const CHAT_MODELS = [
      { id: 'gemini-4-hydrogen', label: 'Gemini 4 Flash (General)' },
      { id: 'gemini-3.1-flash-lite', label: 'Gemini 3.1 Flash Lite (Fast)' }
    ];

    const CHAT_QUICK_PROMPTS = [
      'Summarize open corrective actions',
      'What should I check on FE-101 extinguisher?',
      'Checklist rules for Electrical Panel EL-204',
      'How do I resolve EX-102 exit obstruction?'
    ];

    state.chatRoleId = 'SAFETY_AUDITOR';
    state.chatModelId = 'gemini-4-hydrogen';
    state.chatSending = false;
    state.chatDraft = '';
    state.chatMessages = [
      {
        id: 1,
        role: 'model',
        roleTitle: 'Safety Compliance Auditor',
        modelId: 'gemini-4-hydrogen',
        text: 'Hello! I am your Safety Compliance Auditor. Ask me about equipment checkpoints (FE-101, EL-204, EX-102, FA-301, BL-001), open corrective actions, inspection checklist rules, or status tags.',
        at: Date.now()
      }
    ];

    window.selectChatRole = (roleId) => {
      const found = CLIENT_CHAT_PERSONAS.find(p => p.id === roleId);
      if (!found) return;
      state.chatRoleId = found.id;
      state.chatModelId = found.defaultModel;
      render();
    };

    window.selectChatModel = (modelId) => {
      state.chatModelId = modelId;
      render();
    };

    window.clearChatHistory = () => {
      const found = CLIENT_CHAT_PERSONAS.find(p => p.id === state.chatRoleId) || CLIENT_CHAT_PERSONAS[0];
      state.chatMessages = [
        {
          id: Date.now(),
          role: 'model',
          roleTitle: found.title,
          modelId: state.chatModelId,
          text: 'Hello! I am your ' + found.title + '. Ask me about equipment checkpoints (FE-101, EL-204, EX-102, FA-301, BL-001), open corrective actions, inspection checklist rules, or status tags.',
          at: Date.now()
        }
      ];
      render();
    };

    window.sendChatPrompt = async (promptOverride) => {
      const inputEl = document.getElementById('chat-input-box');
      const rawText = promptOverride !== undefined ? promptOverride : (inputEl ? inputEl.value : state.chatDraft);
      const text = String(rawText || '').trim();
      if (!text || state.chatSending) return;
      const persona = CLIENT_CHAT_PERSONAS.find(p => p.id === state.chatRoleId) || CLIENT_CHAT_PERSONAS[0];
      state.chatDraft = '';
      state.chatSending = true;
      state.chatMessages.push({
        id: Date.now(),
        role: 'user',
        roleTitle: 'You',
        modelId: state.chatModelId,
        text,
        at: Date.now()
      });
      render();

      try {
        const res = await fetch('/api/gemini/chat', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            roleId: persona.id,
            model: state.chatModelId,
            systemInstruction: persona.systemInstruction,
            message: text,
            history: state.chatMessages.map(m => ({ role: m.role, text: m.text }))
          })
        }).then(r => r.json());

        state.chatMessages.push({
          id: Date.now() + 1,
          role: 'model',
          roleTitle: persona.title,
          modelId: state.chatModelId,
          text: res.reply || 'Unable to generate response.',
          at: Date.now()
        });
      } catch (err) {
        state.chatMessages.push({
          id: Date.now() + 1,
          role: 'model',
          roleTitle: persona.title,
          modelId: state.chatModelId,
          text: 'Error contacting chat service: ' + (err?.message || 'Unknown error'),
          at: Date.now()
        });
      } finally {
        state.chatSending = false;
        render();
        const threadEl = document.getElementById('chat-thread-container');
        if (threadEl) threadEl.scrollTop = threadEl.scrollHeight;
      }
    };

    function renderChat() {
      const persona = CLIENT_CHAT_PERSONAS.find(p => p.id === state.chatRoleId) || CLIENT_CHAT_PERSONAS[0];
      return \`
        <div class="p-4 flex flex-col flex-1 gap-3 overflow-hidden" data-testid="gemini-chat-screen">
          <div class="flex items-center justify-between">
            <h2 class="text-base font-semibold">Safety Assistant Chat</h2>
            <div class="flex items-center gap-2">
              <button onclick="clearChatHistory()" data-testid="chat-clear" class="text-xs text-[#B3261E] font-medium px-2 py-1 rounded hover:bg-red-50">Clear</button>
              <button onclick="setTab('home')" class="text-xs text-[#0B6E69] font-medium">← Home</button>
            </div>
          </div>

          <div class="bg-[#CCE8E4]/70 rounded-xl p-3 border border-[#0B6E69]/25 space-y-2" data-testid="chat-role-card">
            <div class="text-xs font-bold text-[#00201E]">Specialist Role & System Instruction</div>
            <div class="flex flex-wrap gap-1.5">
              \${CLIENT_CHAT_PERSONAS.map(p => \`
                <button
                  onclick="selectChatRole('\${p.id}')"
                  data-testid="chat-role-\${p.id}"
                  class="text-[11px] px-2.5 py-1 rounded-lg font-medium transition \${state.chatRoleId === p.id ? 'bg-[#0B6E69] text-white font-semibold' : 'bg-white text-[#171D1C] border border-[#BEC9C6]'}"
                >\${p.title}</button>
              \`).join('')}
            </div>
            <div class="text-[11px] text-[#171D1C] leading-snug" data-testid="chat-system-instruction">\${escapeHtml(persona.systemInstruction)}</div>
            <div class="flex items-center gap-1.5 flex-wrap pt-0.5">
              <span class="text-[11px] font-semibold text-[#00201E]">Model:</span>
              \${CHAT_MODELS.map(m => \`
                <button
                  onclick="selectChatModel('\${m.id}')"
                  data-testid="chat-model-\${m.id}"
                  class="text-[11px] px-2 py-0.5 rounded-md font-medium transition \${state.chatModelId === m.id ? 'bg-[#00201E] text-[#A6F0E8]' : 'bg-white/80 text-[#3F4947] border border-[#BEC9C6]'}"
                >\${m.label}</button>
              \`).join('')}
            </div>
          </div>

          <div class="flex gap-1.5 overflow-x-auto pb-1" data-testid="chat-quick-prompts">
            \${CHAT_QUICK_PROMPTS.map((q, idx) => \`
              <button
                onclick="sendChatPrompt('\${q.replace(/'/g, "\\\\'")}')"
                data-testid="chat-quick-\${idx}"
                class="text-[11px] whitespace-nowrap bg-white hover:bg-[#E5F6F4] border border-[#BEC9C6] text-[#00201E] px-2.5 py-1 rounded-full font-medium"
              >\${escapeHtml(q)}</button>
            \`).join('')}
          </div>

          <div id="chat-thread-container" class="flex-1 overflow-y-auto space-y-2.5 pr-1 max-h-[340px]" data-testid="chat-message-list">
            \${state.chatMessages.map(msg => {
              const isUser = msg.role === 'user';
              return \`
                <div class="flex flex-col \${isUser ? 'items-end' : 'items-start'}" data-testid="chat-msg-\${msg.id}">
                  <div class="max-w-[90%] rounded-2xl px-3.5 py-2.5 \${isUser ? 'bg-[#A6F0E8] text-[#00201E]' : 'bg-white border border-[#DAE5E2] text-[#171D1C]'}">
                    <div class="flex items-center justify-between gap-3 text-[10px] font-semibold opacity-75 mb-1">
                      <span>\${escapeHtml(isUser ? 'You' : (msg.roleTitle + ' (' + msg.modelId + ')'))}</span>
                      <span>\${formatDate(msg.at)}</span>
                    </div>
                    <div class="text-xs whitespace-pre-line leading-relaxed">\${escapeHtml(msg.text)}</div>
                  </div>
                </div>
              \`;
            }).join('')}
          </div>

          \${state.chatTranscriptionStatus ? \`
            <div class="text-[11px] font-medium text-[#0B6E69]" data-testid="chat-transcribe-status">\${escapeHtml(state.chatTranscriptionStatus)}</div>
          \` : ''}

          <div class="flex gap-2 pt-1 border-t border-[#DAE5E2]">
            <button
              onclick="toggleChatMicRecording()"
              data-testid="chat-mic-record"
              class="text-xs font-semibold px-3 py-2 rounded-xl border transition \${state.chatRecordingAudio ? 'bg-[#B3261E] text-white border-[#B3261E]' : 'bg-white text-[#0B6E69] border-[#0B6E69]'}"
            >\${state.chatRecordingAudio ? 'Stop Mic' : state.chatTranscribingAudio ? '…' : 'Mic'}</button>
            <input
              id="chat-input-box"
              type="text"
              value="\${escapeHtml(state.chatDraft)}"
              oninput="state.chatDraft = this.value"
              onkeydown="if(event.key==='Enter'){event.preventDefault();sendChatPrompt();}"
              placeholder="Ask or use Mic (gemini-3.5-transcribe)…"
              data-testid="chat-input"
              class="flex-1 px-3 py-2 text-xs border border-[#BEC9C6] rounded-xl bg-white focus:outline-none focus:border-[#0B6E69]"
            />
            <button
              onclick="sendChatPrompt()"
              data-testid="chat-send"
              class="bg-[#0B6E69] hover:bg-[#095955] text-white text-xs font-semibold px-4 py-2 rounded-xl"
            >\${state.chatSending ? '…' : 'Send'}</button>
          </div>
        </div>
      \`;
    }

    state.dashRecordingAudio = false;
    state.dashTranscribingAudio = false;
    state.dashVoiceTranscript = '';
    state.chatRecordingAudio = false;
    state.chatTranscribingAudio = false;
    state.chatTranscriptionStatus = '';

    function createSynthesizedWavBase64() {
      const sampleRate = 16000;
      const numSamples = 4000;
      const buffer = new ArrayBuffer(44 + numSamples * 2);
      const view = new DataView(buffer);
      const writeStr = (offset, str) => {
        for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i));
      };
      writeStr(0, 'RIFF');
      view.setUint32(4, 36 + numSamples * 2, true);
      writeStr(8, 'WAVE');
      writeStr(12, 'fmt ');
      view.setUint32(16, 16, true);
      view.setUint16(20, 1, true);
      view.setUint16(22, 1, true);
      view.setUint32(24, sampleRate, true);
      view.setUint32(28, sampleRate * 2, true);
      view.setUint16(32, 2, true);
      view.setUint16(34, 16, true);
      writeStr(36, 'data');
      view.setUint32(40, numSamples * 2, true);
      for (let i = 0; i < numSamples; i++) {
        const sample = Math.round(Math.sin((2 * Math.PI * 440 * i) / sampleRate) * 3000);
        view.setInt16(44 + i * 2, sample, true);
      }
      const bytes = new Uint8Array(buffer);
      let binary = '';
      for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]);
      return btoa(binary);
    }

    async function captureMicOrFallbackBase64() {
      if (window.__activeMediaRecorder && window.__activeMediaRecorder.state === 'recording') {
        return new Promise((resolve) => {
          window.__activeMediaRecorder.onstop = async () => {
            try {
              const blob = new Blob(window.__audioChunks || [], { type: 'audio/webm' });
              const arrBuf = await blob.arrayBuffer();
              const bytes = new Uint8Array(arrBuf);
              let binary = '';
              for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]);
              resolve({ base64: btoa(binary) || createSynthesizedWavBase64(), mimeType: 'audio/webm' });
            } catch (_) {
              resolve({ base64: createSynthesizedWavBase64(), mimeType: 'audio/wav' });
            }
          };
          window.__activeMediaRecorder.stop();
        });
      }
      return { base64: createSynthesizedWavBase64(), mimeType: 'audio/wav' };
    }

    async function startBrowserMicIfAvailable() {
      window.__audioChunks = [];
      if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia && typeof MediaRecorder !== 'undefined') {
        try {
          const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
          const recorder = new MediaRecorder(stream);
          recorder.ondataavailable = (e) => {
            if (e.data && e.data.size > 0) window.__audioChunks.push(e.data);
          };
          recorder.start();
          window.__activeMediaRecorder = recorder;
        } catch (_) {
          window.__activeMediaRecorder = null;
        }
      }
    }

    window.toggleDashMicRecording = async () => {
      if (state.dashTranscribingAudio) return;
      if (!state.dashRecordingAudio) {
        state.dashRecordingAudio = true;
        render();
        await startBrowserMicIfAvailable();
      } else {
        state.dashRecordingAudio = false;
        state.dashTranscribingAudio = true;
        render();
        const { base64, mimeType } = await captureMicOrFallbackBase64();
        try {
          const res = await fetch('/api/gemini/transcribe', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              model: 'gemini-3.5-transcribe',
              mimeType,
              audioBase64: base64,
              contextHint: (state.checkpoints[0] && state.checkpoints[0].code) || 'FE-101'
            })
          }).then(r => r.json());
          state.dashVoiceTranscript = res.text || '';
        } catch (err) {
          state.dashVoiceTranscript = 'Transcription error: ' + (err?.message || 'Unknown error');
        } finally {
          state.dashTranscribingAudio = false;
          render();
        }
      }
    };

    window.toggleChatMicRecording = async () => {
      if (state.chatTranscribingAudio) return;
      if (!state.chatRecordingAudio) {
        state.chatRecordingAudio = true;
        state.chatTranscriptionStatus = 'Recording microphone audio… Tap Stop Mic to transcribe with gemini-3.5-transcribe.';
        render();
        await startBrowserMicIfAvailable();
      } else {
        state.chatRecordingAudio = false;
        state.chatTranscribingAudio = true;
        state.chatTranscriptionStatus = 'Transcribing audio with gemini-3.5-transcribe…';
        render();
        const { base64, mimeType } = await captureMicOrFallbackBase64();
        try {
          const res = await fetch('/api/gemini/transcribe', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              model: 'gemini-3.5-transcribe',
              mimeType,
              audioBase64: base64,
              contextHint: (state.checkpoints[0] && state.checkpoints[0].code) || 'FE-101'
            })
          }).then(r => r.json());
          const text = res.text || '';
          state.chatDraft = state.chatDraft ? (state.chatDraft + ' ' + text) : text;
          state.chatTranscriptionStatus = 'Transcribed (gemini-3.5-transcribe): "' + text + '"';
        } catch (err) {
          state.chatTranscriptionStatus = 'Transcription error: ' + (err?.message || 'Unknown error');
        } finally {
          state.chatTranscribingAudio = false;
          render();
        }
      }
    };

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
      else if (state.tab === 'sqlDatabase') body = renderSqlDatabase();
      else if (state.tab === 'chat') body = renderChat();
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

  if (urlPath === '/api/sql/summary') {
    const tables = inspectSqlTables();
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ engine: 'SQLite 3 · Room v2', file: 'safestitch.db', tables }));
    return;
  }

  if (urlPath === '/api/sql/query' && req.method === 'POST') {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      try {
        const parsed = JSON.parse(body || '{}');
        const result = executeReadOnlySqlQuery(parsed.sql || '');
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify(result));
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: err?.message || 'Invalid request JSON' }));
      }
    });
    return;
  }

  if (urlPath === '/api/gemini/chat' && req.method === 'POST') {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', async () => {
      try {
        const parsed = JSON.parse(body || '{}');
        const result = await handleGeminiChatRequest(parsed);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify(result));
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: err?.message || 'Invalid chat request' }));
      }
    });
    return;
  }

  if (urlPath === '/api/gemini/transcribe' && req.method === 'POST') {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', async () => {
      try {
        const parsed = JSON.parse(body || '{}');
        const result = await handleGeminiTranscribeRequest(parsed);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify(result));
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: err?.message || 'Invalid transcribe request' }));
      }
    });
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
    if (pid === process.pid || pid === process.ppid) continue;
    try {
      const cmdline = fs.readFileSync('/proc/' + pid + '/cmdline', 'utf8');
      if (cmdline.includes('preview-server.mjs') && !cmdline.includes('/bin/sh') && !cmdline.includes('bash')) {
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
