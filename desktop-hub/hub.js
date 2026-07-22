/**
 * SRT Camera Hub — Single-process WebSocket signaling + dashboard server.
 *
 * Architecture:
 *   Cameras (Android) ──WebSocket──> Hub <──WebSocket── Directors (browser)
 *                                      │
 *                                  HTTP :3001
 *                                      │
 *                               Dashboard (/) + /api/ips
 *
 * Dependencies: ws (WebSocket), node built-ins (http, os)
 *
 * Usage:  npm install && npm start
 *         then open http://localhost:3001 in a browser.
 */

const http = require("node:http");
const os = require("node:os");
const fs = require("node:fs");
const path = require("node:path");
const { WebSocketServer, WebSocket } = require("ws");

// ── Config ────────────────────────────────────────────────────────────────────
const PORT = parseInt(process.env.PORT, 10) || 3001;
const HEARTBEAT_MS = 3_000;      // Fast 3s heartbeat ping
const STALE_TIMEOUT_MS = 6_000;  // Quick 6s stale eviction
const MAX_CAMERAS = 20;
const MAX_DIRECTORS = 10;
const RATE_LIMIT = 60; // messages per window
const RATE_WINDOW_MS = 2000;

// ── Cache & State ─────────────────────────────────────────────────────────────
const cameras = new Map(); // id -> { ws, port, cameras[], activeCameraId, registeredAt }
const directors = new Map(); // id -> ws
const usedPorts = new Map(); // port -> cameraId
const heartbeats = new Map(); // cameraId -> timestamp
const rateState = new Map(); // ws -> { count, windowStart }

let directorCounter = 0;
let cachedGuestListJson = null;
let cachedIpsJson = null;
let cachedIpsTs = 0;

// ── Helpers ───────────────────────────────────────────────────────────────────
function safeStr(val, fallback = "") {
  if (typeof val !== "string" || val.length === 0) return fallback;
  return val.replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 64) || fallback;
}

function safePort(val, fallback = 0) {
  const n = Number(val);
  if (!Number.isFinite(n) || n < 1024 || n > 65535) return fallback;
  return Math.floor(n);
}

function detectIPs() {
  const now = Date.now();
  if (cachedIpsJson && now - cachedIpsTs < 5000) return cachedIpsJson;
  const ips = { lan: [], wifi: [], tailscale: null, all: [] };
  const ifaces = os.networkInterfaces();
  for (const [name, addrs] of Object.entries(ifaces)) {
    if (!addrs) continue;
    for (const a of addrs) {
      if (a.family === "IPv4" && !a.internal) {
        ips.all.push(a.address);
        const lower = name.toLowerCase();
        if (a.address.startsWith("100.")) {
          ips.tailscale = a.address;
        } else if (lower.includes("wi-fi") || lower.includes("wlan") || lower.includes("wireless") || lower.includes("wifi")) {
          ips.wifi.push(a.address);
        } else {
          ips.lan.push(a.address);
        }
      }
    }
  }
  cachedIpsJson = ips;
  cachedIpsTs = now;
  return ips;
}

function invalidateCaches() {
  cachedGuestListJson = null;
}

function broadcast(msg) {
  const data = typeof msg === "string" ? msg : JSON.stringify(msg);
  for (const ws of directors.values()) {
    if (ws.readyState === WebSocket.OPEN) ws.send(data);
  }
}

function broadcastGuestList() {
  if (!cachedGuestListJson) {
    cachedGuestListJson = JSON.stringify({ type: "guest_list", guests: guestList() });
  }
  broadcast(cachedGuestListJson);
}

function guestList() {
  const guests = [];
  for (const [id, cam] of cameras) {
    guests.push({
      id,
      port: cam.port,
      cameras: cam.cameras,
      activeCameraId: cam.activeCameraId,
      tally: cam.tally || "OFF AIR",
      lossPct: cam.lossPct || 0,
      rttMs: cam.rttMs || 0,
      targetBitrate: cam.targetBitrate || 5000,
    });
  }
  return guests;
}

