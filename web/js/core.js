/* ============================================================
   NOVA-9 — core engine
   canvas • loop • input • audio • particles • juice • save
   ============================================================ */

export const TAU = Math.PI * 2;
export const U = {
  clamp: (v, a, b) => v < a ? a : v > b ? b : v,
  lerp: (a, b, t) => a + (b - a) * t,
  // frame-rate independent approach
  damp: (a, b, l, dt) => U.lerp(a, b, 1 - Math.exp(-l * dt)),
  rnd: (a = 1, b) => b === undefined ? Math.random() * a : a + Math.random() * (b - a),
  rndi: (a, b) => Math.floor(U.rnd(a, b)),
  pick: arr => arr[Math.floor(Math.random() * arr.length)],
  dist: (x1, y1, x2, y2) => Math.hypot(x2 - x1, y2 - y1),
  dist2: (x1, y1, x2, y2) => (x2 - x1) ** 2 + (y2 - y1) ** 2,
  ang: (x1, y1, x2, y2) => Math.atan2(y2 - y1, x2 - x1),
  angDiff: (a, b) => { let d = (b - a) % TAU; if (d > Math.PI) d -= TAU; if (d < -Math.PI) d += TAU; return d; },
  shuffle: a => { for (let i = a.length - 1; i > 0; i--) { const j = U.rndi(0, i + 1);[a[i], a[j]] = [a[j], a[i]]; } return a; },
  // deterministic rng (daily/seeded content)
  seed(s) { let t = s >>> 0; return () => { t += 0x6D2B79F5; let x = t; x = Math.imul(x ^ x >>> 15, x | 1); x ^= x + Math.imul(x ^ x >>> 7, x | 61); return ((x ^ x >>> 14) >>> 0) / 4294967296; }; }
};

/* ---------------- screen ---------------- */
export const canvas = document.getElementById('cv');
export const ctx = canvas.getContext('2d');
export const S = { W: 360, H: 640, dpr: 1, cx: 180, cy: 320, safeT: 0, safeB: 0 };

function resize() {
  const dpr = Math.min(window.devicePixelRatio || 1, 2.5);
  S.W = window.innerWidth; S.H = window.innerHeight; S.dpr = dpr;
  S.cx = S.W / 2; S.cy = S.H / 2;
  canvas.width = Math.round(S.W * dpr); canvas.height = Math.round(S.H * dpr);
  canvas.style.width = S.W + 'px'; canvas.style.height = S.H + 'px';
  const cs = getComputedStyle(document.documentElement);
  S.safeT = parseFloat(cs.getPropertyValue('--safeT')) || 0;
  S.safeB = parseFloat(cs.getPropertyValue('--safeB')) || 0;
  Game.scene?.resize?.();
}
window.addEventListener('resize', resize);
window.addEventListener('orientationchange', () => setTimeout(resize, 120));

/* ---------------- save ---------------- */
const DEFAULT_SAVE = {
  coins: 0, skin: 'nova', owned: ['nova'],
  best: { tap: 0, rogue: 0, arena: 0, impostor: 0 },
  puzzle: { solved: [], stars: 0 },
  seen: {},
  settings: { music: true, sfx: true, haptic: true, shake: true },
  totals: { runs: 0, coins: 0 }
};
export const Save = {
  d: structuredClone(DEFAULT_SAVE),
  load() {
    try {
      const raw = localStorage.getItem('nova9');
      if (raw) {
        const p = JSON.parse(raw);
        this.d = { ...structuredClone(DEFAULT_SAVE), ...p };
        for (const k of ['best', 'puzzle', 'settings', 'totals', 'seen'])
          this.d[k] = { ...DEFAULT_SAVE[k], ...(p[k] || {}) };
      }
    } catch (e) { /* corrupt save — start fresh */ }
    return this.d;
  },
  save() { try { localStorage.setItem('nova9', JSON.stringify(this.d)); } catch (e) { } },
  addCoins(n) { this.d.coins += n; this.d.totals.coins += n; this.save(); },
  setBest(mode, v) {
    if (v > (this.d.best[mode] || 0)) { this.d.best[mode] = v; this.save(); return true; }
    return false;
  },
  reset() { this.d = structuredClone(DEFAULT_SAVE); this.save(); }
};

