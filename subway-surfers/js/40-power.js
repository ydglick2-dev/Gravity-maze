/*! ---------------------------------------------------------------------------
 *  Subway Surfers — personal, non-commercial fan project.
 *  Not affiliated with, endorsed by, or connected to SYBO Games or Kiloo in any
 *  way. All code, art and audio in this project are original and generated
 *  procedurally at runtime; no third-party assets are used. Personal use only.
 *
 *  Bundled dependency: three.min.js — Three.js r128, (c) three.js authors,
 *  MIT License. Its original license header is preserved in that file.
 * ------------------------------------------------------------------------- */

/* 40-power.js — power-up timers, the magnet fly-coin pool, the jetpack
   sequence, the hoverboard contract, and the particle system. */

(() => {
'use strict';

const S = window.SS;
const { clamp, lerp, damp } = S;

/* --------------------------------------------------------------- data */

const POWERS = {
  MAGNET : { base: 8,  per: 2.5, icon: '🧲', color: 0xff5f9e, label: 'Coin Magnet' },
  X2     : { base: 10, per: 3.0, icon: '✖️', color: 0xffd257, label: 'Score Multiplier' },
  JETPACK: { base: 5,  per: 1.2, icon: '🚀', color: 0x7ae0ff, label: 'Jetpack' },
  SNEAK  : { base: 12, per: 3.0, icon: '👟', color: 0x9dff7a, label: 'Super Sneakers' }
};
S.POWERS = POWERS;

const UPGRADE_PRICES = [500, 1500, 4000, 9000];
S.UPGRADE_PRICES = UPGRADE_PRICES;

const dur = p => POWERS[p].base + POWERS[p].per * (S.SV.up[p] | 0);
S.powerDur = dur;

/* -------------------------------------------------------------- state */

const act = { MAGNET: 0, X2: 0, JETPACK: 0, SNEAK: 0, BOARD: 0 };
const total = { MAGNET: 1, X2: 1, JETPACK: 1, SNEAK: 1, BOARD: 1 };
let x2Level = 1;

const Pw = {};
S.Power = Pw;

Pw.active = id => act[id] > 0;
Pw.remain = id => act[id];
Pw.total = id => total[id];
Pw.x2Level = () => x2Level;

Pw.reset = function () {
  for (const k in act) act[k] = 0;
  x2Level = 1;
  clearFly();
  S.humOn(false);
};

Pw.mult = function () {
  const ch = S.charById(S.SV.char).bonus || 1;
  const rail = S.P.grinding ? 1.5 : 1;
  return (act.X2 > 0 ? x2Level : 1) * ch * rail;
};

Pw.grant = function (id) {
  S.sfx.power();
  S.stat('powers', 1);
  S.Missions.bump('POWERS', 1);

  if (id === 'BOARD') {
    S.SV.boardsInv++;
    S.toast('+1 Hoverboard');
    S.save();
    return;
  }

  if (id === 'X2') {
    // A second pickup while already active doubles again, capped at x4.
    x2Level = act.X2 > 0 ? Math.min(4, x2Level * 2) : 2;
  }

  total[id] = dur(id);
  act[id] = total[id];

  if (id === 'JETPACK') startJetpack();
  if (id === 'MAGNET') S.FX.burst(S.P.x, S.P.y + 1, S.P.z, 12, POWERS.MAGNET.color);
  S.toast(POWERS[id].label);
};

Pw.update = function (dt) {
  for (const k in act) {
    if (act[k] <= 0) continue;
    act[k] -= dt;
    if (act[k] <= 0) {
      act[k] = 0;
      if (k === 'X2') x2Level = 1;
      if (k === 'JETPACK') endJetpack();
      if (k === 'BOARD') { S.sfx.boardOff(); }
    }
  }
  if (act.MAGNET > 0) S.Missions.bump('MULT_TIME', 0);
  if (act.X2 > 0) S.Missions.bump('MULT_TIME', dt);
  updateFly(dt);
};

/* ----------------------------------------------------------- jetpack */

function startJetpack() {
  const P = S.P;
  P.jetStartY = P.y;
  P.jetT = 0;
  P.state = S.ST.JETPACK;
  S.Gen.mode = 'JETPACK';
}

function endJetpack() {
  const P = S.P;
  if (P.state === S.ST.JETPACK) {
    P.state = S.ST.RUN;
    P.vy = 0;
    // Do not snap to groundY: it is stale while flying (collision is skipped), so
    // let gravity and the normal landing pass resolve the touchdown instead.
    P.grounded = false;
  }
  S.Gen.mode = 'RUN';
}

/* -------------------------------------------------------- hoverboard */

Pw.activateBoard = function () {
  if (act.BOARD > 0) return false;
  if (S.SV.boardsInv <= 0) { S.sfx.deny(); S.toast('No hoverboards left'); return false; }
  S.SV.boardsInv--;
  const bd = S.boardById(S.SV.board);
  total.BOARD = bd.dur;
  act.BOARD = bd.dur;
  S.sfx.boardOn();
  S.stat('boardsUsed', 1);
  S.Missions.bump('BOARDS', 1);
  S.save();
  return true;
};

// The crash-protection contract: a lethal hit consumes the board instead.
Pw.breakBoard = function () {
  act.BOARD = 0;
  S.sfx.boardOff();
  S.sfx.crash();
  S.FX.burst(S.P.x, S.P.y + 0.6, S.P.z, 14, 0xff8a3d);
  S.World.shake(0.9);
  S.P.invuln = 0.9;
  S.P.speedMul = 0.7;
  S.toast('Board destroyed!');
};

/* ------------------------------------------------- magnet fly coins */

const FLY_MAX = 40;
let flyPool = [], fly = [];

function initFly(scene) {
  const geo = new THREE.CylinderGeometry(0.21, 0.21, 0.09, 12);
  geo.rotateX(Math.PI / 2);              // matches the instanced track coins
  const mat = new THREE.MeshLambertMaterial({ color: 0xffcf3d, emissive: 0x6a4a00 });
  for (let i = 0; i < FLY_MAX; i++) {
    const m = new THREE.Mesh(geo, mat);
    m.visible = false;
    m.frustumCulled = false;
    scene.add(m);
    flyPool.push({ mesh: m, p: new THREE.Vector3() });
  }
}

// Promotes an instanced coin into a real mesh so it can be pulled independently.
Pw.promote = function (chunk, i, x, y, wz) {
  if (fly.length >= FLY_MAX || !flyPool.length) return;
  S.World.killCoin(chunk, i);
  const f = flyPool.pop();
  f.p.set(x, y, wz);
  f.mesh.position.copy(f.p);
  f.mesh.visible = true;
  fly.push(f);
};

function updateFly(dt) {
  if (!fly.length) return;
  const P = S.P;
  const k = damp(11, dt);
  const tx = P.x, ty = P.y + 0.9, tz = P.z;
  for (let i = fly.length - 1; i >= 0; i--) {
    const f = fly[i];
    f.p.x += (tx - f.p.x) * k;
    f.p.y += (ty - f.p.y) * k;
    f.p.z += (tz - f.p.z) * k;
    f.mesh.position.copy(f.p);
    f.mesh.rotation.y += dt * 8;
    const dx = tx - f.p.x, dy = ty - f.p.y, dz = tz - f.p.z;
    if (dx * dx + dy * dy + dz * dz < 0.36) {
      f.mesh.visible = false;
      flyPool.push(f);
      fly.splice(i, 1);
      S.onCoin();
    }
  }
}

function clearFly() {
  for (const f of fly) { f.mesh.visible = false; flyPool.push(f); }
  fly.length = 0;
}

/* ------------------------------------------------------- particle FX */

const FX = {};
S.FX = FX;

let pts = null, pPos = null, pVel = null, pLife = null, pMax = 0, pHead = 0;

function initFX(scene) {
  pMax = S.Q.parts;
  if (pMax <= 0) return;
  pPos = new Float32Array(pMax * 3);
  pVel = new Float32Array(pMax * 3);
  pLife = new Float32Array(pMax);
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(pPos, 3));
  const mat = new THREE.PointsMaterial({
    size: 0.28, color: 0xffffff, transparent: true, opacity: 0.9,
    depthWrite: false, blending: THREE.AdditiveBlending, fog: false
  });
  pts = new THREE.Points(geo, mat);
  pts.frustumCulled = false;
  // Park unused particles far below the world rather than resizing the buffer.
  for (let i = 0; i < pMax; i++) pPos[i * 3 + 1] = -999;
  scene.add(pts);
}

FX.burst = function (x, y, z, n, color) {
  if (!pts) return;
  if (color != null) pts.material.color.setHex(color);
  for (let i = 0; i < n; i++) {
    const j = pHead++ % pMax;
    pPos[j * 3] = x; pPos[j * 3 + 1] = y; pPos[j * 3 + 2] = z;
    pVel[j * 3] = (Math.random() - .5) * 7;
    pVel[j * 3 + 1] = Math.random() * 6 + 1;
    pVel[j * 3 + 2] = (Math.random() - .5) * 7;
    pLife[j] = 0.6 + Math.random() * 0.4;
  }
};

FX.dust = function (x, y) {
  if (!pts) return;
  FX.burst(x, y + 0.1, S.P.z, 5, 0xd8d0c0);
};

FX.update = function (dt) {
  if (!pts) return;
  let any = false;
  for (let i = 0; i < pMax; i++) {
    if (pLife[i] <= 0) continue;
    any = true;
    pLife[i] -= dt;
    if (pLife[i] <= 0) { pPos[i * 3 + 1] = -999; continue; }
    pVel[i * 3 + 1] -= 18 * dt;
    pPos[i * 3] += pVel[i * 3] * dt;
    pPos[i * 3 + 1] += pVel[i * 3 + 1] * dt;
    pPos[i * 3 + 2] += pVel[i * 3 + 2] * dt;
  }
  if (any) pts.geometry.attributes.position.needsUpdate = true;
};

Pw.initScene = function (scene) { initFly(scene); initFX(scene); };

})();
