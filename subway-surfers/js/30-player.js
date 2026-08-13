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
  { id: 'dash',  name: 'Dash',   price: 0,     skin: 0xF0BE92, shirt: 0x2FC4F0, pants: 0x2B3F70, hair: 0xF0402F, bag: 0xF0A32F, shoe: 0xF24A3D, cap: 1, bonus: 1.00 },
  { id: 'nova',  name: 'Nova',   price: 800,   skin: 0xE8AE7E, shirt: 0xFF5FA8, pants: 0x4B2E75, hair: 0x6B2BC4, bag: 0x3ECF8E, shoe: 0xFFFFFF, cap: 0, bonus: 1.02 },
  { id: 'rook',  name: 'Rook',   price: 1800,  skin: 0x9C6B45, shirt: 0x3ECF6E, pants: 0x24435F, hair: 0x1A1310, bag: 0xF0C93A, shoe: 0x2B3350, cap: 1, bonus: 1.03 },
  { id: 'vex',   name: 'Vex',    price: 3500,  skin: 0xF7D9BC, shirt: 0xA45CFF, pants: 0x32245A, hair: 0xFF4FC0, bag: 0x7AE0FF, shoe: 0xFF4FC0, cap: 0, bonus: 1.04 },
  { id: 'bolt',  name: 'Bolt',   price: 6000,  skin: 0xC6D2E0, shirt: 0x4C7CE0, pants: 0x39424E, hair: 0x8FA0B8, bag: 0xE85C3D, shoe: 0xFFD257, cap: 1, bonus: 1.06 },
  { id: 'ember', name: 'Ember',  price: 9000,  skin: 0xF0B48A, shirt: 0xFF7035, pants: 0x5A2418, hair: 0xFFC23A, bag: 0x2B3350, shoe: 0xFFC23A, cap: 0, bonus: 1.08 },
  { id: 'frost', name: 'Frost',  price: 12000, skin: 0xE6F2FA, shirt: 0x7AE0FF, pants: 0x2E5C7E, hair: 0xC8EEFF, bag: 0xFFFFFF, shoe: 0x4C9CE0, cap: 1, bonus: 1.10 },
  { id: 'shade', name: 'Shade',  price: 0, keys: 3, skin: 0x6A6472, shirt: 0x24202E, pants: 0x14121A, hair: 0x8F3ED8, bag: 0x8F3ED8, shoe: 0x24202E, cap: 1, bonus: 1.06 },
  { id: 'aurum', name: 'Aurum',  price: 0, keys: 5, skin: 0xFFE1A8, shirt: 0xFFD257, pants: 0xA6801E, hair: 0xFFF0C0, bag: 0xFFB300, shoe: 0xFFF0C0, cap: 0, bonus: 1.12 }
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
//
// Proportions are deliberately stylised rather than anatomical: an oversized head
// at roughly a quarter of total height, short chunky limbs and a wide torso. That
// silhouette is what makes a runner read as a runner at gameplay distance, where
// a realistically-proportioned figure just looks like a stick.
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

  const mSkin = matFor(0xE8B58A), mShirt = matFor(0x2FA8E0),
        mPants = matFor(0x2B3350), mHair = matFor(0x2A1E18),
        mShoe = matFor(0xF24A3D), mDark = matFor(0x1a1a20);

  P.torso  = put(B(0.54, 0.46, 0.32), mShirt, 0, 1.00, 0);
  P.pelvis = put(B(0.46, 0.18, 0.30), mPants, 0, 0.72, 0);
  // Backpack — a strong part of the runner silhouette from behind, which is the
  // camera's angle for the entire game.
  P.pack   = put(B(0.40, 0.40, 0.16), matFor(0xE8663D), 0, 1.02, -0.23);
  P.strapL = put(B(0.07, 0.30, 0.04), mDark, -0.17, 1.06, 0.16);
  P.strapR = put(B(0.07, 0.30, 0.04), mDark,  0.17, 1.06, 0.16);

  for (const s of [-1, 1]) {
    const tag = s < 0 ? 'L' : 'R';
    const hip = joint(s * 0.14, 0.70, 0);
    P['hip' + tag] = hip;
    P['thigh' + tag] = put(B(0.21, 0.34, 0.21), mPants, 0, -0.17, 0, hip);
    const knee = joint(0, -0.34, 0, hip);
    P['knee' + tag] = knee;
    P['shin' + tag] = put(B(0.18, 0.30, 0.18), mPants, 0, -0.15, 0, knee);
    P['foot' + tag] = put(B(0.21, 0.13, 0.32), mShoe, 0, -0.34, 0.06, knee);

    const sho = joint(s * 0.34, 1.16, 0);
    P['sho' + tag] = sho;
    P['upArm' + tag] = put(B(0.17, 0.30, 0.17), mShirt, 0, -0.15, 0, sho);
    const elb = joint(0, -0.30, 0, sho);
    P['elb' + tag] = elb;
    P['foreArm' + tag] = put(B(0.15, 0.28, 0.15), mSkin, 0, -0.14, 0, elb);
    P['hand' + tag]    = put(B(0.17, 0.15, 0.17), mSkin, 0, -0.30, 0, elb);
  }

  const neck = joint(0, 1.26, 0);
  P.neck = neck;
  P.head = put(new THREE.SphereGeometry(0.30, 14, 12), mSkin, 0, 0.28, 0, neck);
  // Cap: crown plus a forward brim. The crown must sit high enough to cover the
  // top of the 0.30-radius head (0.58), or the skull pokes through it.
  P.hair = put(B(0.60, 0.22, 0.60), mHair, 0, 0.52, 0, neck);
  P.brim = put(B(0.54, 0.07, 0.26), mHair, 0, 0.43, 0.31, neck);
  // A face costs four boxes and does more for readability than anything else here.
  P.eyeL = put(B(0.07, 0.11, 0.05), mDark, -0.11, 0.30, 0.27, neck);
  P.eyeR = put(B(0.07, 0.11, 0.05), mDark,  0.11, 0.30, 0.27, neck);
  P.mouth = put(B(0.14, 0.04, 0.04), mDark, 0, 0.16, 0.28, neck);

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

  // Jetpack, hidden unless flying. Named `jet` so it never collides with the
  // always-visible backpack above.
  const jet = new THREE.Group();
  const tank = new THREE.Mesh(B(0.40, 0.46, 0.20), matFor(0x9aa3ad));
  tank.position.set(0, 1.04, -0.30);
  jet.add(tank);
  for (const s of [-1, 1]) {
    const noz = new THREE.Mesh(new THREE.CylinderGeometry(0.07, 0.11, 0.16, 8), matFor(0x565e68));
    noz.position.set(s * 0.13, 0.76, -0.30);
    jet.add(noz);
    const flame = new THREE.Mesh(new THREE.ConeGeometry(0.11, 0.42, 8),
      new THREE.MeshBasicMaterial({ color: 0x7ae0ff, transparent: true, opacity: 0.9, fog: false }));
    flame.position.set(s * 0.13, 0.50, -0.30);
    flame.rotation.x = Math.PI;
    jet.add(flame);
    P['flame' + (s < 0 ? 'L' : 'R')] = flame;
  }
  jet.visible = false;
  body.add(jet);

  P.body = body;
  P.board = board;
  P.deck = deck;
  P.under = under;
  P.jet = jet;
  P.root = root;
  return P;
}