// ── HTTP server (with in-memory file cache) ───────────────────────────────────
const DASHBOARD_HTML = path.join(__dirname, "dashboard.html");
let DASHBOARD_CACHE = null;

function getDashboardHtml() {
  if (!DASHBOARD_CACHE && fs.existsSync(DASHBOARD_HTML)) {
    DASHBOARD_CACHE = fs.readFileSync(DASHBOARD_HTML, "utf8");
  }
  return DASHBOARD_CACHE || `<!doctype html><meta charset=utf-8><title>SRT Hub</title><h1>SRT Camera Hub</h1>`;
}

const server = http.createServer((req, res) => {
  res.setHeader("Access-Control-Allow-Origin", "*");

  if (req.method === "GET" && (req.url === "/" || req.url === "/dashboard")) {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(getDashboardHtml());
    return;
  }

  if (req.method === "GET" && req.url === "/api/ips") {
    const ips = detectIPs();
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      lanIps: ips.lan,
      wifiIps: ips.wifi,
      allIps: ips.all,
      tailscaleIp: ips.tailscale,
      wsPort: PORT,
      cameras: cameras.size,
      directors: directors.size,
      uptime: Math.floor(process.uptime()),
    }));
    return;
  }

  if (req.method === "GET" && req.url.startsWith("/api/tally")) {
    const urlObj = new URL(req.url, `http://localhost:${PORT}`);
    const id = urlObj.searchParams.get("id");
    const port = urlObj.searchParams.get("port");
    let rawState = (urlObj.searchParams.get("state") || "OFF AIR").toUpperCase();
    if (rawState === "STAGE" || rawState === "ON STAGE") rawState = "BACKSTAGE";
    const state = rawState;

    let targetCam = null;
    let targetId = id;

    if (id && cameras.has(id)) {
      targetCam = cameras.get(id);
    } else if (port) {
      const p = Number(port);
      for (const [cId, cam] of cameras) {
        if (cam.port === p) {
          targetCam = cam;
          targetId = cId;
          break;
        }
      }
    }

    if (targetCam) {
      targetCam.tally = state;
      if (targetCam.ws && targetCam.ws.readyState === WebSocket.OPEN) {
        targetCam.ws.send(JSON.stringify({ type: "tally", state }));
      }
      broadcast({ type: "guest_list", guests: guestList() });
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ success: true, id: targetId, tally: state }));
    } else {
      res.writeHead(404, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "camera not found" }));
    }
    return;
  }

  res.writeHead(404);
  res.end(JSON.stringify({ error: "not found" }));
});

// ── WebSocket ─────────────────────────────────────────────────────────────────
const wss = new WebSocketServer({ noServer: true, maxPayload: 10240 });

server.on("upgrade", (req, socket, head) => {
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit("connection", ws, req);
  });
});

