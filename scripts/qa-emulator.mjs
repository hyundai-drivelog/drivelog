import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';

const adb = process.env.ANDROID_ADB || join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe');
const serial = process.env.QA_SERIAL || 'emulator-5554';
const output = resolve('.local-qa', serial);
mkdirSync(output, { recursive: true });
function call(args, binary = false) {
  return execFileSync(adb, ['-s', serial, ...args], { encoding: binary ? undefined : 'utf8', timeout: 30000, maxBuffer: 32 * 1024 * 1024 });
}
const user = call(['shell', 'am', 'get-current-user']).trim();
const asApp = (...args) => call(['shell', 'run-as', 'com.drivelog.app', '--user', user, ...args]);
function store() { return JSON.parse(asApp('cat', 'files/journey-store.json')); }
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, canonical(value[k])]));
  return value;
}
function inventory() {
  const data = store();
  const records = [...(data.journey?.records || [])].sort((a, b) => a.id.localeCompare(b.id));
  return { records: records.length, trips: data.trips?.length || 0,
    distanceKm: records.reduce((n, r) => n + (r.distanceKm || 0), 0),
    recordHash: createHash('sha256').update(JSON.stringify(canonical(records))).digest('hex'),
    recordingEntries: asApp('ls', '-A', 'files/recording').trim(),
    journeys: records.map(r => ({ id: r.id, name: r.placeName, km: r.distanceKm, seconds: r.elapsedSeconds, simulated: r.simulated })) };
}
const [action, ...args] = process.argv.slice(2);
switch (action) {
  case 'status': console.log(JSON.stringify(inventory(), null, 2)); break;
  case 'backup': {
    const state = inventory();
    if (state.recordingEntries) throw Error('Active/recoverable recording exists; stop before installing.');
    const tar = call(['exec-out', 'run-as', 'com.drivelog.app', '--user', user, 'tar', '-cf', '-', 'files', 'shared_prefs'], true);
    writeFileSync(join(output, 'before-install.tar'), tar, { flag: 'wx' });
    writeFileSync(join(output, 'before-inventory.json'), JSON.stringify(state, null, 2), { flag: 'wx' });
    console.log(`Private backup saved (${tar.length} bytes); records=${state.records}`);
    break;
  }
  case 'install': {
    // Check afresh immediately before every install. Never uninstall or clear data here.
    if (asApp('ls', '-A', 'files/recording').trim()) throw Error('Recording directory is not empty; install cancelled.');
    console.log(call(['install', '-r', '-t', '--user', user, resolve('app/build/outputs/apk/debug/app-debug.apk')]));
    break;
  }
  case 'launch': console.log(call(['shell', 'am', 'start', '--user', user, '-n', 'com.drivelog.app/.MainActivity'])); break;
  case 'screen': {
    if (!/^[a-z0-9-]+$/.test(args[0] || '')) throw Error('Use a simple capture name.');
    const file = join(output, `${args[0]}.png`);
    const displays = [...call(['shell', 'dumpsys', 'SurfaceFlinger', '--display-id']).matchAll(/^Display (\d+) /gm)];
    if (displays.length !== 1) throw Error('Expected one returned physical display.');
    const png = call(['exec-out', 'screencap', '-p', '-d', displays[0][1]], true);
    if (png.length < 8 || png.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a') throw Error('No valid screenshot returned.');
    writeFileSync(file, png);
    console.log(file); break;
  }
  case 'tree': {
    call(['shell', 'uiautomator', 'dump', '/sdcard/drivelog-trip-qa.xml']);
    const xml = call(['shell', 'cat', '/sdcard/drivelog-trip-qa.xml']);
    writeFileSync(join(output, 'ui.xml'), xml);
    const nodes = [...xml.matchAll(/<node\s+([^>]+)>/g)].map(match =>
      Object.fromEntries([...match[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(a => [a[1], a[2]])))
      .filter(n => n.package === 'com.drivelog.app' && (n.text || n['content-desc'] || n['resource-id']));
    console.log(JSON.stringify(nodes.map(n => ({ tag: n['resource-id'], text: (n.text || '').replace(/\u2060/g, ''),
      desc: n['content-desc'], bounds: n.bounds, clickable: n.clickable, enabled: n.enabled })), null, 2)); break;
  }
  case 'tap':
  case 'swipe': {
    if (!args.every(a => /^\d+$/.test(a)) || args.length !== (action === 'tap' ? 2 : 5)) throw Error('Expected numeric coordinates from a fresh screenshot.');
    console.log(call(['shell', 'input', action, ...args])); break;
  }
  case 'back': console.log(call(['shell', 'input', 'keyevent', '4'])); break;
  case 'verify': {
    const before = JSON.parse(readFileSync(join(output, 'before-inventory.json'), 'utf8'));
    const after = inventory();
    console.log(JSON.stringify({ recordsPreserved: before.recordHash === after.recordHash,
      beforeRecords: before.records, afterRecords: after.records, beforeTrips: before.trips, afterTrips: after.trips,
      recordingEmpty: !after.recordingEntries, distanceKm: after.distanceKm }, null, 2));
    if (before.recordHash !== after.recordHash) process.exitCode = 1;
    break;
  }
  default: throw Error('Use status, backup, install, launch, screen <name>, tree, tap x y, swipe x1 y1 x2 y2 ms, back, verify.');
}