function applyCharPalette(P, ch) {
  const mSkin = matFor(ch.skin), mShirt = matFor(ch.shirt),
        mPants = matFor(ch.pants), mHair = matFor(ch.hair);
  P.torso.material = mShirt;
  P.pelvis.material = mPants;
  P.head.material = mSkin;
  P.hair.material = mHair;
  P.brim.material = mHair;
  // `cap` picks a peaked cap versus a low hair block; the brim only fits the cap.
  P.hair.scale.set(1, ch.cap ? 1 : 0.55, 1);
  P.brim.visible = !!ch.cap;
  P.pack.material = matFor(ch.bag || 0xE8663D);
  for (const t of ['L', 'R']) {
    P['thigh' + t].material = mPants;
    P['shin' + t].material = mPants;
    P['upArm' + t].material = mShirt;
    P['foreArm' + t].material = mSkin;
    P['hand' + t].material = mSkin;
    P['foot' + t].material = matFor(ch.shoe || 0xF24A3D);
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
    rig.jet.visible = false;
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
    Chase.close();                    // the pursuer gains ground on a stumble
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
  b.jet.visible = (P.state === JETPACK);
  b.pack.visible = (P.state !== JETPACK);        // backpack swaps out for the jetpack
  if (P.state === JETPACK) {
    const f = 0.7 + Math.abs(Math.sin(performance.now() * 0.018)) * 0.6;
    b.flameL.scale.set(1, f, 1);
    b.flameR.scale.set(1, f, 1);
  }
  if (board) b.board.position.y = 0.07;
}

/* ------------------------------------------------------------- pursuer */

/* The inspector and his dog chasing just behind the runner. This is the most
   recognisable piece of staging in the genre and the game read as oddly empty
   without it. He sits at a resting distance, lunges forward after a stumble, then
   is slowly shrugged off again — so a stumble has visible consequence beyond the
   speed penalty. Purely cosmetic: the pursuer never collides with anything. */

// Tuned against the camera, not picked by eye. With a chase camera looking down
// the track, anything behind the runner projects low in frame, and at 5.4 m the
// pursuer landed at NDC y = -2.07 — a full screen-height below the bottom edge.
// Pulling the camera back to 11.4 m and setting 4.0 m puts his head near -0.7,
// which is exactly how the genre frames him.
const CHASE_REST  = 4.0;      // metres behind the runner while clean
const CHASE_CLOSE = 2.6;      // how close he gets right after a stumble
const CHASE_FALL  = 0.55;     // metres per second he drops back

const Chase = {};
S.Chase = Chase;

let gRig = null, dog = null, chaseZ = CHASE_REST, chasePh = 0;

function buildDog() {
  const B = (w, h, d) => new THREE.BoxGeometry(w, h, d);
  const g = new THREE.Group();
  const mFur = matFor(0x6B4A32), mDark = matFor(0x3A2A1C);

  const body = new THREE.Mesh(B(0.34, 0.34, 0.78), mFur);
  body.position.y = 0.52; body.castShadow = true;
  g.add(body);

  const neck = new THREE.Group();
  neck.position.set(0, 0.62, 0.42);
  g.add(neck);
  const head = new THREE.Mesh(B(0.30, 0.30, 0.34), mFur);
  head.position.z = 0.14; head.castShadow = true;
  neck.add(head);
  const snout = new THREE.Mesh(B(0.16, 0.14, 0.20), mDark);
  snout.position.set(0, -0.05, 0.36);
  neck.add(snout);
  for (const s of [-1, 1]) {
    const ear = new THREE.Mesh(B(0.09, 0.16, 0.06), mDark);
    ear.position.set(s * 0.11, 0.20, 0.06);
    neck.add(ear);
  }
  g.neck = neck;

  g.legs = [];
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    const hip = new THREE.Group();
    hip.position.set(sx * 0.13, 0.50, sz * 0.28);
    g.add(hip);
    const leg = new THREE.Mesh(B(0.10, 0.42, 0.10), mDark);
    leg.position.y = -0.21;
    hip.add(leg);
    g.legs.push(hip);
  }

  const tail = new THREE.Group();
  tail.position.set(0, 0.62, -0.38);
  g.add(tail);
  const tailM = new THREE.Mesh(B(0.08, 0.08, 0.34), mFur);
  tailM.position.z = -0.17;
  tail.add(tailM);
  g.tail = tail;

  return g;
}

