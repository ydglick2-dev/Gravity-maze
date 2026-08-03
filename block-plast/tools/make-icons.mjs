// יוצר את אייקוני ה-PWA ע"י רינדור HTML ב-Chromium וצילום מסך.
// הרצה:  node block-plast/tools/make-icons.mjs
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { loadPlaywright } from './pw.mjs';

const { chromium } = await loadPlaywright();

const OUT_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// ציור מקורי: ארבעה בלוקים בסגנון המשחק על רקע כהה, עם "חיתוך" של אחד מהם
// שרומז על ניקוי שורה.
const page = (S) => `
<div id="ic" style="
  width:${S}px;height:${S}px;position:relative;overflow:hidden;
  background:radial-gradient(120% 100% at 50% 0%,#26326b 0%,#0e1430 55%,#070a18 100%);
  border-radius:${S * 0.22}px;">
  <div style="
    position:absolute;left:50%;top:50%;transform:translate(-50%,-50%) rotate(-8deg);
    display:grid;grid-template-columns:repeat(3,${S * 0.2}px);
    grid-template-rows:repeat(3,${S * 0.2}px);gap:${S * 0.035}px;">
    ${[
      ['#5b8cff', 1], ['#39d3a7', 1], ['#ffd05e', 1],
      ['#ff6f91', 1], ['#b07bff', 1], ['', 0],
      ['#4fd8f5', 1], ['', 0],        ['#ff9f45', 1]
    ].map(([c, on]) => on
      ? `<div style="background:${c};border-radius:${S * 0.045}px;
           box-shadow:inset 0 ${S * 0.014}px 0 rgba(255,255,255,.45),
                      inset 0 -${S * 0.018}px 0 rgba(0,0,0,.3),
                      0 ${S * 0.012}px ${S * 0.03}px rgba(0,0,0,.4);"></div>`
      : `<div></div>`).join('')}
  </div>
  <div style="
    position:absolute;inset:0;
    background:radial-gradient(60% 40% at 50% 22%,rgba(255,255,255,.14),transparent 70%);"></div>
</div>`;

const browser = await chromium.launch();
try {
  for (const size of [192, 512]) {
    const p = await browser.newPage({ viewport: { width: size, height: size },
                                      deviceScaleFactor: 1 });
    await p.setContent(
      `<body style="margin:0;background:transparent">${page(size)}</body>`);
    await p.locator('#ic').screenshot({ path: path.join(OUT_DIR, `icon-${size}.png`) });
    await p.close();
    console.log(`wrote icon-${size}.png`);
  }
} finally {
  await browser.close();
}
