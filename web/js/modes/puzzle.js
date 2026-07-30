/* ============================================================
   מצב 2 — מעגלי אור  (laser / mirror rotation puzzle)
   Rotate mirrors so every crystal is lit. 40 seeded levels.
   ============================================================ */
import { S, U, TAU, FX, D, Sound, Save, haptic, alpha, Game, skinColor } from '../core.js';
import { UI } from '../ui.js';

export const meta = {
  id: 'puzzle', title: 'מעגלי אור', ico: '◈', color: '#ffc63a', music: 'puzzle',
  desc: 'הקש על מראה כדי לסובב אותה.<br>האר את כל הגבישים — כמה שפחות מהלכים.'
};

export const LEVELS = 40;
const DIRS = [[1, 0], [0, 1], [-1, 0], [0, -1]];   // 0→ 1↓ 2← 3↑
const REF = [                                      // [mirrorType][inDir] → outDir
  [3, 2, 1, 0],   // '/'
  [1, 0, 3, 2]    // '\'
];
function mirrorFor(inD, outD) { return REF[0][inD] === outD ? 0 : 1; }

/* ---------------- level generation (always solvable) ---------------- */
export function buildLevel(idx, variant = 0) {
  const rng = U.seed(1000 + idx * 7919 + variant * 104729);
  const ri = (a, b) => a + Math.floor(rng() * (b - a));
  const n = idx < 6 ? 5 : idx < 14 ? 6 : idx < 26 ? 7 : 8;
  const paths = idx < 4 ? 1 : idx < 12 ? 2 : idx < 24 ? 2 + (idx % 2) : 3;
  const decoys = Math.min(9, 1 + Math.floor(idx / 3));
  const blocks = idx < 8 ? 0 : Math.min(6, Math.floor((idx - 6) / 4) + 1);

  const grid = Array.from({ length: n }, () => Array.from({ length: n }, () => ({ t: 'empty', o: 0 })));
  const through = Array.from({ length: n }, () => Array(n).fill(false));
  const solution = [];
  const inside = (x, y) => x >= 0 && y >= 0 && x < n && y < n;
  const free = (x, y) => inside(x, y) && grid[y][x].t === 'empty' && !through[y][x];

  let made = 0;
  for (let attempt = 0; attempt < 400 && made < paths; attempt++) {
    // pick a border start heading inward
    const side = ri(0, 4);
    let x, y, d;
    if (side === 0) { x = 0; y = ri(0, n); d = 0; }
    else if (side === 1) { x = n - 1; y = ri(0, n); d = 2; }
    else if (side === 2) { x = ri(0, n); y = 0; d = 1; }
    else { x = ri(0, n); y = n - 1; d = 3; }
    if (!free(x, y)) continue;

    // a path must not collide with itself either, so track its own pending cells:
    // pendOcc = cells this path already fills, pendThru = cells its beam already crosses
    const pendOcc = new Set([x + ',' + y]), pendThru = new Set();
    const canCross = (cx, cy) => inside(cx, cy) && grid[cy][cx].t === 'empty' && !pendOcc.has(cx + ',' + cy);
    const canPlace = (cx, cy) => free(cx, cy) && !pendOcc.has(cx + ',' + cy) && !pendThru.has(cx + ',' + cy);

    const cells = [{ x, y, kind: 'source', d }];
    let cx = x, cy = y, cd = d, ok = true;
    const turns = ri(2, idx < 10 ? 4 : 5);
    for (let t = 0; t < turns && ok; t++) {
      const run = ri(1, 4);
      const trav = [];
      for (let s = 0; s < run; s++) {
        cx += DIRS[cd][0]; cy += DIRS[cd][1];
        if (!inside(cx, cy)) { ok = false; break; }
        if (s < run - 1) {
          if (!canCross(cx, cy)) { ok = false; break; }
          trav.push({ x: cx, y: cy, kind: 'through' });
        }
      }
      if (!ok || !canPlace(cx, cy)) { ok = false; break; }
      const nd = (cd + (rng() < .5 ? 1 : 3)) % 4;
      cells.push(...trav, { x: cx, y: cy, kind: 'mirror', o: mirrorFor(cd, nd) });
      for (const c of trav) pendThru.add(c.x + ',' + c.y);
      pendOcc.add(cx + ',' + cy);
      cd = nd;
    }
    if (!ok) continue;
    // walk a little further, then land the crystal
    const run = ri(1, 4);
    const trav = [];
    for (let s = 0; s < run; s++) {
      cx += DIRS[cd][0]; cy += DIRS[cd][1];
      if (!inside(cx, cy)) { ok = false; break; }
      if (s < run - 1) { if (!canCross(cx, cy)) { ok = false; break; } trav.push({ x: cx, y: cy, kind: 'through' }); }
    }
    if (!ok || !canPlace(cx, cy)) continue;
    cells.push(...trav, { x: cx, y: cy, kind: 'crystal' });

    // commit
    for (const c of cells) {
      if (c.kind === 'through') through[c.y][c.x] = true;
      else if (c.kind === 'source') { grid[c.y][c.x] = { t: 'source', o: c.d }; }
      else if (c.kind === 'mirror') { grid[c.y][c.x] = { t: 'mirror', o: c.o }; solution.push({ x: c.x, y: c.y, o: c.o }); }
      else if (c.kind === 'crystal') grid[c.y][c.x] = { t: 'crystal', o: 0 };
    }
    made++;
  }

  const spare = [];
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (free(x, y)) spare.push({ x, y });
  U.shuffle(spare);
  for (let i = 0; i < decoys && spare.length; i++) { const s = spare.pop(); grid[s.y][s.x] = { t: 'mirror', o: ri(0, 2) }; }
  for (let i = 0; i < blocks && spare.length; i++) { const s = spare.pop(); grid[s.y][s.x] = { t: 'block', o: 0 }; }

  // scramble the real mirrors (rotating never makes a level unsolvable)
  const mirrors = [];
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (grid[y][x].t === 'mirror') mirrors.push(grid[y][x]);
  for (let tries = 0; tries < 30; tries++) {
    for (const m of mirrors) m.o = ri(0, 2);
    if (!isSolved(grid, n).all) break;
  }
  // some layouts light up under most rotations — walk the mirrors until one breaks the beam
  if (isSolved(grid, n).all) {
    for (const m of mirrors) { m.o = 1 - m.o; if (!isSolved(grid, n).all) break; }
  }
  if (isSolved(grid, n).all && variant < 4) return buildLevel(idx, variant + 1);

  return { grid, n, par: Math.max(1, solution.length), idx, sol: solution };
}

