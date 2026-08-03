// בדיקת עשן ל-Block Plast.
//   node block-plast/tools/smoke-test.mjs [baseUrl]
// ברירת מחדל: מרים שרת סטטי מקומי על התיקייה block-plast.
// אפשר להעביר URL כדי לבדוק את הגרסה החיה אחרי הפריסה.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright } from './pw.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SHOTS = process.env.SHOT_DIR || path.join(ROOT, '.smoke-shots');

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

function startServer() {
  const server = http.createServer((req, res) => {
    let p = decodeURIComponent(req.url.split('?')[0]);
    if (p === '/') p = '/index.html';
    const file = path.join(ROOT, p);
    if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404); res.end('not found'); return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-store'
    });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise(resolve => {
    server.listen(0, '127.0.0.1', () =>
      resolve({ server, base: 'http://127.0.0.1:' + server.address().port }));
  });
}

const { chromium } = await loadPlaywright();
const external = process.argv[2];
const started = external ? null : await startServer();
const base = (external || started.base).replace(/\/$/, '');
fs.mkdirSync(SHOTS, { recursive: true });

console.log('בודק: ' + base + '\n');

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 400, height: 860 },
  deviceScaleFactor: 2,
  hasTouch: true
});
const page = await ctx.newPage();

const errors = [];
const offOrigin = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('pageerror: ' + e.message));
page.on('request', r => {
  const u = r.url();
  if (!u.startsWith(base) && !u.startsWith('data:') && !u.startsWith('blob:')) offOrigin.push(u);
});

try {
  /* ---------- 1. טעינה ---------- */
  console.log('1. טעינה ומשאבים');
  await page.goto(base + '/index.html', { waitUntil: 'networkidle' });
  check('הכותרת נכונה', (await page.title()) === 'Block Plast', await page.title());
  check('64 תאים בלוח', (await page.locator('#board .cell').count()) === 64);
  check('אין בקשות לדומיינים חיצוניים', offOrigin.length === 0, offOrigin.join(', '));

  for (const f of ['manifest.webmanifest', 'sw.js', 'icon-192.png', 'icon-512.png', 'privacy.html']) {
    const r = await page.request.get(base + '/' + f);
    check(f + ' מוגש (200)', r.status() === 200, 'status ' + r.status());
  }
  const mf = await (await page.request.get(base + '/manifest.webmanifest')).json();
  check('manifest: שם ו-RTL', mf.name === 'Block Plast' && mf.dir === 'rtl');

  const swReady = await page.evaluate(() =>
    navigator.serviceWorker.ready.then(() => true).catch(() => false));
  check('service worker נרשם', swReady === true);

  await page.screenshot({ path: path.join(SHOTS, '1-start.png') });

  /* ---------- 2. התחלת משחק ---------- */
  console.log('\n2. התחלת משחק');
  await page.click('#btnPlay');
  await page.waitForTimeout(350);
  let st = await page.evaluate(() => window.__bp.state());
  check('המשחק רץ', st.running === true);
  check('3 חלקים במגש', st.tray.filter(Boolean).length === 3);
  check('הלוח ריק', st.board.every(v => v === -1));
  check('3 חלקים מרונדרים', (await page.locator('.slot .piece').count()) === 3);
  await page.screenshot({ path: path.join(SHOTS, '2-playing.png') });

  /* ---------- 3. גרירה אמיתית ---------- */
  console.log('\n3. גרירה עם עכבר');
  // מכניסים חלק ידוע (ריבוע 2×2, id=9) לתא הראשון כדי שהבדיקה תהיה דטרמיניסטית
  await page.evaluate(() => window.__bp.setTray(0, 9));
  const m = await page.evaluate(() => window.__bp.metrics());
  const br = await page.evaluate(() => {
    const r = window.__bp.boardRect();
    return { left: r.left, top: r.top };
  });
  const pieceBox = await page.locator('.slot[data-slot="0"] .piece').boundingBox();

  // יעד: שורה 3, עמודה 3. הגרירה מחזיקה את החלק בפינה השמאלית-עליונה,
  // ולכן מוסיפים חזרה את ההיסט ואת ההרמה שהקוד מחיל.
  const step = m.cellPx + m.gapPx;
  const targetLeft = br.left + m.gapPx + 3 * step;
  const targetTop = br.top + m.gapPx + 3 * step;
  const grabX = 4, grabY = 4;                       // נקודת אחיזה בתוך החלק (בפיקסלים של המגש)
  const scale = step / (m.trayCellPx + m.trayGapPx);

  await page.mouse.move(pieceBox.x + grabX, pieceBox.y + grabY);
  await page.mouse.down();
  await page.mouse.move(
    targetLeft + grabX * scale,
    targetTop + grabY * scale + m.LIFT * step,
    { steps: 12 });
  await page.waitForTimeout(80);
  const hinted = await page.locator('#board .cell.hint').count();
  check('תצוגה מקדימה מסמנת 4 תאים', hinted === 4, 'סומנו ' + hinted);
  await page.screenshot({ path: path.join(SHOTS, '3-drag-preview.png') });

  await page.mouse.up();
  await page.waitForTimeout(300);
  st = await page.evaluate(() => window.__bp.state());
  const filled = st.board.filter(v => v !== -1).length;
  check('4 תאים הונחו על הלוח', filled === 4, 'בפועל ' + filled);
  check('ההנחה במיקום הנכון', st.board[3 * 8 + 3] !== -1 && st.board[4 * 8 + 4] !== -1);
  check('הניקוד עלה ב-4', st.score === 4, 'ניקוד ' + st.score);

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
  await page.locator('.swatch[data-theme="forest"]').click();
  await page.waitForTimeout(200);
  check('החלפת ערכה עובדת',
    (await page.evaluate(() => document.body.dataset.theme)) === 'forest');
  await page.screenshot({ path: path.join(SHOTS, '6-settings.png') });
  await page.click('#btnCloseSettings');
  await page.waitForTimeout(250);
  check('סגירת הגדרות חוזרת לתפריט',
    !(await page.locator('#ovStart').evaluate(e => e.classList.contains('hidden'))));
  await page.evaluate(() => document.querySelector('.swatch[data-theme="aurora"]') && 0);

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
