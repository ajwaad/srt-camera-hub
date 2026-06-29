/**
 * Build script: Create install-srt-plugin.exe — a self-extracting installer
 * that copies obs-srt-source.dll + locale files to the OBS Studio directory.
 * 
 * The installer:
 *   - Auto-detects OBS install location from registry and common paths
 *   - Copies DLL to obs-plugins/64bit/
 *   - Copies locale to data/obs-plugins/obs-srt-source/locale/
 *   - Self-elevates to admin if needed (writes to Program Files)
 * 
 * Usage: node build-plugin-installer.js
 */

const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const DIST = path.join(ROOT, 'releases');
const INSTALLER_ENTRY = path.join(ROOT, '_plugin-installer-entry.js');
const INSTALLER_BUNDLE = path.join(DIST, 'installer-bundle.js');
const SEA_CONFIG = path.join(DIST, 'installer-sea-config.json');
const SEA_BLOB = path.join(DIST, 'installer-sea-prep.blob');
const EXE_NAME = 'install-srt-plugin.exe';
const EXE_PATH = path.join(DIST, EXE_NAME);

// Files to embed
const DLL_PATH = path.join(ROOT, 'obs-plugin', 'build_x64', 'RelWithDebInfo', 'obs-srt-source.dll');
const LOCALE_PATH = path.join(ROOT, 'obs-plugin', 'data', 'locale', 'en-US.ini');

function run(cmd, opts = {}) {
  console.log(`  > ${cmd}`);
  return execSync(cmd, { cwd: ROOT, stdio: 'inherit', ...opts });
}

// ── Verify source files exist ───────────────────────────────────────────────
console.log('\n[0] Verifying source files...');
if (!fs.existsSync(DLL_PATH)) {
  console.error(`ERROR: DLL not found: ${DLL_PATH}`);
  console.error('Build the OBS plugin first (MSBuild)');
  process.exit(1);
}
if (!fs.existsSync(LOCALE_PATH)) {
  console.error(`ERROR: Locale file not found: ${LOCALE_PATH}`);
  process.exit(1);
}

const dllBytes = fs.readFileSync(DLL_PATH);
const localeBytes = fs.readFileSync(LOCALE_PATH);

console.log(`  DLL: ${DLL_PATH} (${dllBytes.length} bytes)`);
console.log(`  Locale: ${LOCALE_PATH} (${localeBytes.length} bytes)`);

// ── Create installer JS ─────────────────────────────────────────────────────
console.log('\n[1/4] Creating installer script...');

if (!fs.existsSync(DIST)) {
  fs.mkdirSync(DIST, { recursive: true });
}