wss.on("connection", (ws) => {
  let clientId = null;
  let isCamera = false;
  let isDirector = false;

  rateState.set(ws, { count: 0, windowStart: Date.now() });
  ws.isAlive = true;
  ws.on("pong", () => {
    ws.isAlive = true;
  });

  ws.on("message", (raw) => {
    // Rate limit
    const rs = rateState.get(ws);
    if (rs) {
      const now = Date.now();
      if (now - rs.windowStart > RATE_WINDOW_MS) {
        rs.count = 0;
        rs.windowStart = now;
      }
      if (++rs.count > RATE_LIMIT) {
        ws.close(4029, "rate limit");
        return;
      }
    }

    let pkt;
    try {
      pkt = JSON.parse(raw.toString());
    } catch {
      return;
    }

    // ── Camera registration ────────────────────────────────────────────────
    if (pkt.type === "register") {
      const id = safeStr(pkt.id, "cam_" + Date.now().toString(36));
      const port = safePort(pkt.port, 0);

      if (!id || !port) {
        ws.send(JSON.stringify({ type: "error", message: "Invalid id or port" }));
        ws.close(4000, "bad register");
        return;
      }

      if (!cameras.has(id) && cameras.size >= MAX_CAMERAS) {
        ws.send(JSON.stringify({ type: "error", message: "Hub full" }));
        ws.close(4011, "full");
        return;
      }

      // Port conflict check
      const owner = usedPorts.get(port);
      if (owner && owner !== id) {
        ws.send(JSON.stringify({ type: "error", message: `Port ${port} in use by ${owner}` }));
        ws.close(4012, "port conflict");
        return;
      }

      // Handle duplicate camera ID — only allow if old connection is dead (reconnect)
      const old = cameras.get(id);
      if (old) {
        if (old.ws !== ws && old.ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: "error", message: `Camera ID "${id}" is already in use by another device. Change the stream ID on one of your phones (tap the ID field).` }));
          ws.close(4009, "duplicate id");
          return;
        }
        // Old connection is dead — clean up before replacing
        try { old.ws.close(4009, "replaced"); } catch {}
      }

      const camList = Array.isArray(pkt.cameras)
        ? pkt.cameras.filter(c => c && typeof c === "object").slice(0, 10).map(c => ({
            id: safeStr(c.id, "0"),
            name: String(c.name || "Camera").replace(/[<>&"']/g, "").slice(0, 64),
            facing: safeStr(c.facing, "back"),
          }))
        : [];

      const activeCamId = safeStr(pkt.activeCameraId, camList[0]?.id || "0");

      const existingTally = old ? (old.tally || "OFF AIR") : "OFF AIR";
      clientId = id;
      isCamera = true;
      cameras.set(id, { ws, port, cameras: camList, activeCameraId: activeCamId, tally: existingTally, registeredAt: Date.now() });
      usedPorts.set(port, id);
      heartbeats.set(id, Date.now());

      console.log(`[cam] ${id} on port ${port} (tally: ${existingTally}, total: ${cameras.size})`);
      ws.send(JSON.stringify({ type: "welcome", id, port }));
      ws.send(JSON.stringify({ type: "tally", state: existingTally }));

      invalidateCaches();
      broadcastGuestList();
      return;
    }

    // ── Director registration ───────────────────────────────────────────────
    if (pkt.type === "director") {
      if (directors.size >= MAX_DIRECTORS) {
        ws.close(4010, "too many directors");
        return;
      }
      clientId = "dir_" + (++directorCounter);
      isDirector = true;
      directors.set(clientId, ws);
      console.log(`[dir] ${clientId} joined (total: ${directors.size})`);

      // Send current state
      invalidateCaches();
      ws.send(cachedGuestListJson || JSON.stringify({ type: "guest_list", guests: guestList() }));
      return;
    }

    // ── Camera command (director → camera) ──────────────────────────────────
    if (pkt.type === "set_tally") {
      const targetId = safeStr(pkt.targetId);
      let rawState = (pkt.tally || "OFF AIR").toUpperCase();
      if (rawState === "STAGE" || rawState === "ON STAGE") rawState = "BACKSTAGE";
      const state = rawState;
      const target = targetId ? cameras.get(targetId) : null;
      if (target) {
        target.tally = state;
        if (target.ws && target.ws.readyState === WebSocket.OPEN) {
          target.ws.send(JSON.stringify({ type: "tally", state }));
        }
        invalidateCaches();
        broadcastGuestList();
      }
      return;
    }

    if (pkt.type === "cmd" && isDirector) {
      const targetId = safeStr(pkt.targetId);
      const target = targetId ? cameras.get(targetId) : null;
      if (!target || target.ws.readyState !== WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: "error", message: `Camera ${targetId} not connected` }));
        return;
      }
      const cmd = pkt.command;
      if (!cmd || !["zoom", "torch", "exposure", "select_camera"].includes(cmd.type)) {
        ws.send(JSON.stringify({ type: "error", message: "Invalid command" }));
        return;
      }
      target.ws.send(JSON.stringify({ type: cmd.type, value: cmd.value }));
      return;
    }

    // ── Camera update (camera → hub) ───────────────────────────────────────
    if (pkt.type === "camera_update" && isCamera) {
      const cam = cameras.get(clientId);
      if (cam && pkt.activeCameraId) {
        cam.activeCameraId = safeStr(pkt.activeCameraId, cam.activeCameraId);
        invalidateCaches();
        broadcastGuestList();
      }
      return;
    }

    // ── Telemetry & ABR Control (camera → hub) ─────────────────────────────
    if (pkt.type === "telemetry" && isCamera) {
      const cam = cameras.get(clientId);
      if (cam) {
        cam.lossPct = Number(pkt.lossPct) || 0;
        cam.rttMs = Number(pkt.rttMs) || 0;
        cam.droppedFrames = Number(pkt.droppedFrames) || 0;

        // Adaptive Bitrate Logic: Adjust encoder bitrate on congestion / recovery
        if (cam.lossPct > 3.0 || cam.rttMs > 250) {
          const newBitrate = Math.max(1500, Math.floor((cam.targetBitrate || 5000) * 0.75));
          if (newBitrate !== cam.targetBitrate) {
            cam.targetBitrate = newBitrate;
            ws.send(JSON.stringify({ type: "abr_adjust", bitrate: newBitrate }));
          }
        } else if (cam.lossPct < 0.5 && cam.rttMs < 100) {
          const newBitrate = Math.min(8000, Math.floor((cam.targetBitrate || 5000) * 1.1));
          if (newBitrate !== cam.targetBitrate) {
            cam.targetBitrate = newBitrate;
            ws.send(JSON.stringify({ type: "abr_adjust", bitrate: newBitrate }));
          }
        }
        invalidateCaches();
        broadcastGuestList();
      }
      return;
    }

    // Heartbeat refresh
    if (isCamera && clientId) {
      heartbeats.set(clientId, Date.now());
    }

    // Echo pings
    if (pkt.type === "ping") {
      ws.send(JSON.stringify({ type: "pong", t: pkt.t }));
    }
  });

  ws.on("close", () => {
    rateState.delete(ws);
    if (isCamera && clientId) {
      const cam = cameras.get(clientId);
      if (cam && cam.ws === ws) {
        usedPorts.delete(cam.port);
        cameras.delete(clientId);
        heartbeats.delete(clientId);
        console.log(`[cam] ${clientId} disconnected (${cameras.size} remaining)`);
        broadcast({ type: "guest_list", guests: guestList() });
      }
    }
    if (isDirector && clientId) {
      directors.delete(clientId);
      console.log(`[dir] ${clientId} left (${directors.size} remaining)`);
    }
  });

  ws.on("error", () => {
    rateState.delete(ws);
  });
});

