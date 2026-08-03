// בדיקת עשן ל-Block Plast.
//   node block-plast/tools/smoke-test.mjs            → בודק את הקבצים המקומיים
//   node block-plast/tools/smoke-test.mjs <url>      → מוריד את הגרסה החיה ובודק אותה
//
// בשני המקרים הבדיקה רצה מול שרת סטטי מקומי. ל-Chromium בסביבה הזו אין יציאה
// לאינטרנט, ולכן בדיקה של אתר חי מתבצעת ע"י הורדת הקבצים שהשרת באמת מגיש
// (כולל כל מה שהמארח הזריק לתוכם) והרצת אותן בדיקות עליהם.
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright } from './pw.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SHOTS = process.env.SHOT_DIR || path.join(ROOT, '.smoke-shots');
const ASSET_FILES = ['index.html', 'privacy.html', 'sw.js', 'manifest.webmanifest',
                     'icon-192.png', 'icon-512.png'];

// מוריד את המשחק החי לתיקייה זמנית ומחזיר את הנתיב אליה
async function mirror(url) {
  const first = await fetch(url);
  if (!first.ok) throw new Error('לא ניתן לטעון ' + url + ' (' + first.status + ')');
  const gameDir = first.url.replace(/\/[^/]*$/, '');   // אחרי ההפניה של המארח
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bp-live-'));
  console.log('ממשיך מהמארח: ' + gameDir);

  let indexHtml = '';
  for (const f of ASSET_FILES) {
    const r = await fetch(gameDir + '/' + f);
    if (!r.ok) throw new Error('חסר בשרת החי: ' + f + ' (' + r.status + ')');
    const buf = Buffer.from(await r.arrayBuffer());
    if (f === 'index.html') indexHtml = buf.toString('utf8');
    fs.writeFileSync(path.join(dir, f), buf);
  }

  // מארחים מזריקים לדף קישורים לנתיבים מוחלטים משלהם (Service Worker, manifest).
  // מורידים גם אותם לאותם נתיבים, אחרת העותק המקומי ייתן 404 מדומה.
  const origin = new URL(gameDir).origin;
  const scope = new URL(gameDir).pathname.replace(/\/[^/]*$/, '');   // ללא /game
  const injected = new Set(
    (indexHtml.match(/["'](\/[A-Za-z0-9_\-./]+\.(?:js|webmanifest|css|png|ico))["']/g) || [])
      .map(m => m.slice(1, -1))
      // חלק מהנתיבים נבנים בזמן ריצה משרשור (APP_SCOPE + "/sw.js"),
      // ולכן מוסיפים גם את הווריאנט שמתחת ל-scope של האתר
      .concat([scope + '/sw.js', scope + '/manifest.webmanifest']));
  for (const p of injected) {
    const r = await fetch(origin + p).catch(() => null);
    if (!r || !r.ok) continue;
    const dest = path.join(dir, p);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest, Buffer.from(await r.arrayBuffer()));
    console.log('  + נתיב מהמארח: ' + p);
  }
  return dir;
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.png': 'image/png',
  '.webmanifest': 'application/manifest+json'
};

let failures = 0;
function check(name, cond, detail) {
  if (cond) { console.log('  ✓ ' + name); }
  else { failures++; console.log('  ✗ ' + name + (detail ? '  → ' + detail : '')); }
}
function skip(name, why) { console.log('  – ' + name + ' (דילוג: ' + why + ')'); }

function startServer(root) {
  const server = http.createServer((req, res) => {
    let p = decodeURIComponent(req.url.split('?')[0]);
    if (p === '/') p = '/index.html';
    const file = path.join(root, p);
    if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404); res.end('not found'); return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-store',
      // מארחים שמגישים Service Worker מתת-נתיב שולחים את הכותרת הזו;
      // בלעדיה רישום ה-SW של המארח ייכשל בעותק המקומי.
      'Service-Worker-Allowed': '/'
    });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise(resolve => {
    server.listen(0, '127.0.0.1', () =>
      resolve({ server, base: 'http://127.0.0.1:' + server.address().port }));
  });
}

const { chromium } = await loadPlaywright();
const liveUrl = process.argv[2];
const serveRoot = liveUrl ? await mirror(liveUrl) : ROOT;
const started = await startServer(serveRoot);
const base = started.base.replace(/\/$/, '');
fs.mkdirSync(SHOTS, { recursive: true });

console.log('בודק: ' + (liveUrl ? liveUrl + ' (עותק מקומי)' : 'קבצים מקומיים') + '\n');

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 400, height: 860 },
  deviceScaleFactor: 2,
  hasTouch: true
});
const page = await ctx.newPage();