export const SKINS = [
  { id: 'nova', name: 'נובה', c: '#21e6ff', c2: '#0b4a7a', price: 0 },
  { id: 'ember', name: 'גחלת', c: '#ff7a2f', c2: '#7a2a08', price: 250 },
  { id: 'toxic', name: 'רעל', c: '#46f08a', c2: '#0d5c34', price: 400 },
  { id: 'plasma', name: 'פלזמה', c: '#ff2e88', c2: '#78124a', price: 600 },
  { id: 'void', name: 'ריק', c: '#9b5cff', c2: '#3a1878', price: 900 },
  { id: 'gold', name: 'זהב', c: '#ffc63a', c2: '#8a6210', price: 1400 },
  { id: 'ghost', name: 'רפאים', c: '#dfe9ff', c2: '#5a6480', price: 2000 },
  { id: 'rainbow', name: 'קשת', c: '#ff5ec4', c2: '#5ec8ff', price: 3000, rainbow: true }
];
export function skin() { return SKINS.find(s => s.id === Save.d.skin) || SKINS[0]; }
export function skinColor(t = 0) {
  const s = skin();
  if (s.rainbow) return `hsl(${(t * 90) % 360} 95% 62%)`;
  return s.c;
}

/* ---------------- audio ---------------- */
export const Sound = {
  ac: null, master: null, sfxG: null, musG: null, ready: false,
  init() {
    if (this.ready) return;
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    this.ac = new AC();
    this.master = this.ac.createGain(); this.master.gain.value = .9; this.master.connect(this.ac.destination);
    this.sfxG = this.ac.createGain(); this.sfxG.gain.value = .55; this.sfxG.connect(this.master);
    this.musG = this.ac.createGain(); this.musG.gain.value = .0; this.musG.connect(this.master);
    this.ready = true;
    this._startMusic();
  },
  resume() { if (this.ac && this.ac.state === 'suspended') this.ac.resume(); },

  tone(freq, dur = .12, type = 'sine', vol = .5, slide = 0, delay = 0) {
    if (!this.ready || !Save.d.settings.sfx) return;
    const t = this.ac.currentTime + delay;
    const o = this.ac.createOscillator(), g = this.ac.createGain();
    o.type = type; o.frequency.setValueAtTime(freq, t);
    if (slide) o.frequency.exponentialRampToValueAtTime(Math.max(30, freq + slide), t + dur);
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(vol, t + .008);
    g.gain.exponentialRampToValueAtTime(.0008, t + dur);
    o.connect(g); g.connect(this.sfxG); o.start(t); o.stop(t + dur + .04);
  },
  noise(dur = .2, vol = .4, freq = 900, q = 1, delay = 0) {
    if (!this.ready || !Save.d.settings.sfx) return;
    const t = this.ac.currentTime + delay;
    const len = Math.ceil(this.ac.sampleRate * dur);
    const buf = this.ac.createBuffer(1, len, this.ac.sampleRate);
    const dta = buf.getChannelData(0);
    for (let i = 0; i < len; i++) dta[i] = (Math.random() * 2 - 1) * (1 - i / len);
    const src = this.ac.createBufferSource(); src.buffer = buf;
    const f = this.ac.createBiquadFilter(); f.type = 'bandpass'; f.frequency.value = freq; f.Q.value = q;
    const g = this.ac.createGain(); g.gain.setValueAtTime(vol, t);
    g.gain.exponentialRampToValueAtTime(.001, t + dur);
    src.connect(f); f.connect(g); g.connect(this.sfxG); src.start(t);
  },

  play(name) {
    if (!this.ready) return;
    switch (name) {
      case 'click': this.tone(660, .07, 'square', .25); break;
      case 'tap': this.tone(520, .1, 'triangle', .35, 220); break;
      case 'jump': this.tone(340, .13, 'sine', .4, 260); break;
      case 'coin': this.tone(1180, .08, 'square', .3); this.tone(1760, .1, 'square', .22, 0, .05); break;
      case 'gem': this.tone(880, .07, 'triangle', .3); this.tone(1320, .1, 'triangle', .25, 0, .04); break;
      case 'shoot': this.tone(760, .07, 'square', .18, -420); this.noise(.05, .12, 1800, 2); break;
      case 'hit': this.noise(.12, .35, 700, 1.5); this.tone(180, .1, 'square', .25, -80); break;
      case 'hurt': this.tone(220, .22, 'sawtooth', .35, -140); this.noise(.2, .3, 400, 1); break;
      case 'boom': this.noise(.45, .5, 240, .7); this.tone(90, .4, 'sine', .4, -50); break;
      case 'die': [440, 330, 262, 196].forEach((f, i) => this.tone(f, .22, 'triangle', .35, -20, i * .1)); break;
      case 'win': [523, 659, 784, 1046].forEach((f, i) => this.tone(f, .3, 'triangle', .34, 0, i * .09)); break;
      case 'level': [660, 880, 1320].forEach((f, i) => this.tone(f, .22, 'square', .26, 0, i * .07)); break;
      case 'lock': this.tone(1046, .1, 'sine', .3); this.tone(1568, .18, 'sine', .25, 0, .06); break;
      case 'alarm': [880, 660].forEach((f, i) => this.tone(f, .28, 'sawtooth', .3, 0, i * .3)); break;
      case 'kill': this.tone(140, .35, 'sawtooth', .4, -70); this.noise(.35, .4, 300, .8); break;
      case 'vote': this.tone(500, .09, 'square', .25); break;
      case 'step': this.noise(.05, .07, 500, 2); break;
      case 'no': this.tone(200, .2, 'square', .3, -60); break;
      case 'super': [392, 523, 659, 880, 1046].forEach((f, i) => this.tone(f, .25, 'sawtooth', .22, 0, i * .05)); break;
    }
  },

  /* --- procedural music: arpeggiated pads, mood per mode --- */
  mood: 'menu', _next: 0, _step: 0, _timer: null,
  MOODS: {
    menu: { bpm: 92, scale: [0, 3, 7, 10, 12, 15], root: 55, wave: 'triangle', drums: 0, gain: .16 },
    tap: { bpm: 124, scale: [0, 2, 7, 9, 12, 14], root: 58, wave: 'square', drums: 1, gain: .13 },
    puzzle: { bpm: 76, scale: [0, 4, 7, 11, 12, 16], root: 52, wave: 'sine', drums: 0, gain: .15 },
    rogue: { bpm: 140, scale: [0, 3, 5, 7, 10, 12], root: 48, wave: 'sawtooth', drums: 1, gain: .12 },
    impostor: { bpm: 70, scale: [0, 1, 5, 8, 12, 13], root: 44, wave: 'triangle', drums: 0, gain: .14 },
    arena: { bpm: 150, scale: [0, 2, 5, 7, 9, 12], root: 50, wave: 'square', drums: 1, gain: .12 }
  },
  setMood(m) {
    if (this.mood === m) return;
    this.mood = m; this._step = 0;
    if (this.ready) this._applyGain();
  },
  _applyGain() {
    const cfg = this.MOODS[this.mood] || this.MOODS.menu;
    const g = Save.d.settings.music ? cfg.gain : 0;
    this.musG.gain.setTargetAtTime(g, this.ac.currentTime, .4);
  },
  musicToggle() { if (this.ready) this._applyGain(); },
  _startMusic() {
    this._next = this.ac.currentTime + .1;
    this._applyGain();
    clearInterval(this._timer);
    this._timer = setInterval(() => this._sched(), 40);
  },
  _sched() {
    if (!this.ready || this.ac.state !== 'running') return;
    const cfg = this.MOODS[this.mood] || this.MOODS.menu;
    const spb = 60 / cfg.bpm / 4; // 16th
    while (this._next < this.ac.currentTime + .25) {
      const s = this._step, t = this._next;
      const bar = Math.floor(s / 16), chord = [0, 5, 3, 8][bar % 4];
      if (s % 2 === 0) {
        const n = cfg.scale[(s / 2 + bar) % cfg.scale.length] + chord;
        this._note(cfg.root + n + 24, t, .22, cfg.wave, .5);
      }
      if (s % 16 === 0) this._note(cfg.root + chord, t, .9, 'triangle', .85);
      if (cfg.drums && s % 8 === 0) this._kick(t);
      if (cfg.drums && s % 8 === 4) this._hat(t);
      this._next += spb; this._step = (s + 1) % 64;
    }
  },
  _note(midi, t, dur, wave, vol) {
    const f = 440 * Math.pow(2, (midi - 69) / 12);
    const o = this.ac.createOscillator(), g = this.ac.createGain(), lp = this.ac.createBiquadFilter();
    lp.type = 'lowpass'; lp.frequency.value = 2200;
    o.type = wave; o.frequency.value = f;
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(vol * .3, t + .02);
    g.gain.exponentialRampToValueAtTime(.001, t + dur);
    o.connect(lp); lp.connect(g); g.connect(this.musG); o.start(t); o.stop(t + dur + .05);
  },
  _kick(t) {
    const o = this.ac.createOscillator(), g = this.ac.createGain();
    o.frequency.setValueAtTime(150, t); o.frequency.exponentialRampToValueAtTime(45, t + .12);
    g.gain.setValueAtTime(.5, t); g.gain.exponentialRampToValueAtTime(.001, t + .16);
    o.connect(g); g.connect(this.musG); o.start(t); o.stop(t + .2);
  },
  _hat(t) {
    const len = Math.ceil(this.ac.sampleRate * .04);
    const buf = this.ac.createBuffer(1, len, this.ac.sampleRate);
    const d = buf.getChannelData(0);
    for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / len);
    const src = this.ac.createBufferSource(); src.buffer = buf;
    const f = this.ac.createBiquadFilter(); f.type = 'highpass'; f.frequency.value = 7000;
    const g = this.ac.createGain(); g.gain.value = .18;
    src.connect(f); f.connect(g); g.connect(this.musG); src.start(t);
  }
};