// ── Heartbeat: detect dead connections ────────────────────────────────────────
setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
}, HEARTBEAT_MS);

// ── Stale camera eviction ────────────────────────────────────────────────────
setInterval(() => {
  const now = Date.now();
  for (const [id, ts] of heartbeats) {
    if (now - ts > STALE_TIMEOUT_MS) {
      const cam = cameras.get(id);
      if (cam) {
        usedPorts.delete(cam.port);
        try { cam.ws.terminate(); } catch {}
        cameras.delete(id);
      }
      heartbeats.delete(id);
      console.log(`[stale] ${id} evicted`);
      broadcast({ type: "guest_list", guests: guestList() });
    }
  }
}, 8000);

// ── Start ─────────────────────────────────────────────────────────────────────
server.listen(PORT, () => {
  const ips = detectIPs();
  console.log(`\n  SRT Camera Hub ready on http://0.0.0.0:${PORT}`);
  if (ips.lan.length) console.log(`  LAN:    http://${ips.lan[0]}:${PORT}`);
  if (ips.wifi.length) console.log(`  Wi-Fi:  http://${ips.wifi[0]}:${PORT}`);
  if (ips.tailscale) console.log(`  TS:     http://${ips.tailscale}:${PORT}`);
  console.log("");
});

// ── Graceful shutdown ─────────────────────────────────────────────────────────
process.on("SIGINT", () => {
  console.log("\n[shutdown] closing...");
  wss.clients.forEach((ws) => ws.close());
  wss.close();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 2000);
});