Chase.init = function (scene) {
  gRig = buildRig();
  // Inspector livery: dark uniform, peaked cap, no backpack.
  applyCharPalette(gRig, { skin: 0xE0B088, shirt: 0x2A3D6B, pants: 0x1E2A4A,
                           hair: 0x16203A, bag: 0x2A3D6B, shoe: 0x14161C, cap: 1 });
  gRig.pack.visible = false;
  gRig.strapL.visible = false;
  gRig.strapR.visible = false;
  // He runs the same way the player does, so his face is never on camera.
  gRig.eyeL.visible = gRig.eyeR.visible = gRig.mouth.visible = false;
  gRig.root.scale.setScalar(1.08);          // slightly heavier than the runner
  scene.add(gRig.root);

  dog = buildDog();
  scene.add(dog);
};

Chase.reset = function () {
  chaseZ = CHASE_REST;
  chasePh = 0;
};

// Called when the runner stumbles: the pursuer lunges forward.
Chase.close = function () { chaseZ = Math.min(chaseZ, CHASE_CLOSE); };

// Exposed so the harness can check where the pursuer actually lands on screen.
Chase._dbg = function () {
  if (!gRig) return null;
  const cam = S.World.camera();
  const v = gRig.root.position.clone(); v.y += 1.2; v.project(cam);
  const d = dog.position.clone(); d.y += 0.6; d.project(cam);
  return {
    visible: gRig.root.visible, chaseZ: +chaseZ.toFixed(2),
    guardPos: gRig.root.position.toArray().map(n => +n.toFixed(2)),
    guardNdc: [+v.x.toFixed(2), +v.y.toFixed(2), +v.z.toFixed(3)],
    dogNdc: [+d.x.toFixed(2), +d.y.toFixed(2), +d.z.toFixed(3)]
  };
};