export function haptic(ms = 12) {
  if (Save.d.settings.haptic && navigator.vibrate) { try { navigator.vibrate(ms); } catch (e) { } }
}

/* ---------------- juice / particles ---------------- */
const PMAX = 900;
class P {
  constructor() { this.on = false; }
  set(x, y, vx, vy, life, size, color, opt = {}) {
    this.on = true; this.x = x; this.y = y; this.vx = vx; this.vy = vy;
    this.life = this.maxLife = life; this.size = size; this.color = color;
    this.grav = opt.grav || 0; this.drag = opt.drag ?? .92; this.spin = opt.spin || 0;
    this.rot = opt.rot || 0; this.shape = opt.shape || 'circle'; this.glow = opt.glow ?? true;
    this.fade = opt.fade ?? true; this.shrink = opt.shrink ?? true;
  }
}
export const FX = {
  pool: Array.from({ length: PMAX }, () => new P()), pi: 0,
  floats: [], shakeAmt: 0, shakeX: 0, shakeY: 0, flashA: 0, flashC: '#fff',
  hitstop: 0, ripples: [],

  spawn(x, y, vx, vy, life, size, color, opt) {
    const p = this.pool[this.pi = (this.pi + 1) % PMAX];
    p.set(x, y, vx, vy, life, size, color, opt); return p;
  },
  burst(x, y, n, color, opt = {}) {
    const sp = opt.speed || 220, life = opt.life || .5, size = opt.size || 4;
    for (let i = 0; i < n; i++) {
      const a = opt.dir !== undefined ? opt.dir + U.rnd(-(opt.spread || .6), opt.spread || .6) : U.rnd(TAU);
      const s = sp * U.rnd(.35, 1.15);
      this.spawn(x, y, Math.cos(a) * s, Math.sin(a) * s, life * U.rnd(.6, 1.25),
        size * U.rnd(.6, 1.3), color, opt);
    }
  },
  trail(x, y, color, size = 4, life = .35) {
    this.spawn(x, y, U.rnd(-16, 16), U.rnd(-16, 16), life, size, color, { drag: .9 });
  },
  ring(x, y, color, r = 10, max = 90, w = 3, life = .45) {
    this.ripples.push({ x, y, r, max, w, life, maxLife: life, color });
  },
  float(x, y, text, color = '#fff', size = 18) {
    this.floats.push({ x, y, text, color, size, life: .9, vy: -46 });
  },
  shake(a) { if (Save.d.settings.shake) this.shakeAmt = Math.min(26, this.shakeAmt + a); },
  flash(c = '#fff', a = .5) { this.flashC = c; this.flashA = Math.max(this.flashA, a); },
  stop(t = .06) { this.hitstop = Math.max(this.hitstop, t); },

  clear() {
    for (const p of this.pool) p.on = false;
    this.floats.length = 0; this.ripples.length = 0;
    this.shakeAmt = 0; this.flashA = 0; this.hitstop = 0;
  },

  update(dt) {
    for (const p of this.pool) {
      if (!p.on) continue;
      p.life -= dt;
      if (p.life <= 0) { p.on = false; continue; }
      p.vy += p.grav * dt;
      const d = Math.pow(p.drag, dt * 60);
      p.vx *= d; p.vy *= d;
      p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.spin * dt;
    }
    for (let i = this.floats.length - 1; i >= 0; i--) {
      const f = this.floats[i]; f.life -= dt; f.y += f.vy * dt; f.vy *= .94;
      if (f.life <= 0) this.floats.splice(i, 1);
    }
    for (let i = this.ripples.length - 1; i >= 0; i--) {
      const r = this.ripples[i]; r.life -= dt;
      r.r = U.lerp(r.r, r.max, 1 - Math.exp(-7 * dt));
      if (r.life <= 0) this.ripples.splice(i, 1);
    }
    this.shakeAmt *= Math.pow(.001, dt);
    if (this.shakeAmt < .3) this.shakeAmt = 0;
    this.shakeX = U.rnd(-1, 1) * this.shakeAmt;
    this.shakeY = U.rnd(-1, 1) * this.shakeAmt;
    this.flashA = Math.max(0, this.flashA - dt * 2.6);
  },

  draw(c) {
    c.save(); c.globalCompositeOperation = 'lighter';
    for (const p of this.pool) {
      if (!p.on) continue;
      const k = p.life / p.maxLife;
      c.globalAlpha = p.fade ? U.clamp(k * 1.2, 0, 1) : 1;
      const s = p.shrink ? p.size * k : p.size;
      c.fillStyle = p.color;
      if (p.glow) { c.shadowBlur = s * 2.4; c.shadowColor = p.color; } else c.shadowBlur = 0;
      if (p.shape === 'rect') {
        c.save(); c.translate(p.x, p.y); c.rotate(p.rot);
        c.fillRect(-s, -s * .5, s * 2, s); c.restore();
      } else {
        c.beginPath(); c.arc(p.x, p.y, Math.max(.4, s), 0, TAU); c.fill();
      }
    }
    for (const r of this.ripples) {
      const k = r.life / r.maxLife;
      c.globalAlpha = k; c.strokeStyle = r.color; c.lineWidth = r.w * k;
      c.shadowBlur = 18; c.shadowColor = r.color;
      c.beginPath(); c.arc(r.x, r.y, r.r, 0, TAU); c.stroke();
    }
    c.restore();

    c.save(); c.textAlign = 'center'; c.shadowBlur = 10;
    for (const f of this.floats) {
      c.globalAlpha = U.clamp(f.life * 1.6, 0, 1);
      c.fillStyle = f.color; c.shadowColor = f.color;
      c.font = `900 ${f.size}px system-ui,sans-serif`;
      c.fillText(f.text, f.x, f.y);
    }
    c.restore();
  },
  drawFlash(c) {
    if (this.flashA <= 0) return;
    c.save(); c.globalAlpha = Math.min(1, this.flashA); c.fillStyle = this.flashC;
    c.fillRect(0, 0, S.W, S.H); c.restore();
  }
};

