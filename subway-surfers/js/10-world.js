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

/* Themes swap every 800 m with a 2 s cross-lerp. Colours are deliberately
   high-chroma: the genre reads as a bright toy world, and desaturated palettes
   make the same geometry look like a grey industrial estate. */
const THEMES = [
  { name: 'Day',    sky: 0x62C8F5, gnd: 0x5FBF52, ball: 0x9A8A73, rail: 0xEDE7DC, wall: 0xE86A4A,
    bldg: [0xF0C93A, 0xE86A4A, 0x4FC3F0, 0x8FD44A, 0xF0904A, 0xC98FE0], hemi: 0xCFF0FF, sun: 0xFFF6DC, sunI: 1.05 },
  { name: 'Sunset', sky: 0xFF9A5C, gnd: 0x8A6B4A, ball: 0x9A7355, rail: 0xF0D8B8, wall: 0xD4503C,
    bldg: [0xFFB05C, 0xE0603C, 0xC97AE0, 0xFFD070, 0xA05070, 0xE08A50], hemi: 0xFFD8B0, sun: 0xFFC070, sunI: 1.15 },
  { name: 'Night',  sky: 0x1B2352, gnd: 0x2B3A5C, ball: 0x2E3550, rail: 0x8FA0D0, wall: 0x8F3ED8,
    bldg: [0x3A4A8C, 0x5A3ED8, 0x2E3A70, 0x7A4FE0, 0x3E5AA8, 0x9F5FE0], hemi: 0x6A7AD0, sun: 0xBFD0FF, sunI: 0.75 },
  { name: 'Rain',   sky: 0x8FA8BC, gnd: 0x5A7A5C, ball: 0x7A8085, rail: 0xC8D0D8, wall: 0x4A8FB0,
    bldg: [0x9FB4C4, 0x7A94A8, 0xB0C4D4, 0x6A8494, 0x8FA8BC, 0xA0B8C8], hemi: 0xC4D8E8, sun: 0xE0EAF2, sunI: 0.85 },
  { name: 'Snow',   sky: 0xC4E4F5, gnd: 0xF0F6FA, ball: 0xD8E4EC, rail: 0xEAF2FA, wall: 0x5FA8D4,
    bldg: [0xE0EAF2, 0xC4D8E8, 0xF2F8FC, 0xAFC8DC, 0xD4E4F0, 0xBCD4E4], hemi: 0xFFFFFF, sun: 0xF0F8FF, sunI: 1.15 },
  { name: 'Tunnel', sky: 0x3A2A24, gnd: 0x4A3A30, ball: 0x54453A, rail: 0xC8A882, wall: 0xE0902F,
    bldg: [0x6A5340, 0x54433A, 0x7A6048, 0x5A4838, 0x6E5842, 0x483A2E], hemi: 0x9A7A5A, sun: 0xFFD48A, sunI: 0.9 }
];

