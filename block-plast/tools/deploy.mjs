// אורז את המשחק ל-ZIP ומעלה ל-NOVA, ואז מאמת שהקישור החי עובד.
//   node block-plast/tools/deploy.mjs
// אם כבר קיים אתר עם ה-slug הזה — מתבצע עדכון (PUT) במקום יצירה (POST).
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HUB  = 'https://nova-deploy-hub.dtubugk.chatgpt.site';
const NAME = 'block-plast';
const MAX  = 25 * 1024 * 1024;

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FILES = ['index.html', 'privacy.html', 'sw.js', 'manifest.webmanifest',
               'icon-192.png', 'icon-512.png', '_headers'];

/* ---------- אריזה ---------- */
// ה-ZIP חייב להיות שטוח עם index.html בשורש — כך NOVA מגישה את האתר.
const zipPath = path.join(ROOT, NAME + '.zip');
fs.rmSync(zipPath, { force: true });
for (const f of FILES) {
  if (!fs.existsSync(path.join(ROOT, f))) throw new Error('חסר קובץ: ' + f);
}
execFileSync('zip', ['-q', '-X', zipPath, ...FILES], { cwd: ROOT });

const size = fs.statSync(zipPath).size;
console.log('ZIP: ' + (size / 1024).toFixed(0) + 'KB (' + FILES.length + ' קבצים)');
if (size > MAX) throw new Error('ה-ZIP גדול מ-25MB');

/* ---------- העלאה ---------- */
const listed = await (await fetch(HUB + '/api/sites')).json();
const existing = (listed.sites || []).find(s => s.slug === NAME);

const form = new FormData();
const blob = new Blob([fs.readFileSync(zipPath)], { type: 'application/zip' });
let res;
if (existing) {
  console.log('קיים כבר (id ' + existing.id + ', revision ' + existing.revision + ') — מעדכן');
  form.set('slug', existing.slug);
  form.set('zip', blob, NAME + '.zip');
  res = await fetch(HUB + '/api/sites', { method: 'PUT', body: form });
} else {
  console.log('אתר חדש — מעלה');
  form.set('name', NAME);
  form.set('zip', blob, NAME + '.zip');
  res = await fetch(HUB + '/api/sites', { method: 'POST', body: form });
}

const body = await res.json();
if (!res.ok) throw new Error('ההעלאה נכשלה (' + res.status + '): ' + JSON.stringify(body));

const slug = (body.site && body.site.slug) || (existing && existing.slug);
if (!slug) throw new Error('לא התקבל slug: ' + JSON.stringify(body));
const url = HUB + '/s/' + slug;
console.log('הועלה. כתובת: ' + url);

/* ---------- אימות ---------- */
// NOVA עוטפת את ההעלאה: שורש האתר מפנה ל-<slug>/game/index.html, ובשורש
// מוגשים manifest ו-sw משלה. הקבצים שהעלינו נמצאים תחת /game/.
const GAME = url + '/game';

let bad = 0;
async function verify(label, target, test) {
  const r = await fetch(target);
  const text = r.headers.get('content-type')?.includes('image') ? '' : await r.text();
  const ok = r.status === 200 && (!test || test(text));
  console.log((ok ? '  ✓ ' : '  ✗ ') + label + ' (' + r.status + ')');
  if (!ok) bad++;
}
console.log('\nאימות הקישור החי:');
await verify('שורש האתר מפנה למשחק', url, t => t.includes('Block Plast') && t.includes('__bp'));
await verify('game/index.html', GAME + '/index.html', t => t.includes('__bp'));
await verify('game/manifest.webmanifest', GAME + '/manifest.webmanifest',
  t => JSON.parse(t).name === 'Block Plast');
await verify('game/sw.js', GAME + '/sw.js', t => t.includes('blockplast-v1'));
await verify('game/icon-512.png', GAME + '/icon-512.png');
await verify('game/privacy.html', GAME + '/privacy.html', t => t.includes('מדיניות פרטיות'));

fs.rmSync(zipPath, { force: true });

if (bad) { console.log('\n' + bad + ' בדיקות נכשלו ✗'); process.exit(1); }
console.log('\nהמשחק באוויר ✓\n' + url);