/* ---------------- input ---------------- */
export const Input = {
  pointers: new Map(),   // id -> {x,y,sx,sy,down,t}
  keys: new Set(),
  tapped: false,         // any pointer went down this frame
  released: false,
  _tapQ: false, _relQ: false,

  first() { return this.pointers.values().next().value || null; },
  count() { return this.pointers.size; },
  endFrame() { this.tapped = this._tapQ; this._tapQ = false; this.released = this._relQ; this._relQ = false; },
  reset() { this.pointers.clear(); this.keys.clear(); this._tapQ = this._relQ = false; }
};

function ptPos(e) { return { x: e.clientX, y: e.clientY }; }
function onDown(e) {
  Sound.init(); Sound.resume();
  const { x, y } = ptPos(e);
  const p = { id: e.pointerId, x, y, sx: x, sy: y, down: true, t: performance.now() };
  Input.pointers.set(e.pointerId, p); Input._tapQ = true;
  Game.scene?.onDown?.(p);
}
function onMove(e) {
  const p = Input.pointers.get(e.pointerId);
  if (!p) return;
  const { x, y } = ptPos(e); p.x = x; p.y = y;
  Game.scene?.onMove?.(p);
}
function onUp(e) {
  const p = Input.pointers.get(e.pointerId);
  if (!p) return;
  p.down = false; Input.pointers.delete(e.pointerId); Input._relQ = true;
  Game.scene?.onUp?.(p);
}
canvas.addEventListener('pointerdown', onDown);
canvas.addEventListener('pointermove', onMove);
window.addEventListener('pointerup', onUp);
window.addEventListener('pointercancel', onUp);
canvas.addEventListener('contextmenu', e => e.preventDefault());
window.addEventListener('keydown', e => {
  Sound.init(); Sound.resume();
  if (!Input.keys.has(e.code)) { Input._tapQ = true; Game.scene?.onKey?.(e.code); }
  Input.keys.add(e.code);
  if ([' ', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key)) e.preventDefault();
});
window.addEventListener('keyup', e => Input.keys.delete(e.code));
document.addEventListener('visibilitychange', () => {
  if (document.hidden) { Input.reset(); Game.scene?.onBlur?.(); }
});

