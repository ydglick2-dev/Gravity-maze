/*! ---------------------------------------------------------------------------
 *  Subway Surfers — personal, non-commercial fan project.
 *  Not affiliated with, endorsed by, or connected to SYBO Games or Kiloo in any
 *  way. All code, art and audio in this project are original and generated
 *  procedurally at runtime; no third-party assets are used. Personal use only.
 *
 *  Bundled dependency: three.min.js — Three.js r128, (c) three.js authors,
 *  MIT License. Its original license header is preserved in that file.
 * ------------------------------------------------------------------------- */

/* 50-ui.js — missions, word challenge, leaderboard, shop with a 3D preview,
   HUD, every overlay screen, and the unlock-all button. */

(() => {
'use strict';

const S = window.SS;
const { $, clamp, fmt } = S;

/* ==================================================================== */
/*  Missions                                                            */
/* ==================================================================== */

const M = {};
S.Missions = M;

const WORDS = ['SUBWAY', 'SPRINT', 'TICKET', 'TUNNEL', 'ESCAPE', 'RECORD', 'COMBO'];

const TYPES = {
  COINS_RUN:   { run: 1, label: n => `Collect ${n} coins in a single run`,   scale: b => Math.round((120 + b / 40) / 25) * 25 },
  DIST_RUN:    { run: 1, label: n => `Run ${n} m in a single run`,           scale: b => Math.max(300, Math.round(0.55 * b / 100) * 100) },
  DIST_TOTAL:  { run: 0, label: n => `Run ${n} m in total today`,            scale: b => Math.max(1000, Math.round(1.8 * b / 100) * 100) },
  JUMPS:       { run: 0, label: n => `Jump ${n} times`,                      scale: () => 40 + ((Math.random() * 80) | 0) },
  ROLLS:       { run: 0, label: n => `Roll ${n} times`,                      scale: () => 30 + ((Math.random() * 60) | 0) },
  BOARDS:      { run: 0, label: n => `Use ${n} hoverboards`,                 scale: () => 2 + ((Math.random() * 3) | 0) },
  POWERS:      { run: 0, label: n => `Pick up ${n} power-ups`,               scale: () => 6 + ((Math.random() * 9) | 0) },
  MULT_TIME:   { run: 0, label: n => `Spend ${n}s under a multiplier`,       scale: () => 45 + ((Math.random() * 75) | 0) },
  NO_HIT_DIST: { run: 1, label: n => `Run ${n} m without stumbling`,         scale: b => Math.max(250, Math.round(0.4 * b / 50) * 50) },
  WORD:        { run: 0, label: () => `Complete today's word`,               scale: () => 1 }
};

const TYPE_IDS = Object.keys(TYPES);

let runVals = {};

function newMission(rng) {
  const id = TYPE_IDS[(rng() * (TYPE_IDS.length - 1)) | 0];   // WORD is added separately
  const best = Math.max(300, S.SV.bestDist | 0);
  const n = Math.max(1, TYPES[id].scale(best) | 0);
  return { t: id, n, p: 0, r: 250 + ((rng() * 550) | 0), done: false };
}

M.ensureDaily = function () {
  const today = S.todayKey();
  const ms = S.SV.ms;
  if (ms.date === today && ms.list && ms.list.length === 3) return;

  const rng = S.mulberry(S.hashStr(today));
  ms.date = today;
  ms.rerolls = 1;
  ms.list = [newMission(rng), newMission(rng), newMission(rng)];

  const w = S.SV.word;
  if (w.date !== today) {
    w.date = today;
    w.w = WORDS[(rng() * WORDS.length) | 0];
    w.got = [];
  }
  S.save();
};

M.bump = function (type, amt) {
  const list = S.SV.ms.list || [];
  let changed = false;
  for (const m of list) {
    if (m.t !== type || m.done) continue;
    if (TYPES[type].run) {
      runVals[type] = (runVals[type] || 0) + amt;
      if (runVals[type] > m.p) { m.p = Math.min(m.n, runVals[type]); changed = true; }
    } else {
      m.p = Math.min(m.n, m.p + amt);
      changed = true;
    }
    if (m.p >= m.n && !m.done) { m.done = true; complete(m); }
  }
  if (changed) S.save();
};

// Run-scoped counters restart each run; only an improvement is committed.
M.runStart = function () { runVals = {}; };

M.setRunValue = function (type, v) {
  runVals[type] = v;
  for (const m of (S.SV.ms.list || [])) {
    if (m.t !== type || m.done) continue;
    if (v > m.p) m.p = Math.min(m.n, v);
    if (m.p >= m.n && !m.done) { m.done = true; complete(m); }
  }
};

function complete(m) {
  S.SV.coins += m.r;
  S.sfx.key();
  S.toast(`Mission complete  +${m.r} coins`);
  const list = S.SV.ms.list || [];
  if (list.length && list.every(x => x.done)) {
    S.SV.keys++;
    S.SV.ms.setsDone++;
    S.toast('All missions done  +1 Key');
  }
  S.save();
}

M.reroll = function (i) {
  const ms = S.SV.ms;
  if (ms.rerolls > 0) ms.rerolls--;
  else if (S.SV.keys > 0) S.SV.keys--;
  else { S.sfx.deny(); S.toast('Need a key to reroll'); return; }
  ms.list[i] = newMission(Math.random);
  S.sfx.ui();
  S.save();
  renderMissions();
};

M.nextLetter = function () {
  const w = S.SV.word;
  if (!w.w) return null;
  const i = (w.got || []).length;
  if (i >= w.w.length) return null;
  return { ch: w.w[i], i };
};

M.gotLetter = function (ch) {
  const w = S.SV.word;
  const want = M.nextLetter();
  if (!want || want.ch !== ch) return;
  w.got.push(ch);
  S.sfx.key();
  S.toast(`Letter ${ch}  (${w.got.length}/${w.w.length})`);
  if (w.got.length >= w.w.length) {
    S.SV.coins += 1000;
    S.SV.keys++;
    S.toast(`"${w.w}" complete!  +1000 coins  +1 Key`);
    M.bump('WORD', 1);
  }
  S.save();
};

M.label = m => TYPES[m.t].label(m.n);

/* ==================================================================== */
/*  Leaderboard                                                         */
/* ==================================================================== */

S.submitScore = function (score, dist, coins) {
  const lb = S.SV.lb;
  const e = { n: S.SV.name || 'Player', s: score | 0, d: dist | 0, c: coins | 0, t: Date.now() };
  let i = 0;
  while (i < lb.length && lb[i].s >= e.s) i++;
  if (i >= 10) return -1;
  lb.splice(i, 0, e);
  if (lb.length > 10) lb.length = 10;
  S.save();
  return i;
};

/* ==================================================================== */
/*  Unlock-all                                                          */
/* ==================================================================== */

S.applyUnlockAll = function () {
  const SV = S.SV;
  SV.coins = 999999999;
  SV.keys = 9999;
  SV.boardsInv = 999;
  SV.chars = S.CHARS.map(c => c.id);
  SV.boards = S.BOARDS.map(b => b.id);
  for (const k in SV.up) SV.up[k] = 4;
};

function toggleUnlockAll() {
  const SV = S.SV;
  if (!SV.unlockAll) {
    SV.unlockAll = true;
    S.applyUnlockAll();
    S.sfx.key();
    S.toast('Everything unlocked');
  } else {
    if (!confirm('Return to normal mode? All progress will be reset.')) return;
    SV.unlockAll = false;
    const keep = { name: SV.name, set: SV.set, lb: SV.lb, hs: SV.hs, bestDist: SV.bestDist };
    const fresh = JSON.parse(JSON.stringify(S.DEF));
    Object.assign(fresh, keep);
    S.SV = fresh;
    S.sfx.ui();
    S.toast('Back to normal mode');
  }
  S.save(true);
  refreshAll();
}

/* ==================================================================== */
/*  Screens                                                             */
/* ==================================================================== */

const OVS = ['menuOv', 'shopOv', 'missionOv', 'lbOv', 'statsOv', 'setOv', 'pauseOv', 'overOv', 'helpOv'];
let mode = 'menu';

const U = {};
S.UI = U;

U.mode = () => mode;
U.anyOverlayOpen = () => OVS.some(id => !$(id).classList.contains('hidden'));

function hideAll() { for (const id of OVS) $(id).classList.add('hidden'); }

function show(id) {
  hideAll();
  if (id) $(id).classList.remove('hidden');
  mode = id === 'shopOv' ? 'shop' : id ? id.replace('Ov', '') : 'play';
  document.body.classList.toggle('playing', !id);
}
U.show = show;

/* ------------------------------------------------------------- toast */

let toastT = 0;
S.toast = function (msg) {
  const el = $('toast');
  el.textContent = msg;
  el.classList.add('on');
  clearTimeout(toastT);
  toastT = setTimeout(() => el.classList.remove('on'), 1600);
};

/* --------------------------------------------------------------- HUD */

let uiAcc = 0;

U.hud = function (dt, score, dist, runCoins) {
  uiAcc += dt;
  if (uiAcc < 0.1) return;              // 10 Hz; 60 Hz DOM writes thrash layout
  uiAcc = 0;

  $('hScore').textContent = fmt(score);
  $('hDist').textContent = (dist | 0) + 'm';
  $('hCoins').textContent = S.SV.unlockAll ? '∞' : fmt(S.SV.coins);
  $('hKeys').textContent = S.SV.unlockAll ? '∞' : fmt(S.SV.keys);

  const m = S.Power.mult();
  const mEl = $('hMult');
  if (m > 1.001) { mEl.textContent = 'x' + (Math.round(m * 100) / 100); mEl.style.display = ''; }
  else mEl.style.display = 'none';

  renderPowerPills();
  $('boardBtn').classList.toggle('dim', S.SV.boardsInv <= 0 || S.Power.active('BOARD'));
  $('boardCount').textContent = S.SV.unlockAll ? '∞' : S.SV.boardsInv;
};

function renderPowerPills() {
  const box = $('hPows');
  let html = '';
  for (const id in S.POWERS) {
    const t = S.Power.remain(id);
    if (t <= 0) continue;
    const pct = clamp(t / S.Power.total(id), 0, 1) * 100;
    html += `<div class="pill"><span>${S.POWERS[id].icon}</span><i style="width:${pct}%"></i></div>`;
  }
  const b = S.Power.remain('BOARD');
  if (b > 0) {
    const pct = clamp(b / S.Power.total('BOARD'), 0, 1) * 100;
    html += `<div class="pill${b < 3 ? ' warn' : ''}"><span>🛹</span><i style="width:${pct}%"></i></div>`;
  }
  if (box.innerHTML !== html) box.innerHTML = html;
}

/* ------------------------------------------------------------- shop */

let prevScene = null, prevCam = null, prevRig = null, shopTab = 'chars';

// The preview band, in CSS pixels from the top of the viewport. Must match the
// transparent window cut into #shopOv's background gradient.
const BAND_TOP = 92, BAND_H = 168;

function initPreview() {
  prevScene = new THREE.Scene();
  prevCam = new THREE.PerspectiveCamera(35, 1, 0.1, 20);
  prevCam.position.set(0, 0, 4.1);         // leaves headroom inside the band
  prevCam.lookAt(0, 0, 0);
  prevScene.add(new THREE.HemisphereLight(0xcfe4ff, 0x40404a, 1.0));
  const rim = new THREE.DirectionalLight(0xffffff, 0.9);
  rim.position.set(2, 4, 3);
  prevScene.add(rim);
  prevRig = S.buildRig();
  prevRig.root.position.y = -0.9;          // centres the 1.75 m body on the camera axis
  prevScene.add(prevRig.root);
}

// Renders into a scissored strip so the model lands exactly in the transparent
// band, instead of being hidden behind the overlay's own background.
U.previewFrame = function (dt) {
  if (!prevRig) return;
  const ren = S.World.renderer();
  const W = window.innerWidth, H = window.innerHeight;
  const y = Math.max(0, H - BAND_TOP - BAND_H);

  prevRig.root.rotation.y += dt * 0.7;
  const aspect = W / BAND_H;
  if (prevCam.aspect !== aspect) { prevCam.aspect = aspect; prevCam.updateProjectionMatrix(); }

  ren.setScissorTest(true);
  ren.setScissor(0, y, W, BAND_H);
  ren.setViewport(0, y, W, BAND_H);
  ren.setClearColor(0x0d131d, 1);
  ren.clear();
  S.World.renderScene(prevScene, prevCam);
  ren.setScissorTest(false);
  ren.setViewport(0, 0, W, H);             // restore before the world renders again
};

function updatePreview() {
  if (!prevRig) return;
  S.applyCharPalette(prevRig, S.charById(S.SV.char));
  S.applyBoardPalette(prevRig, S.boardById(S.SV.board));
  prevRig.board.visible = (shopTab === 'boards');
  prevRig.board.position.y = 0.07;
}

function owned(kind, id) {
  return (kind === 'chars' ? S.SV.chars : S.SV.boards).indexOf(id) >= 0;
}

function buy(kind, item) {
  const SV = S.SV;
  const needKeys = item.keys | 0;
  if (needKeys) {
    if (SV.keys < needKeys) { S.sfx.deny(); S.toast(`Need ${needKeys} keys`); return; }
    SV.keys -= needKeys;
  } else {
    if (SV.coins < item.price) { S.sfx.deny(); S.toast('Not enough coins'); return; }
    SV.coins -= item.price;
  }
  (kind === 'chars' ? SV.chars : SV.boards).push(item.id);
  if (kind === 'chars') SV.char = item.id; else SV.board = item.id;
  S.sfx.buy();
  S.toast(`${item.name} unlocked`);
  S.save();
  renderShop();
}

function selectItem(kind, item) {
  if (!owned(kind, item.id)) { buy(kind, item); return; }
  if (kind === 'chars') S.SV.char = item.id; else S.SV.board = item.id;
  S.sfx.ui();
  S.save();
  renderShop();
  if (S.P.rig) {
    S.applyCharPalette(S.P.rig, S.charById(S.SV.char));
    S.applyBoardPalette(S.P.rig, S.boardById(S.SV.board));
  }
}

function upgradeCost(id) {
  const lvl = S.SV.up[id] | 0;
  return lvl >= 4 ? null : S.UPGRADE_PRICES[lvl];
}

function buyUpgrade(id) {
  const cost = upgradeCost(id);
  if (cost == null) { S.sfx.deny(); return; }
  if (S.SV.coins < cost) { S.sfx.deny(); S.toast('Not enough coins'); return; }
  S.SV.coins -= cost;
  S.SV.up[id]++;
  S.sfx.buy();
  S.save();
  renderShop();
}

function renderShop() {
  $('shopCoins').textContent = S.SV.unlockAll ? '∞' : fmt(S.SV.coins);
  $('shopKeys').textContent = S.SV.unlockAll ? '∞' : fmt(S.SV.keys);

  for (const t of ['chars', 'boards', 'ups'])
    $('tab_' + t).classList.toggle('on', shopTab === t);

  const list = $('shopList');
  let html = '';

  if (shopTab === 'ups') {
    for (const id in S.POWERS) {
      const p = S.POWERS[id];
      const lvl = S.SV.up[id] | 0;
      const cost = upgradeCost(id);
      const d = S.powerDur(id);
      html += `<div class="item">
        <div class="ic">${p.icon}</div>
        <div class="meta"><b>${p.label}</b><span>Level ${lvl}/4 · ${d.toFixed(1)}s</span></div>
        <button class="btn sm" data-up="${id}" ${cost == null ? 'disabled' : ''}>
          ${cost == null ? 'MAX' : '🪙 ' + fmt(cost)}</button></div>`;
    }
  } else {
    const kind = shopTab;
    const items = kind === 'chars' ? S.CHARS : S.BOARDS;
    const sel = kind === 'chars' ? S.SV.char : S.SV.board;
    for (const it of items) {
      const own = owned(kind, it.id);
      const on = it.id === sel;
      const col = kind === 'chars' ? it.shirt : it.deck;
      const price = it.keys ? `🗝 ${it.keys}` : (it.price ? `🪙 ${fmt(it.price)}` : 'FREE');
      html += `<div class="item${own ? '' : ' locked'}${on ? ' sel' : ''}" data-kind="${kind}" data-id="${it.id}">
        <div class="ic" style="background:#${col.toString(16).padStart(6, '0')}"></div>
        <div class="meta"><b>${it.name}</b><span>${own ? (on ? 'Equipped' : 'Owned') : price}</span></div>
        <button class="btn sm">${own ? (on ? '✓' : 'USE') : 'BUY'}</button></div>`;
    }
  }
  list.innerHTML = html;
  updatePreview();
}

/* --------------------------------------------------------- missions */

function renderMissions() {
  M.ensureDaily();
  const w = S.SV.word;
  let html = '';
  (S.SV.ms.list || []).forEach((m, i) => {
    const pct = clamp(m.p / m.n, 0, 1) * 100;
    html += `<div class="item ms${m.done ? ' done' : ''}">
      <div class="meta"><b>${M.label(m)}</b>
      <span>${Math.min(m.p, m.n) | 0} / ${m.n} · 🪙 ${m.r}</span>
      <i class="bar"><u style="width:${pct}%"></u></i></div>
      ${m.done ? '<div class="tick">✓</div>' : `<button class="btn sm" data-rr="${i}">↻</button>`}</div>`;
  });

  if (w.w) {
    const got = w.got || [];
    let letters = '';
    for (let i = 0; i < w.w.length; i++)
      letters += `<b class="ltr${i < got.length ? ' on' : ''}">${i < got.length ? w.w[i] : '?'}</b>`;
    html += `<div class="item ms"><div class="meta"><b>Word of the day</b>
      <span>Collect the letters while you run · 🪙 1000 + 🗝 1</span>
      <div class="word">${letters}</div></div></div>`;
  }

  $('missionList').innerHTML = html;
  $('msRerolls').textContent = S.SV.ms.rerolls > 0
    ? `${S.SV.ms.rerolls} free reroll left`
    : 'Rerolls cost 1 key';
}

/* ------------------------------------------------- leaderboard, stats */

function renderLb(highlight) {
  const lb = S.SV.lb || [];
  let html = '<div class="lbrow head"><span>#</span><span>Name</span><span>Score</span><span>Dist</span></div>';
  if (!lb.length) html += '<div class="sub" style="padding:14px">No runs yet — go set a record.</div>';
  lb.forEach((e, i) => {
    html += `<div class="lbrow${i === highlight ? ' me' : ''}">
      <span>${i + 1}</span><span>${escapeHtml(e.n)}</span>
      <span>${fmt(e.s)}</span><span>${fmt(e.d)}m</span></div>`;
  });
  $('lbList').innerHTML = html;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function renderStats() {
  const st = S.SV.st;
  const rows = [
    ['Best score', fmt(S.SV.hs)],
    ['Best distance', fmt(S.SV.bestDist) + 'm'],
    ['Total runs', fmt(st.runs)],
    ['Total distance', fmt(st.dist) + 'm'],
    ['Total coins', fmt(st.coins)],
    ['Jumps', fmt(st.jumps)],
    ['Rolls', fmt(st.rolls)],
    ['Hoverboards used', fmt(st.boardsUsed)],
    ['Power-ups collected', fmt(st.powers)],
    ['Mission sets done', fmt(S.SV.ms.setsDone)],
    ['Time played', Math.floor(st.time / 60) + 'm ' + Math.floor(st.time % 60) + 's']
  ];
  $('statsList').innerHTML = rows.map(r =>
    `<div class="lbrow"><span style="flex:2;text-align:left">${r[0]}</span><span>${r[1]}</span></div>`).join('');
}

/* ---------------------------------------------------------- settings */

function renderSettings() {
  $('setSfx').classList.toggle('on', !!S.SV.set.sfx);
  $('setMus').classList.toggle('on', !!S.SV.set.mus);
  $('setHand').textContent = S.SV.set.hand === 'l' ? 'Left' : 'Right';
  document.body.classList.toggle('righty', S.SV.set.hand === 'r');
  for (const q of ['a', 'high', 'low']) $('q_' + q).classList.toggle('on', S.SV.set.q === q);
  $('setName').value = S.SV.name || '';
}

/* --------------------------------------------------------- game over */

U.gameOver = function (r) {
  $('goScore').textContent = fmt(r.score);
  $('goDist').textContent = fmt(r.dist) + 'm';
  $('goCoins').textContent = fmt(r.coins);
  $('goBest').textContent = fmt(S.SV.hs);
  $('goNew').style.display = r.isBest ? '' : 'none';

  const cost = r.reviveCost;
  const btn = $('reviveBtn');
  const can = r.revives < 3 && S.SV.keys >= cost;
  btn.style.display = r.revives < 3 ? '' : 'none';
  btn.disabled = !can;
  btn.innerHTML = `🗝 REVIVE (${cost})`;
  show('overOv');
};

/* ------------------------------------------------------------ wiring */

function refreshAll() {
  renderShop();
  renderMissions();
  renderLb(-1);
  renderStats();
  renderSettings();
  $('menuCoins').textContent = S.SV.unlockAll ? '∞' : fmt(S.SV.coins);
  $('menuKeys').textContent = S.SV.unlockAll ? '∞' : fmt(S.SV.keys);
  $('menuBest').textContent = fmt(S.SV.hs);
  $('unlockAll').classList.toggle('on', !!S.SV.unlockAll);
  if (S.P.rig) {
    S.applyCharPalette(S.P.rig, S.charById(S.SV.char));
    S.applyBoardPalette(S.P.rig, S.boardById(S.SV.board));
  }
}
U.refreshAll = refreshAll;

U.init = function () {
  initPreview();
  M.ensureDaily();

  const click = (id, fn) => $(id).addEventListener('click', e => { S.initAudio(); fn(e); });

  click('playBtn',    () => S.startRun());
  click('shopBtn',    () => { renderShop(); show('shopOv'); });
  click('missionBtn', () => { renderMissions(); show('missionOv'); });
  click('lbBtn',      () => { renderLb(-1); show('lbOv'); });
  click('statsBtn',   () => { renderStats(); show('statsOv'); });
  click('setBtn',     () => { renderSettings(); show('setOv'); });
  click('helpBtn',    () => show('helpOv'));

  for (const id of ['shopBack', 'missionBack', 'lbBack', 'statsBack', 'setBack', 'helpBack'])
    click(id, () => { refreshAll(); show('menuOv'); });

  click('unlockAll', toggleUnlockAll);

  click('pauseBtn',  () => S.pauseRun());
  click('resumeBtn', () => S.resumeRun());
  click('quitBtn',   () => { S.endRun(true); refreshAll(); show('menuOv'); });
  click('boardBtn',  () => S.Power.activateBoard());

  click('againBtn',  () => S.startRun());
  click('menuBtn',   () => { refreshAll(); show('menuOv'); });
  click('reviveBtn', () => S.revive());

  for (const t of ['chars', 'boards', 'ups'])
    click('tab_' + t, () => { shopTab = t; S.sfx.ui(); renderShop(); });

  $('shopList').addEventListener('click', e => {
    S.initAudio();
    const up = e.target.getAttribute('data-up');
    if (up) { buyUpgrade(up); return; }
    const row = e.target.closest('.item');
    if (!row || !row.dataset.id) return;
    const kind = row.dataset.kind;
    const items = kind === 'chars' ? S.CHARS : S.BOARDS;
    const it = items.find(x => x.id === row.dataset.id);
    if (it) selectItem(kind, it);
  });

  $('missionList').addEventListener('click', e => {
    const i = e.target.getAttribute('data-rr');
    if (i != null) { S.initAudio(); M.reroll(+i); }
  });

  click('setSfx',  () => { S.SV.set.sfx = S.SV.set.sfx ? 0 : 1; S.applyVolumes(); S.save(); renderSettings(); });
  click('setMus',  () => { S.SV.set.mus = S.SV.set.mus ? 0 : 1; S.applyVolumes(); S.save(); renderSettings(); });
  click('setHand', () => { S.SV.set.hand = S.SV.set.hand === 'l' ? 'r' : 'l'; S.save(); renderSettings(); });
  for (const q of ['a', 'high', 'low'])
    click('q_' + q, () => {
      S.SV.set.q = q;
      S.save();
      renderSettings();
      S.toast('Reload to apply the quality change');
    });
  $('setName').addEventListener('change', () => {
    S.SV.name = $('setName').value.slice(0, 14);
    S.save();
  });
  click('resetBtn', () => {
    if (!confirm('Erase all progress?')) return;
    if (!confirm('This cannot be undone. Really erase everything?')) return;
    const st = S.store();
    if (st) try { st.removeItem('ss_v1'); } catch (e) {}
    location.reload();
  });

  refreshAll();
  show('menuOv');
};

U.renderMissions = renderMissions;
U.renderLb = renderLb;

})();
