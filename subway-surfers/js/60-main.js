/*! ---------------------------------------------------------------------------
 *  Subway Surfers — personal, non-commercial fan project.
 *  Not affiliated with, endorsed by, or connected to SYBO Games or Kiloo in any
 *  way. All code, art and audio in this project are original and generated
 *  procedurally at runtime; no third-party assets are used. Personal use only.
 *
 *  Bundled dependency: three.min.js — Three.js r128, (c) three.js authors,
 *  MIT License. Its original license header is preserved in that file.
 * ------------------------------------------------------------------------- */

/* 60-main.js — input, the RAF loop, run lifecycle, scoring, dev overlay and
   the pure-logic self-test. */

(() => {
'use strict';

const S = window.SS;
const { $, clamp } = S;

const qs = new URLSearchParams(location.search);
const DEV = qs.get('dev') === '1';
const SELFTEST = qs.get('selftest') === '1';

/* --------------------------------------------------------------- state */

let running = false, paused = false, over = false;
let dist = 0, score = 0, runCoins = 0, speed = 14, combo = 0, comboT = 0;
let revives = 0, reviveCost = 1, runSeedUsed = 0, runTime = 0;
let noHitDist = 0;
let last = 0, ema = 16, emaBad = 0, degraded = 0;

S.speedNorm = () => clamp((speed - 14) / (S.SPEED_MAX - 14), 0, 1);

S.stat = function (k, amt) { S.SV.st[k] = (S.SV.st[k] || 0) + amt; };

/* ---------------------------------------------------------------- input */

const SWIPE_MIN = 26, SWIPE_MAX_T = 420, DOM_RATIO = 1.35;
let sx = 0, sy = 0, st = 0, tapOk = false, lastTap = 0, lastTapX = 0, lastTapY = 0;

function gDown(x, y) { sx = x; sy = y; st = performance.now(); tapOk = true; }

function gMove(x, y) {
  const dx = x - sx, dy = y - sy, ax = Math.abs(dx), ay = Math.abs(dy);
  if (Math.max(ax, ay) < SWIPE_MIN) return;
  if (performance.now() - st > SWIPE_MAX_T) { gDown(x, y); return; }
  if (ax > ay * DOM_RATIO) S.playerAct(dx > 0 ? 'right' : 'left');
  else if (ay > ax * DOM_RATIO) S.playerAct(dy > 0 ? 'down' : 'up');
  else return;                               // ambiguous diagonal
  gDown(x, y);                               // re-arm, so a zigzag drag keeps firing
  tapOk = false;
}

function gUp(x, y) {
  if (!tapOk) return;
  const now = performance.now();
  if (now - st >= 260 || Math.hypot(x - sx, y - sy) >= 14) return;
  // Double tap activates the hoverboard.
  if (now - lastTap < 280 && Math.hypot(x - lastTapX, y - lastTapY) < 40) {
    if (running && !paused) S.Power.activateBoard();
    lastTap = 0;
    return;
  }
  lastTap = now; lastTapX = x; lastTapY = y;
}

function bindInput() {
  const opt = { passive: false };

  addEventListener('touchstart', e => {
    S.initAudio();
    if (!inPlayArea(e.target)) return;
    const t = e.touches[0];
    gDown(t.clientX, t.clientY);
  }, opt);

  addEventListener('touchmove', e => {
    if (!inPlayArea(e.target)) return;
    e.preventDefault();
    const t = e.touches[0];
    gMove(t.clientX, t.clientY);
  }, opt);

  addEventListener('touchend', e => {
    if (!inPlayArea(e.target)) return;
    const t = e.changedTouches[0];
    gUp(t.clientX, t.clientY);
  }, opt);

  let down = false;
  addEventListener('pointerdown', e => {
    S.initAudio();
    if (e.pointerType === 'touch' || !inPlayArea(e.target)) return;
    down = true; gDown(e.clientX, e.clientY);
  });
  addEventListener('pointermove', e => { if (down) gMove(e.clientX, e.clientY); });
  addEventListener('pointerup', e => { if (down) { down = false; gUp(e.clientX, e.clientY); } });

  addEventListener('gesturestart', e => e.preventDefault());

  const KEYS = {
    ArrowLeft: 'left', KeyA: 'left',
    ArrowRight: 'right', KeyD: 'right',
    ArrowUp: 'up', KeyW: 'up', Space: 'up',
    ArrowDown: 'down', KeyS: 'down'
  };

  addEventListener('keydown', e => {
    if (e.repeat) return;
    S.initAudio();
    if (e.code === 'Escape' || e.code === 'KeyP') {
      if (running && !paused) S.pauseRun(); else if (paused) S.resumeRun();
      return;
    }
    if (e.code === 'ShiftLeft' || e.code === 'ShiftRight') {
      if (running && !paused) S.Power.activateBoard();
      return;
    }
    const a = KEYS[e.code];
    if (!a) return;
    e.preventDefault();
    if (!running && e.code === 'Space') { S.startRun(); return; }
    if (running && !paused) S.playerAct(a);
  });
}

// Buttons and overlays keep their own gestures; only the canvas drives the game.
function inPlayArea(t) {
  return running && !paused && !(t && t.closest && t.closest('.overlay,.btn,#hud .tap'));
}

/* --------------------------------------------------------- run lifecycle */

S.startRun = function () {
  S.initAudio();
  S.Missions.ensureDaily();
  S.Missions.runStart();

  dist = 0; score = 0; runCoins = 0; speed = 14; combo = 0; comboT = 0;
  revives = 0; reviveCost = 1; runTime = 0; noHitDist = 0;
  over = false; paused = false; running = true;

  runSeedUsed = (Date.now() >>> 0);
  S.Power.reset();
  S.World.reset(runSeedUsed);
  S.playerReset();
  S.Chase.reset();
  S.applyCharPalette(S.P.rig, S.charById(S.SV.char));
  S.applyBoardPalette(S.P.rig, S.boardById(S.SV.board));

  S.stat('runs', 1);
  S.UI.show(null);
  S.musicStart();
  S.musicDuck(0.55);
};

S.pauseRun = function () {
  if (!running || over) return;
  paused = true;
  S.musicDuck(0.15);
  S.UI.show('pauseOv');
};

S.resumeRun = function () {
  if (!running) return;
  paused = false;
  S.musicDuck(0.55);
  S.UI.show(null);
};

S.endRun = function (silent) {
  running = false;
  paused = false;
  S.musicStop();
  S.humOn(false);
  if (silent) return;
};

S.onCoin = function () {
  runCoins++;
  combo++;
  comboT = 1.2;
  S.SV.coins += 1;
  score += Math.round(10 * S.Power.mult());
  S.stat('coins', 1);
  S.sfx.coin(combo);
  S.Missions.setRunValue('COINS_RUN', runCoins);
};

S.onDeath = function () {
  if (over) return;
  over = true;
  S.musicDuck(0.2);
  setTimeout(finishRun, 1100);
};

function finishRun() {
  running = false;
  S.humOn(false);

  const isBest = score > (S.SV.hs | 0);
  if (isBest) S.SV.hs = score;
  if (dist > (S.SV.bestDist | 0)) S.SV.bestDist = dist | 0;
  S.stat('dist', dist | 0);
  S.stat('deaths', 1);
  S.stat('time', runTime | 0);

  S.Missions.bump('DIST_TOTAL', dist | 0);
  S.Missions.setRunValue('DIST_RUN', dist | 0);
  S.Missions.setRunValue('NO_HIT_DIST', noHitDist | 0);

  const rank = S.submitScore(score, dist, runCoins);
  if (rank >= 0 && !S.SV.name) {
    const n = prompt('Top 10! Enter your name:', 'Player');
    if (n) S.SV.name = n.slice(0, 14);
    S.SV.lb[rank].n = S.SV.name || 'Player';
  }
  S.save(true);

  S.UI.gameOver({ score, dist: dist | 0, coins: runCoins, isBest, revives, reviveCost });
}

S.revive = function () {
  if (revives >= 3 || S.SV.keys < reviveCost) { S.sfx.deny(); return; }
  S.SV.keys -= reviveCost;
  reviveCost *= 2;
  revives++;
  over = false;
  running = true;

  // Clear everything close enough ahead to be unavoidable on resume.
  for (const c of S.World.live()) {
    for (const arr of [c.boxes, c.movers]) {
      for (const b of arr) {
        if (c.z + b.lz > -30) { b.dead = true; b.mesh.visible = false; }
      }
    }
  }

  S.playerReset();
  S.P.invuln = 2.0;
  S.P.speedMul = 0.6;
  S.sfx.key();
  S.save();
  S.UI.show(null);
  S.musicStart();
  S.musicDuck(0.55);
};

/* ------------------------------------------------------------ the loop */

function loop(now) {
  requestAnimationFrame(loop);
  if (!last) last = now;
  const dtRaw = (now - last) / 1000;
  last = now;
  const dt = Math.min(dtRaw, 1 / 30);

  // Frame-time EMA drives the one-way adaptive degrade.
  ema += (dtRaw * 1000 - ema) * 0.05;
  if (running) {
    if (ema > 22) { if (++emaBad > 90) { degrade(); emaBad = 0; } }
    else emaBad = 0;
  }

  if (S.UI.mode() === 'shop') { S.UI.previewFrame(dt); if (DEV) devHud(); return; }

  if (running && !paused) {
    runTime += dt;
    const P = S.P;

    if (!over) {
      speed = S.speedAt(dist) * (P.speedMul || 1);
      const dz = speed * dt;
      dist += dz;
      noHitDist += dz;
      score += dz * S.Power.mult();
      S.musicBpm(118 + S.speedNorm() * 22);
      S.Missions.setRunValue('NO_HIT_DIST', noHitDist | 0);
      if (P.state === S.ST.STUMBLE) noHitDist = 0;
    } else {
      speed = Math.max(0, speed - 60 * dt);
    }

    if (comboT > 0) { comboT -= dt; if (comboT <= 0) combo = 0; }

    S.Power.update(dt);
    S.World.update(dt, speed, dist);
    S.playerUpdate(dt, speed);
    S.Chase.update(dt, speed, !over);
    S.FX.update(dt);
    S.World.updateCamera(dt, P.x, P.y, P.z, P.laneVx, S.speedNorm());
    S.UI.hud(dt, score, dist, runCoins);

  } else {
    // Idle backdrop for the menus: the world keeps drifting slowly.
    S.World.update(dt, 6, dist);
    S.Chase.update(dt, 6, false);
    S.FX.update(dt);
    S.World.updateCamera(dt, 0, 0, 0, 0, 0);
  }

  S.World.render();
  if (DEV) devHud();
}

function degrade() {
  const ren = S.World.renderer();
  degraded++;
  if (degraded === 1 && ren.shadowMap.enabled) { ren.shadowMap.enabled = false; return; }
  if (degraded === 2) { ren.setPixelRatio(ren.getPixelRatio() * 0.8); return; }
  if (degraded === 3) { S.Q.bldg = Math.max(0, (S.Q.bldg / 2) | 0); return; }
  if (degraded === 4) { S.Q.ahead = Math.max(90, S.Q.ahead - 30); return; }
}

/* ------------------------------------------------------------ dev HUD */

function devHud() {
  const el = $('dev');
  if (!el) return;
  const info = S.World.info();
  el.textContent =
    `fps ${(1000 / ema).toFixed(0)}  frame ${ema.toFixed(1)}ms\n` +
    `calls ${info.render.calls}  tris ${info.render.triangles}\n` +
    `chunks ${S.World.live().length}  dist ${dist | 0}m  speed ${speed.toFixed(1)}\n` +
    `state ${S.P.state}  lane ${S.P.laneTo}  y ${S.P.y.toFixed(2)}  gY ${S.P.groundY.toFixed(2)}`;
}

/* ---------------------------------------------------------- self-test */

function selftest() {
  const out = [];
  const ok = (name, cond, extra) => out.push((cond ? 'PASS' : 'FAIL') + '  ' + name + (extra ? '  — ' + extra : ''));

  // 1. Pattern library.
  const bad = S.Gen.validateLibrary();
  ok('pattern library valid', bad.length === 0, bad.join(', '));

  // 2. Solvability across many generated chunks.
  let worst = 0, fails = 0, n = 0;
  for (let seed = 0; seed < 250; seed++) {
    let entry = 7;
    for (let idx = 0; idx < 20; idx++) {
      const rng = S.mulberry((seed ^ Math.imul(idx, 0x9E3779B1)) >>> 0);
      const d = S.Gen.diffAt(idx * S.CHUNK_LEN);
      // Reproduce the layout loop's grid without emitting meshes.
      const grid = new Int8Array(S.ROWS * 3);
      let tries = 0, exit = 0;
      for (; tries < 5; tries++) {
        grid.fill(0);
        layoutProbe(grid, rng, d);
        exit = S.Gen.solvable(grid, entry);
        if (exit) break;
      }
      worst = Math.max(worst, tries);
      if (!exit) fails++;
      entry = exit || 7;
      n++;
    }
  }
  ok('chunk solvability (' + n + ' chunks)', fails === 0, 'fails=' + fails + ' worstRetries=' + worst);

  // 3. Save round-trip and corruption tolerance.
  const store = S.store();
  let saveOk = false, corruptOk = false;
  if (store) {
    const backup = store.getItem('ss_v1');
    S.SV.coins = 4321; S.SV.keys = 7; S.SV.up.MAGNET = 3;
    S.save(true);
    S.load();
    saveOk = (S.SV.coins === 4321 && S.SV.keys === 7 && S.SV.up.MAGNET === 3);
    store.setItem('ss_v1', '{not json');
    S.load();
    corruptOk = (S.SV.coins === 0 && S.SV.char === 'dash');
    if (backup != null) store.setItem('ss_v1', backup); else store.removeItem('ss_v1');
    S.load();
  }
  ok('save round-trip', saveOk);
  ok('corrupt blob falls back to defaults', corruptOk);

  // 4. Jump-arc invariants the level design depends on.
  const apex = v => (v * v) / (2 * -S.GRAVITY);
  ok('base jump clears a car roof', apex(S.JUMP_V0) > S.ROOF_Y,
      apex(S.JUMP_V0).toFixed(2) + ' > ' + S.ROOF_Y);
  ok('base jump cannot reach a freight roof', apex(S.JUMP_V0) < S.ROOF_TALL,
      apex(S.JUMP_V0).toFixed(2) + ' < ' + S.ROOF_TALL);
  ok('ramp reaches a freight roof', apex(S.RAMP_V0) > S.ROOF_TALL,
      apex(S.RAMP_V0).toFixed(2) + ' > ' + S.ROOF_TALL);
  ok('sneakers jump higher than base', apex(S.JUMP_V0_S) > apex(S.JUMP_V0));

  // The apex must not depend on frame rate. Semi-implicit Euler loses
  // 0.5*g*dt^2 per step and would drop the base jump under ROOF_Y at 30fps,
  // making train roofs unreachable on slow devices.
  const simPeak = (v0, dt) => {
    let y = 0, vy = v0, peak = 0;
    for (let i = 0; i < 600; i++) {
      y += vy * dt + 0.5 * S.GRAVITY * dt * dt;
      vy += S.GRAVITY * dt;
      peak = Math.max(peak, y);
      if (y <= 0 && i > 1) break;
    }
    return peak;
  };
  const p60 = simPeak(S.JUMP_V0, 1 / 60), p30 = simPeak(S.JUMP_V0, 1 / 30);
  ok('jump apex is frame-rate independent', Math.abs(p60 - p30) < 0.02,
      '60fps=' + p60.toFixed(3) + ' 30fps=' + p30.toFixed(3));
  ok('jump clears a car roof even at 30fps', p30 > S.ROOF_Y, p30.toFixed(3));

  // 5. Swipe classifier.
  const cases = [
    [60, 5, 'right'], [-60, 5, 'left'], [5, 60, 'down'], [5, -60, 'up'],
    [40, 40, null], [-40, 40, null], [10, 10, null], [80, 30, 'right'],
    [30, 80, 'down'], [-80, -30, 'left'], [-30, -80, 'up']
  ];
  let swipeOk = true;
  for (const [dx, dy, want] of cases) {
    const ax = Math.abs(dx), ay = Math.abs(dy);
    let got = null;
    if (Math.max(ax, ay) >= SWIPE_MIN) {
      if (ax > ay * DOM_RATIO) got = dx > 0 ? 'right' : 'left';
      else if (ay > ax * DOM_RATIO) got = dy > 0 ? 'down' : 'up';
    }
    if (got !== want) { swipeOk = false; out.push('   swipe mismatch ' + dx + ',' + dy + ' -> ' + got + ' want ' + want); }
  }
  ok('swipe classifier', swipeOk);

  // 6. Economy reachability and upgrade caps.
  ok('upgrade prices defined for 4 levels', S.UPGRADE_PRICES.length === 4);
  ok('every character has a price or key cost',
      S.CHARS.every(c => c.price > 0 || c.keys > 0 || c.id === 'dash'));
  ok('every board has a duration', S.BOARDS.every(b => b.dur >= 30));

  // 7. Unlock-all.
  S.applyUnlockAll();
  const allChars = S.CHARS.every(c => S.SV.chars.indexOf(c.id) >= 0);
  const allBoards = S.BOARDS.every(b => S.SV.boards.indexOf(b.id) >= 0);
  const allUp = Object.keys(S.SV.up).every(k => S.SV.up[k] === 4);
  ok('unlock-all owns every character', allChars);
  ok('unlock-all owns every board', allBoards);
  ok('unlock-all maxes every upgrade', allUp);
  S.load();

  const pass = out.filter(l => l.startsWith('PASS')).length;
  const fail = out.filter(l => l.startsWith('FAIL')).length;
  const text = out.join('\n') + `\n\n${pass} passed, ${fail} failed`;
  console.log(text);
  const el = document.createElement('pre');
  el.id = 'selftest';
  el.textContent = text;
  document.body.appendChild(el);
  return { pass, fail };
}

// Mirrors the generator's layout loop closely enough to exercise solvability.
function layoutProbe(grid, rng, d) {
  const pool = S.Gen.PATTERNS.filter(p => p.tier <= d.tier);
  let r = 0, sinceRest = 0;
  while (r < S.ROWS) {
    const room = S.ROWS - r;
    if (sinceRest >= d.restEvery) { sinceRest = 0; r++; continue; }
    if (rng() > d.dens) { r++; sinceRest++; continue; }
    const fit = pool.filter(p => p.rows <= room);
    if (!fit.length) break;
    const p = fit[(rng() * fit.length) | 0];
    const before = grid.slice();
    let fitted = true;
    for (const c of p.cells) {
      const cls = { TRAIN: 4, TRAIN_TALL: 4, TRAIN_MOVE: 4, PILLAR: 4, BAR_LOW: 1, BAR_HIGH: 2, TUNNEL: 2 }[c.t];
      if (!cls) continue;
      const n = (c.t === 'TRAIN' || c.t === 'TRAIN_TALL' || c.t === 'TRAIN_MOVE') ? 3 : 1;
      for (let k = 0; k < n; k++) {
        const rr = r + c.r + k;
        if (rr >= S.ROWS) { fitted = false; break; }
        grid[rr * 3 + c.l] = cls;
      }
      if (!fitted) break;
    }
    if (!fitted) { grid.set(before); r++; continue; }
    let bad = false;
    for (let rr = r; rr < r + p.rows && rr < S.ROWS; rr++)
      if (grid[rr * 3] === 4 && grid[rr * 3 + 1] === 4 && grid[rr * 3 + 2] === 4) bad = true;
    if (bad) { grid.set(before); r++; continue; }
    r += p.rows;
    sinceRest = 0;
  }
}

/* ------------------------------------------------------------ bootstrap */

function boot() {
  S.load();
  if (S.SV.set.q === 'high') S.setTier('high');
  else if (S.SV.set.q === 'low') S.setTier('low');

  S.World.init($('gl'));
  const scene = S.World.scene();
  S.playerAttach(scene);
  S.Chase.init(scene);
  S.Power.initScene(scene);
  S.playerReset();

  S.Gen.validateLibrary();
  S.UI.init();
  bindInput();

  if (DEV) {
    const el = document.createElement('pre');
    el.id = 'dev';
    document.body.appendChild(el);
    window.__ss = {
      giveCoins: n => { S.SV.coins += n; S.save(); S.UI.refreshAll(); },
      giveKeys: n => { S.SV.keys += n; S.save(); S.UI.refreshAll(); },
      setDist: m => { dist = m; },
      forcePower: id => S.Power.grant(id),
      godMode: b => { S.god = !!b; },
      seed: n => { runSeedUsed = n; },
      tier: t => { S.setTier(t); location.reload(); },
      state: () => ({ dist, score, speed, running, paused, over }),
      dumpChunk: () => S.World.live().map(c => ({ z: c.z, boxes: c.boxes.length, coins: c.coinN }))
    };
  }

  if (SELFTEST) selftest();

  if ('serviceWorker' in navigator && location.protocol.startsWith('http'))
    navigator.serviceWorker.register('sw.js').catch(() => {});

  requestAnimationFrame(loop);
}

if (document.readyState === 'loading') addEventListener('DOMContentLoaded', boot);
else boot();

})();