/* virtual joystick — anchored where the finger lands */
export class Stick {
  constructor(side = 'left') { this.side = side; this.id = null; this.ax = 0; this.ay = 0; this.dx = 0; this.dy = 0; this.mag = 0; this.R = 62; }
  claim(p) {
    if (this.id !== null) return false;
    const half = S.W / 2;
    // NOTE: 'left'/'right' are physical screen halves (layout-independent)
    if (this.side === 'left' && p.x > half) return false;
    if (this.side === 'right' && p.x <= half) return false;
    this.id = p.id; this.ax = p.x; this.ay = p.y; this.dx = this.dy = this.mag = 0;
    return true;
  }
  move(p) {
    if (p.id !== this.id) return;
    let dx = p.x - this.ax, dy = p.y - this.ay;
    const m = Math.hypot(dx, dy);
    if (m > this.R) { this.ax += dx * (1 - this.R / m); this.ay += dy * (1 - this.R / m); dx = p.x - this.ax; dy = p.y - this.ay; }
    this.mag = Math.min(1, Math.hypot(dx, dy) / this.R);
    if (this.mag > .001) { this.dx = dx / (this.R * Math.max(this.mag, .001)) * this.mag; this.dy = dy / (this.R * Math.max(this.mag, .001)) * this.mag; }
    else { this.dx = this.dy = 0; }
  }
  release(p) { if (p.id === this.id) { this.id = null; this.dx = this.dy = 0; this.mag = 0; } }
  get active() { return this.id !== null; }
  draw(c, color = '#8fd9ff') {
    if (!this.active) return;
    c.save(); c.globalAlpha = .3; c.strokeStyle = color; c.lineWidth = 2;
    c.beginPath(); c.arc(this.ax, this.ay, this.R, 0, TAU); c.stroke();
    c.globalAlpha = .55; c.fillStyle = color;
    c.beginPath(); c.arc(this.ax + this.dx * this.R, this.ay + this.dy * this.R, 20, 0, TAU); c.fill();
    c.restore();
  }
}