const errors = [];
const offOrigin = [];
const origin = new URL(base).origin;
page.on('console', m => {
  if (m.type() !== 'error') return;
  if (liveUrl && isHostInfra(m.location()?.url || '')) return;
  errors.push(m.text());
});
page.on('pageerror', e => errors.push('pageerror: ' + e.message));
page.on('request', r => {
  const u = r.url();
  if (!u.startsWith(origin) && !u.startsWith('data:') && !u.startsWith('blob:')) offOrigin.push(u);
});
// /cdn-cgi/ הוא תשתית ה-CDN של המארח, לא חלק מהמשחק. בעותק המקומי הוא
// בהכרח חסר, ולכן הוא מוחרג מבדיקות המשאבים והשגיאות.
const isHostInfra = u => u.includes('/cdn-cgi/');
const notFound = [];
page.on('response', r => {
  if (r.status() === 404 && !isHostInfra(r.url())) notFound.push(new URL(r.url()).pathname);
});

try {
  /* ---------- 1. טעינה ---------- */
  console.log('1. טעינה ומשאבים');
  await page.goto(base + '/', { waitUntil: 'networkidle' });
  // מארח כמו NOVA מפנה לתת-נתיב, ולכן הנכסים נבדקים יחסית לכתובת הסופית
  const assets = page.url().replace(/\/[^/]*$/, '');
  check('הכותרת נכונה', (await page.title()) === 'Block Plast', await page.title());
  const geo = await page.evaluate(() => window.__bp.layout());
  check('הקנבס נפרס עם לוח בגודל סביר', geo.size > 150 && geo.cell > 15,
    JSON.stringify(geo));
  check('הקנבס בגודל התצוגה עם devicePixelRatio',
    await page.evaluate(() => {
      const c = document.getElementById('stage');
      return c.width === Math.round(innerWidth * Math.min(devicePixelRatio, 3));
    }));
  check('אין בקשות לדומיינים חיצוניים', offOrigin.length === 0, offOrigin.join(', '));

  for (const f of ['manifest.webmanifest', 'sw.js', 'icon-192.png', 'icon-512.png', 'privacy.html']) {
    const r = await page.request.get(assets + '/' + f);
    check(f + ' מוגש (200)', r.status() === 200, 'status ' + r.status());
  }
  const mf = await (await page.request.get(assets + '/manifest.webmanifest')).json();
  check('manifest: שם ו-RTL', mf.name === 'Block Plast' && mf.dir === 'rtl');

  // מארחים מסוימים חוסמים navigator.serviceWorker בתוך המשחק ורושמים
  // Service Worker משלהם ברמת האתר — במקרה כזה אין מה לבדוק כאן.
  const swAvailable = await page.evaluate(() => 'serviceWorker' in navigator);
  if (swAvailable) {
    const swReady = await page.evaluate(() =>
      navigator.serviceWorker.ready.then(() => true).catch(() => false));
    check('service worker נרשם', swReady === true);
  } else {
    skip('service worker נרשם', 'המארח חוסם navigator.serviceWorker');
  }

  await page.screenshot({ path: path.join(SHOTS, '1-start.png') });

  /* ---------- 2. התחלת משחק ---------- */
  console.log('\n2. התחלת משחק');
  await page.click('#btnPlay');
  await page.waitForTimeout(350);
  let st = await page.evaluate(() => window.__bp.state());
  check('המשחק רץ', st.running === true);
  // מזהה הצורה 0 הוא חלק תקין (1×1), ולכן משווים מול null ולא לפי truthiness
  check('3 חלקים במגש', st.tray.filter(t => t !== null).length === 3, JSON.stringify(st.tray));
  check('הלוח ריק', st.board.every(v => v === -1));
  // הלוח מצויר על קנבס, ולכן בודקים שיש שם פיקסלים ולא DOM
  const painted = await page.evaluate(() => {
    const c = document.getElementById('stage');
    const g = c.getContext('2d');
    const L = window.__bp.layout();
    const d = Math.min(devicePixelRatio, 3);
    const px = g.getImageData(Math.round(L.bx * d) + 4, Math.round(L.by * d) + 4, 1, 1).data;
    return px[3] > 0 && (px[0] + px[1] + px[2]) > 0;
  });
  check('הלוח מצויר על הקנבס', painted === true);
  await page.screenshot({ path: path.join(SHOTS, '2-playing.png') });

  /* ---------- 3. גרירה אמיתית ---------- */
  console.log('\n3. גרירה עם עכבר');
  // מכניסים חלק ידוע (ריבוע 2×2, id=9) לתא הראשון כדי שהבדיקה תהיה דטרמיניסטית
  await page.evaluate(() => window.__bp.setTray(0, 9));
  await page.waitForTimeout(250);
  const grab = await page.evaluate(() => window.__bp.slotCenter(0));

  await page.mouse.move(grab.x, grab.y);
  await page.mouse.down();
  await page.waitForTimeout(40);
  check('הגרירה התחילה', await page.evaluate(() => window.__bp.dragging()) === true);

  // dropPoint מחשב לאן להזיז את המצביע כדי שהחלק יעגון בדיוק ב-(3,3)
  const drop = await page.evaluate(() => window.__bp.dropPoint(3, 3));
  await page.mouse.move(drop.x, drop.y, { steps: 14 });
  await page.waitForTimeout(90);
  const pv = await page.evaluate(() => window.__bp.preview());
  check('תצוגה מקדימה נעולה על (3,3)', pv && pv.r === 3 && pv.c === 3, JSON.stringify(pv));
  check('תצוגה מקדימה מכסה 4 תאים', pv && pv.cells === 4);
  await page.screenshot({ path: path.join(SHOTS, '3-drag-preview.png') });

  // חלקות: כמה פריימים צוירו לאורך גרירה של חצי שנייה
  const f0 = await page.evaluate(() => window.__bp.frames());
  const t0 = Date.now();
  for (let i = 0; i < 30; i++) {
    await page.mouse.move(drop.x + Math.sin(i / 4) * 40, drop.y + Math.cos(i / 4) * 30);
    await page.waitForTimeout(16);
  }
  const elapsed = Date.now() - t0;
  const fps = ((await page.evaluate(() => window.__bp.frames())) - f0) / (elapsed / 1000);
  check('הגרירה מציירת בקצב חלק (>40fps)', fps > 40, Math.round(fps) + 'fps');

  await page.mouse.move(drop.x, drop.y, { steps: 6 });
  await page.waitForTimeout(60);
  await page.mouse.up();
  await page.waitForTimeout(400);
  st = await page.evaluate(() => window.__bp.state());
  const filled = st.board.filter(v => v !== -1).length;
  check('4 תאים הונחו על הלוח', filled === 4, 'בפועל ' + filled);
  check('ההנחה במיקום הנכון', st.board[3 * 8 + 3] !== -1 && st.board[4 * 8 + 4] !== -1);
  check('הניקוד עלה ב-4', st.score === 4, 'ניקוד ' + st.score);
  check('הגרירה הסתיימה', await page.evaluate(() => window.__bp.dragging()) === false);

  /* ---------- 4. Undo ---------- */
  console.log('\n4. ביטול מהלך');
  await page.click('#btnUndo');
  await page.waitForTimeout(150);
  st = await page.evaluate(() => window.__bp.state());
  check('הלוח חזר להיות ריק', st.board.every(v => v === -1));
  check('הניקוד חזר ל-0', st.score === 0);
  check('כפתור הביטול ננעל שוב', await page.locator('#btnUndo').isDisabled());

  /* ---------- 5. ניקוי שורה וקומבו ---------- */
  console.log('\n5. ניקוי שורה');
  await page.evaluate(() => {
    window.__bp.newGame();
    window.__bp.fillRow(7, 7);      // ממלא עמודות 0..6 בשורה 7
    window.__bp.setTray(0, 0);      // חלק 1×1
  });
  const before = await page.evaluate(() => window.__bp.state().score);
  await page.evaluate(() => window.__bp.place(0, 7, 7));
  await page.waitForTimeout(120);
  await page.screenshot({ path: path.join(SHOTS, '4-clearing.png') });
  await page.waitForTimeout(500);
  st = await page.evaluate(() => window.__bp.state());
  const row7 = st.board.slice(56, 64);
  check('שורה 7 התנקתה', row7.every(v => v === -1), JSON.stringify(row7));
  check('הניקוד קפץ (1 + 10)', st.score === before + 11, 'ניקוד ' + st.score);
  check('הקומבו עלה ל-1', st.combo === 1, 'קומבו ' + st.combo);

  // מהלך שני שמנקה — הקומבו אמור לעלות ל-2 והבונוס להיכנס
  await page.evaluate(() => {
    window.__bp.fillRow(6, 7);
    window.__bp.setTray(1, 0);
  });
  const before2 = await page.evaluate(() => window.__bp.state().score);
  await page.evaluate(() => window.__bp.place(1, 6, 7));
  await page.waitForTimeout(500);
  st = await page.evaluate(() => window.__bp.state());
  check('הקומבו עלה ל-2', st.combo === 2, 'קומבו ' + st.combo);
  check('בונוס קומבו נוסף (1 + 10 + 5)', st.score === before2 + 16, 'ניקוד ' + st.score);

  // מהלך שלא מנקה — הקומבו מתאפס
  await page.evaluate(() => { window.__bp.setTray(2, 0); window.__bp.place(2, 0, 0); });
  await page.waitForTimeout(150);
  st = await page.evaluate(() => window.__bp.state());
  check('הקומבו מתאפס במהלך בלי ניקוי', st.combo === 0, 'קומבו ' + st.combo);

  /* ---------- 6. סוף משחק ושמירת שיא ---------- */
  console.log('\n6. סוף משחק ושיא');
  await page.evaluate(() => window.__bp.gameOver());
  await page.waitForTimeout(300);
  check('מסך הסיום נפתח', !(await page.locator('#ovOver').evaluate(e => e.classList.contains('hidden'))));
  check('כפתור המשך חינם מוצג', await page.locator('#btnContinue').isVisible());
  const finalScore = await page.evaluate(() => window.__bp.state().score);
  await page.screenshot({ path: path.join(SHOTS, '5-gameover.png') });

  await page.click('#btnContinue');
  await page.waitForTimeout(250);
  st = await page.evaluate(() => window.__bp.state());
  check('המשך מחזיר את המשחק לפעולה', st.running === true && st.over === false);
  check('ההמשך שומר על הניקוד', st.score === finalScore);
  check('הלוח פונה חלקית אחרי המשך', st.board.slice(56, 64).every(v => v === -1));
  // סיום נוסף באותו משחק — ההמשך כבר נוצל ולכן הכפתור חייב להיעלם
  await page.evaluate(() => window.__bp.gameOver());
  await page.waitForTimeout(250);
  check('כפתור ההמשך נעלם אחרי שנוצל',
    (await page.locator('#btnContinue').evaluate(e => e.style.display)) === 'none');

  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(250);
  const bestAfter = await page.evaluate(() => window.__bp.state().best);
  check('השיא נשמר אחרי רענון', bestAfter >= finalScore, 'שיא ' + bestAfter + ' מול ' + finalScore);

  /* ---------- 7. הגדרות וערכות ---------- */
  console.log('\n7. הגדרות');
  await page.click('#btnSettings');
  await page.waitForTimeout(250);
  check('4 ערכות צבע', (await page.locator('.swatch').count()) === 4);
  const swatchColors = await page.locator('.swatch i').evaluateAll(
    els => [...new Set(els.map(e => getComputedStyle(e).backgroundColor))].length);
  check('לכל ערכה צבעים משלה', swatchColors > 4, 'גוונים ייחודיים: ' + swatchColors);
  const beforeTheme = await page.evaluate(() => {
    const L = window.__bp.layout();
    const g = document.getElementById('stage').getContext('2d');
    const d = Math.min(devicePixelRatio, 3);
    return [...g.getImageData(Math.round(L.bx * d) + 4, Math.round(L.by * d) + 4, 1, 1).data];
  });
  await page.locator('.swatch[data-theme="forest"]').click();
  await page.waitForTimeout(250);
  check('החלפת ערכה נשמרת',
    (await page.evaluate(() => localStorage.getItem('bp.theme'))) === 'forest');
  const afterTheme = await page.evaluate(() => {
    const L = window.__bp.layout();
    const g = document.getElementById('stage').getContext('2d');
    const d = Math.min(devicePixelRatio, 3);
    return [...g.getImageData(Math.round(L.bx * d) + 4, Math.round(L.by * d) + 4, 1, 1).data];
  });
  check('החלפת ערכה משנה את מה שמצויר',
    beforeTheme.join() !== afterTheme.join(), beforeTheme + ' → ' + afterTheme);
  await page.screenshot({ path: path.join(SHOTS, '6-settings.png') });
  await page.click('#btnCloseSettings');
  await page.waitForTimeout(250);
  check('סגירת הגדרות חוזרת לתפריט',
    !(await page.locator('#ovStart').evaluate(e => e.classList.contains('hidden'))));

  /* ---------- 7b. פינוי מקום ל-UI שהמארח מזריק ---------- */
  console.log('\n7b. התאמה ל-UI של המארח');
  await page.click('#btnPlay');
  await page.waitForTimeout(300);
  const trayBefore = await page.evaluate(() => window.__bp.layout());
  // מדמים כפתור צף כמו זה ש-NOVA מזריקה לתחתית המסך
  await page.evaluate(() => {
    const b = document.createElement('button');
    b.id = 'fake-host-chrome';
    b.textContent = 'התקן אפליקציה';
    b.style.cssText = 'position:fixed;left:14px;bottom:14px;padding:11px 16px;z-index:2147483647';
    document.body.appendChild(b);
  });
  await page.waitForTimeout(350);
  const trayAfter = await page.evaluate(() => window.__bp.layout());
  const hostBox = await page.locator('#fake-host-chrome').boundingBox();
  check('המגש התרומם מעל הכפתור של המארח',
    trayAfter.trayY + trayAfter.trayH <= hostBox.y + 1,
    'תחתית המגש ' + Math.round(trayAfter.trayY + trayAfter.trayH) + ' מול ' + Math.round(hostBox.y));
  check('המגש אכן זז', trayAfter.trayY < trayBefore.trayY);
  await page.evaluate(() => document.getElementById('fake-host-chrome').remove());
  await page.waitForTimeout(350);
  const trayBack = await page.evaluate(() => window.__bp.layout());
  check('המגש חוזר למקומו כשה-UI נעלם',
    Math.abs(trayBack.trayY - trayBefore.trayY) < 2);

  /* ---------- 8. ריצת עומס: משחקים שלמים אוטומטית ---------- */
  console.log('\n8. ריצת עומס (5 משחקים מלאים)');
  const soak = await page.evaluate(() => {
    const bp = window.__bp;
    const games = [];
    for (let g = 0; g < 5; g++) {
      bp.newGame();
      let moves = 0;
      // שחקן חמדן: מניח כל חלק במקום הראשון שמתאים, עד שנגמרים המהלכים
      while (moves < 4000) {
        const st = bp.state();
        if (st.over || !st.running) break;
        let did = false;
        outer:
        for (let i = 0; i < 3; i++) {
          if (st.tray[i] === null) continue;
          for (let r = 0; r < 8 && !did; r++)
            for (let c = 0; c < 8; c++)
              if (bp.fitsAt(st.tray[i], r, c) && bp.place(i, r, c)) { did = true; moves++; break outer; }
        }
        if (!did) { bp.gameOver(); break; }
      }
      const fin = bp.state();
      games.push({ moves, score: fin.score, over: fin.over });
    }
    return games;
  });
  await page.waitForTimeout(600);
  check('כל 5 המשחקים הסתיימו בסוף משחק תקין', soak.every(g => g.over === true),
    JSON.stringify(soak));
  check('כל משחק ביצע מהלכים ממשיים', soak.every(g => g.moves > 5),
    soak.map(g => g.moves).join(', '));
  check('כל משחק צבר ניקוד', soak.every(g => g.score > 0), soak.map(g => g.score).join(', '));
  console.log('    מהלכים: ' + soak.map(g => g.moves).join(', ') +
              ' | ניקוד: ' + soak.map(g => g.score).join(', '));

  /* ---------- 9. שגיאות ---------- */
  console.log('\n9. קונסולה');
  check('אין שגיאות בקונסולה', errors.length === 0, errors.slice(0, 4).join(' | '));
  check('אין בקשות חיצוניות לאורך כל הריצה', offOrigin.length === 0, offOrigin.slice(0, 3).join(', '));
  check('כל המשאבים נמצאו', notFound.length === 0, [...new Set(notFound)].join(', '));

} catch (e) {
  failures++;
  console.log('\n✗ הבדיקה נפלה: ' + e.message + '\n' + e.stack);
} finally {
  await browser.close();
  if (started) started.server.close();
}

console.log('\nצילומי מסך: ' + SHOTS);
console.log(failures === 0 ? '\nהכול עבר ✓' : '\n' + failures + ' בדיקות נכשלו ✗');
process.exit(failures === 0 ? 0 : 1);