/* ---------------- beam simulation ---------------- */
export function trace(grid, n) {
  const segs = [], lit = new Set();
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    const cell = grid[y][x];
    if (cell.t !== 'source') continue;
    let cx = x, cy = y, cd = cell.o, steps = 0;
    while (steps++ < n * n * 4) {
      const nx = cx + DIRS[cd][0], ny = cy + DIRS[cd][1];
      if (nx < 0 || ny < 0 || nx >= n || ny >= n) { segs.push({ x1: cx, y1: cy, x2: nx, y2: ny }); break; }
      segs.push({ x1: cx, y1: cy, x2: nx, y2: ny });
      const t = grid[ny][nx];
      cx = nx; cy = ny;
      if (t.t === 'mirror') cd = REF[t.o][cd];
      else if (t.t === 'crystal') { lit.add(ny * n + nx); break; }
      else if (t.t === 'block' || t.t === 'source') break;
    }
  }
  return { segs, lit };
}
function isSolved(grid, n) {
  const { lit } = trace(grid, n);
  let total = 0;
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (grid[y][x].t === 'crystal') total++;
  return { all: total > 0 && lit.size === total, lit, total };
}

/* ---------------- custom intro: level picker ---------------- */
export function intro(api, start) {
  const solved = new Set(Save.d.puzzle.solved);
  const maxOpen = solved.size ? Math.min(LEVELS - 1, Math.max(...solved) + 1) : 0;
  let html = '<div class="lvlgrid">';
  for (let i = 0; i < LEVELS; i++) {
    const done = solved.has(i), open = i <= maxOpen;
    html += `<button class="lvl ${done ? 'done' : open ? 'open' : ''}" data-lvl="${i}" ${open ? '' : 'disabled'}>${i + 1}</button>`;
  }
  html += '</div>';
  UI.intro({
    ico: meta.ico, title: meta.title, color: meta.color,
    text: meta.desc + `<br><b style="color:#ffc63a">${solved.size}/${LEVELS} נפתרו</b>`,
    extraHTML: html, playLabel: solved.size >= LEVELS ? 'שחק שוב' : 'המשך',
    onPlay: () => start({ level: maxOpen }),
    onMount(el) {
      el.querySelectorAll('.lvl').forEach(b => b.onclick = () => {
        Sound.play('click'); start({ level: +b.dataset.lvl });
      });
    }
  });
}