/* keyboard fallback vector (desktop) */
export function keyVec() {
  const k = Input.keys; let x = 0, y = 0;
  if (k.has('KeyA') || k.has('ArrowLeft')) x -= 1;
  if (k.has('KeyD') || k.has('ArrowRight')) x += 1;
  if (k.has('KeyW') || k.has('ArrowUp')) y -= 1;
  if (k.has('KeyS') || k.has('ArrowDown')) y += 1;
  const m = Math.hypot(x, y);
  return m > 0 ? { x: x / m, y: y / m, mag: 1 } : { x: 0, y: 0, mag: 0 };
}

/* ---------------- draw helpers ---------------- */
export const D = {
  orb(c, x, y, r, color, glow = 1) {
    c.save();
    c.shadowBlur = r * 2.2 * glow; c.shadowColor = color;
    const g = c.createRadialGradient(x - r * .3, y - r * .35, r * .1, x, y, r);
    g.addColorStop(0, '#ffffff'); g.addColorStop(.35, color); g.addColorStop(1, shade(color, -.55));
    c.fillStyle = g; c.beginPath(); c.arc(x, y, r, 0, TAU); c.fill();
    c.restore();
  },
  glowCircle(c, x, y, r, color, a = 1) {
    c.save(); c.globalAlpha = a; c.shadowBlur = r * 1.6; c.shadowColor = color;
    c.fillStyle = color; c.beginPath(); c.arc(x, y, r, 0, TAU); c.fill(); c.restore();
  },
  ring(c, x, y, r, color, w = 2, a = 1) {
    c.save(); c.globalAlpha = a; c.strokeStyle = color; c.lineWidth = w;
    c.shadowBlur = 14; c.shadowColor = color;
    c.beginPath(); c.arc(x, y, r, 0, TAU); c.stroke(); c.restore();
  },
  rr(c, x, y, w, h, r) {
    c.beginPath();
    r = Math.min(r, w / 2, h / 2);
    c.moveTo(x + r, y); c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath();
  },
  text(c, txt, x, y, size = 16, color = '#fff', align = 'center', weight = 800, glow = 0) {
    c.save(); c.font = `${weight} ${size}px system-ui,"Segoe UI",Arial,sans-serif`;
    c.textAlign = align; c.textBaseline = 'middle'; c.fillStyle = color;
    if (glow) { c.shadowBlur = glow; c.shadowColor = color; }
    c.fillText(txt, x, y); c.restore();
  },
  bar(c, x, y, w, h, pct, color, bg = 'rgba(255,255,255,.14)') {
    c.save(); c.fillStyle = bg; D.rr(c, x, y, w, h, h / 2); c.fill();
    if (pct > 0) {
      c.fillStyle = color; c.shadowBlur = 10; c.shadowColor = color;
      D.rr(c, x, y, Math.max(h, w * U.clamp(pct, 0, 1)), h, h / 2); c.fill();
    }
    c.restore();
  },
  grid(c, ox, oy, size, color = 'rgba(90,140,220,.10)') {
    c.save(); c.strokeStyle = color; c.lineWidth = 1;
    c.beginPath();
    for (let x = -(ox % size); x < S.W; x += size) { c.moveTo(x, 0); c.lineTo(x, S.H); }
    for (let y = -(oy % size); y < S.H; y += size) { c.moveTo(0, y); c.lineTo(S.W, y); }
    c.stroke(); c.restore();
  },
  vignette(c, a = .5) {
    const g = c.createRadialGradient(S.cx, S.cy, Math.min(S.W, S.H) * .35, S.cx, S.cy, Math.max(S.W, S.H) * .78);
    g.addColorStop(0, 'rgba(0,0,0,0)'); g.addColorStop(1, `rgba(0,0,0,${a})`);
    c.fillStyle = g; c.fillRect(0, 0, S.W, S.H);
  }
};