Chase.update = function (dt, speed, running) {
  if (!gRig) return;

  const show = running && P.state !== DEAD;
  gRig.root.visible = show;
  dog.visible = show;
  if (!show) return;

  chaseZ = Math.min(CHASE_REST, chaseZ + CHASE_FALL * dt);

  // Trails the runner's lane, but lags behind it — he is chasing, not mirroring.
  const tx = P.x * 0.75;
  gRig.root.position.x += (tx - gRig.root.position.x) * damp(4, dt);
  gRig.root.position.set(gRig.root.position.x, 0, P.z + chaseZ);
  dog.position.set(gRig.root.position.x - 0.85, 0, P.z + chaseZ - 0.5);

  // Run cycles, deliberately out of phase with the runner's so they read apart.
  chasePh += dt * (2.6 + speed * 0.22);
  const s = Math.sin(chasePh), c = Math.cos(chasePh);
  gRig.hipL.rotation.x = s * 1.05;  gRig.hipR.rotation.x = -s * 1.05;
  gRig.kneeL.rotation.x = Math.max(0, -Math.sin(chasePh + 0.6)) * 1.30;
  gRig.kneeR.rotation.x = Math.max(0,  Math.sin(chasePh + 0.6)) * 1.30;
  gRig.shoL.rotation.x = -s * 0.95; gRig.shoR.rotation.x = s * 0.95;
  gRig.elbL.rotation.x = -0.6 - Math.max(0, s) * 0.5;
  gRig.elbR.rotation.x = -0.6 - Math.max(0, -s) * 0.5;
  gRig.body.position.y = 0.06 * Math.abs(c);
  gRig.body.rotation.x = 0.12;              // leaning into the chase

  const dp = chasePh * 1.6;
  for (let i = 0; i < dog.legs.length; i++) {
    const off = (i === 0 || i === 3) ? 0 : Math.PI;
    dog.legs[i].rotation.x = Math.sin(dp + off) * 0.9;
  }
  dog.position.y = Math.abs(Math.sin(dp)) * 0.07;
  dog.neck.rotation.x = -0.12 + Math.sin(dp) * 0.06;
  dog.tail.rotation.y = Math.sin(dp * 1.4) * 0.5;
};

Object.assign(S, { playerAttach: attach, playerReset: reset, playerUpdate: update, setOpacity });

})();
