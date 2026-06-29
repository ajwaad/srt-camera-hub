/**
 * Build script: Create srt-camera-hub.exe using Node.js SEA (Single Executable Application)
 * 
 * Steps:
 *   1. Bundle hub.js + dashboard.html + ws dependency into single JS file using esbuild
 *   2. Generate SEA blob using node --experimental-sea-config
 *   3. Copy node.exe → srt-camera-hub.exe
 *   4. Inject blob using postject
 * 
 * Usage: node build-runtime.js
 */

const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const DIST = path.join(ROOT, 'releases');
const BUNDLE = path.join(DIST, 'hub-bundle.js');
const SEA_CONFIG = path.join(DIST, 'sea-config.json');
const SEA_BLOB = path.join(DIST, 'sea-prep.blob');
const EXE_NAME = 'srt-camera-hub.exe';
const EXE_PATH = path.join(DIST, EXE_NAME);

function run(cmd, opts = {}) {
  console.log(`  > ${cmd}`);
  return execSync(cmd, { cwd: ROOT, stdio: 'inherit', ...opts });
}

// ── Step 0: Prepare dist directory ──────────────────────────────────────────
console.log('\n[1/5] Preparing dist directory...');
if (fs.existsSync(DIST)) {
  fs.rmSync(DIST, { recursive: true, force: true });
}
fs.mkdirSync(DIST, { recursive: true });

// ── Step 1: Create the bundled JS file ──────────────────────────────────────
console.log('\n[2/5] Bundling hub.js + dashboard.html + ws into single JS...');

// Read dashboard.html and embed it
const dashboardHTML = fs.readFileSync(path.join(ROOT, 'desktop-hub', 'dashboard.html'), 'utf8');

// Create entry point in the ROOT directory (where hub.js and node_modules live)
const entryPath = path.join(ROOT, 'desktop-hub', '_sea-entry.js');
const entrySrc = `
// SEA entry point — inlines dashboard.html so no file reads needed at runtime
const __DASHBOARD_HTML__ = ${JSON.stringify(dashboardHTML)};

const origReadFileSync = require('fs').readFileSync;
const origExistsSync = require('fs').existsSync;
const _path = require('path');

// The hub uses: path.join(__dirname, 'dashboard.html')
// In SEA, __dirname points to the exe's directory, but the file won't exist.
// Intercept all reads for 'dashboard.html' regardless of directory.

const _origFS = { ...require('fs') };

require('fs').existsSync = function(p) {
  if (typeof p === 'string' && _path.basename(p) === 'dashboard.html') {
    return true;
  }
  return origExistsSync.call(this, p);
};

require('fs').readFileSync = function(p, enc) {
  if (typeof p === 'string' && _path.basename(p) === 'dashboard.html') {
    return enc ? __DASHBOARD_HTML__ : Buffer.from(__DASHBOARD_HTML__);
  }
  return origReadFileSync.call(this, p, enc);
};

// Now load the hub
require('./hub.js');
`;
fs.writeFileSync(entryPath, entrySrc);

try {
  // esbuild bundles from ROOT so it can find ./hub.js and node_modules/ws
  run(`npx.cmd -y esbuild "${entryPath}" --bundle --platform=node --target=node22 --outfile="${BUNDLE}" --external:bufferutil --external:utf-8-validate`);
} finally {
  // Clean up temp entry file
  try { fs.unlinkSync(entryPath); } catch {}
}

// Verify bundle exists
if (!fs.existsSync(BUNDLE)) {
  console.error('ERROR: Bundle was not created');
  process.exit(1);
}
console.log(`  Bundle size: ${(fs.statSync(BUNDLE).size / 1024).toFixed(1)} KB`);

// ── Step 2: Generate SEA config ─────────────────────────────────────────────
console.log('\n[3/5] Generating SEA blob...');

const seaConfig = {
  main: BUNDLE,
  output: SEA_BLOB,
  disableExperimentalSEAWarning: true,
  useSnapshot: false,
  useCodeCache: true
};
fs.writeFileSync(SEA_CONFIG, JSON.stringify(seaConfig, null, 2));

run(`node --experimental-sea-config "${SEA_CONFIG}"`);

if (!fs.existsSync(SEA_BLOB)) {
  console.error('ERROR: SEA blob was not created');
  process.exit(1);
}
console.log(`  Blob size: ${(fs.statSync(SEA_BLOB).size / 1024).toFixed(1)} KB`);

// ── Step 3: Copy node.exe ───────────────────────────────────────────────────
console.log('\n[4/5] Creating executable...');

const nodePath = process.execPath;
fs.copyFileSync(nodePath, EXE_PATH);
console.log(`  Copied node.exe (${(fs.statSync(EXE_PATH).size / 1024 / 1024).toFixed(1)} MB)`);

// ── Step 4: Inject blob with postject ───────────────────────────────────────
console.log('\n[5/5] Injecting SEA blob into executable...');

// Remove the signature first (Windows code signing) — needed for postject to work
try {
  run(`npx.cmd -y @aspect-build/signtool remove "${EXE_PATH}"`, { stdio: 'pipe' });
  console.log('  Signature removed');
} catch {
  // No signature or signtool unavailable — try postject anyway
  console.log('  (No signature to remove or signtool not available — continuing)');
}

run(`npx.cmd -y postject "${EXE_PATH}" NODE_SEA_BLOB "${SEA_BLOB}" --sentinel-fuse NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2`);

console.log(`\n✅ Built: ${EXE_PATH}`);
console.log(`   Size: ${(fs.statSync(EXE_PATH).size / 1024 / 1024).toFixed(1)} MB`);
console.log(`\n   Run:       .\\dist\\${EXE_NAME}`);
console.log(`   Dashboard: http://localhost:3001\n`);