/* ---------------- scene ---------------- */
export function create(api, opts = {}) {
  let L, level, moves, solved, winT, cellPx, ox, oy, beam, litSet, glowT, hint;
  const rot = new Map();   // cell key -> animated angle

  function load(i) {
    level = U.clamp(i, 0, LEVELS - 1);
    L = buildLevel(level);
    moves = 0; solved = false; winT = 0; glowT = 0; hint = 3;
    rot.clear();
    layout(); sim();
    api.hud(`שלב ${level + 1}`, `מהלכים: ${moves}`);
  }
  function layout() {
    if (!L) return;
    const pad = 22;
    const avail = Math.min(S.W - pad * 2, S.H - 200);
    cellPx = Math.floor(avail / L.n);
    ox = (S.W - cellPx * L.n) / 2;
    oy = (S.H - cellPx * L.n) / 2 + 12;
  }
  function sim() {
    const r = trace(L.grid, L.n);
    beam = r.segs; litSet = r.lit;
    const s = isSolved(L.grid, L.n);
    if (s.all && !solved) win();
    api.hud(`שלב ${level + 1}`, `מהלכים: ${moves}`);
  }
  const cx = gx => ox + gx * cellPx + cellPx / 2;
  const cy = gy => oy + gy * cellPx + cellPx / 2;

  function win() {
    solved = true; winT = 0;
    Sound.play('win'); haptic([20, 40, 30]);
    FX.flash('#ffc63a', .35); FX.shake(6);
    for (let y = 0; y < L.n; y++) for (let x = 0; x < L.n; x++)
      if (L.grid[y][x].t === 'crystal') {
        FX.burst(cx(x), cy(y), 26, '#ffd964', { speed: 300, life: .9, size: 5 });
        FX.ring(cx(x), cy(y), '#fff2c4', 8, 150, 4, .6);
      }
    const stars = moves <= L.par ? 3 : moves <= Math.ceil(L.par * 1.7) ? 2 : 1;
    const first = !Save.d.puzzle.solved.includes(level);
    if (first) { Save.d.puzzle.solved.push(level); Save.d.puzzle.stars += stars; }
    Save.setBest('puzzle', Save.d.puzzle.solved.length);
    Save.save();
    const earn = first ? 40 + stars * 25 + level * 3 : 8;
    setTimeout(() => api.end({
      ico: '✦'.repeat(stars), title: stars === 3 ? 'מושלם!' : 'פתרת!',
      color: meta.color,
      stats: [['שלב', level + 1], ['מהלכים', `${moves} (יעד ${L.par})`],
      ['סה״כ נפתרו', `${Save.d.puzzle.solved.length}/${LEVELS}`]],
      coins: earn,
      retryLabel: level + 1 < LEVELS ? 'השלב הבא' : 'תפריט',
      onRetry: () => { if (level + 1 < LEVELS) { UI.hideAll(); UI.hud(true); load(level + 1); } else UI.onHome(); }
    }), 900);
  }

  return {
    resize: layout,
    enter() { load(opts.level || 0); },
    onDown(p) {
      if (solved) return;
      const gx = Math.floor((p.x - ox) / cellPx), gy = Math.floor((p.y - oy) / cellPx);
      if (gx < 0 || gy < 0 || gx >= L.n || gy >= L.n) return;
      const cell = L.grid[gy][gx];
      if (cell.t !== 'mirror') {
        if (cell.t === 'block') { Sound.play('no'); haptic(18); FX.shake(3); }
        return;
      }
      cell.o = 1 - cell.o; moves++;
      const key = gy * L.n + gx;
      rot.set(key, (rot.get(key) || 0) - Math.PI / 2);
      Sound.play('lock'); haptic(12);
      FX.ring(cx(gx), cy(gy), '#8fd9ff', 6, cellPx * .62, 2.5, .3);
      FX.burst(cx(gx), cy(gy), 7, '#bfe9ff', { speed: 120, life: .35, size: 3 });
      sim();
    },
    onKey(code) { if (code === 'KeyR') load(level); },

    update(dt) {
      glowT += dt;
      if (solved) winT += dt;
      if (hint > 0) hint -= dt;
      for (const [k, v] of rot) {
        const nv = U.damp(v, 0, 14, dt);
        if (Math.abs(nv) < .002) rot.delete(k); else rot.set(k, nv);
      }
      // ambient sparks along the live beam
      if (beam && beam.length && Math.random() < dt * 26) {
        const s = U.pick(beam), t = Math.random();
        FX.spawn(U.lerp(cx(s.x1), cx(s.x2), t), U.lerp(cy(s.y1), cy(s.y2), t),
          U.rnd(-20, 20), U.rnd(-20, 20), .5, 2.4, '#9fe8ff', { drag: .9 });
      }
    },

    draw(c) {
      const g = c.createLinearGradient(0, 0, S.W, S.H);
      g.addColorStop(0, '#0b0c1c'); g.addColorStop(1, '#06070f');
      c.fillStyle = g; c.fillRect(0, 0, S.W, S.H);
      D.grid(c, 0, glowT * 10, 44, 'rgba(255,198,58,.05)');

      const n = L.n;
      // board
      c.save();
      c.fillStyle = 'rgba(12,16,32,.8)';
      D.rr(c, ox - 8, oy - 8, cellPx * n + 16, cellPx * n + 16, 18); c.fill();
      c.strokeStyle = alpha('#ffc63a', .35); c.lineWidth = 1.5; c.stroke();
      c.strokeStyle = 'rgba(255,255,255,.055)'; c.lineWidth = 1;
      c.beginPath();
      for (let i = 1; i < n; i++) {
        c.moveTo(ox + i * cellPx, oy); c.lineTo(ox + i * cellPx, oy + n * cellPx);
        c.moveTo(ox, oy + i * cellPx); c.lineTo(ox + n * cellPx, oy + i * cellPx);
      }
      c.stroke(); c.restore();

      // beam
      c.save();
      c.globalCompositeOperation = 'lighter';
      c.lineCap = 'round'; c.lineJoin = 'round';
      for (const pass of [{ w: 13, a: .16 }, { w: 5, a: .55 }, { w: 2, a: 1 }]) {
        c.strokeStyle = alpha('#7ff0ff', pass.a); c.lineWidth = pass.w;
        c.shadowBlur = 22; c.shadowColor = '#4fd8ff';
        c.beginPath();
        for (const s of beam) { c.moveTo(cx(s.x1), cy(s.y1)); c.lineTo(cx(s.x2), cy(s.y2)); }
        c.stroke();
      }
      // travelling pulse
      c.setLineDash([9, 26]); c.lineDashOffset = -glowT * 130;
      c.strokeStyle = '#ffffff'; c.lineWidth = 3; c.globalAlpha = .9;
      c.beginPath();
      for (const s of beam) { c.moveTo(cx(s.x1), cy(s.y1)); c.lineTo(cx(s.x2), cy(s.y2)); }
      c.stroke();
      c.restore();

      // tiles
      const R = cellPx * .34;
      for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
        const t = L.grid[y][x], X = cx(x), Y = cy(y);
        if (t.t === 'source') {
          c.save(); c.translate(X, Y);
          c.fillStyle = '#2a3a68'; D.rr(c, -R, -R, R * 2, R * 2, 8); c.fill();
          c.rotate(t.o * Math.PI / 2);
          c.fillStyle = '#7ff0ff'; c.shadowBlur = 20; c.shadowColor = '#7ff0ff';
          c.beginPath(); c.moveTo(R * .9, 0); c.lineTo(-R * .3, -R * .55); c.lineTo(-R * .3, R * .55);
          c.closePath(); c.fill(); c.restore();
        }
        else if (t.t === 'block') {
          c.save(); c.fillStyle = '#3b2033'; c.strokeStyle = '#8a4468'; c.lineWidth = 2;
          D.rr(c, X - R, Y - R, R * 2, R * 2, 7); c.fill(); c.stroke();
          c.strokeStyle = 'rgba(255,120,170,.5)';
          c.beginPath(); c.moveTo(X - R * .5, Y - R * .5); c.lineTo(X + R * .5, Y + R * .5);
          c.moveTo(X + R * .5, Y - R * .5); c.lineTo(X - R * .5, Y + R * .5); c.stroke(); c.restore();
        }
        else if (t.t === 'crystal') {
          const on = litSet.has(y * n + x);
          const pop = on ? 1 + .09 * Math.sin(glowT * 6) : 1;
          c.save(); c.translate(X, Y); c.rotate(glowT * (on ? .8 : .25)); c.scale(pop, pop);
          c.fillStyle = on ? '#ffe89a' : '#4a5170';
          if (on) { c.shadowBlur = 30; c.shadowColor = '#ffc63a'; }
          c.beginPath();
          for (let i = 0; i < 6; i++) {
            const a = i * TAU / 6;
            c.lineTo(Math.cos(a) * R, Math.sin(a) * R);
          }
          c.closePath(); c.fill();
          c.fillStyle = on ? '#fff8dc' : '#666e92';
          c.beginPath(); c.arc(0, 0, R * .34, 0, TAU); c.fill();
          c.restore();
        }
        else if (t.t === 'mirror') {
          const key = y * n + x;
          const base = t.o ? Math.PI / 4 : -Math.PI / 4;
          c.save(); c.translate(X, Y); c.rotate(base + (rot.get(key) || 0));
          c.strokeStyle = '#eaf4ff'; c.lineWidth = Math.max(4, cellPx * .11); c.lineCap = 'round';
          c.shadowBlur = 14; c.shadowColor = '#bfe9ff';
          c.beginPath(); c.moveTo(-R * 1.05, 0); c.lineTo(R * 1.05, 0); c.stroke();
          c.strokeStyle = 'rgba(120,180,255,.55)'; c.lineWidth = 2;
          c.beginPath(); c.moveTo(-R * 1.05, R * .28); c.lineTo(R * 1.05, R * .28); c.stroke();
          c.restore();
        }
      }

      FX.draw(c);

      if (solved) {
        c.save(); c.globalAlpha = Math.min(.85, winT * 2);
        D.text(c, 'הושלם!', S.cx, oy - 44, 30, '#ffe08a', 'center', 900, 20);
        c.restore();
      } else if (hint > 0) {
        c.save(); c.globalAlpha = U.clamp(hint, 0, 1) * .85;
        D.text(c, 'הקש על מראה כדי לסובב', S.cx, oy + cellPx * n + 44, 16, '#cbd6f0', 'center', 700, 8);
        c.restore();
      }
      D.vignette(c, .5);
    }
  };
}
