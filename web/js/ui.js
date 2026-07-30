/* ============================================================
   NOVA-9 — UI shell: screens, HUD, shop, settings, results
   ============================================================ */
import { Game, Save, Sound, SKINS, haptic, FX, shade } from './core.js';

const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const SCREENS = ['home', 'intro', 'result', 'pause', 'shop', 'settings', 'meeting'];

export const UI = {
  current: 'home',
  onHome: null,          // set by main.js

  show(id) {
    for (const s of SCREENS) $('#' + s).classList.toggle('hidden', s !== id);
    this.current = id;
    if (id === 'home' || id === 'shop') this.refreshCoins();
  },
  hideAll() { for (const s of SCREENS) $('#' + s).classList.add('hidden'); this.current = null; },

  /* ---------- HUD ---------- */
  hud(on) { $('#hud').classList.toggle('hidden', !on); },
  setHud(center = '', right = '') {
    $('#hudCenter').innerHTML = center;
    $('#hudRight').innerHTML = right;
  },
  toast(text, color = '#fff') {
    const t = $('#toast');
    t.textContent = text; t.style.color = color;
    t.classList.remove('show'); void t.offsetWidth; t.classList.add('show');
  },
  refreshCoins() {
    $('#coinsHome').textContent = Save.d.coins;
    $('#coinsShop').textContent = Save.d.coins;
  },

  /* ---------- intro ---------- */
  intro({ ico, title, text, color, extraHTML = '', playLabel = 'שחק', onPlay, onMount }) {
    $('#introIco').textContent = ico;
    $('#introIco').style.filter = `drop-shadow(0 0 18px ${color})`;
    $('#introTitle').textContent = title;
    $('#introText').innerHTML = text;
    $('#introExtra').innerHTML = extraHTML;
    const btn = $('#introPlay');
    btn.textContent = playLabel;
    btn.style.background = `linear-gradient(180deg,${shade(color, .45)},${color})`;
    btn.onclick = () => { Sound.play('click'); haptic(10); onPlay(); };
    onMount?.($('#introExtra'), btn);
    this.show('intro');
  },

  /* ---------- result ---------- */
  result({ ico = '★', title, stats = [], coins = 0, color = '#21e6ff', onRetry, retryLabel = 'שוב' }) {
    $('#resIco').textContent = ico;
    $('#resIco').style.filter = `drop-shadow(0 0 18px ${color})`;
    $('#resTitle').textContent = title;
    $('#resStats').innerHTML = stats.map(([k, v]) => `<div><span>${k}</span><b>${v}</b></div>`).join('');
    $('#resCoins').textContent = 0;
    $('#resRetry').textContent = retryLabel;
    $('#resRetry').onclick = () => { Sound.play('click'); onRetry(); };
    this.show('result');
    // count-up on the coin reward
    if (coins > 0) {
      Save.addCoins(coins);
      const el = $('#resCoins'); const t0 = performance.now(), dur = Math.min(900, 220 + coins * 6);
      const tick = now => {
        const k = Math.min(1, (now - t0) / dur);
        el.textContent = Math.round(coins * (1 - Math.pow(1 - k, 3)));
        if (k < 1) requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
      Sound.play('coin');
    }
  },

  /* ---------- pause ---------- */
  pause(onRestart) {
    if (!Game.scene) return;
    Game.paused = true;
    $('#pauseRestart').onclick = () => { Sound.play('click'); this.hideAll(); this.hud(true); Game.paused = false; onRestart(); };
    this.show('pause');
  },
  resume() { this.hideAll(); this.hud(true); Game.paused = false; }
};

/* ---------- shop ---------- */
export function buildShop() {
  const grid = document.querySelector('#skinGrid');
  grid.innerHTML = '';
  for (const s of SKINS) {
    const owned = Save.d.owned.includes(s.id);
    const on = Save.d.skin === s.id;
    const b = document.createElement('button');
    b.className = 'skin' + (owned ? ' owned' : '') + (on ? ' on' : '');
    const bg = s.rainbow
      ? 'conic-gradient(from 0deg,#ff5ec4,#ffd75e,#5eff9b,#5ec8ff,#b45eff,#ff5ec4)'
      : `radial-gradient(circle at 34% 30%,#fff,${s.c} 45%,${s.c2})`;
    b.innerHTML = `<div class="ball" style="background:${bg};box-shadow:0 0 18px ${s.c}"></div>
      <b>${s.name}</b><small>${on ? 'פעיל' : owned ? 'בבעלותך' : '◈ ' + s.price}</small>`;
    b.onclick = () => {
      if (owned) {
        Save.d.skin = s.id; Save.save(); Sound.play('lock'); haptic(14);
      } else if (Save.d.coins >= s.price) {
        Save.d.coins -= s.price; Save.d.owned.push(s.id); Save.d.skin = s.id; Save.save();
        Sound.play('win'); haptic(30);
        FX.flash(s.c, .3);
      } else {
        Sound.play('no'); haptic(40);
        b.animate([{ transform: 'translateX(0)' }, { transform: 'translateX(-7px)' },
        { transform: 'translateX(7px)' }, { transform: 'translateX(0)' }], { duration: 220 });
        return;
      }
      buildShop(); UI.refreshCoins();
    };
    grid.appendChild(b);
  }
}

/* ---------- settings ---------- */
export function bindSettings(onReset) {
  const map = { setMusic: 'music', setSfx: 'sfx', setHaptic: 'haptic', setShake: 'shake' };
  for (const [id, key] of Object.entries(map)) {
    const el = document.querySelector('#' + id);
    el.checked = Save.d.settings[key];
    el.onchange = () => {
      Save.d.settings[key] = el.checked; Save.save();
      if (key === 'music') Sound.musicToggle();
      if (key === 'haptic' && el.checked) haptic(20);
      Sound.play('click');
    };
  }
  document.querySelector('#btnReset').onclick = () => {
    if (confirm('לאפס את כל ההתקדמות, המטבעות והשיאים?')) {
      Save.reset(); onReset?.(); Sound.play('no');
    }
  };
}

export { $, $$ };
