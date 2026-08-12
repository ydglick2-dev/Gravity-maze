/*! ---------------------------------------------------------------------------
 *  Subway Surfers — personal, non-commercial fan project.
 *  Not affiliated with, endorsed by, or connected to SYBO Games or Kiloo in any
 *  way. All code, art and audio in this project are original and generated
 *  procedurally at runtime; no third-party assets are used. Personal use only.
 *
 *  Bundled dependency: three.min.js — Three.js r128, (c) three.js authors,
 *  MIT License. Its original license header is preserved in that file.
 * ------------------------------------------------------------------------- */

/* 00-core.js — namespace, math, RNG, device tier, storage, audio. */

(() => {
'use strict';

const S = (window.SS = window.SS || {});

/* ---------------------------------------------------------------- helpers */

const $ = id => document.getElementById(id);
const TAU = Math.PI * 2;
const clamp = (v, a, b) => v < a ? a : v > b ? b : v;
const lerp = (a, b, t) => a + (b - a) * t;
// Frame-rate independent smoothing factor. rate ~ "how fast", dt in seconds.
const damp = (rate, dt) => 1 - Math.exp(-rate * dt);
const easeOutCubic = t => 1 - Math.pow(1 - t, 3);
const easeInOutCubic = t => t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;

// Deterministic PRNG. Same shape as the helper used by the sibling game.
function mulberry(seed) {
  return function () {
    seed |= 0; seed = seed + 0x6D2B79F5 | 0;
    let t = Math.imul(seed ^ seed >>> 15, 1 | seed);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}

function hashStr(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
}

// Local calendar day as YYYY-MM-DD. Deliberately not toISOString(), which is UTC
// and would roll the daily reset at the wrong hour.
const todayKey = () => new Date().toLocaleDateString('sv');

const fmt = n => (n | 0).toLocaleString('en-US');

Object.assign(S, { $, TAU, clamp, lerp, damp, easeOutCubic, easeInOutCubic,
                   mulberry, hashStr, todayKey, fmt });

/* ------------------------------------------------------------ device tier */

const TIER_KEY = 'ssTier_v1';

function probeTier() {
  let t = null;
  try { t = localStorage.getItem(TIER_KEY); } catch (e) {}
  if (t === 'low' || t === 'med' || t === 'high') return t;

  let gpu = '';
  try {
    const gl = document.createElement('canvas').getContext('webgl');
    const ext = gl && gl.getExtension('WEBGL_debug_renderer_info');
    if (ext) gpu = String(gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) || '').toLowerCase();
  } catch (e) {}

  const weak = /mali-g[0-5]\d|mali-[t4]|adreno \(tm\) [1-5]\d\d|adreno \(tm\) 61\d|powervr|videocore|swiftshader|llvmpipe/.test(gpu);
  const strong = /adreno \(tm\) [7-9]\d\d|apple|mali-g7[6-9]|mali-g[89]|immortalis|nvidia|radeon|geforce|intel iris/.test(gpu);
  const mem = navigator.deviceMemory || 4;
  const cores = navigator.hardwareConcurrency || 4;

  t = (weak || mem <= 3 || cores <= 4) ? 'low'
    : (strong && mem >= 6) ? 'high'
    : 'med';

  try { localStorage.setItem(TIER_KEY, t); } catch (e) {}
  return t;
}

S.TIER = probeTier();

S.QUALITY = {
  high: { dpr: 2.0,  aa: true,  shadow: 1024, ahead: 180, fogN: 45, fogF: 115, bldg: 28, parts: 120, detail: true },
  med:  { dpr: 1.5,  aa: true,  shadow: 512,  ahead: 150, fogN: 40, fogF: 95,  bldg: 18, parts: 60,  detail: true },
  low:  { dpr: 1.0,  aa: false, shadow: 0,    ahead: 120, fogN: 35, fogF: 75,  bldg: 8,  parts: 0,   detail: false }
};

// Resolved at boot; the settings screen may override the auto-detected tier.
S.Q = S.QUALITY[S.TIER];
S.setTier = t => { S.TIER = t; S.Q = S.QUALITY[t]; };

/* --------------------------------------------------------------- storage */

const KEY = 'ss_v1';

const DEF = {
  v: 1,
  coins: 0, keys: 0, hs: 0, bestDist: 0,
  chars: ['dash'], char: 'dash',
  boards: ['classic'], board: 'classic', boardsInv: 1,
  up: { MAGNET: 0, X2: 0, JETPACK: 0, SNEAK: 0 },
  st: { runs: 0, dist: 0, coins: 0, jumps: 0, rolls: 0, boardsUsed: 0, powers: 0, time: 0, deaths: 0 },
  ms: { date: '', list: [], setsDone: 0, rerolls: 1 },
  lb: [],
  word: { w: '', got: [], date: '' },
  set: { sfx: 1, mus: 1, q: 'a', hand: 'l' },
  name: '',
  unlockAll: false
};

// Structural migrations only; additive keys are handled by deepDefault below.
const MIG = {};

function store() {
  try { const s = localStorage; s.setItem('__t', '1'); s.removeItem('__t'); return s; }
  catch (e) { return null; }
}

function deepDefault(dst, def) {
  for (const k in def) {
    const dv = def[k];
    if (dv && typeof dv === 'object' && !Array.isArray(dv)) {
      if (!dst[k] || typeof dst[k] !== 'object' || Array.isArray(dst[k])) dst[k] = {};
      deepDefault(dst[k], dv);
    } else if (dst[k] === undefined || typeof dst[k] !== typeof dv) {
      dst[k] = Array.isArray(dv) ? dv.slice() : dv;
    }
  }
  return dst;
}

let SV = JSON.parse(JSON.stringify(DEF));
S.SV = SV;

function load() {
  SV = JSON.parse(JSON.stringify(DEF));
  const s = store();
  if (s) {
    try {
      const raw = JSON.parse(s.getItem(KEY) || 'null');
      if (raw && typeof raw === 'object') {
        let d = raw;
        while (MIG[d.v]) MIG[d.v](d);
        SV = deepDefault(d, DEF);
      }
    } catch (e) { /* corrupt blob -> defaults */ }
  }

  // Integrity: the freebies are always owned, and the selection is always owned.
  if (!Array.isArray(SV.chars) || !SV.chars.length) SV.chars = ['dash'];
  if (!Array.isArray(SV.boards) || !SV.boards.length) SV.boards = ['classic'];
  if (SV.chars.indexOf('dash') < 0) SV.chars.unshift('dash');
  if (SV.boards.indexOf('classic') < 0) SV.boards.unshift('classic');
  if (SV.chars.indexOf(SV.char) < 0) SV.char = 'dash';
  if (SV.boards.indexOf(SV.board) < 0) SV.board = 'classic';
  SV.coins = Math.max(0, SV.coins | 0);
  SV.keys = Math.max(0, SV.keys | 0);
  SV.boardsInv = Math.max(0, SV.boardsInv | 0);
  for (const k in DEF.up) SV.up[k] = clamp(SV.up[k] | 0, 0, 4);

  S.SV = SV;
  // Re-apply so content added in a later version stays unlocked too.
  if (SV.unlockAll && S.applyUnlockAll) S.applyUnlockAll();
  return SV;
}

let saveT = 0;
function save(now) {
  clearTimeout(saveT);
  const write = () => {
    const s = store();
    if (s) try { s.setItem(KEY, JSON.stringify(S.SV)); } catch (e) {}
  };
  if (now) write(); else saveT = setTimeout(write, 400);
}

addEventListener('pagehide', () => save(true));
addEventListener('visibilitychange', () => { if (document.hidden) save(true); });

S.DEF = DEF; S.load = load; S.save = save; S.store = store; S.deepDefault = deepDefault;

/* ----------------------------------------------------------------- audio */

let ac = null, master = null, musGain = null, sfxGain = null;
let noise = null, rollGain = null, rollFilt = null, hum = null, humGain = null;
let liveOsc = 0, musTimer = 0, musStep = 0, musBpm = 0, musRoot = 0, musBar = 0;

function initAudio() {
  if (ac) return ac;
  const AC = window.AudioContext || window.webkitAudioContext;
  if (!AC) return null;
  try { ac = new AC(); } catch (e) { return null; }

  master = ac.createGain(); master.gain.value = 0.9; master.connect(ac.destination);
  sfxGain = ac.createGain(); sfxGain.gain.value = 1; sfxGain.connect(master);
  musGain = ac.createGain(); musGain.gain.value = 0.55; musGain.connect(master);

  // Persistent filtered-noise bus, used for the roll swell and impact bursts.
  const len = ac.sampleRate * 2;
  const buf = ac.createBuffer(1, len, ac.sampleRate);
  const d = buf.getChannelData(0);
  for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
  noise = ac.createBufferSource(); noise.buffer = buf; noise.loop = true;
  rollFilt = ac.createBiquadFilter(); rollFilt.type = 'lowpass'; rollFilt.frequency.value = 400;
  rollGain = ac.createGain(); rollGain.gain.value = 0;
  noise.connect(rollFilt).connect(rollGain).connect(sfxGain);
  noise.start();

  // Hoverboard hum, gated by gain rather than created per activation.
  hum = ac.createOscillator(); hum.type = 'triangle'; hum.frequency.value = 60;
  humGain = ac.createGain(); humGain.gain.value = 0;
  hum.connect(humGain).connect(sfxGain);
  hum.start();

  applyVolumes();
  return ac;
}

function applyVolumes() {
  if (!ac) return;
  sfxGain.gain.value = S.SV.set.sfx ? 1 : 0;
  musGain.gain.value = S.SV.set.mus ? 0.55 : 0;
}

// f: Hz, dur: seconds, type: oscillator type, vol: 0..1, slide: target Hz or 0.
function tone(f, dur, type, vol, slide, dest) {
  if (!ac || !S.SV.set.sfx) return;
  if (liveOsc > 28) return;                       // voice cap; coin bursts can be dense
  liveOsc++;
  const t = ac.currentTime;
  const o = ac.createOscillator();
  const g = ac.createGain();
  o.type = type || 'sine';
  o.frequency.setValueAtTime(f, t);
  if (slide) o.frequency.exponentialRampToValueAtTime(Math.max(20, slide), t + dur);
  g.gain.setValueAtTime(0.0001, t);
  g.gain.exponentialRampToValueAtTime(Math.max(0.0002, vol == null ? .2 : vol), t + 0.012);
  g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  o.connect(g).connect(dest || sfxGain);
  o.start(t); o.stop(t + dur + 0.02);
  o.onended = () => { liveOsc--; o.disconnect(); g.disconnect(); };
}

function noiseBurst(dur, freq, vol) {
  if (!ac || !S.SV.set.sfx) return;
  const t = ac.currentTime;
  const g = ac.createGain();
  const f = ac.createBiquadFilter();
  f.type = 'lowpass'; f.frequency.value = freq || 1200;
  g.gain.setValueAtTime(vol || .25, t);
  g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  const src = ac.createBufferSource();
  src.buffer = noise.buffer; src.loop = true;
  src.connect(f).connect(g).connect(sfxGain);
  src.start(t); src.stop(t + dur);
  src.onended = () => { src.disconnect(); f.disconnect(); g.disconnect(); };
}

const sfx = {
  coin(combo) {
    const f = 1180 * Math.pow(1.0595, Math.min(12, combo | 0));
    tone(f, .07, 'square', .13);
    setTimeout(() => tone(f * 1.5, .05, 'square', .07), 30);
  },
  jump()  { tone(280, .13, 'sine', .20, 520); },
  land()  { tone(150, .09, 'sine', .17, 70); noiseBurst(.06, 700, .12); },
  roll()  {
    if (!ac || !S.SV.set.sfx) return;
    const t = ac.currentTime, D = 0.55;
    rollGain.gain.cancelScheduledValues(t);
    rollGain.gain.setValueAtTime(0.0001, t);
    rollGain.gain.linearRampToValueAtTime(.18, t + D * .35);
    rollGain.gain.linearRampToValueAtTime(0.0001, t + D);
    rollFilt.frequency.cancelScheduledValues(t);
    rollFilt.frequency.setValueAtTime(400, t);
    rollFilt.frequency.linearRampToValueAtTime(1400, t + D * .35);
    rollFilt.frequency.linearRampToValueAtTime(400, t + D);
  },
  power() { [523, 659, 784, 1047].forEach((f, i) => setTimeout(() => tone(f, .12, 'triangle', .16), i * 55)); },
  stumble() { tone(200, .18, 'square', .18, 120); noiseBurst(.14, 500, .16); },
  crash() {
    tone(90, .5, 'sawtooth', .30, 30);
    noiseBurst(.35, 900, .28);
    if (ac) {
      const t = ac.currentTime;
      master.gain.setValueAtTime(.9, t);
      master.gain.linearRampToValueAtTime(.45, t + .05);
      master.gain.linearRampToValueAtTime(.9, t + .30);
    }
  },
  boardOn()  { tone(420, .2, 'triangle', .18, 900); S.humOn(true); },
  boardOff() { S.humOn(false); },
  buy()   { tone(660, .1, 'triangle', .18); setTimeout(() => tone(990, .14, 'triangle', .18), 90); },
  deny()  { tone(160, .16, 'square', .16, 110); },
  ui()    { tone(520, .05, 'triangle', .10); },
  key()   { [784, 988, 1319].forEach((f, i) => setTimeout(() => tone(f, .16, 'sine', .18), i * 70)); }
};

S.humOn = on => {
  if (!ac) return;
  const t = ac.currentTime;
  humGain.gain.cancelScheduledValues(t);
  humGain.gain.linearRampToValueAtTime(on && S.SV.set.sfx ? .07 : 0, t + .2);
};

/* Generative music: pentatonic arpeggio + bass, tempo follows run speed. */
const SCALE = [0, 3, 5, 7, 10];

function musTick() {
  if (!ac || !S.SV.set.mus) return;
  const step = musStep++;
  if (step % 32 === 0) { musBar++; musRoot = [0, -3, 2, 5][musBar % 4]; }
  const base = 220 * Math.pow(2, musRoot / 12);
  const n = SCALE[(step * 3) % SCALE.length] + (step % 8 < 4 ? 0 : 12);
  tone(base * Math.pow(2, n / 12), .16, 'triangle', .09, 0, musGain);
  if (step % 4 === 0) tone(base / 2, .22, 'sine', .16, 0, musGain);
  if (step % 2 === 1 && ac) {                       // hi-hat
    const t = ac.currentTime, g = ac.createGain(), f = ac.createBiquadFilter();
    f.type = 'highpass'; f.frequency.value = 6000;
    g.gain.setValueAtTime(.05, t); g.gain.exponentialRampToValueAtTime(.0001, t + .02);
    const s = ac.createBufferSource(); s.buffer = noise.buffer; s.loop = true;
    s.connect(f).connect(g).connect(musGain); s.start(t); s.stop(t + .02);
    s.onended = () => { s.disconnect(); f.disconnect(); g.disconnect(); };
  }
}

// Re-arm only on a meaningful tempo change; never per frame.
function musicBpm(bpm) {
  bpm = Math.round(bpm);
  if (Math.abs(bpm - musBpm) < 4 && musTimer) return;
  musBpm = bpm;
  clearInterval(musTimer);
  musTimer = setInterval(musTick, 60000 / bpm / 2);
}

function musicStart() { if (!ac || !S.SV.set.mus) return; musStep = 0; musBar = 0; musicBpm(118); }
function musicStop() { clearInterval(musTimer); musTimer = 0; musBpm = 0; }
function musicDuck(v) { if (ac) musGain.gain.linearRampToValueAtTime(S.SV.set.mus ? v : 0, ac.currentTime + .2); }

Object.assign(S, { initAudio, tone, noiseBurst, sfx, applyVolumes,
                   musicStart, musicStop, musicBpm, musicDuck,
                   audioCtx: () => ac });

})();
