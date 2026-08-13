/*! ---------------------------------------------------------------------------
 *  Subway Surfers — personal, non-commercial fan project.
 *  Not affiliated with, endorsed by, or connected to SYBO Games or Kiloo in any
 *  way. All code, art and audio in this project are original and generated
 *  procedurally at runtime; no third-party assets are used. Personal use only.
 *
 *  Bundled dependency: three.min.js — Three.js r128, (c) three.js authors,
 *  MIT License. Its original license header is preserved in that file.
 * ------------------------------------------------------------------------- */

/* 20-gen.js — pattern library, solvability validator, difficulty ramp and
   coin / power-up placement. Works on passability classes, never on meshes. */

(() => {
'use strict';

const S = window.SS;
const { CHUNK_LEN, SLOT_LEN, ROWS, LANE_X } = S;

/* Passability classes. SOLID is treated as impassable by the solver even where a
   roof exists — the guarantee is therefore that a *ground* route always exists,
   and roof routes are a bonus. */
const FREE = 0, JUMP = 1, ROLL = 2, SOLID = 4;

const G = {};
S.Gen = G;

/* ------------------------------------------------------------- patterns */

// cells: {r: row, l: lane, t: type, len?: rows spanned}
// Trains are always 3 rows (15 m) because the car mesh is a fixed 15 m long.
const PATTERNS = [
  /* ---- tier 0 : teaching ------------------------------------------- */
  { id: 'barLow1',   tier: 0, rows: 2, cells: [{ r: 0, l: 1, t: 'BAR_LOW' }, { r: 0, l: 1, t: 'COIN_ARC' }] },
  { id: 'barHigh1',  tier: 0, rows: 2, cells: [{ r: 0, l: 1, t: 'BAR_HIGH' }] },
  { id: 'barLowSide',tier: 0, rows: 2, cells: [{ r: 0, l: 0, t: 'BAR_LOW' }, { r: 0, l: 0, t: 'COIN_ARC' }] },
  { id: 'coinLane',  tier: 0, rows: 2, cells: [{ r: 0, l: 2, t: 'COIN_LINE', len: 2 }] },
  { id: 'train1',    tier: 0, rows: 4, cells: [{ r: 0, l: 0, t: 'TRAIN' }, { r: 0, l: 2, t: 'COIN_LINE', len: 3 }] },

  /* ---- tier 1 : combinations --------------------------------------- */
  { id: 'trainPair', tier: 1, rows: 4, cells: [
      { r: 0, l: 0, t: 'TRAIN' }, { r: 0, l: 2, t: 'TRAIN' }, { r: 0, l: 1, t: 'COIN_LINE', len: 3 }] },
  { id: 'trainBar',  tier: 1, rows: 5, cells: [
      { r: 0, l: 0, t: 'TRAIN' }, { r: 1, l: 1, t: 'BAR_LOW' }, { r: 1, l: 1, t: 'COIN_ARC' }] },
  { id: 'twoLow',    tier: 1, rows: 3, cells: [
      { r: 0, l: 0, t: 'BAR_LOW' }, { r: 0, l: 1, t: 'BAR_LOW' }, { r: 0, l: 0, t: 'COIN_ARC' }] },
  { id: 'highLow',   tier: 1, rows: 4, cells: [
      { r: 0, l: 1, t: 'BAR_HIGH' }, { r: 2, l: 1, t: 'BAR_LOW' }, { r: 2, l: 1, t: 'COIN_ARC' }] },
  { id: 'rampRoof',  tier: 1, rows: 5, cells: [
      { r: 0, l: 1, t: 'RAMP' }, { r: 1, l: 1, t: 'TRAIN' }, { r: 1, l: 1, t: 'COIN_ROOF', len: 3 }] },
  { id: 'zigCoins',  tier: 1, rows: 3, cells: [{ r: 0, l: 1, t: 'COIN_ZIG', len: 3 }] },

  /* ---- tier 2 : pressure ------------------------------------------- */
  { id: 'twoTrains', tier: 2, rows: 5, cells: [
      { r: 0, l: 0, t: 'TRAIN' }, { r: 0, l: 1, t: 'TRAIN' }, { r: 0, l: 2, t: 'COIN_LINE', len: 4 }] },
  { id: 'tunnelDuo', tier: 2, rows: 4, cells: [
      { r: 0, l: 0, t: 'BAR_HIGH' }, { r: 0, l: 1, t: 'BAR_HIGH' }] },
  { id: 'pillars',   tier: 2, rows: 3, cells: [
      { r: 0, l: 0, t: 'PILLAR' }, { r: 1, l: 2, t: 'PILLAR' }, { r: 0, l: 1, t: 'COIN_LINE', len: 3 }] },
  { id: 'railGrind', tier: 2, rows: 3, cells: [
      { r: 0, l: 1, t: 'RAIL' }, { r: 0, l: 0, t: 'BAR_LOW' }] },
  { id: 'trainMove', tier: 2, rows: 5, cells: [
      { r: 0, l: 2, t: 'TRAIN_MOVE' }, { r: 0, l: 0, t: 'COIN_LINE', len: 4 }] },
  { id: 'lowHighLow',tier: 2, rows: 5, cells: [
      { r: 0, l: 0, t: 'BAR_LOW' }, { r: 2, l: 1, t: 'BAR_HIGH' }, { r: 0, l: 1, t: 'COIN_LINE', len: 2 }] },

  /* ---- tier 3 : maximum -------------------------------------------- */
  { id: 'tallGate',  tier: 3, rows: 5, cells: [
      { r: 0, l: 0, t: 'TRAIN_TALL' }, { r: 0, l: 2, t: 'TRAIN' }, { r: 3, l: 1, t: 'BAR_LOW' }, { r: 3, l: 1, t: 'COIN_ARC' }] },
  { id: 'gauntlet',  tier: 3, rows: 6, cells: [
      { r: 0, l: 0, t: 'BAR_LOW' }, { r: 0, l: 2, t: 'BAR_HIGH' },
      { r: 2, l: 1, t: 'BAR_LOW' }, { r: 4, l: 0, t: 'PILLAR' }, { r: 2, l: 1, t: 'COIN_ARC' }] },
  { id: 'twinMove',  tier: 3, rows: 5, cells: [
      { r: 0, l: 0, t: 'TRAIN_MOVE' }, { r: 1, l: 2, t: 'BAR_HIGH' }, { r: 0, l: 1, t: 'COIN_LINE', len: 4 }] },
  { id: 'rampGap',   tier: 3, rows: 6, cells: [
      { r: 0, l: 2, t: 'RAMP' }, { r: 1, l: 2, t: 'TRAIN_TALL' }, { r: 1, l: 0, t: 'TRAIN' },
      { r: 1, l: 2, t: 'COIN_ROOF', len: 3 }] }
];

// Which class an obstacle imposes on the cells it occupies.
const CLASS_OF = {
  TRAIN: SOLID, TRAIN_TALL: SOLID, TRAIN_MOVE: SOLID, PILLAR: SOLID,
  BAR_LOW: JUMP, BAR_HIGH: ROLL, TUNNEL: ROLL,
  RAMP: FREE, RAIL: FREE,
  COIN_LINE: FREE, COIN_ARC: FREE, COIN_ZIG: FREE, COIN_ROOF: FREE
};

const ROWS_OF = t => (t === 'TRAIN' || t === 'TRAIN_TALL' || t === 'TRAIN_MOVE') ? 3 : 1;

/* --------------------------------------------------------- solvability */

// Forward reachability over the 6x3 class grid. `entry` and the return value are
// 3-bit lane masks. Returns 0 when no route exists.
function solvable(grid, entry) {
  let cur = entry & 7;
  if (!cur) cur = 7;
  for (let r = 0; r < ROWS; r++) {
    let next = 0;
    for (let l = 0; l < 3; l++) {
      if (!(cur & (1 << l))) continue;
      for (let d = -1; d <= 1; d++) {
        const nl = l + d;
        if (nl < 0 || nl > 2) continue;
        const cell = grid[r * 3 + nl];
        if (cell === SOLID) continue;
        // A jump cannot be held through a roll in the same lane on adjacent rows.
        if (cell === JUMP && r > 0 && grid[(r - 1) * 3 + nl] === ROLL) continue;
        next |= 1 << nl;
      }
    }
    if (!next) return 0;
    cur = next;
  }
  return cur;
}
G.solvable = solvable;

// Boot-time validation: every authored pattern must be passable on its own.
function validateLibrary() {
  const bad = [];
  for (const p of PATTERNS) {
    const grid = new Int8Array(ROWS * 3);
    if (!stamp(grid, p, 0)) { bad.push(p.id + ' (does not fit)'); continue; }
    if (!solvable(grid, 7)) bad.push(p.id + ' (unsolvable)');
    // Structural rule: never all three lanes solid in one row.
    for (let r = 0; r < ROWS; r++) {
      if (grid[r * 3] === SOLID && grid[r * 3 + 1] === SOLID && grid[r * 3 + 2] === SOLID)
        bad.push(p.id + ' (row ' + r + ' fully blocked)');
    }
  }
  if (bad.length) console.error('[gen] invalid patterns:', bad);
  return bad;
}
G.validateLibrary = validateLibrary;

// Writes a pattern's classes into the grid at row offset `off`.
// Returns false when it would overflow the chunk.
function stamp(grid, pat, off) {
  if (off + pat.rows > ROWS) return false;
  for (const c of pat.cells) {
    const cls = CLASS_OF[c.t];
    if (cls === FREE) continue;
    const n = ROWS_OF(c.t);
    for (let k = 0; k < n; k++) {
      const r = off + c.r + k;
      if (r >= ROWS) return false;
      grid[r * 3 + c.l] = cls;
    }
  }
  return true;
}

/* -------------------------------------------------------- difficulty */

function diffAt(dist) {
  return {
    dens: Math.min(0.88, 0.52 + dist / 7000),
    tier: dist < 400 ? 0 : dist < 1200 ? 1 : dist < 2600 ? 2 : 3,
    restEvery: dist < 800 ? 3 : 5,
    coinRate: 0.55 - Math.min(0.2, dist / 15000)
  };
}
G.diffAt = diffAt;

S.speedAt = dist => 14 + Math.min(20, dist / 220);
S.SPEED_MAX = 34;

/* ------------------------------------------------------------- state */

let entryMask = 7;
let sincePower = 0;
let sinceKey = 0;
let sinceLetter = 0;
G.mode = 'RUN';                  // 'RUN' | 'JETPACK'

function reset() {
  entryMask = 7;
  sincePower = 0;
  sinceKey = 0;
  sinceLetter = 0;
  G.mode = 'RUN';
}
G.reset = reset;

/* --------------------------------------------------------------- fill */

const POWER_WEIGHTS = [
  ['MAGNET', .30], ['X2', .25], ['JETPACK', .15], ['SNEAK', .15], ['BOARD', .15]
];

function pickPower(rng) {
  let r = rng(), acc = 0;
  for (const [id, w] of POWER_WEIGHTS) { acc += w; if (r < acc) return id; }
  return 'MAGNET';
}

const rowZ = r => -(r + 0.5) * SLOT_LEN;

function fill(c, idx, rng) {
  const dist = idx * CHUNK_LEN;

  // Jetpack override: an empty runway plus a high coin ribbon.
  if (G.mode === 'JETPACK') {
    for (let i = 0; i < 24; i++) {
      const t = i / 24;
      S.World.addCoin(c, LANE_X[1] + Math.sin(t * 6.2) * 2.0,
                         9 + Math.sin(t * 9) * 1.2,
                         -t * CHUNK_LEN);
    }
    c.exit = entryMask = 7;
    return;
  }

  // The first two chunks straddle and lead away from the player's start position,
  // so they stay obstacle-free: a clear runway to read the track from.
  if (idx < 2) {
    emitCoinLine(c, 1, 2, 3);
    c.exit = entryMask = 7;
    return;
  }

  const d = diffAt(dist);
  const grid = new Int8Array(ROWS * 3);
  let placed = null;

  // Up to 5 attempts, then a guaranteed-safe breather chunk.
  for (let attempt = 0; attempt < 5; attempt++) {
    grid.fill(FREE);
    placed = layout(grid, rng, d, dist);
    if (solvable(grid, entryMask)) break;
    placed = null;
  }

  if (!placed) {
    grid.fill(FREE);
    placed = [];
    emitCoinLine(c, 1, 0, 4);
  }

  entryMask = solvable(grid, entryMask) || 7;
  c.exit = entryMask;

  for (const it of placed) emit(c, it, rng, d);

  // Power-ups, keys and word letters go into cells the layout left FREE.
  sincePower++;
  if (sincePower >= 4 && (rng() < .45 || sincePower >= 8)) {
    const spot = freeCell(grid, rng);
    if (spot) {
      sincePower = 0;
      const pw = pickPower(rng);
      S.World.addProp(c, 'POWER', spot.l, rowZ(spot.r), { y: 1.1, color: powerColor(pw), pw });
      grid[spot.r * 3 + spot.l] = -1;             // reserved, not re-used below
    }
  }

  sinceKey++;
  if (dist > 1500 && sinceKey >= 8 && rng() < .5) {
    const spot = freeCell(grid, rng);
    if (spot) { sinceKey = 0; S.World.addProp(c, 'KEY', spot.l, rowZ(spot.r), { y: 1.1 }); grid[spot.r * 3 + spot.l] = -1; }
  }

  sinceLetter++;
  const next = S.Missions && S.Missions.nextLetter && S.Missions.nextLetter();
  if (next && sinceLetter >= 3 && rng() < .5) {
    const spot = freeCell(grid, rng);
    if (spot) {
      sinceLetter = 0;
      const b = S.World.addProp(c, 'LETTER', spot.l, rowZ(spot.r), { y: 1.2, ch: next.ch });
      paintLetter(b.mesh, next.ch);
      grid[spot.r * 3 + spot.l] = -1;
    }
  }
}
G.fill = fill;

function powerColor(pw) {
  return { MAGNET: 0xff5f9e, X2: 0xffd257, JETPACK: 0x7ae0ff, SNEAK: 0x9dff7a, BOARD: 0xff8a3d }[pw];
}

const _letterTex = new Map();
function letterTex(ch) {
  let t = _letterTex.get(ch);
  if (!t) {
    t = S.cvTex(128, 128, (g, w, h) => {
      g.fillStyle = '#ffd257'; g.fillRect(0, 0, w, h);
      g.fillStyle = '#241505';
      g.font = '900 84px system-ui, Arial';
      g.textAlign = 'center'; g.textBaseline = 'middle';
      g.fillText(ch, w / 2, h / 2 + 6);
    });
    _letterTex.set(ch, t);
  }
  return t;
}

function paintLetter(mesh, ch) {
  const face = mesh.userData.face;
  const m = new THREE.MeshLambertMaterial({ map: letterTex(ch) });
  face.material = [
    S.matFor(0xe0b13a), S.matFor(0xe0b13a), S.matFor(0xe0b13a),
    S.matFor(0xe0b13a), m, m
  ];
}

function freeCell(grid, rng) {
  const opts = [];
  for (let r = 0; r < ROWS; r++)
    for (let l = 0; l < 3; l++)
      if (grid[r * 3 + l] === FREE) opts.push({ r, l });
  if (!opts.length) return null;
  return opts[(rng() * opts.length) | 0];
}

/* Chooses patterns row by row and returns the concrete placement list. */
function layout(grid, rng, d, dist) {
  const out = [];
  const pool = PATTERNS.filter(p => p.tier <= d.tier);
  let r = 0, sinceRest = 0;

  while (r < ROWS) {
    const room = ROWS - r;

    if (sinceRest >= d.restEvery) {                 // guaranteed breather row
      sinceRest = 0; r++;
      continue;
    }
    if (rng() > d.dens) { r++; sinceRest++; continue; }

    const fit = pool.filter(p => p.rows <= room);
    if (!fit.length) break;
    const p = fit[(rng() * fit.length) | 0];

    const before = grid.slice();
    if (!stamp(grid, p, r)) { grid.set(before); r++; continue; }

    // Structural guard: never fully block a row.
    let bad = false;
    for (let rr = r; rr < r + p.rows && rr < ROWS; rr++) {
      if (grid[rr * 3] === SOLID && grid[rr * 3 + 1] === SOLID && grid[rr * 3 + 2] === SOLID) bad = true;
    }
    if (bad) { grid.set(before); r++; continue; }

    out.push({ pat: p, off: r });
    r += p.rows;
    sinceRest = 0;
  }
  return out;
}

/* -------------------------------------------------------------- emit */

function emit(c, item, rng, d) {
  const { pat, off } = item;
  for (const cell of pat.cells) {
    const lane = cell.l;
    const r0 = off + cell.r;
    if (r0 >= ROWS) continue;

    switch (cell.t) {
      case 'TRAIN':
      case 'TRAIN_TALL':
      case 'TRAIN_MOVE': {
        const key = cell.t === 'TRAIN_TALL' ? 'TRAIN_TALL' : 'TRAIN';
        // 3 rows -> centre sits 1.5 rows in.
        const lz = -(r0 + 1.5) * SLOT_LEN;
        const o = { livery: (rng() * 6) | 0 };
        if (cell.t === 'TRAIN_MOVE') o.vz = 6;
        S.World.addProp(c, key, lane, lz, o);
        break;
      }
      case 'BAR_LOW':  S.World.addProp(c, 'BAR_LOW',  lane, rowZ(r0)); break;
      case 'BAR_HIGH': S.World.addProp(c, 'BAR_HIGH', lane, rowZ(r0)); break;
      case 'TUNNEL':   S.World.addProp(c, 'TUNNEL',   lane, rowZ(r0)); break;
      case 'RAMP':     S.World.addProp(c, 'RAMP',     lane, rowZ(r0)); break;
      case 'RAIL':     S.World.addProp(c, 'RAIL',     lane, rowZ(r0)); break;
      case 'PILLAR':   S.World.addProp(c, 'PILLAR',   lane, rowZ(r0)); break;

      case 'COIN_LINE': if (rng() < d.coinRate + .3) emitCoinLine(c, lane, r0, cell.len || 2); break;
      case 'COIN_ARC':  emitCoinArc(c, lane, r0); break;
      case 'COIN_ZIG':  emitCoinZig(c, lane, r0, cell.len || 3); break;
      case 'COIN_ROOF': emitCoinRoof(c, lane, r0, cell.len || 3); break;
    }
  }
}

function emitCoinLine(c, lane, r0, rows) {
  const z0 = -r0 * SLOT_LEN;
  const n = Math.round(rows * SLOT_LEN / 1.5);
  for (let i = 0; i < n; i++) S.World.addCoin(c, LANE_X[lane], 1.0, z0 - i * 1.5);
}

// Matched to the base jump arc (apex 2.19 m over ~7 m) so following the coins is
// the correct jump timing.
function emitCoinArc(c, lane, r0) {
  const n = 7, span = 7, z0 = -r0 * SLOT_LEN + 1.5;
  for (let i = 0; i < n; i++) {
    const t = i / (n - 1);
    S.World.addCoin(c, LANE_X[lane], 1.0 + 2.4 * Math.sin(Math.PI * t), z0 - t * span);
  }
}

function emitCoinZig(c, lane, r0, rows) {
  const z0 = -r0 * SLOT_LEN;
  const n = Math.round(rows * SLOT_LEN / 1.5);
  for (let i = 0; i < n; i++) {
    const l = (i >> 1) % 3;
    S.World.addCoin(c, LANE_X[l], 1.0, z0 - i * 1.5);
  }
}

function emitCoinRoof(c, lane, r0, rows) {
  const z0 = -r0 * SLOT_LEN;
  const n = Math.round(rows * SLOT_LEN / 1.5);
  for (let i = 0; i < n; i++) S.World.addCoin(c, LANE_X[lane], S.ROOF_Y + 0.9, z0 - i * 1.5);
}

G.PATTERNS = PATTERNS;

})();