export function shade(hex, amt) {
  const h = hex.replace('#', '');
  const full = h.length === 3 ? h.split('').map(x => x + x).join('') : h;
  let r = parseInt(full.slice(0, 2), 16), g = parseInt(full.slice(2, 4), 16), b = parseInt(full.slice(4, 6), 16);
  if (amt > 0) { r += (255 - r) * amt; g += (255 - g) * amt; b += (255 - b) * amt; }
  else { r *= 1 + amt; g *= 1 + amt; b *= 1 + amt; }
  return `rgb(${r | 0},${g | 0},${b | 0})`;
}
export function alpha(hex, a) {
  const h = hex.replace('#', '');
  const full = h.length === 3 ? h.split('').map(x => x + x).join('') : h;
  return `rgba(${parseInt(full.slice(0, 2), 16)},${parseInt(full.slice(2, 4), 16)},${parseInt(full.slice(4, 6), 16)},${a})`;
}

/* ---------------- starfield background ---------------- */
export const Stars = {
  list: [], hue: 210,
  init() {
    this.list = Array.from({ length: 90 }, () => ({
      x: U.rnd(S.W), y: U.rnd(S.H), z: U.rnd(.2, 1), r: U.rnd(.6, 2.1), tw: U.rnd(TAU)
    }));
  },
  update(dt, vx = 0, vy = 8) {
    if (!this.list.length) this.init();
    for (const s of this.list) {
      s.x -= vx * s.z * dt; s.y += vy * s.z * dt; s.tw += dt * 2;
      if (s.y > S.H + 4) { s.y = -4; s.x = U.rnd(S.W); }
      if (s.y < -4) { s.y = S.H + 4; s.x = U.rnd(S.W); }
      if (s.x < -4) s.x = S.W + 4; if (s.x > S.W + 4) s.x = -4;
    }
  },
  draw(c) {
    c.save();
    for (const s of this.list) {
      c.globalAlpha = .25 + .55 * s.z * (.6 + .4 * Math.sin(s.tw));
      c.fillStyle = '#cfe4ff';
      c.fillRect(s.x, s.y, s.r, s.r);
    }
    c.restore();
  }
};