const installerSrc = `
'use strict';
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

// Embedded binary data (base64-encoded)
const DLL_B64 = ${JSON.stringify(dllBytes.toString('base64'))};
const LOCALE_B64 = ${JSON.stringify(localeBytes.toString('base64'))};

const DLL_DATA = Buffer.from(DLL_B64, 'base64');
const LOCALE_DATA = Buffer.from(LOCALE_B64, 'base64');

// ── Self-elevation ──────────────────────────────────────────────────────────

function isAdmin() {
  try {
    execSync('net session', { stdio: 'pipe' });
    return true;
  } catch {
    return false;
  }
}

function selfElevate() {
  const exe = process.execPath;
  const args = process.argv.slice(1).join(' ');
  try {
    // Use PowerShell Start-Process with -Verb RunAs for UAC elevation
    execSync(
      'powershell -Command "Start-Process -FilePath \\\'' + exe.replace(/'/g, "''") + '\\' -Verb RunAs -Wait"',
      { stdio: 'inherit' }
    );
    process.exit(0);
  } catch (e) {
    console.error('ERROR: Could not elevate to administrator.');
    console.error('Please right-click the installer and select "Run as administrator".');
    process.exit(1);
  }
}

// ── Find OBS ────────────────────────────────────────────────────────────────

function findOBS() {
  const candidates = [
    'C:\\\\Program Files\\\\obs-studio',
    'C:\\\\Program Files (x86)\\\\obs-studio',
    'D:\\\\Program Files\\\\obs-studio',
    'D:\\\\obs-studio',
  ];

  // Try registry
  try {
    const regOut = execSync(
      'reg query "HKLM\\\\SOFTWARE\\\\OBS Studio" /v "" 2>nul',
      { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }
    );
    const match = regOut.match(/REG_SZ\\s+(.+)/);
    if (match && match[1]) {
      const regPath = match[1].trim();
      if (fs.existsSync(regPath)) {
        candidates.unshift(regPath);
      }
    }
  } catch {}

  // Try user-level install
  try {
    const appData = process.env.LOCALAPPDATA || '';
    if (appData) {
      candidates.push(path.join(appData, 'obs-studio'));
    }
  } catch {}

  for (const dir of candidates) {
    const obsExe = path.join(dir, 'bin', '64bit', 'obs64.exe');
    if (fs.existsSync(obsExe) || fs.existsSync(path.join(dir, 'bin', '64bit'))) {
      return dir;
    }
  }
  return null;
}

// ── Main ────────────────────────────────────────────────────────────────────

console.log('');
console.log('============================================');
console.log('  SRT Source Plugin Installer for OBS Studio');
console.log('============================================');
console.log('');

const obsDir = findOBS();

if (!obsDir) {
  console.error('ERROR: OBS Studio installation not found.');
  console.error('Checked: C:\\\\Program Files\\\\obs-studio and common locations.');
  console.error('');
  console.error('Please install OBS Studio first, then run this installer again.');
  if (typeof process.stdin.setRawMode === 'function') {
    console.log('\\nPress any key to exit...');
    process.stdin.setRawMode(true);
    process.stdin.resume();
    process.stdin.once('data', () => process.exit(1));
  } else {
    process.exit(1);
  }
  return;
}

console.log('Found OBS: ' + obsDir);
console.log('');

// Check admin
if (!isAdmin()) {
  console.log('Requesting administrator privileges...');
  selfElevate();
  return;
}

// Target paths
const dllDir = path.join(obsDir, 'obs-plugins', '64bit');
const localeDir = path.join(obsDir, 'data', 'obs-plugins', 'obs-srt-source', 'locale');
const dllDest = path.join(dllDir, 'obs-srt-source.dll');
const localeDest = path.join(localeDir, 'en-US.ini');

let success = true;

// Install DLL
try {
  fs.mkdirSync(dllDir, { recursive: true });
  fs.writeFileSync(dllDest, DLL_DATA);
  console.log('  [OK] ' + dllDest + ' (' + DLL_DATA.length + ' bytes)');
} catch (e) {
  console.error('  [FAIL] DLL: ' + e.message);
  success = false;
}

// Install locale
try {
  fs.mkdirSync(localeDir, { recursive: true });
  fs.writeFileSync(localeDest, LOCALE_DATA);
  console.log('  [OK] ' + localeDest + ' (' + LOCALE_DATA.length + ' bytes)');
} catch (e) {
  console.error('  [FAIL] Locale: ' + e.message);
  success = false;
}

console.log('');
if (success) {
  console.log('Installation complete!');
  console.log('Restart OBS Studio and look for "SRT Source" in the Add Source menu.');
} else {
  console.log('Installation had errors. Try running as administrator.');
}

console.log('');

// Keep console open
if (typeof process.stdin.setRawMode === 'function') {
  console.log('Press any key to exit...');
  process.stdin.setRawMode(true);
  process.stdin.resume();
  process.stdin.once('data', () => process.exit(success ? 0 : 1));
} else {
  // Wait briefly for user to read output
  setTimeout(() => process.exit(success ? 0 : 1), 5000);
}
`;

fs.writeFileSync(INSTALLER_ENTRY, installerSrc);

// ── Bundle (no external deps, but use esbuild for consistency) ──────────────
console.log('\n[2/4] Bundling installer...');

try {
  run(`npx.cmd -y esbuild "${INSTALLER_ENTRY}" --bundle --platform=node --target=node22 --outfile="${INSTALLER_BUNDLE}"`);
} finally {
  try { fs.unlinkSync(INSTALLER_ENTRY); } catch {}
}

if (!fs.existsSync(INSTALLER_BUNDLE)) {
  console.error('ERROR: Installer bundle was not created');
  process.exit(1);
}
console.log(`  Bundle size: ${(fs.statSync(INSTALLER_BUNDLE).size / 1024).toFixed(1)} KB`);

// ── SEA config ──────────────────────────────────────────────────────────────
console.log('\n[3/4] Generating SEA blob...');

const seaConfig = {
  main: INSTALLER_BUNDLE,
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

// ── Copy node.exe and inject ────────────────────────────────────────────────
console.log('\n[4/4] Creating installer executable...');

const nodePath = process.execPath;
fs.copyFileSync(nodePath, EXE_PATH);

// Remove signature
try {
  run(`npx.cmd -y @aspect-build/signtool remove "${EXE_PATH}"`, { stdio: 'pipe' });
} catch {
  console.log('  (No signature to remove — continuing)');
}

// Inject blob
run(`npx.cmd -y postject "${EXE_PATH}" NODE_SEA_BLOB "${SEA_BLOB}" --sentinel-fuse NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2`);

console.log(`\n✅ Built: ${EXE_PATH}`);
console.log(`   Size: ${(fs.statSync(EXE_PATH).size / 1024 / 1024).toFixed(1)} MB`);
console.log(`\n   To install the OBS plugin on any machine, just run:`);
console.log(`   .\\dist\\${EXE_NAME}\n`);
