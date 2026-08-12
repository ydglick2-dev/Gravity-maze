/*! ---------------------------------------------------------------------------
 *  Subway Surfers — personal, non-commercial fan project.
 *  Not affiliated with, endorsed by, or connected to SYBO Games or Kiloo in any
 *  way. All code, art and audio in this project are original and generated
 *  procedurally at runtime; no third-party assets are used. Personal use only.
 *
 *  Bundled dependency: three.min.js — Three.js r128, (c) three.js authors,
 *  MIT License. Its original license header is preserved in that file.
 * ------------------------------------------------------------------------- */

/* 10-world.js — renderer, scene, camera, lights, fog, chunk streaming, pools,
   and every procedurally built prop. Three.js r128 API only. */

(() => {
'use strict';

const S = window.SS;
const { clamp, lerp, damp } = S;

/* -------------------------------------------------------------- constants */

const CHUNK_LEN = 30;                  // meters per chunk
const SLOT_LEN  = 5;                   // meters per row
const ROWS      = 6;                   // rows per chunk
const LANE_X    = [-2.2, 0, 2.2];
const RECYCLE_Z = 36;                  // recycle once the far edge is this far behind
const COIN_CAP  = 96;                  // instanced coins per chunk
const TRACK_W   = 11;

const ROOF_Y    = 2.00;                // subway car roof
const ROOF_TALL = 3.20;                // freight roof

Object.assign(S, { CHUNK_LEN, SLOT_LEN, ROWS, LANE_X, COIN_CAP, ROOF_Y, ROOF_TALL });

/* Themes swap every 800 m with a 2 s cross-lerp. */
const THEMES = [
  { name: 'Day',    sky: 0x9fc6e8, gnd: 0x6d7a66, rail: 0x4a4f56, bldg: [0xc9b9a4, 0xb4a894, 0xd8cbb6, 0x9fae9a, 0xc2a58e, 0xa9b4bd], hemi: 0xbcd8ff, sun: 0xfff2d6, sunI: 0.95 },
  { name: 'Sunset', sky: 0xf0a071, gnd: 0x6a5a4e, rail: 0x53483f, bldg: [0xd9906a, 0xb87a5e, 0xe0a883, 0x8f6d5c, 0xc98e6f, 0xa07a66], hemi: 0xffd0a8, sun: 0xffc890, sunI: 1.05 },
  { name: 'Night',  sky: 0x141a2e, gnd: 0x232a3a, rail: 0x2c3446, bldg: [0x2b3350, 0x3a4468, 0x232a45, 0x46527a, 0x2f3a5c, 0x1f2740], hemi: 0x4a5a90, sun: 0x9fb4ff, sunI: 0.55 },
  { name: 'Rain',   sky: 0x8c98a4, gnd: 0x50564f, rail: 0x424750, bldg: [0x8e97a0, 0x7b848d, 0x9aa3ac, 0x6f7880, 0x868f98, 0x757e87], hemi: 0xa8b6c4, sun: 0xd0d8e0, sunI: 0.7 },
  { name: 'Snow',   sky: 0xdfeaf5, gnd: 0xe4ecf2, rail: 0x8d97a2, bldg: [0xc4ced8, 0xd6dfe8, 0xb2bcc6, 0xe2e9f0, 0xc9d3dc, 0xbac4ce], hemi: 0xffffff, sun: 0xeaf2ff, sunI: 1.0 },
  { name: 'Tunnel', sky: 0x2a2320, gnd: 0x3a332e, rail: 0x2e2925, bldg: [0x4a423a, 0x3d362f, 0x554c42, 0x413a33, 0x4f463d, 0x362f29], hemi: 0x6a5a4a, sun: 0xffcf8a, sunI: 0.7 }
];

/* ------------------------------------------------------------- scratch */

const _v3a = new THREE.Vector3();
const _v3b = new THREE.Vector3();
const _m4  = new THREE.Matrix4();
const _q   = new THREE.Quaternion();
const _s3  = new THREE.Vector3(1, 1, 1);
const _c1  = new THREE.Color();
const _c2  = new THREE.Color();
const _qIdent = new THREE.Quaternion();
const _axisY  = new THREE.Vector3(0, 1, 0);
let coinSpin = 0;

/* ----------------------------------------------------------- materials */

const _mats = new Map();
function matFor(hex, opts) {
  const k = hex + (opts ? JSON.stringify(opts) : '');
  let m = _mats.get(k);
  if (!m) {
    m = new THREE.MeshLambertMaterial(Object.assign({ color: hex }, opts));
    _mats.set(k, m);
  }
  return m;
}
S.matFor = matFor;

/* Draws into an offscreen canvas once and returns a CanvasTexture. */
function cvTex(w, h, paint) {
  const cv = document.createElement('canvas');
  cv.width = w; cv.height = h;
  paint(cv.getContext('2d'), w, h);
  const t = new THREE.CanvasTexture(cv);
  t.encoding = THREE.sRGBEncoding;
  return t;
}
S.cvTex = cvTex;

const texWindows = cvTex(128, 64, (g, w, h) => {
  g.fillStyle = '#20262f'; g.fillRect(0, 0, w, h);
  for (let x = 6; x < w - 6; x += 20) {
    g.fillStyle = '#8fd4ff'; g.fillRect(x, 14, 14, 30);
    g.fillStyle = 'rgba(255,255,255,.25)'; g.fillRect(x, 14, 14, 8);
  }
});

// Ballast, sleepers and two steel rails — repeated along the length of each lane.
const texTrack = cvTex(64, 64, (g, w, h) => {
  g.fillStyle = '#ffffff'; g.fillRect(0, 0, w, h);
  g.fillStyle = 'rgba(0,0,0,.30)';                       // sleepers
  for (let y = 4; y < h; y += 16) g.fillRect(2, y, w - 4, 8);
  g.fillStyle = 'rgba(255,255,255,.75)';                 // rails
  g.fillRect(13, 0, 5, h);
  g.fillRect(w - 18, 0, 5, h);
});
texTrack.wrapS = texTrack.wrapT = THREE.RepeatWrapping;
texTrack.repeat.set(1, 6);

const _trackMats = new Map();
function trackMat(hex) {
  let m = _trackMats.get(hex);
  if (!m) { m = new THREE.MeshLambertMaterial({ color: hex, map: texTrack }); _trackMats.set(hex, m); }
  return m;
}

const texBldg = cvTex(64, 128, (g, w, h) => {
  g.fillStyle = '#ffffff'; g.fillRect(0, 0, w, h);
  g.fillStyle = 'rgba(0,0,0,.35)';
  for (let y = 8; y < h - 8; y += 14)
    for (let x = 6; x < w - 6; x += 14) g.fillRect(x, y, 8, 8);
});
texBldg.wrapS = texBldg.wrapT = THREE.RepeatWrapping;

/* --------------------------------------------------------------- pools */

function Pool(factory, prewarm) {
  const free = [];
  for (let i = 0; i < prewarm; i++) free.push(factory());
  return {
    get() { const o = free.pop() || factory(); o.visible = true; return o; },
    put(o) { o.visible = false; if (o.parent) o.parent.remove(o); free.push(o); },
    free
  };
}

/* -------------------------------------------------------- prop factories */

// Every factory returns an Object3D whose userData carries the collider extents,
// so the generator never has to know the geometry.

function mkTrain(tall) {
  const h = tall ? ROOF_TALL : ROOF_Y;
  const g = new THREE.Group();

  const body = new THREE.Mesh(new THREE.BoxGeometry(1.9, h, 15), matFor(0xd8d8dc));
  body.position.y = h / 2;
  body.castShadow = true; body.receiveShadow = true;
  g.add(body);

  const roof = new THREE.Mesh(new THREE.BoxGeometry(1.96, 0.10, 15.1), matFor(0xb2b6bd));
  roof.position.y = h + 0.05;
  g.add(roof);

  // Window strips, inset so they never z-fight with the body.
  const wm = new THREE.MeshLambertMaterial({ map: texWindows });
  for (const s of [-1, 1]) {
    const win = new THREE.Mesh(new THREE.PlaneGeometry(14, h * 0.34), wm);
    win.position.set(s * 0.96, h * 0.62, 0);
    win.rotation.y = s * Math.PI / 2;
    g.add(win);
  }

  const nose = new THREE.Mesh(new THREE.BoxGeometry(1.7, h * 0.8, 0.6), matFor(0xe45c4a));
  nose.position.set(0, h * 0.42, 7.6);
  g.add(nose);

  g.userData = { hx: 0.95, hy: h / 2, cy: h / 2, hz: 7.5, top: h + 0.10, kind: 'SOLID' };
  return g;
}

const mkTrainStd  = () => mkTrain(false);
const mkTrainTall = () => mkTrain(true);

function mkBarLow() {
  const g = new THREE.Group();
  const m = new THREE.Mesh(new THREE.BoxGeometry(1.7, 0.55, 0.5), matFor(0xf5b23c));
  m.position.y = 0.275; m.castShadow = true;
  g.add(m);
  const stripe = new THREE.Mesh(new THREE.BoxGeometry(1.72, 0.14, 0.52), matFor(0x2b2b30));
  stripe.position.y = 0.275;
  g.add(stripe);
  g.userData = { hx: 0.85, hy: 0.275, cy: 0.275, hz: 0.25, top: 0.55, kind: 'BAR_LOW' };
  return g;
}

function mkBarHigh() {
  const g = new THREE.Group();
  const m = new THREE.Mesh(new THREE.BoxGeometry(1.7, 0.9, 0.4), matFor(0x4fb4e8));
  m.position.y = 1.40; m.castShadow = true;
  g.add(m);
  for (const s of [-1, 1]) {
    const leg = new THREE.Mesh(new THREE.BoxGeometry(0.12, 0.95, 0.12), matFor(0x39424e));
    leg.position.set(s * 0.79, 0.475, 0);
    g.add(leg);
  }
  // Bottom at 0.95 -> you must be rolling to pass under it.
  g.userData = { hx: 0.85, hy: 0.45, cy: 1.40, hz: 0.20, top: -1, kind: 'BAR_HIGH' };
  return g;
}

function mkRamp() {
  // A box whose top face is raised at the far end, edited once at build time.
  const geo = new THREE.BoxGeometry(1.8, 0.2, 4);
  const p = geo.attributes.position;
  for (let i = 0; i < p.count; i++) {
    if (p.getY(i) > 0 && p.getZ(i) < 0) p.setY(i, 1.4);   // far end (encountered last) lifts
  }
  p.needsUpdate = true;
  geo.computeVertexNormals();
  const m = new THREE.Mesh(geo, matFor(0xc98f4a));
  m.position.y = 0.1; m.castShadow = true; m.receiveShadow = true;
  const g = new THREE.Group();
  g.add(m);
  g.userData = { hx: 0.9, hy: 0.7, cy: 0.7, hz: 2, top: -1, kind: 'RAMP' };
  return g;
}

function mkTunnel() {
  const geo = new THREE.CylinderGeometry(3.2, 3.2, 12, 14, 1, true, 0, Math.PI);
  const m = new THREE.Mesh(geo, matFor(0x3c4450, { side: THREE.BackSide }));
  m.rotation.z = Math.PI / 2;
  m.rotation.y = Math.PI / 2;
  const g = new THREE.Group();
  g.add(m);
  const lip = new THREE.Mesh(new THREE.BoxGeometry(7, 0.5, 0.4), matFor(0x2b3038));
  lip.position.set(0, 1.35, 6);
  g.add(lip);
  // Only the mouth lip is lethal; the tube itself is scenery.
  g.userData = { hx: 3.2, hy: 0.4, cy: 1.35, hz: 0.2, top: -1, kind: 'BAR_HIGH' };
  return g;
}

function mkRail() {
  const g = new THREE.Group();
  const bar = new THREE.Mesh(new THREE.BoxGeometry(0.14, 0.14, 10), matFor(0xd9d24a));
  bar.position.y = 0.85; bar.castShadow = true;
  g.add(bar);
  for (const z of [-4.5, 0, 4.5]) {
    const post = new THREE.Mesh(new THREE.BoxGeometry(0.1, 0.85, 0.1), matFor(0x666c76));
    post.position.set(0, 0.425, z);
    g.add(post);
  }
  g.userData = { hx: 0.4, hy: 0.07, cy: 0.85, hz: 5, top: 0.92, kind: 'RAIL' };
  return g;
}

function mkPillar() {
  const m = new THREE.Mesh(new THREE.BoxGeometry(0.5, 4, 0.5), matFor(0x8a8f98));
  m.position.y = 2; m.castShadow = true;
  const g = new THREE.Group();
  g.add(m);
  g.userData = { hx: 0.25, hy: 2, cy: 2, hz: 0.25, top: -1, kind: 'SOLID' };
  return g;
}

const ORB_COLOR = { MAGNET: 0xff5f9e, X2: 0xffd257, JETPACK: 0x7ae0ff, SNEAK: 0x9dff7a, BOARD: 0xff8a3d };

function mkPowerOrb() {
  const g = new THREE.Group();
  const core = new THREE.Mesh(new THREE.SphereGeometry(0.42, 12, 10), matFor(0xffffff));
  g.add(core);
  const ring = new THREE.Mesh(new THREE.TorusGeometry(0.6, 0.05, 6, 16), matFor(0xffffff));
  ring.rotation.x = Math.PI / 2;
  g.add(ring);
  g.userData = { hx: 0.6, hy: 0.6, cy: 0, hz: 0.6, top: -1, kind: 'POWER', core, ring };
  return g;
}

function mkLetter() {
  const g = new THREE.Group();
  const m = new THREE.Mesh(new THREE.BoxGeometry(0.7, 0.7, 0.12), [
    matFor(0xe0b13a), matFor(0xe0b13a), matFor(0xe0b13a),
    matFor(0xe0b13a), matFor(0xffd257), matFor(0xffd257)
  ]);
  g.add(m);
  g.userData = { hx: 0.4, hy: 0.4, cy: 0, hz: 0.2, top: -1, kind: 'LETTER', face: m };
  return g;
}

function mkKey() {
  const g = new THREE.Group();
  const ring = new THREE.Mesh(new THREE.TorusGeometry(0.22, 0.07, 6, 12), matFor(0xffd257));
  g.add(ring);
  const bit = new THREE.Mesh(new THREE.BoxGeometry(0.1, 0.45, 0.1), matFor(0xffd257));
  bit.position.y = -0.36;
  g.add(bit);
  g.userData = { hx: 0.35, hy: 0.45, cy: 0, hz: 0.35, top: -1, kind: 'KEY' };
  return g;
}

const POOLS = {
  TRAIN:     Pool(mkTrainStd, 20),
  TRAIN_TALL:Pool(mkTrainTall, 8),
  BAR_LOW:   Pool(mkBarLow, 24),
  BAR_HIGH:  Pool(mkBarHigh, 18),
  RAMP:      Pool(mkRamp, 8),
  TUNNEL:    Pool(mkTunnel, 5),
  RAIL:      Pool(mkRail, 10),
  PILLAR:    Pool(mkPillar, 20),
  POWER:     Pool(mkPowerOrb, 8),
  LETTER:    Pool(mkLetter, 6),
  KEY:       Pool(mkKey, 6)
};
S.POOLS = POOLS;

/* ---------------------------------------------------------------- state */

let ren, scene, cam, hemi, dl, sky;
let live = [], freeChunks = [], chunkIdx = 0, frontZ = 0;
let camX = 0, camY = 4.6, shakeX = 0, shakeAmp = 0, fovCur = 62;
let themeA = 0, themeB = 0, themeT = 1;
let coinGeo, coinMat;
let W = 1, H = 1;

const W3 = {};
S.World = W3;

/* --------------------------------------------------------------- setup */

function init(canvas) {
  const Q = S.Q;

  ren = new THREE.WebGLRenderer({
    canvas, antialias: Q.aa, powerPreference: 'high-performance',
    stencil: false, alpha: false, depth: true
  });
  ren.setPixelRatio(Math.min(window.devicePixelRatio || 1, Q.dpr));
  ren.outputEncoding = THREE.sRGBEncoding;
  ren.shadowMap.enabled = Q.shadow > 0;
  ren.shadowMap.type = THREE.PCFShadowMap;
  ren.shadowMap.autoUpdate = (S.TIER === 'high');

  scene = new THREE.Scene();
  const th = THEMES[0];
  scene.background = new THREE.Color(th.sky);
  scene.fog = new THREE.Fog(th.sky, Q.fogN, Q.fogF);

  cam = new THREE.PerspectiveCamera(62, 1, 0.5, 260);
  cam.position.set(0, 4.6, 8.2);

  hemi = new THREE.HemisphereLight(th.hemi, 0x3d4a34, 0.85);
  scene.add(hemi);

  dl = new THREE.DirectionalLight(th.sun, th.sunI);
  dl.position.set(9, 20, 8);
  dl.target.position.set(0, 0, -6);
  scene.add(dl, dl.target);

  if (Q.shadow > 0) {
    dl.castShadow = true;
    dl.shadow.mapSize.set(Q.shadow, Q.shadow);
    const c = dl.shadow.camera;
    c.left = -16; c.right = 16; c.top = 22; c.bottom = -22; c.near = 2; c.far = 60;
    c.updateProjectionMatrix();
    dl.shadow.bias = -0.0012;
    dl.shadow.normalBias = 0.02;
  }

  // Sky dome. fog:false so it never dissolves into itself.
  const skyTex = cvTex(4, 128, (g, w, h) => {
    const grd = g.createLinearGradient(0, 0, 0, h);
    grd.addColorStop(0, '#ffffff'); grd.addColorStop(1, '#b8b8b8');
    g.fillStyle = grd; g.fillRect(0, 0, w, h);
  });
  sky = new THREE.Mesh(
    new THREE.SphereGeometry(200, 14, 10),
    new THREE.MeshBasicMaterial({ map: skyTex, side: THREE.BackSide, fog: false, color: th.sky })
  );
  scene.add(sky);

  // A flat disc standing upright and facing the camera, so a Y-axis spin reads as
  // the classic coin flip. Pre-rotated so the cylinder axis points along z.
  // Thick enough that the edge-on part of the flip still reads as a gold coin.
  coinGeo = new THREE.CylinderGeometry(0.21, 0.21, 0.09, 14);
  coinGeo.rotateX(Math.PI / 2);
  coinMat = new THREE.MeshLambertMaterial({ color: 0xffcf3d, emissive: 0x6a4a00 });

  resize();
  addEventListener('resize', debounce(resize, 150));
}

function debounce(fn, ms) {
  let t = 0;
  return () => { clearTimeout(t); t = setTimeout(fn, ms); };
}

function resize() {
  W = window.innerWidth; H = window.innerHeight;
  ren.setSize(W, H, false);
  cam.aspect = W / H;
  cam.fov = (W / H > 1.2) ? 52 : 62;
  fovCur = cam.fov;
  cam.updateProjectionMatrix();
}

/* --------------------------------------------------------------- chunks */

function newChunk() {
  const c = {
    group: new THREE.Group(),
    z: 0, idx: 0, props: [], boxes: [], movers: [], hitFrom: 0, exit: 7,
    coinN: 0, coinX: [], coinY: [], coinZ: [], coinAlive: []
  };

  const ground = new THREE.Mesh(new THREE.BoxGeometry(TRACK_W, 0.5, CHUNK_LEN), matFor(0x6d7a66));
  ground.position.set(0, -0.25, -CHUNK_LEN / 2);
  ground.receiveShadow = true;
  c.group.add(ground);
  c.ground = ground;

  // One textured strip per lane carries the sleepers and rails in a single draw.
  c.rails = [];
  for (let l = 0; l < 3; l++) {
    const geo = new THREE.PlaneGeometry(1.9, CHUNK_LEN);
    const r = new THREE.Mesh(geo, trackMat(0x4a4f56));
    r.rotation.x = -Math.PI / 2;
    r.position.set(LANE_X[l], 0.03, -CHUNK_LEN / 2);
    r.receiveShadow = true;
    c.group.add(r);
    c.rails.push(r);
  }

  c.coins = new THREE.InstancedMesh(coinGeo, coinMat, COIN_CAP);
  c.coins.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
  c.coins.frustumCulled = false;
  c.group.add(c.coins);

  const bn = S.Q.bldg;
  if (bn > 0) {
    c.bldg = new THREE.InstancedMesh(
      new THREE.BoxGeometry(1, 1, 1),
      new THREE.MeshLambertMaterial({ map: texBldg }),
      bn
    );
    c.bldg.frustumCulled = false;
    c.group.add(c.bldg);
  }

  scene.add(c.group);
  return c;
}

function buildChunk(idx, nearZ) {
  const c = freeChunks.pop() || newChunk();
  c.idx = idx;
  c.z = nearZ;
  c.group.position.z = nearZ;
  c.group.visible = true;
  c.props.length = 0;
  c.boxes.length = 0;
  c.movers.length = 0;
  c.hitFrom = 0;
  c.coinN = 0;

  const rng = S.mulberry((S.runSeed ^ Math.imul(idx, 0x9E3779B1)) >>> 0);

  S.Gen.fill(c, idx, rng);

  // Sorted descending by local z: the nearest box (largest lz) is hit first, and
  // because world z only increases, a cursor over this order never rewinds.
  c.boxes.sort((a, b) => b.lz - a.lz);

  flushCoins(c);
  buildSkyline(c, rng);
  applyThemeToChunk(c);
  return c;
}

// Rewrites every instance matrix. `q` is the shared spin; identity when static.
function flushCoins(c, q) {
  const rot = q || _qIdent;
  for (let i = 0; i < COIN_CAP; i++) {
    if (i < c.coinN && c.coinAlive[i]) {
      _v3a.set(c.coinX[i], c.coinY[i], c.coinZ[i]);
      _m4.compose(_v3a, rot, _s3);
    } else {
      _m4.makeScale(0, 0, 0);
    }
    c.coins.setMatrixAt(i, _m4);
  }
  c.coins.instanceMatrix.needsUpdate = true;
}

function buildSkyline(c, rng) {
  if (!c.bldg) return;
  const n = S.Q.bldg;
  const pal = THEMES[themeB].bldg;
  for (let i = 0; i < n; i++) {
    const side = i % 2 ? 1 : -1;
    const x = side * (9 + rng() * 17);
    const h = 4 + rng() * 22;
    const w = 3 + rng() * 4;
    const z = -rng() * CHUNK_LEN;
    _v3a.set(x, h / 2, z);
    _s3.set(w, h, w);
    _m4.compose(_v3a, _qIdent, _s3);   // _q carries the coin spin — never reuse it here
    c.bldg.setMatrixAt(i, _m4);
    c.bldg.setColorAt(i, _c1.setHex(pal[(rng() * pal.length) | 0]));
    _s3.set(1, 1, 1);
  }
  c.bldg.instanceMatrix.needsUpdate = true;
  if (c.bldg.instanceColor) c.bldg.instanceColor.needsUpdate = true;
}

function recycle(c) {
  for (let i = 0; i < c.props.length; i++) {
    const p = c.props[i];
    POOLS[p.pool].put(p.mesh);
  }
  c.props.length = 0;
  c.boxes.length = 0;
  c.movers.length = 0;
  c.group.visible = false;
  freeChunks.push(c);
}

// Called by the generator to place a pooled prop.
function addProp(c, poolKey, lane, lz, opt) {
  const mesh = POOLS[poolKey].get();
  const u = mesh.userData;
  const x = (opt && opt.x != null) ? opt.x : LANE_X[lane];
  const y = (opt && opt.y != null) ? opt.y : 0;
  mesh.position.set(x, y, lz);
  if (opt && opt.color && u.core) {
    u.core.material = matFor(opt.color, { emissive: opt.color });
    u.ring.material = matFor(opt.color);
  }
  c.group.add(mesh);
  c.props.push({ pool: poolKey, mesh });

  const box = {
    lz, hz: u.hz, x, hx: u.hx,
    y: y + u.cy, hy: u.hy,
    kind: (opt && opt.kind) || u.kind,
    top: u.top < 0 ? -1 : y + u.top,
    vz: (opt && opt.vz) || 0,
    dead: false,
    pw: opt && opt.pw,
    ch: opt && opt.ch,
    mesh
  };
  // Movers break the "world z only increases" invariant the sorted cursor relies
  // on, so they live in their own always-tested list instead.
  if (box.vz) c.movers.push(box); else c.boxes.push(box);
  return box;
}

function addCoin(c, x, y, lz) {
  if (c.coinN >= COIN_CAP) return;
  const i = c.coinN++;
  c.coinX[i] = x; c.coinY[i] = y; c.coinZ[i] = lz; c.coinAlive[i] = 1;
}

// Hide a single instanced coin (collected, or promoted to a magnet fly-coin).
function killCoin(c, i) {
  c.coinAlive[i] = 0;
  _m4.makeScale(0, 0, 0);
  c.coins.setMatrixAt(i, _m4);
  c.coins.instanceMatrix.needsUpdate = true;
}

Object.assign(W3, { addProp, addCoin, killCoin });

/* --------------------------------------------------------------- themes */

function applyThemeToChunk(c) {
  const th = THEMES[themeB];
  c.ground.material = matFor(th.gnd);
  for (const r of c.rails) r.material = trackMat(th.rail);
}

function themeFor(dist) { return ((dist / 800) | 0) % THEMES.length; }

function updateTheme(dist, dt) {
  const want = themeFor(dist);
  if (want !== themeB) { themeA = themeB; themeB = want; themeT = 0; }
  if (themeT >= 1) return;

  themeT = Math.min(1, themeT + dt / 2);
  const a = THEMES[themeA], b = THEMES[themeB], t = themeT;

  _c1.setHex(a.sky).lerp(_c2.setHex(b.sky), t);
  scene.background.copy(_c1);
  scene.fog.color.copy(_c1);
  sky.material.color.copy(_c1);

  _c1.setHex(a.hemi).lerp(_c2.setHex(b.hemi), t);
  hemi.color.copy(_c1);

  _c1.setHex(a.sun).lerp(_c2.setHex(b.sun), t);
  dl.color.copy(_c1);
  dl.intensity = lerp(a.sunI, b.sunI, t);

  if (t >= 1) for (const c of live) applyThemeToChunk(c);
}

/* ---------------------------------------------------------------- frame */

function reset(seed) {
  for (const c of live) recycle(c);
  live.length = 0;
  chunkIdx = 0;
  frontZ = 8;                   // start a little behind the camera
  S.runSeed = seed;
  themeA = themeB = 0; themeT = 1;
  scene.background.setHex(THEMES[0].sky);
  scene.fog.color.setHex(THEMES[0].sky);
  sky.material.color.setHex(THEMES[0].sky);
  hemi.color.setHex(THEMES[0].hemi);
  dl.color.setHex(THEMES[0].sun);
  dl.intensity = THEMES[0].sunI;
  camX = 0; camY = 4.6; shakeAmp = 0;
  S.Gen.reset();
  stream(0);
}

function stream(dz) {
  for (let i = live.length - 1; i >= 0; i--) {
    const c = live[i];
    c.z += dz;
    c.group.position.z = c.z;
    if (c.z - CHUNK_LEN > RECYCLE_Z) { recycle(c); live.splice(i, 1); }
  }
  frontZ += dz;

  const ahead = S.Q.ahead;
  while (frontZ > -ahead) {
    const c = buildChunk(chunkIdx++, frontZ);
    live.push(c);
    frontZ = c.z - CHUNK_LEN;
  }
}

function update(dt, speed, dist) {
  stream(speed * dt);

  coinSpin += dt * 2.0;
  _q.setFromAxisAngle(_axisY, coinSpin);

  // Moving trains advance within their chunk, closing on the player from ahead.
  for (let i = 0; i < live.length; i++) {
    const c = live[i];
    for (let j = 0; j < c.movers.length; j++) {
      const b = c.movers[j];
      b.lz += b.vz * dt;
      b.mesh.position.z = b.lz;
    }
    // Coins spin by re-composing their instance matrices, NOT by rotating the mesh —
    // rotating the InstancedMesh would swing every coin around the chunk origin.
    // Only chunks near the camera are re-composed; distant spin is imperceptible.
    if (c.z > -70 && c.coinN) flushCoins(c, _q);

    for (let j = 0; j < c.props.length; j++) {
      const p = c.props[j];
      const k = p.mesh.userData.kind;
      if (k === 'POWER' || k === 'LETTER' || k === 'KEY') p.mesh.rotation.y += dt * 1.8;
    }
  }

  updateTheme(dist, dt);
}

function updateCamera(dt, px, py, pz, laneVx, speedNorm) {
  camX += (px * 0.42 - camX) * damp(9, dt);
  camY += (4.6 + py * 0.55 - camY) * damp(6, dt);

  if (shakeAmp > 0.001) {
    shakeX = Math.sin(performance.now() * 0.047) * shakeAmp;
    shakeAmp *= Math.exp(-7 * dt);
  } else shakeX = 0;

  cam.position.set(camX + shakeX, camY, 8.2 + pz);
  cam.lookAt(px * 0.6, 1.55 + py * 0.6, -9);

  const base = (W / H > 1.2) ? 52 : 62;
  const want = base + 9 * speedNorm;
  if (Math.abs(fovCur - want) > 0.05) {
    fovCur += (want - fovCur) * damp(4, dt);
    cam.fov = fovCur;
    cam.updateProjectionMatrix();
  }
}

function shake(a) { shakeAmp = Math.max(shakeAmp, a); }

function render() { ren.render(scene, cam); }
function renderScene(sc, c) { ren.render(sc, c); }

Object.assign(W3, {
  init, reset, update, updateCamera, render, renderScene, shake, resize,
  live: () => live,
  scene: () => scene,
  camera: () => cam,
  renderer: () => ren,
  themes: THEMES,
  info: () => ren.info
});

})();
