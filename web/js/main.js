/* ============================================================
   NOVA-9 — entry point: boots the engine, routes the modes
   ============================================================ */
import { Game, Save, Sound, S } from './core.js';
import { UI, buildShop, bindSettings, $, $$ } from './ui.js';
import * as tap from './modes/tap.js';
import * as puzzle from './modes/puzzle.js';
import * as rogue from './modes/rogue.js';
import * as impostor from './modes/impostor.js';
import * as arena from './modes/arena.js';

const MODES = { tap, puzzle, rogue, impostor, arena };
let current = null, currentOpts = {};

Save.load();

/* ---------------- mode API handed to every scene ---------------- */
function makeApi(modId) {
  return {
    hud: (center, right) => UI.setHud(center, right),
    toast: (t, c) => UI.toast(t, c),
    end(res) {
      UI.hud(false);
      Game.paused = true;
      UI.result({
        ...res,
        onRetry: res.onRetry || (() => startMode(modId, currentOpts))
      });
      refreshHome();
    },
    restart: () => startMode(modId, currentOpts)
  };
}

/* ---------------- flow ---------------- */
function openMode(id) {
  const m = MODES[id];
  Sound.play('click');
  if (m.intro) m.intro(makeApi(id), opts => startMode(id, opts));
  else UI.intro({
    ico: m.meta.ico, title: m.meta.title, color: m.meta.color, text: m.meta.desc,
    extraHTML: Save.d.best[id] ? `<p class="muted" style="margin-top:10px">השיא שלך: <b style="color:${m.meta.color}">${Save.d.best[id]}</b></p>` : '',
    onPlay: () => startMode(id, {})
  });
}

function startMode(id, opts = {}) {
  const m = MODES[id];
  current = id; currentOpts = opts;
  Sound.init(); Sound.resume(); Sound.setMood(m.meta.music);
  UI.hideAll(); UI.hud(true);
  Game.paused = false;
  Game.set(m.create(makeApi(id), opts));
  Save.d.totals.runs++; Save.save();
}

function goHome() {
  current = null;
  Game.set(null);
  Sound.setMood('menu');
  UI.hud(false);
  UI.show('home');
  refreshHome();
}

function refreshHome() {
  UI.refreshCoins();
  const labels = {
    tap: v => v ? v + ' מ׳' : '',
    rogue: v => v ? v + ' נק׳' : '',
    arena: v => v ? v + ' נק׳' : '',
    impostor: v => v ? v + ' נק׳' : '',
    puzzle: () => Save.d.puzzle.solved.length ? `${Save.d.puzzle.solved.length}/${puzzle.LEVELS}` : ''
  };
  $$('[data-best]').forEach(el => {
    const k = el.dataset.best;
    const v = labels[k](Save.d.best[k] || 0);
    el.textContent = v ? '★ ' + v : '';
  });
}

/* ---------------- wiring ---------------- */
$$('.mode').forEach(b => b.onclick = () => openMode(b.dataset.mode));
$$('[data-home]').forEach(b => b.onclick = () => { Sound.play('click'); goHome(); });

$('#btnPause').onclick = () => {
  Sound.play('click');
  UI.hud(false);
  UI.pause(() => startMode(current, currentOpts));
};
$('#pauseResume').onclick = () => { Sound.play('click'); UI.resume(); };

$('#btnShop').onclick = () => { Sound.play('click'); buildShop(); UI.show('shop'); };
$('#btnSettings').onclick = () => { Sound.play('click'); UI.show('settings'); };

bindSettings(() => { refreshHome(); buildShop(); });
UI.onHome = goHome;

/* pause when the tab/app goes away mid-run */
document.addEventListener('visibilitychange', () => {
  if (document.hidden && current && UI.current === null) {
    UI.hud(false); UI.pause(() => startMode(current, currentOpts));
  }
});

/* ---------------- install prompt (PWA) ---------------- */
let deferred = null;
window.addEventListener('beforeinstallprompt', e => {
  e.preventDefault(); deferred = e;
  $('#btnInstall').classList.remove('hidden');
});
$('#btnInstall').onclick = async () => {
  if (!deferred) return;
  deferred.prompt();
  await deferred.userChoice;
  deferred = null;
  $('#btnInstall').classList.add('hidden');
};
window.addEventListener('appinstalled', () => $('#btnInstall').classList.add('hidden'));

/* ---------------- Android hardware back ----------------
   The APK shell calls this; returning false lets the system close the app. */
window.novaBack = () => {
  if (UI.current === 'home') return false;
  if (UI.current === 'meeting') return true;          // don't walk out of a vote
  if (UI.current === 'pause') { UI.resume(); return true; }
  if (UI.current === null) {                          // mid-run: pause instead of quitting
    UI.hud(false);
    UI.pause(() => startMode(current, currentOpts));
    return true;
  }
  goHome();
  return true;
};

/* ---------------- boot ---------------- */
refreshHome();
buildShop();
UI.show('home');
Game.start();

// The APK ships its assets locally, so the offline cache is only useful on the web.
const packaged = location.hostname === 'appassets.androidplatform.net';
if ('serviceWorker' in navigator && location.protocol.startsWith('http') && !packaged) {
  window.addEventListener('load', () => navigator.serviceWorker.register('./sw.js').catch(() => { }));
}