/* ---------------- game loop / scene manager ---------------- */
export const Game = {
  scene: null, last: 0, timeScale: 1, running: false, paused: false, t: 0,
  hooks: { onSceneChange: null },

  set(scene, ...args) {
    this.scene?.exit?.();
    FX.clear(); Input.reset();
    this.scene = scene;
    this.timeScale = 1; this.paused = false;
    scene?.enter?.(...args);
    // opt-in inspection hook for automated testing (?debug=1)
    if (location.search.includes('debug')) window.__scene = scene;
    this.hooks.onSceneChange?.(scene);
  },
  start() {
    if (this.running) return;
    this.running = true; this.last = performance.now();
    requestAnimationFrame(this._frame);
  },
  _frame: (now) => {
    const G = Game;
    requestAnimationFrame(G._frame);
    let dt = (now - G.last) / 1000; G.last = now;
    if (dt > .05) dt = .05;           // clamp long frames (tab switches)
    G.t += dt;

    if (FX.hitstop > 0) { FX.hitstop -= dt; dt *= .08; }
    const sdt = dt * G.timeScale;

    ctx.setTransform(S.dpr, 0, 0, S.dpr, 0, 0);
    ctx.clearRect(0, 0, S.W, S.H);

    if (!G.paused) { FX.update(sdt); G.scene?.update?.(sdt, dt); }

    ctx.save();
    ctx.translate(FX.shakeX, FX.shakeY);
    G.scene?.draw?.(ctx);
    ctx.restore();
    FX.drawFlash(ctx);
    Input.endFrame();
  }
};

resize();
Stars.init();