// Train liveries. Bright, varied cars are the single biggest visual cue of the genre.
const TRAIN_COLORS = [
  { body: 0xF0C93A, roof: 0xD4A81E, nose: 0xE0402F },
  { body: 0x4FC3F0, roof: 0x2E9AC4, nose: 0xF0C93A },
  { body: 0xE86A4A, roof: 0xC44E30, nose: 0xFFF0D0 },
  { body: 0x8FD44A, roof: 0x6AAE2E, nose: 0xE0402F },
  { body: 0xF2F2F4, roof: 0xC8CCD4, nose: 0xE0402F },
  { body: 0xC98FE0, roof: 0xA066C0, nose: 0xFFD257 }
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
// Contrast lives in the texture, not in the material tint: a near-white base with
// near-white rails would collapse to a single flat colour once tinted.
const texTrack = cvTex(64, 64, (g, w, h) => {
  g.fillStyle = '#8d8578'; g.fillRect(0, 0, w, h);       // ballast
  g.fillStyle = '#4a3a2a';                               // wooden sleepers
  for (let y = 4; y < h; y += 16) g.fillRect(2, y, w - 4, 9);
  g.fillStyle = '#20242a';                               // rail shadow
  g.fillRect(12, 0, 8, h);
  g.fillRect(w - 20, 0, 8, h);
  g.fillStyle = '#e8ecf0';                               // polished rail head
  g.fillRect(14, 0, 4, h);
  g.fillRect(w - 18, 0, 4, h);
});
texTrack.wrapS = texTrack.wrapT = THREE.RepeatWrapping;
texTrack.repeat.set(3, 6);   // one plane spans all three lanes

const _trackMats = new Map();
function trackMat(hex) {
  let m = _trackMats.get(hex);
  if (!m) { m = new THREE.MeshLambertMaterial({ color: hex, map: texTrack }); _trackMats.set(hex, m); }
  return m;
}

// Concrete panels with a pale coping stone along the top edge, so the wall needs
// only one mesh instead of a wall plus a separate cap.
const texWall = cvTex(64, 64, (g, w, h) => {
  g.fillStyle = '#ffffff'; g.fillRect(0, 0, w, h);
  g.fillStyle = 'rgba(0,0,0,.16)';
  for (let y = 10; y < h; y += 14) g.fillRect(0, y, w, 2);
  g.fillStyle = 'rgba(255,255,255,.85)';                 // coping along the top
  g.fillRect(0, 0, w, 7);
  g.fillStyle = 'rgba(0,0,0,.10)';
  g.fillRect(0, 7, w, 2);
});
texWall.wrapS = texWall.wrapT = THREE.RepeatWrapping;
texWall.repeat.set(6, 1);

const _wallMats = new Map();
function wallMat(hex) {
  let m = _wallMats.get(hex);
  if (!m) { m = new THREE.MeshLambertMaterial({ color: hex, map: texWall }); _wallMats.set(hex, m); }
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

  const nose = new THREE.Mesh(new THREE.BoxGeometry(1.74, h * 0.8, 0.6), matFor(0xe45c4a));
  nose.position.set(0, h * 0.42, 7.6);
  g.add(nose);

  // Skirt and bogies: without something dark at the bottom the car looks like it
  // is floating rather than sitting on the rails.
  const skirt = new THREE.Mesh(new THREE.BoxGeometry(1.8, 0.26, 14.6), matFor(0x2E3440));
  skirt.position.y = 0.13;
  g.add(skirt);
  for (const z of [-5.2, 5.2]) {
    const bogie = new THREE.Mesh(new THREE.BoxGeometry(1.5, 0.3, 2.2), matFor(0x1E242E));
    bogie.position.set(0, 0.18, z);
    g.add(bogie);
  }

  g.userData = { hx: 0.95, hy: h / 2, cy: h / 2, hz: 7.5, top: h + 0.10, kind: 'SOLID',
                 body, roof, nose };
  return g;
}

// Applies one of the liveries to a pooled car at placement time.
function paintTrain(mesh, livery) {
  const u = mesh.userData;
  u.body.material = matFor(livery.body);
  u.roof.material = matFor(livery.roof);
  u.nose.material = matFor(livery.nose);
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
let camX = 0, camY = 5.3, shakeX = 0, shakeAmp = 0, fovCur = 56;
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

  cam = new THREE.PerspectiveCamera(56, 1, 0.5, 300);
  cam.position.set(0, 5.3, 7.8);

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
  const skyTex = cvTex(512, 256, (g, w, h) => {
    const grd = g.createLinearGradient(0, 0, 0, h);
    grd.addColorStop(0, '#8ec8ff'); grd.addColorStop(0.55, '#ffffff'); grd.addColorStop(1, '#dcdcdc');
    g.fillStyle = grd; g.fillRect(0, 0, w, h);
    // Soft cartoon clouds: overlapping discs across the upper band.
    g.fillStyle = 'rgba(255,255,255,.95)';
    const puff = (x, y, r) => { g.beginPath(); g.arc(x, y, r, 0, Math.PI * 2); g.fill(); };
    for (let i = 0; i < 14; i++) {
      const x = (i * 97 + 40) % w, y = 40 + ((i * 53) % 70), r = 14 + (i % 4) * 6;
      puff(x, y, r); puff(x + r * 0.9, y + 4, r * 0.72); puff(x - r * 0.9, y + 5, r * 0.66);
      puff(x + r * 0.3, y - r * 0.6, r * 0.6);
    }
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
  cam.fov = (W / H > 1.2) ? 46 : 56;
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

  // Grass verge either side of the yard.
  const ground = new THREE.Mesh(new THREE.BoxGeometry(46, 0.5, CHUNK_LEN), matFor(0x5FBF52));
  ground.position.set(0, -0.30, -CHUNK_LEN / 2);
  ground.receiveShadow = true;
  c.group.add(ground);
  c.ground = ground;

  // Gravel ballast bed the three lanes sit on — this is what makes it read as a
  // rail yard rather than a road.
  const ballast = new THREE.Mesh(new THREE.BoxGeometry(TRACK_W, 0.30, CHUNK_LEN), matFor(0x9A8A73));
  ballast.position.set(0, -0.13, -CHUNK_LEN / 2);
  ballast.receiveShadow = true;
  c.group.add(ballast);
  c.ballast = ballast;

  // Retaining walls flanking the yard, with a colour band along the top.
  c.walls = [];
  for (const sgn of [-1, 1]) {
    const wall = new THREE.Mesh(new THREE.BoxGeometry(0.7, 3.4, CHUNK_LEN), wallMat(0xE86A4A));
    wall.position.set(sgn * 7.6, 1.4, -CHUNK_LEN / 2);
    wall.receiveShadow = true;
    c.group.add(wall);
    c.walls.push(wall);
  }

  // Lamp posts, spaced so they strobe past at speed and sell the sense of motion.
  if (S.Q.detail && (chunkIdx & 1)) {
    for (let i = 0; i < 1; i++) {
      const sgn = -1;
      const post = new THREE.Group();
      const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.09, 0.11, 5.2, 6), matFor(0x4A5058));
      pole.position.y = 2.6;
      post.add(pole);
      const lamp = new THREE.Mesh(new THREE.BoxGeometry(1.3, 0.18, 0.30), matFor(0xFFF3C4));
      lamp.position.set(-sgn * 0.95, 5.0, 0);
      post.add(lamp);
      post.position.set(sgn * 8.6, 0, -6 - i * 15);
      c.group.add(post);
    }
  }

  // A single textured plane covers all three lanes; the texture repeats 3x across,
  // so one draw call replaces three without changing how it looks.
  c.rails = [];
  {
    const r = new THREE.Mesh(new THREE.PlaneGeometry(6.6, CHUNK_LEN), trackMat(0xEDE7DC));
    r.rotation.x = -Math.PI / 2;
    r.position.set(0, 0.03, -CHUNK_LEN / 2);      // just above the ballast top (0.02)
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
    const x = side * (13 + rng() * 20);
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
  if (u.body) paintTrain(mesh, TRAIN_COLORS[(opt && opt.livery != null ? opt.livery : 0) % TRAIN_COLORS.length]);
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
  c.ballast.material = matFor(th.ball);
  for (const w of c.walls) w.material = wallMat(th.wall);
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
  camX = 0; camY = 5.3; shakeAmp = 0;
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
  camY += (5.3 + py * 0.55 - camY) * damp(6, dt);

  if (shakeAmp > 0.001) {
    shakeX = Math.sin(performance.now() * 0.047) * shakeAmp;
    shakeAmp *= Math.exp(-7 * dt);
  } else shakeX = 0;

  cam.position.set(camX + shakeX, camY, 7.8 + pz);
  cam.lookAt(px * 0.6, 1.35 + py * 0.6, -11);

  const base = (W / H > 1.2) ? 46 : 56;
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
