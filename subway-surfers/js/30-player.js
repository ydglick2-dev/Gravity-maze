/*! ---------------------------------------------------------------------------
 *  Subway Surfers — personal, non-commercial fan project.
 *  Not affiliated with, endorsed by, or connected to SYBO Games or Kiloo in any
 *  way. All code, art and audio in this project are original and generated
 *  procedurally at runtime; no third-party assets are used. Personal use only.
 *
 *  Bundled dependency: three.min.js — Three.js r128, (c) three.js authors,
 *  MIT License. Its original license header is preserved in that file.
 * ------------------------------------------------------------------------- */

/* 30-player.js — procedural character rig, state machine, lane tween and the
   AABB collision pass. */

(() => {
'use strict';

const S = window.SS;
const { clamp, lerp, damp, TAU, LANE_X, ROOF_Y, ROOF_TALL, matFor } = S;

/* ----------------------------------------------------------- constants */

const GRAVITY   = -55;
const JUMP_V0   = 15.5;    // apex 2.19 m, airtime 0.564 s
const JUMP_V0_S = 19.0;    // super sneakers: apex 3.28 m
const RAMP_V0   = 22.0;    // apex 4.40 m — the only way onto a tall freight roof
const FASTFALL  = -34;
const ROLL_DUR  = 0.55;
const LANE_T    = 0.14;
const BUFFER    = 0.18;
const COYOTE    = 0.09;
const STUMBLE_T = 0.55;
const STUMBLE_2 = 1.50;

Object.assign(S, { GRAVITY, JUMP_V0, JUMP_V0_S, RAMP_V0, ROLL_DUR });

const RUN = 0, JUMP = 1, ROLL = 2, JETPACK = 3, STUMBLE = 4, DEAD = 5;
S.ST = { RUN, JUMP, ROLL, JETPACK, STUMBLE, DEAD };

/* ------------------------------------------------------- catalogue data */

const CHARS = [
  { id: 'dash',  name: 'Dash',   price: 0,     skin: 0xE8B58A, shirt: 0x2FA8E0, pants: 0x2B3350, hair: 0x2A1E18, cap: 1, bonus: 1.00 },
  { id: 'nova',  name: 'Nova',   price: 800,   skin: 0xD9A374, shirt: 0xFF5F9E, pants: 0x3B2E55, hair: 0x4A2B1E, cap: 0, bonus: 1.02 },
  { id: 'rook',  name: 'Rook',   price: 1800,  skin: 0x8D6242, shirt: 0x3ECF8E, pants: 0x24343F, hair: 0x1A1310, cap: 1, bonus: 1.03 },
  { id: 'vex',   name: 'Vex',    price: 3500,  skin: 0xF0D2B4, shirt: 0x9B5CFF, pants: 0x2A2440, hair: 0xE04FA0, cap: 0, bonus: 1.04 },
  { id: 'bolt',  name: 'Bolt',   price: 6000,  skin: 0xB9C4D0, shirt: 0x556070, pants: 0x39424E, hair: 0x7A8698, cap: 1, bonus: 1.06 },
  { id: 'ember', name: 'Ember',  price: 9000,  skin: 0xE8A87C, shirt: 0xFF6B35, pants: 0x4A2018, hair: 0xFFB03A, cap: 0, bonus: 1.08 },
  { id: 'frost', name: 'Frost',  price: 12000, skin: 0xDCEAF5, shirt: 0x7AE0FF, pants: 0x2E4A5E, hair: 0xBFE8FF, cap: 1, bonus: 1.10 },
  { id: 'shade', name: 'Shade',  price: 0, keys: 3, skin: 0x5A5560, shirt: 0x1E1B24, pants: 0x14121A, hair: 0x38333F, cap: 1, bonus: 1.06 },
  { id: 'aurum', name: 'Aurum',  price: 0, keys: 5, skin: 0xFFE1A8, shirt: 0xFFD257, pants: 0x8A6A1E, hair: 0xFFF0C0, cap: 0, bonus: 1.12 }
];

const BOARDS = [
  { id: 'classic', name: 'Classic', price: 0,    deck: 0xFF8A3D, rail: 0x2B3350, dur: 30, mult: 1.0 },
  { id: 'wave',    name: 'Wave',    price: 500,  deck: 0x3ECF8E, rail: 0x1E4A3A, dur: 33, mult: 1.0 },
  { id: 'neon',    name: 'Neon',    price: 1200, deck: 0xFF3DA5, rail: 0x3A1030, dur: 35, mult: 1.05 },
  { id: 'storm',   name: 'Storm',   price: 2500, deck: 0x7AE0FF, rail: 0x1B3A4A, dur: 38, mult: 1.05 },
  { id: 'magma',   name: 'Magma',   price: 4000, deck: 0xFF5A2B, rail: 0x431408, dur: 40, mult: 1.10 },
  { id: 'prism',   name: 'Prism',   price: 5000, deck: 0xC9A0FF, rail: 0x2E1E4A, dur: 45, mult: 1.15 }
];

S.CHARS = CHARS;
S.BOARDS = BOARDS;
S.charById  = id => CHARS.find(c => c.id === id) || CHARS[0];
S.boardById = id => BOARDS.find(b => b.id === id) || BOARDS[0];

/* --------------------------------------------------------------- rig */

// r128 has no CapsuleGeometry, so the whole body is boxes plus a sphere head.
// The pivot sits at the feet (y = 0), which makes root.position.y the ground
// contact directly — no offset maths anywhere else.
function buildRig() {
  const B = (w, h, d) => new THREE.BoxGeometry(w, h, d);
  const root = new THREE.Group();
  const body = new THREE.Group();
  root.add(body);

  const P = {};
  const put = (geo, mat, x, y, z, parent) => {
    const m = new THREE.Mesh(geo, mat);
    m.position.set(x, y, z);
    m.castShadow = true;
    (parent || body).add(m);
    return m;
  };
  const joint = (x, y, z, parent) => {
    const g = new THREE.Group();
    g.position.set(x, y, z);
    (parent || body).add(g);
    return g;
  };

  const mSkin = matFor(0xE8B58A), mShirt = matFor(0x2FA8E0), mPants = matFor(0x2B3350), mHair = matFor(0x2A1E18);

  P.torso  = put(B(0.46, 0.52, 0.26), mShirt, 0, 1.18, 0);
  P.pelvis = put(B(0.42, 0.20, 0.24), mPants, 0, 0.86, 0);

  for (const s of [-1, 1]) {
    const tag = s < 0 ? 'L' : 'R';
    const hip = joint(s * 0.13, 0.86, 0);
    P['hip' + tag] = hip;
    P['thigh' + tag] = put(B(0.17, 0.42, 0.18), mPants, 0, -0.21, 0, hip);
    const knee = joint(0, -0.42, 0, hip);
    P['knee' + tag] = knee;
    P['shin' + tag] = put(B(0.15, 0.40, 0.16), mSkin, 0, -0.20, 0, knee);
    P['foot' + tag] = put(B(0.17, 0.10, 0.28), matFor(0x24262c), 0, -0.42, 0.05, knee);

    const sho = joint(s * 0.30, 1.42, 0);
    P['sho' + tag] = sho;
    P['upArm' + tag] = put(B(0.14, 0.36, 0.14), mShirt, 0, -0.18, 0, sho);
    const elb = joint(0, -0.36, 0, sho);
    P['elb' + tag] = elb;
    P['foreArm' + tag] = put(B(0.12, 0.34, 0.12), mSkin, 0, -0.17, 0, elb);
  }

  const neck = joint(0, 1.56, 0);
  P.neck = neck;
  P.head = put(new THREE.SphereGeometry(0.19, 10, 8), mSkin, 0, 0.17, 0, neck);
  P.hair = put(B(0.40, 0.10, 0.40), mHair, 0, 0.32, 0, neck);

  // Board, hidden unless the hoverboard is active.
  const board = new THREE.Group();
  const deck = new THREE.Mesh(B(0.92, 0.06, 0.36), matFor(0xFF8A3D));
  deck.castShadow = true;
  board.add(deck);
  const under = new THREE.Mesh(B(0.7, 0.05, 0.28), matFor(0x2B3350));
  under.position.y = -0.06;
  board.add(under);
  board.position.y = 0.07;
  board.visible = false;
  root.add(board);

  // Jetpack, hidden unless flying.
  const pack = new THREE.Group();
  const tank = new THREE.Mesh(B(0.34, 0.42, 0.18), matFor(0x9aa3ad));
  tank.position.set(0, 1.22, -0.22);
  pack.add(tank);
  pack.visible = false;
  body.add(pack);

  P.body = body;
  P.board = board;
  P.deck = deck;
  P.under = under;
  P.pack = pack;
  P.root = root;
  return P;
}

function applyCharPalette(P, ch) {
  const mSkin = matFor(ch.skin), mShirt = matFor(ch.shirt), mPants = matFor(ch.pants), mHair = matFor(ch.hair);
  P.torso.material = mShirt;
  P.pelvis.material = mPants;
  P.head.material = mSkin;
  P.hair.material = mHair;
  P.hair.visible = true;
  P.hair.scale.set(1, ch.cap ? 1 : 0.7, 1);
  for (const t of ['L', 'R']) {
    P['thigh' + t].material = mPants;
    P['shin' + t].material = mSkin;
    P['upArm' + t].material = mShirt;
    P['foreArm' + t].material = mSkin;
  }
}

function applyBoardPalette(P, bd) {
  P.deck.material = matFor(bd.deck);
  P.under.material = matFor(bd.rail);
}

S.buildRig = buildRig;
S.applyCharPalette = applyCharPalette;
S.applyBoardPalette = applyBoardPalette;

/* -------------------------------------------------------------- state */

const P = {};
S.P = P;

let rig = null;

function attach(scene) {
  rig = buildRig();
  applyCharPalette(rig, S.charById(S.SV.char));
  applyBoardPalette(rig, S.boardById(S.SV.board));
  scene.add(rig.root);
  P.rig = rig;
}

function reset() {
  P.x = 0; P.y = 0; P.z = 0;
  P.vy = 0;
  P.state = RUN;
  P.lane = 1; P.laneFrom = 0; P.laneTo = 1; P.laneT = 1; P.laneDur = LANE_T; P.laneVx = 0;
  P.rollT = 0;
  P.groundY = 0;
  P.grounded = true;
  P.sinceGround = 0;
  P.jumpedThisAir = false;
  P.invuln = 0;
  P.stumbleT = 0;
  P.lastStumble = -99;
  P.stumbleZ = 0;
  P.buf = 0; P.bufT = 0;
  P.runPh = 0;
  P.deadT = 0;
  P.grinding = false;
  P.speedMul = 1;
  P.prevFoot = 0;
  P.jetT = 0;
  if (rig) {
    rig.root.position.set(0, 0, 0);
    rig.root.rotation.set(0, 0, 0);
    rig.board.visible = false;
    rig.pack.visible = false;
    rig.root.visible = true;
    setOpacity(1);
  }
}

function setOpacity(a) {
  rig.root.traverse(o => {
    if (o.isMesh && o.material && !Array.isArray(o.material)) {
      o.material.transparent = a < 1;
      o.material.opacity = a;
    }
  });
}

/* -------------------------------------------------------------- input */

function act(a) {
  if (P.state === DEAD) return;
  if (a === 'left')  { tryLane(-1); return; }
  if (a === 'right') { tryLane(+1); return; }
  if (a === 'up')    { if (!doJump()) { P.buf = 1; P.bufT = BUFFER; } return; }
  if (a === 'down')  { doRoll(); return; }
}
S.playerAct = act;

function tryLane(dir) {
  const t = clamp(P.laneTo + dir, 0, 2);
  if (t === P.laneTo && P.laneT >= 1) { S.sfx.deny(); return; }
  P.laneFrom = P.x;                       // re-target from the current float x
  P.laneTo = t;
  P.laneT = 0;
  P.laneDur = LANE_T * (1 - 0.25 * S.speedNorm());
  P.grinding = false;
}

function doJump() {
  if (P.state === JETPACK) return true;
  const canJump = P.grounded || (P.sinceGround < COYOTE && !P.jumpedThisAir);
  if (!canJump) return false;
  P.vy = S.Power.active('SNEAK') ? JUMP_V0_S : JUMP_V0;
  P.state = JUMP;
  P.grounded = false;
  P.jumpedThisAir = true;
  P.grinding = false;
  P.rollT = 0;
  S.sfx.jump();
  S.stat('jumps', 1);
  return true;
}

function doRoll() {
  if (P.state === JETPACK) return true;
  // Airborne: slam down AND queue the roll, so you land already rolling — that is
  // how you get under a high barrier you mistimed a jump into.
  // The window is wider than BUFFER because the slam itself takes time to land.
  if (!P.grounded) { P.vy = FASTFALL; P.buf = 2; P.bufT = 0.5; return true; }
  P.state = ROLL;
  P.rollT = 0;
  P.grinding = false;
  S.sfx.roll();
  S.stat('rolls', 1);
  return true;
}

function launch(v0) {
  P.vy = v0;
  P.state = JUMP;
  P.grounded = false;
  P.jumpedThisAir = true;
  S.sfx.jump();
}

S.playerLaunch = launch;

/* ------------------------------------------------------------- update */

function update(dt, speed) {
  if (P.state === DEAD) { updateDead(dt); return; }

  P.sinceGround = P.grounded ? 0 : P.sinceGround + dt;
  if (P.invuln > 0) {
    P.invuln -= dt;
    setOpacity(P.invuln > 0 && (((performance.now() / 70) | 0) & 1) ? 0.35 : 1);
    if (P.invuln <= 0) setOpacity(1);
  }

  // Lane tween.
  if (P.laneT < 1) {
    P.laneT = Math.min(1, P.laneT + dt / P.laneDur);
    const e = S.easeOutCubic(P.laneT);
    const prev = P.x;
    P.x = P.laneFrom + (LANE_X[P.laneTo] - P.laneFrom) * e;
    P.laneVx = (P.x - prev) / Math.max(1e-4, dt);
    if (P.laneT >= 1) P.lane = P.laneTo;
  } else {
    P.x = LANE_X[P.laneTo];
    P.laneVx *= Math.exp(-12 * dt);
  }

  if (P.state === JETPACK) { updateJetpack(dt); }
  else {
    // Exact constant-acceleration integration, NOT semi-implicit Euler. Euler
    // loses 0.5*g*dt^2 of height per step, so on a 30fps device the base jump
    // would peak below ROOF_Y and silently break the level design's invariants.
    P.prevFoot = P.y;
    P.y += P.vy * dt + 0.5 * GRAVITY * dt * dt;
    P.vy += GRAVITY * dt;

    if (P.y <= P.groundY) {
      if (!P.grounded) { S.sfx.land(); S.FX && S.FX.dust(P.x, P.groundY); }
      P.y = P.groundY;
      P.vy = 0;
      P.grounded = true;
      P.jumpedThisAir = false;
      if (P.state === JUMP) P.state = RUN;
    } else {
      P.grounded = false;
    }

    // Roll timer.
    if (P.state === ROLL) {
      P.rollT += dt;
      if (P.rollT >= ROLL_DUR) { P.state = RUN; P.rollT = 0; }
    }

    // Buffered action fires on landing.
    P.bufT -= dt;
    if (P.grounded && P.buf && P.bufT > 0) {
      const b = P.buf; P.buf = 0;
      if (b === 1) doJump(); else doRoll();
    }

    // Stumble recovery.
    if (P.state === STUMBLE) {
      P.stumbleT += dt;
      const t = P.stumbleT / 0.9;
      P.z = t < 1 ? Math.sin(t * Math.PI) * 2.6 : 0;
      P.speedMul = lerp(0.62, 1, clamp(P.stumbleT / STUMBLE_T, 0, 1));
      if (P.stumbleT >= 0.9) { P.state = RUN; P.z = 0; P.speedMul = 1; }
    } else if (P.speedMul < 1) {
      P.speedMul = Math.min(1, P.speedMul + dt * 0.8);
    }
  }

  collide(dt, speed);
  animate(dt, speed);

  rig.root.position.set(P.x, P.y, P.z);
}

function updateJetpack(dt) {
  P.jetT += dt;
  const total = S.Power.remain('JETPACK');
  const targetY = 9.0;
  if (P.jetT < 0.55) {
    P.y = lerp(P.jetStartY, targetY, S.easeOutCubic(P.jetT / 0.55));
  } else if (total > 0.8) {
    P.y = targetY + Math.sin(performance.now() * 0.0022) * 0.25;
  } else {
    // Descend to ground level, not P.groundY — that value is stale while flying
    // because the collision pass is skipped, and the jetpack track is empty anyway.
    const t = 1 - clamp(total / 0.8, 0, 1);
    P.y = lerp(targetY, 0, S.easeInOutCubic(t));
  }
  P.vy = 0;
  P.grounded = false;
}

function updateDead(dt) {
  P.deadT += dt;
  rig.root.rotation.x += 6 * dt;
  P.y = Math.max(0, P.y - 6 * dt);
  rig.root.position.set(P.x, P.y, P.z);
}

/* ---------------------------------------------------------- collision */

function boxOf() {
  switch (P.state) {
    case ROLL:    return { cy: P.y + 0.36, hy: 0.36, hx: 0.42, hz: 0.35 };
    case JUMP:    return { cy: P.y + 0.80, hy: 0.80, hx: 0.40, hz: 0.35 };
    default:
      if (S.Power.active('BOARD')) return { cy: P.y + 1.00, hy: 0.85, hx: 0.42, hz: 0.35 };
      return { cy: P.y + 0.85, hy: 0.85, hx: 0.42, hz: 0.35 };
  }
}

function collide(dt, speed) {
  if (P.state === JETPACK || P.state === DEAD) return;

  const pb = boxOf();
  const sweep = speed * dt * 0.5;         // guards against tunnelling at high speed
  const live = S.World.live();
  let support = 0;                        // best roof under the feet this frame

  for (let ci = 0; ci < live.length; ci++) {
    const c = live[ci];
    if (c.z < -14 || c.z - S.CHUNK_LEN > 14) continue;

    // Sorted, cursor-scanned static boxes.
    const B = c.boxes;
    while (c.hitFrom < B.length && c.z + B[c.hitFrom].lz - B[c.hitFrom].hz > 3) c.hitFrom++;
    for (let i = c.hitFrom; i < B.length; i++) {
      const b = B[i];
      const bz = c.z + b.lz;
      if (bz + b.hz < -3) break;          // still ahead; so is everything after it
      support = Math.max(support, test(b, bz, pb, sweep, c));
    }

    // Movers are unsorted by construction, so they are always fully scanned.
    for (let i = 0; i < c.movers.length; i++) {
      const b = c.movers[i];
      support = Math.max(support, test(b, c.z + b.lz, pb, sweep, c));
    }

    collectCoins(c, pb);
  }

  P.groundY = support;
  if (P.grounded && P.y > support + 0.05) P.grounded = false;
  if (P.grounded) P.y = support;
}

// Returns the roof height if this box supports the player, else 0.
function test(b, bz, pb, sweep, chunk) {
  if (b.dead) return 0;

  const dz = Math.abs(bz - P.z);
  const dx = Math.abs(b.x - P.x);
  const sumZ = b.hz + pb.hz + sweep;
  const sumX = b.hx + pb.hx;

  // Roof support: a separate, looser test — the footprint only needs to overlap.
  let support = 0;
  if (b.top >= 0 && dx < b.hx + 0.30 && dz < b.hz + 0.30) {
    if (P.y >= b.top - 0.12 || (P.vy < 0 && P.prevFoot >= b.top - 0.05)) support = b.top;
  }

  if (dz >= sumZ || dx >= sumX) return support;

  const pyLo = pb.cy - pb.hy, pyHi = pb.cy + pb.hy;
  const byLo = b.y - b.hy,    byHi = b.y + b.hy;
  if (pyHi <= byLo || pyLo >= byHi) return support;   // cleared it vertically

  switch (b.kind) {
    case 'SOLID': {
      if (support > 0 && P.y >= b.top - 0.12) return support;   // standing on the roof
      const penZ = sumZ - dz, penX = sumX - dx;
      const side = penZ >= penX * 1.6 && Math.abs(P.laneVx) > 0.5;
      hit(side ? 'STUMBLE' : 'DEAD');
      return support;
    }
    case 'BAR_HIGH':
      if (P.state !== ROLL) hit('DEAD');
      return support;
    case 'BAR_LOW':
      hit('DEAD');
      return support;
    case 'RAMP':
      if (P.grounded) launch(RAMP_V0);
      return support;
    case 'RAIL':
      if (P.vy < 0 && P.y >= b.top - 0.2) { P.grinding = true; }
      return support;
    case 'POWER':
      b.dead = true; b.mesh.visible = false;
      S.Power.grant(b.pw);
      return support;
    case 'KEY':
      b.dead = true; b.mesh.visible = false;
      S.SV.keys++; S.sfx.key(); S.toast('+1 Key');
      return support;
    case 'LETTER':
      b.dead = true; b.mesh.visible = false;
      S.Missions.gotLetter(b.ch);
      return support;
  }
  return support;
}

function collectCoins(c, pb) {
  const magnet = S.Power.active('MAGNET');
  const R2 = magnet ? Math.pow(9 + 0.8 * (S.SV.up.MAGNET | 0), 2) : 2.0;
  for (let i = 0; i < c.coinN; i++) {
    if (!c.coinAlive[i]) continue;
    const wz = c.z + c.coinZ[i];
    const dx = c.coinX[i] - P.x, dy = c.coinY[i] - (P.y + 0.9), dz = wz - P.z;
    const d2 = dx * dx + dy * dy + dz * dz;
    if (d2 > R2) continue;
    if (magnet && d2 > 1.6) { S.Power.promote(c, i, c.coinX[i], c.coinY[i], wz); continue; }
    S.World.killCoin(c, i);
    S.onCoin();
  }
}

function hit(kind) {
  if (S.god || P.invuln > 0 || P.state === DEAD) return;

  if (S.Power.active('BOARD')) {          // the board absorbs it instead
    S.Power.breakBoard();
    return;
  }

  const now = performance.now() / 1000;
  if (kind === 'STUMBLE' && P.z < 0.4) {
    if (now - P.lastStumble < STUMBLE_2) { die(); return; }
    P.lastStumble = now;
    P.state = STUMBLE;
    P.stumbleT = 0;
    P.speedMul = 0.62;
    S.sfx.stumble();
    S.World.shake(0.5);
    return;
  }
  die();
}

function die() {
  if (P.state === DEAD) return;
  P.state = DEAD;
  P.deadT = 0;
  S.sfx.crash();
  S.World.shake(1.2);
  S.FX && S.FX.burst(P.x, P.y + 0.9, P.z, 22, 0xffffff);
  S.onDeath();
}

S.playerDie = die;
S.playerHit = hit;

/* ----------------------------------------------------------- animation */

function poseTo(o, axis, target, k) { o.rotation[axis] += (target - o.rotation[axis]) * k; }

function animate(dt, speed) {
  const k = damp(14, dt);
  const b = rig;
  const board = S.Power.active('BOARD');

  if (P.state === ROLL) {
    b.body.rotation.x = -TAU * (P.rollT / ROLL_DUR);
    b.body.position.y = 0.42;
    for (const t of ['L', 'R']) {
      poseTo(b['hip' + t], 'x', 1.2, k);
      poseTo(b['knee' + t], 'x', 1.2, k);
      poseTo(b['sho' + t], 'x', 1.2, k);
      poseTo(b['elb' + t], 'x', -1.2, k);
    }
    poseTo(b.neck, 'x', 0.4, k);
  } else {
    b.body.rotation.x += (0 - b.body.rotation.x) * k;

    if (P.state === JETPACK) {
      for (const t of ['L', 'R']) {
        poseTo(b['hip' + t], 'x', -0.85, k);
        poseTo(b['knee' + t], 'x', 0.3, k);
        poseTo(b['sho' + t], 'x', 1.5, k);
        poseTo(b['elb' + t], 'x', -0.3, k);
      }
      b.body.rotation.x += (-0.22 - b.body.rotation.x) * k;
      b.body.position.y += (0 - b.body.position.y) * k;

    } else if (P.state === STUMBLE) {
      const w = Math.sin(performance.now() * 0.022) * 2.4;
      poseTo(b.shoL, 'x', w, k * 2);
      poseTo(b.shoR, 'x', -w, k * 2);
      b.body.rotation.x += (0.5 - b.body.rotation.x) * k;
      for (const t of ['L', 'R']) poseTo(b['hip' + t], 'x', 0.3, k);

    } else if (!P.grounded && !board) {
      for (const t of ['L', 'R']) {
        poseTo(b['hip' + t], 'x', -0.55, k);
        poseTo(b['knee' + t], 'x', 1.35, k);
        poseTo(b['sho' + t], 'x', -2.1, k);
        poseTo(b['elb' + t], 'x', -0.4, k);
      }
      b.body.rotation.x += (0.14 - b.body.rotation.x) * k;
      b.body.position.y += (0 - b.body.position.y) * k;

    } else if (board) {
      for (const s of [-1, 1]) {
        const t = s < 0 ? 'L' : 'R';
        poseTo(b['hip' + t], 'x', 0.10, k);
        b['hip' + t].rotation.z += (s * 0.26 - b['hip' + t].rotation.z) * k;
        poseTo(b['knee' + t], 'x', 0.42, k);
        poseTo(b['sho' + t], 'x', 0, k);
        b['sho' + t].rotation.z += (s * 0.9 - b['sho' + t].rotation.z) * k;
        poseTo(b['elb' + t], 'x', -0.2, k);
      }
      b.body.rotation.z = Math.sin(performance.now() * 0.0026) * 0.09;
      b.body.position.y += (0 - b.body.position.y) * k;

    } else {
      // Run cycle: stride frequency follows ground speed.
      P.runPh += dt * (2.6 + speed * 0.22);
      const s = Math.sin(P.runPh), c = Math.cos(P.runPh);
      b.hipL.rotation.x = s * 0.95;   b.hipR.rotation.x = -s * 0.95;
      b.kneeL.rotation.x = Math.max(0, -Math.sin(P.runPh + 0.6)) * 1.30;
      b.kneeR.rotation.x = Math.max(0,  Math.sin(P.runPh + 0.6)) * 1.30;
      b.shoL.rotation.x = -s * 0.80;  b.shoR.rotation.x = s * 0.80;
      b.elbL.rotation.x = -0.55 - Math.max(0, s) * 0.5;
      b.elbR.rotation.x = -0.55 - Math.max(0, -s) * 0.5;
      b.hipL.rotation.z = b.hipR.rotation.z = 0;
      b.shoL.rotation.z = b.shoR.rotation.z = 0;
      b.body.position.y = 0.055 * Math.abs(c);
    }
  }

  if (P.state !== ROLL) b.body.rotation.z = board
    ? Math.sin(performance.now() * 0.0026) * 0.09
    : -clamp(P.laneVx * 0.055, -0.28, 0.28);
  b.neck.rotation.y = clamp(P.laneVx * 0.03, -0.2, 0.2);

  b.board.visible = board;
  b.pack.visible = (P.state === JETPACK);
  if (board) b.board.position.y = 0.07;
}

Object.assign(S, { playerAttach: attach, playerReset: reset, playerUpdate: update, setOpacity });

})();
