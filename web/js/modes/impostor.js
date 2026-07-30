/* ============================================================
   בוגד בתחנה  (social deduction vs bots)
   7 crew, 1 traitor. Real task mini-games, vents, sabotages,
   bodies, emergency meetings and voting. Bots remember what
   they saw — including who they watched climb out of a vent.
   ============================================================ */
import {
  S, U, TAU, FX, D, Sound, Save, haptic, alpha, Game, Stick, keyVec
} from '../core.js';
import { UI, $ } from '../ui.js';
import { openTask } from './tasks.js';

export const meta = {
  id: 'impostor', title: 'בוגד בתחנה', ico: '☠', color: '#ff2e88', music: 'impostor',
  desc: 'שבעה בתחנה, אחד בוגד — אולי אתה.<br>משימות אמיתיות, פתחי אוורור, חבלות והצבעה.'
};

/* ---------------- station map ---------------- */
const ROOMS = [
  { n: 'קפיטריה', x: 340, y: 250, w: 240, h: 200, c: '#3a4a7a' },
  { n: 'מנועים', x: 60, y: 60, w: 200, h: 160, c: '#7a3a3a' },
  { n: 'חשמל', x: 60, y: 470, w: 200, h: 160, c: '#7a6a3a' },
  { n: 'ניווט', x: 660, y: 60, w: 200, h: 160, c: '#3a7a6a' },
  { n: 'מעבדה', x: 660, y: 470, w: 200, h: 160, c: '#5a3a7a' },
  { n: 'מחסן', x: 360, y: 560, w: 200, h: 130, c: '#3a5a5a' },
  { n: 'רפואה', x: 360, y: 40, w: 200, h: 130, c: '#7a4a6a' }
];
// every corridor overlaps the rooms it joins by ~30px, so each junction is a real doorway
const HALLS = [
  { x: 160, y: 190, w: 60, h: 310 },     // west spine
  { x: 700, y: 190, w: 60, h: 310 },     // east spine
  { x: 180, y: 320, w: 190, h: 60 },     // west → cafe
  { x: 550, y: 320, w: 180, h: 60 },     // cafe → east
  { x: 430, y: 140, w: 60, h: 140 },     // med → cafe
  { x: 430, y: 420, w: 60, h: 170 },     // cafe → storage
  { x: 220, y: 110, w: 220, h: 55 },     // engines → med
  { x: 480, y: 110, w: 220, h: 55 },     // med → nav
  { x: 220, y: 560, w: 170, h: 55 },     // electrical → storage
  { x: 530, y: 560, w: 180, h: 55 }      // storage → lab
];
const AREAS = [...ROOMS, ...HALLS];
const MAPW = 920, MAPH = 720;

/* ---------------- navigation graph ----------------
   Rooms and corridors are drawn as overlapping rectangles, so two areas are connected
   wherever they intersect. The centre of that intersection is the doorway to steer through. */
const LINKS = AREAS.map(() => []);
for (let i = 0; i < AREAS.length; i++) {
  for (let j = i + 1; j < AREAS.length; j++) {
    const a = AREAS[i], b = AREAS[j];
    const x1 = Math.max(a.x, b.x), x2 = Math.min(a.x + a.w, b.x + b.w);
    const y1 = Math.max(a.y, b.y), y2 = Math.min(a.y + a.h, b.y + b.h);
    if (x2 - x1 > 12 && y2 - y1 > 12) {
      const door = { x: (x1 + x2) / 2, y: (y1 + y2) / 2 };
      LINKS[i].push({ to: j, ...door });
      LINKS[j].push({ to: i, ...door });
    }
  }
}
function areaAt(x, y) {
  for (let i = 0; i < AREAS.length; i++) {
    const a = AREAS[i];
    if (x > a.x && x < a.x + a.w && y > a.y && y < a.y + a.h) return i;
  }
  return -1;
}
/** BFS over areas; returns the doorway to head for next, or null when already there. */
function nextDoor(fromA, toA) {
  if (fromA < 0 || toA < 0 || fromA === toA) return null;
  const prev = new Map([[fromA, null]]);
  const q = [fromA];
  while (q.length) {
    const cur = q.shift();
    if (cur === toA) break;
    for (const l of LINKS[cur]) if (!prev.has(l.to)) { prev.set(l.to, { from: cur, door: l }); q.push(l.to); }
  }
  if (!prev.has(toA)) return null;
  let cur = toA, step = prev.get(toA);
  while (step && step.from !== fromA) { cur = step.from; step = prev.get(cur); }
  return step ? step.door : null;
}

/* task spots — each runs its own mini-game */
const SPOT_DEFS = [
  { x: 110, y: 110, r: 'מנועים', t: 'calibrate' }, { x: 200, y: 170, r: 'מנועים', t: 'wires' },
  { x: 110, y: 520, r: 'חשמל', t: 'wires' }, { x: 200, y: 590, r: 'חשמל', t: 'download' },
  { x: 800, y: 110, r: 'ניווט', t: 'asteroids' }, { x: 710, y: 170, r: 'ניווט', t: 'calibrate' },
  { x: 800, y: 520, r: 'מעבדה', t: 'download' }, { x: 710, y: 590, r: 'מעבדה', t: 'wires' },
  { x: 410, y: 610, r: 'מחסן', t: 'swipe' }, { x: 510, y: 610, r: 'מחסן', t: 'calibrate' },
  { x: 410, y: 90, r: 'רפואה', t: 'swipe' }, { x: 510, y: 90, r: 'רפואה', t: 'download' },
  { x: 380, y: 300, r: 'קפיטריה', t: 'swipe' }, { x: 540, y: 400, r: 'קפיטריה', t: 'asteroids' }
];

/* vents — the traitor's shortcuts. Each network is a loop. */
const VENTS = [
  { x: 95, y: 195, net: 0 },   // engines
  { x: 95, y: 495, net: 0 },   // electrical
  { x: 385, y: 630, net: 0 },  // storage
  { x: 825, y: 195, net: 1 },  // nav
  { x: 825, y: 495, net: 1 },  // lab
  { x: 545, y: 275, net: 1 }   // cafeteria
];

const CREW = [
  { n: 'ניר', c: '#21e6ff' },
  { n: 'רותם', c: '#ff4d6d' }, { n: 'עומר', c: '#46f08a' }, { n: 'שחר', c: '#ffc63a' },
  { n: 'ניצן', c: '#c46bff' }, { n: 'ליאם', c: '#ff8a3d' }, { n: 'דניאל', c: '#ffffff' }
];

const VISION = 175, DARK_VISION = 92, KILL_R = 46, REPORT_R = 80, TASK_R = 32;
const TASKS_EACH = 4;
const KILL_COOL = 25, SAB_COOL = 28, REACTOR_TIME = 35;
const FIX_LIGHTS = { x: 110, y: 520, r: 'חשמל' };
const FIX_REACTOR = { x: 110, y: 110, r: 'מנועים' };

export function create(api, opts = {}) {
  const stick = new Stick('any');
  let ps, me, bodies, tasksDone, tasksTotal, killCool, over, sab, sabCool,
    meetingOn, hint, emergencyLeft, gameT, tick, impostor, mini, buttons;

  const inArea = (x, y) => AREAS.some(a => x > a.x && x < a.x + a.w && y > a.y && y < a.y + a.h);
  const roomAt = (x, y) => ROOMS.find(a => x > a.x && x < a.x + a.w && y > a.y && y < a.y + a.h)?.n || 'מסדרון';
  const alive = () => ps.filter(p => !p.dead);
  const crewAlive = () => ps.filter(p => !p.dead && !p.imp);
  const lightsOn = () => sab?.kind !== 'lights';

  function reset() {
    const impIdx = Math.random() < .3 ? 0 : U.rndi(1, 7);
    ps = CREW.map((c, i) => ({
      i, name: c.n, c: c.c, x: 460 + Math.cos(i / 7 * TAU) * 70, y: 350 + Math.sin(i / 7 * TAU) * 70,
      vx: 0, vy: 0, r: 15, dead: false, imp: i === impIdx, me: i === 0,
      taskT: 0, done: 0, mem: [], sus: {}, walkT: 0, witness: [], inVent: false
    }));
    me = ps[0]; impostor = ps[impIdx];
    // everyone carries a personal list; ghosts keep working it, exactly like the real game
    for (const p of ps) {
      p.list = U.shuffle([...SPOT_DEFS]).slice(0, TASKS_EACH).map(s => ({ s, done: false }));
      p.todo = p.imp ? 0 : TASKS_EACH;
      p.spot = U.pick(SPOT_DEFS);
    }
    bodies = []; tasksDone = 0; over = null; mini = null;
    tasksTotal = ps.filter(p => !p.imp).length * TASKS_EACH;
    killCool = 10; sab = null; sabCool = 10; meetingOn = false;
    hint = 4.5; emergencyLeft = 1; gameT = 0; tick = 0; buttons = [];
    api.toast(me.imp ? 'אתה הבוגד!' : 'אתה איש צוות', me.imp ? '#ff2e88' : '#46f08a');
    Sound.play(me.imp ? 'alarm' : 'level');
    haptic(me.imp ? [40, 60, 40] : 20);
  }

  /* ---------------- movement & sight ---------------- */
  function step(p, dx, dy, dt, spd = 128) {
    const nx = p.x + dx * spd * dt, ny = p.y + dy * spd * dt;
    if (inArea(nx, p.y)) p.x = nx;
    if (inArea(p.x, ny)) p.y = ny;
    p.vx = dx * spd; p.vy = dy * spd;
  }

  function canSee(a, b) {
    if (b.inVent) return false;
    const d = U.dist(a.x, a.y, b.x, b.y);
    if (d > (lightsOn() ? VISION : DARK_VISION)) return false;
    const steps = Math.ceil(d / 20);
    for (let i = 1; i < steps; i++) {
      const t = i / steps;
      if (!inArea(U.lerp(a.x, b.x, t), U.lerp(a.y, b.y, t))) return false;
    }
    return true;
  }
  const clearLine = (p, b) => {
    for (let i = 1; i < 8; i++) {
      const t = i / 8;
      if (!inArea(U.lerp(p.x, b.x, t), U.lerp(p.y, b.y, t))) return false;
    }
    return true;
  };

  /* ---------------- memory / suspicion ---------------- */
  function remember(p) {
    const seen = [];
    for (const o of ps) if (o !== p && !o.dead && canSee(p, o)) seen.push(o.i);
    p.mem.push({ t: gameT, seen, room: roomAt(p.x, p.y) });
    if (p.mem.length > 90) p.mem.shift();
  }

  function witnessKill(killer, victim) {
    for (const w of ps) {
      if (w.dead || w === killer) continue;
      if (canSee(w, victim) || canSee(w, killer)) {
        w.witness.push({ kind: 'kill', who: killer.i, victim: victim.i, t: gameT });
        w.sus[killer.i] = (w.sus[killer.i] || 0) + 100;
      }
    }
  }
  function witnessVent(who, x, y) {
    for (const w of ps) {
      if (w.dead || w === who) continue;
      const d = U.dist(w.x, w.y, x, y);
      if (d < (lightsOn() ? VISION : DARK_VISION) && clearLine(w, { x, y })) {
        w.witness.push({ kind: 'vent', who: who.i, room: roomAt(x, y), t: gameT });
        w.sus[who.i] = (w.sus[who.i] || 0) + 90;
      }
    }
  }

  function buildSuspicion() {
    for (const p of ps) {
      if (p.dead || p.me) continue;
      const sus = p.sus;
      for (const o of ps) {
        if (o === p || o.dead) continue;
        sus[o.i] = (sus[o.i] || 0) + U.rnd(0, 12);
        const recent = p.mem.filter(m => gameT - m.t < 20);
        const withMe = recent.filter(m => m.seen.includes(o.i)).length;
        if (recent.length > 4 && withMe / recent.length > .6) sus[o.i] -= 45;
        for (const b of bodies) {
          const around = p.mem.filter(m => Math.abs(m.t - b.t) < 12 && m.seen.includes(o.i) && m.room === b.room);
          if (around.length) sus[o.i] += 40;
        }
      }
      if (p.imp) {   // the traitor frames someone innocent and never itself
        const target = U.pick(alive().filter(o => o !== p));
        if (target) sus[target.i] = (sus[target.i] || 0) + 70;
        for (const o of ps) if (o.imp) sus[o.i] = -999;
      }
    }
  }

  /* ---------------- bots ---------------- */
  function pickSpot(p) {
    const cands = SPOT_DEFS.filter(s => s !== p.spot)
      .sort((a, b) => U.dist2(p.x, p.y, a.x, a.y) - U.dist2(p.x, p.y, b.x, b.y));
    p.spot = U.pick(cands.slice(0, 4));
  }

  function botCrew(p, dt) {
    // a live crewmate drops everything for an active sabotage, then for a body
    if (!p.dead && sab) {
      const fix = sab.kind === 'lights' ? FIX_LIGHTS : FIX_REACTOR;
      if (U.dist(p.x, p.y, fix.x, fix.y) < TASK_R) {
        p.taskT += dt;
        if (p.taskT > 2.5) { p.taskT = 0; clearSabotage(p); }
      } else navigate(p, fix.x, fix.y, dt);
      return;
    }
    if (!p.dead) {
      for (const b of bodies) {
        if (!b.found && U.dist(p.x, p.y, b.x, b.y) < REPORT_R && clearLine(p, b)) { report(p, b); return; }
      }
    }
    const spot = p.spot;
    if (U.dist(p.x, p.y, spot.x, spot.y) < TASK_R) {
      p.taskT += dt;
      if (p.taskT > U.rnd(3.5, 6)) {
        p.taskT = 0; pickSpot(p);
        if (p.todo > 0) {              // ghosts keep finishing their own list
          p.todo--; p.done++;
          tasksDone = Math.min(tasksTotal, tasksDone + 1);
          Sound.tone(880, .06, 'sine', .1);
          checkEnd();
        }
      }
    } else navigate(p, spot.x, spot.y, dt);
  }

  function botImp(p, dt) {
    const targets = crewAlive().filter(o => o !== p);
    if (!targets.length) return;

    // sabotage to split the crew up, or to steal the win outright
    if (!sab && sabCool <= 0 && Math.random() < dt * .4) {
      startSabotage(Math.random() < .45 ? 'reactor' : 'lights', p);
      return;
    }
    if (killCool > 4) {                // cooling down: blend in by faking tasks
      const spot = p.spot;
      if (U.dist(p.x, p.y, spot.x, spot.y) < TASK_R) { p.taskT += dt; if (p.taskT > 3) { p.taskT = 0; pickSpot(p); } }
      else navigate(p, spot.x, spot.y, dt);
      return;
    }

    p.frust = (p.frust || 0) + dt;
    const reckless = p.frust > 22;     // impatience is how the traitor gets caught
    let best = null, bd = 1e9;
    for (const t of targets) {
      const watched = ps.some(w => w !== p && w !== t && !w.dead && canSee(w, t));
      if (watched && !reckless) continue;
      const d = U.dist(p.x, p.y, t.x, t.y);
      if (d < bd) { bd = d; best = t; }
    }
    if (!best) {
      const near = targets.map(o => ({ o, d: U.dist(p.x, p.y, o.x, o.y) })).sort((a, b) => a.d - b.d)[0];
      navigate(p, near.o.x, near.o.y, dt);
      return;
    }
    if (killCool <= 0 && bd < KILL_R) { doKill(p, best); p.frust = 0; return; }
    navigate(p, best.x, best.y, dt);
  }

  // route over the area graph: steer to the next doorway, then to the target itself
  function navigate(p, tx, ty, dt) {
    const here = areaAt(p.x, p.y), dest = areaAt(tx, ty);
    let gx = tx, gy = ty;
    if (here !== dest) {
      const door = nextDoor(here, dest);
      if (door) {
        // doorways sit inside both areas — once we're on one, aim at the next area's centre,
        // otherwise the bot arrives with a zero-length heading and stalls in the gap
        if (U.dist(p.x, p.y, door.x, door.y) < 36) {
          const a = AREAS[door.to];
          gx = a.x + a.w / 2; gy = a.y + a.h / 2;
        } else { gx = door.x; gy = door.y; }
      }
    }
    let dx = gx - p.x, dy = gy - p.y;
    const d = Math.hypot(dx, dy) || 1; dx /= d; dy /= d;
    const probe = 22;
    if (!inArea(p.x + dx * probe, p.y + dy * probe)) {
      if (inArea(p.x + dx * probe, p.y)) dy = 0;
      else if (inArea(p.x, p.y + dy * probe)) dx = 0;
      else {
        p.walkT -= dt;
        if (p.walkT <= 0) { p.walkT = .5; p.detour = U.rnd(TAU); }
        dx = Math.cos(p.detour); dy = Math.sin(p.detour);
      }
    }
    const m = Math.hypot(dx, dy) || 1;
    step(p, dx / m, dy / m, dt, 122);
  }

  /* ---------------- actions ---------------- */
  function doKill(killer, victim) {
    victim.dead = true; killCool = KILL_COOL;
    bodies.push({
      x: victim.x, y: victim.y, i: victim.i, c: victim.c,
      t: gameT, room: roomAt(victim.x, victim.y), found: false
    });
    witnessKill(killer, victim);
    if (victim.me && mini) mini = null;
    FX.burst(victim.x, victim.y, 22, '#ff2e5f', { speed: 260, life: .6, size: 4.5 });
    FX.ring(victim.x, victim.y, '#ff2e5f', 8, 90, 3, .4);
    Sound.play('kill');
    if (killer.me || victim.me) { FX.shake(12); FX.flash('#ff2e5f', .35); haptic([40, 60]); }
    if (victim.me) api.toast('נרצחת! המשך את המשימות כרוח', '#ff6b8a');
    if (killer.me) api.toast('חיסלת את ' + victim.name, '#ff2e88');
    checkEnd();
  }

  function useVent(p) {
    const here = VENTS.find(v => U.dist(p.x, p.y, v.x, v.y) < 40);
    if (!here) return;
    const loop = VENTS.filter(v => v.net === here.net);
    const idx = loop.indexOf(here);
    const next = loop[(idx + 1) % loop.length];
    witnessVent(p, p.x, p.y);          // crew see you drop in…
    FX.burst(p.x, p.y, 14, '#9b5cff', { speed: 160, life: .45, size: 4 });
    FX.ring(p.x, p.y, '#9b5cff', 6, 60, 3, .3);
    p.x = next.x; p.y = next.y;
    witnessVent(p, p.x, p.y);          // …and climb back out
    FX.burst(p.x, p.y, 14, '#9b5cff', { speed: 160, life: .45, size: 4 });
    Sound.play('lock'); haptic(18);
    if (p.me) api.toast('עברת דרך פיר האוורור', '#c9a3ff');
  }

  function startSabotage(kind, by) {
    if (sab || meetingOn || over) return;
    sab = { kind, t: kind === 'reactor' ? REACTOR_TIME : 45, by: by.i };
    sabCool = SAB_COOL;
    Sound.play('alarm'); FX.flash(kind === 'reactor' ? '#ff2e5f' : '#ffc63a', .3); haptic([40, 40, 40]);
    api.toast(kind === 'reactor' ? 'התכת הכור!' : 'האורות כבו!', '#ff8a9d');
  }
  function clearSabotage(by) {
    if (!sab) return;
    const kind = sab.kind;
    sab = null;
    Sound.play('win'); haptic(20);
    if (by?.me) api.toast(kind === 'reactor' ? 'הכור יוצב!' : 'האורות חזרו!', '#9dffc6');
    else api.toast(kind === 'reactor' ? 'הכור יוצב' : 'האורות חזרו', '#9dffc6');
  }

  function report(by, body) {
    if (meetingOn || over) return;
    if (body) body.found = true;
    if (sab?.kind === 'reactor') return;     // no meetings during a meltdown
    Sound.play('alarm'); FX.flash('#ffc63a', .3); haptic([30, 40, 30]);
    openMeeting(by, body);
  }

  function checkEnd() {
    if (over) return;
    const c = crewAlive().length, i = alive().filter(p => p.imp).length;
    if (i === 0) finish(!me.imp, 'הבוגד הודח');
    else if (c <= i) finish(!!me.imp, 'הבוגד השתלט על התחנה');
    else if (tasksDone >= tasksTotal) finish(!me.imp, 'כל המשימות הושלמו');
  }

  function finish(win, why) {
    if (over) return;
    over = { win, why };
    mini = null;
    Sound.play(win ? 'win' : 'die');
    FX.flash(win ? '#46f08a' : '#ff2e5f', .4);
    haptic(win ? [20, 40, 20] : [70]);
    const score = (win ? 500 : 100) + tasksDone * 20 + me.done * 40;
    Save.setBest('impostor', score);
    setTimeout(() => {
      UI.hideAll();
      api.end({
        ico: win ? '🏆' : '☠', title: win ? 'ניצחתם!' : 'הפסדתם',
        color: meta.color,
        stats: [['תפקיד', me.imp ? 'בוגד' : 'איש צוות'], ['סיבה', why],
        ['משימות שלך', `${me.done}/${TASKS_EACH}`], ['משימות התחנה', `${tasksDone}/${tasksTotal}`]],
        coins: win ? 150 + me.done * 25 : 40 + me.done * 15
      });
    }, 900);
  }

  /* ---------------- meeting ---------------- */
  function openMeeting(by, body) {
    meetingOn = true; Game.paused = true; mini = null;
    buildSuspicion();
    const chat = $('#meetChat'), votes = $('#meetVotes');
    $('#meetTitle').textContent = body ? 'נמצאה גופה!' : 'אספת חירום';
    chat.innerHTML = ''; votes.innerHTML = '';
    UI.hud(false); UI.show('meeting');

    const lines = [];
    const nameOf = i => ps[i].name;
    if (body) lines.push({ s: 1, txt: `${by.name} דיווח על הגופה של ${nameOf(body.i)} ב${body.room}.` });
    else lines.push({ s: 1, txt: `${by.name} לחץ על כפתור החירום.` });

    // each speaker takes a line nobody else used this meeting
    const used = new Set();
    const say = opts => {
      const fresh = opts.filter(t => !used.has(t));
      const txt = U.pick(fresh.length ? fresh : opts);
      used.add(txt); return txt;
    };

    for (const p of alive()) {
      if (p.me) continue;
      const w = (p.witness || []).filter(w => !ps[w.who].dead).pop();
      const top = Object.entries(p.sus).filter(([i]) => !ps[+i].dead && +i !== p.i)
        .sort((a, b) => b[1] - a[1])[0];
      const where = body ? body.room : 'הקפיטריה';
      if (w?.kind === 'kill')
        lines.push({ p, txt: `ראיתי את ${nameOf(w.who)} הורג את ${nameOf(w.victim)}! זה הוא, בלי ספק.` });
      else if (w?.kind === 'vent')
        lines.push({ p, txt: `${nameOf(w.who)} יצא מפיר אוורור ב${w.room}. אני נשבע.` });
      else if (top && top[1] > 55) lines.push({
        p, txt: say([
          `${nameOf(+top[0])} התנהג מוזר ליד ${where}.`,
          `אני לא סומך על ${nameOf(+top[0])}. איפה היית?`,
          `${nameOf(+top[0])} הלך אחריי כל הזמן ולא עשה כלום.`,
          `ראיתי את ${nameOf(+top[0])} יוצא מ${where} ממש לפני זה.`,
          `${nameOf(+top[0])} עמד ליד המשימה ולא נגע בה בכלל.`,
          `למה ${nameOf(+top[0])} מסתובב לבד כל הזמן?`
        ])
      });
      else if (top && top[1] < -20) lines.push({
        p, txt: say([
          `הייתי עם ${nameOf(+top[0])} רוב הזמן, הוא נקי מבחינתי.`,
          `${nameOf(+top[0])} איתי מההתחלה. תורידו ממנו.`,
          `אני ערב ל${nameOf(+top[0])}, עשינו משימות ביחד.`
        ])
      });
      else lines.push({
        p, txt: say([
          `הייתי ב${U.pick(ROOMS).n} כל הזמן, עשיתי משימות.`,
          `לא ראיתי כלום. מישהו ראה משהו?`,
          `בואו לא נדיח סתם, אין לי מספיק מידע.`,
          `סיימתי משימה ב${U.pick(ROOMS).n} ורצתי לכאן.`,
          `אני מדלג. אין לי על מי להצביע.`,
          `מי היה האחרון שראה אותו חי?`,
          `שמעתי משהו ב${U.pick(ROOMS).n} אבל לא ראיתי מי.`
        ])
      });
    }

    let i = 0;
    const push = () => {
      if (i >= lines.length) { openVote(); return; }
      const l = lines[i++];
      const el = document.createElement('div');
      el.className = 'msg' + (l.s ? ' sys' : '');
      el.innerHTML = l.s ? l.txt : `<b style="color:${l.p.c}">${l.p.name}:</b> ${l.txt}`;
      chat.appendChild(el); chat.scrollTop = chat.scrollHeight;
      Sound.tone(520 + Math.random() * 140, .05, 'sine', .12);
      setTimeout(push, l.s ? 700 : U.rnd(750, 1250));
    };
    setTimeout(push, 350);

    function openVote() {
      let picked = null, timeLeft = 15;
      const cards = alive().map(p => {
        const b = document.createElement('button');
        b.className = 'vote';
        b.innerHTML = `<div class="dot" style="background:${p.c};box-shadow:0 0 12px ${p.c}"></div>
                       <span>${p.name}${p.me ? ' (אתה)' : ''}</span><span class="tally"></span>`;
        b.onclick = () => {
          picked = p; Sound.play('vote'); haptic(12);
          votes.querySelectorAll('.vote').forEach(x => x.classList.toggle('pick', x === b));
        };
        votes.appendChild(b); return { p, b };
      });
      const foot = $('#meetFoot'), skip = $('#meetSkip');
      skip.onclick = () => {
        picked = 'skip'; Sound.play('vote');
        votes.querySelectorAll('.vote').forEach(x => x.classList.remove('pick'));
      };
      const doneBtn = document.createElement('button');
      doneBtn.className = 'btn'; doneBtn.textContent = 'הצבע (15)';
      foot.appendChild(doneBtn);

      const iv = setInterval(() => {
        timeLeft--; doneBtn.textContent = `הצבע (${timeLeft})`;
        if (timeLeft <= 0) finishVote();
      }, 1000);
      doneBtn.onclick = finishVote;

      function finishVote() {
        clearInterval(iv); doneBtn.remove();
        const tally = {};
        if (picked && picked !== 'skip') tally[picked.i] = 1;
        for (const p of alive()) {
          if (p.me) continue;
          const top = Object.entries(p.sus).filter(([i]) => !ps[+i].dead && +i !== p.i)
            .sort((a, b) => b[1] - a[1])[0];
          if (top && top[1] > 45) tally[+top[0]] = (tally[+top[0]] || 0) + 1;
        }
        for (const { p, b } of cards) {
          const n = tally[p.i] || 0;
          b.querySelector('.tally').textContent = n ? '▮'.repeat(n) : '—';
        }
        const best = Object.entries(tally).sort((a, b) => b[1] - a[1]);
        const ejected = best.length && (best.length === 1 || best[0][1] > best[1][1]) ? ps[+best[0][0]] : null;

        const chatEl = $('#meetChat');
        const say = txt => {
          const el = document.createElement('div'); el.className = 'msg sys'; el.textContent = txt;
          chatEl.appendChild(el); chatEl.scrollTop = chatEl.scrollHeight;
        };
        if (ejected) {
          ejected.dead = true;
          say(ejected.me
            ? `הודחת מהתחנה... ${ejected.imp ? 'היית הבוגד!' : 'לא היית הבוגד.'}`
            : `${ejected.name} הודח מהתחנה... ${ejected.imp ? 'הוא היה הבוגד!' : 'הוא לא היה הבוגד.'}`);
          Sound.play(ejected.imp ? 'win' : 'no'); haptic(30);
        } else { say('אין רוב — אף אחד לא הודח.'); Sound.play('no'); }

        setTimeout(() => {
          votes.innerHTML = ''; skip.onclick = null;
          meetingOn = false; Game.paused = false;
          UI.hideAll(); UI.hud(true);
          bodies = []; sab = null;
          killCool = Math.max(killCool, 12);
          for (const p of ps) {
            p.witness = []; p.sus = {}; p.mem = []; p.taskT = 0; p.inVent = false;
            p.x = 460 + Math.cos(p.i / 7 * TAU) * 70;
            p.y = 350 + Math.sin(p.i / 7 * TAU) * 70;
          }
          checkEnd();
        }, 2600);
      }
    }
  }

  /* ---------------- player actions ---------------- */
  function nearestJob() {
    return me.list.find(j => !j.done && U.dist(me.x, me.y, j.s.x, j.s.y) < TASK_R);
  }
  function nearBody() { return bodies.find(b => !b.found && U.dist(me.x, me.y, b.x, b.y) < REPORT_R); }
  function nearVent() { return VENTS.find(v => U.dist(me.x, me.y, v.x, v.y) < 40); }
  function atFix() {
    if (!sab) return false;
    const f = sab.kind === 'lights' ? FIX_LIGHTS : FIX_REACTOR;
    return U.dist(me.x, me.y, f.x, f.y) < TASK_R;
  }
  function killTarget() {
    if (!me.imp || killCool > 0) return null;
    const t = crewAlive().filter(o => o !== me)
      .map(o => ({ o, d: U.dist(me.x, me.y, o.x, o.y) })).sort((a, b) => a.d - b.d)[0];
    return t && t.d < KILL_R ? t.o : null;
  }

  /* on-screen buttons, laid out bottom-right like the game they come from */
  function layoutButtons() {
    const bx = S.W - 66, by = S.H - 96 - S.safeB;
    buttons = [];
    const job = nearestJob(), body = nearBody(), vent = nearVent();
    const canEmergency = emergencyLeft > 0 && gameT > 15 && U.dist(me.x, me.y, 460, 350) < 60 && !me.dead;

    buttons.push({
      id: 'use', x: bx, y: by, r: 42,
      on: !!(job || atFix()) && !mini,
      label: atFix() ? '🔧' : '⚙', color: '#ffc63a'
    });
    buttons.push({
      id: 'report', x: bx - 100, y: by, r: 36,
      on: !!body && !me.dead, label: '☠', color: '#ff4d6d'
    });
    if (canEmergency) buttons.push({ id: 'emergency', x: bx - 100, y: by - 92, r: 36, on: true, label: '!', color: '#ffc63a' });
    if (me.imp && !me.dead) {
      buttons.push({ id: 'kill', x: bx, y: by - 92, r: 40, on: !!killTarget(), label: '🔪', color: '#ff2e5f' });
      buttons.push({ id: 'vent', x: bx - 100, y: by - 176, r: 34, on: !!vent, label: '⇵', color: '#9b5cff' });
      buttons.push({
        id: 'sabotage', x: bx, y: by - 176, r: 34,
        on: !sab && sabCool <= 0, label: '⚡', color: '#ff8a3d'
      });
    }
  }

  function pressButton(id) {
    if (id === 'use') {
      if (atFix()) { clearSabotage(me); return; }
      const job = nearestJob();
      if (job) { mini = openTask(job.s.t); mini.job = job; Sound.play('click'); haptic(10); }
      else { Sound.play('no'); haptic(18); }
    }
    else if (id === 'report') { const b = nearBody(); if (b) report(me, b); else { Sound.play('no'); haptic(18); } }
    else if (id === 'emergency') { emergencyLeft--; report(me, null); }
    else if (id === 'kill') { const t = killTarget(); if (t) doKill(me, t); else { Sound.play('no'); haptic(20); } }
    else if (id === 'vent') { if (nearVent()) useVent(me); else { Sound.play('no'); haptic(18); } }
    else if (id === 'sabotage') {
      if (sab || sabCool > 0) { Sound.play('no'); haptic(20); return; }
      // nearest hazard to where you're standing keeps it a one-tap decision
      startSabotage(U.dist(me.x, me.y, FIX_REACTOR.x, FIX_REACTOR.y) > 320 ? 'reactor' : 'lights', me);
    }
  }

  /* ---------------- scene ---------------- */
  return {
    debug: () => ({
      meImp: me.imp, impostor: impostor.name, killCool: Math.round(killCool),
      bodies: bodies.length, tasks: `${tasksDone}/${tasksTotal}`, myTodo: me.todo,
      sab: sab ? `${sab.kind}:${Math.round(sab.t)}` : null,
      sabCool: Math.round(sabCool), pos: [Math.round(me.x), Math.round(me.y)],
      alive: alive().map(p => p.name), mini: mini?.type || null,
      gameT: Math.round(gameT), meetingOn, over
    }),
    openTaskForTest(type) { mini = openTask(type); mini.job = me.list.find(j => !j.done); },
    tpForTest(x, y) { me.x = x; me.y = y; },
    pressForTest(id) { layoutButtons(); pressButton(id); },
    ventsForTest: () => VENTS,

    enter() { reset(); },
    exit() { Game.paused = false; },

    onDown(p) {
      if (meetingOn || over) return;
      if (mini) {
        if (mini.hitClose(p)) { mini = null; Sound.play('click'); return; }
        mini.onDown?.(p); return;
      }
      for (const b of buttons) {
        if (U.dist(p.x, p.y, b.x, b.y) < b.r + 8) {
          if (b.on) pressButton(b.id); else { Sound.play('no'); haptic(16); }
          return;
        }
      }
      stick.claim(p);
    },
    onMove(p) { if (mini) { mini.onMove?.(p); return; } stick.move(p); },
    onUp(p) { if (mini) { mini.onUp?.(p); return; } stick.release(p); },

    update(dt) {
      if (meetingOn || over) return;
      gameT += dt;
      if (hint > 0) hint -= dt;
      if (killCool > 0) killCool -= dt;
      if (sabCool > 0) sabCool -= dt;

      // sabotage countdown
      if (sab) {
        sab.t -= dt;
        if (sab.kind === 'reactor' && sab.t <= 0) { finish(!!me.imp, 'הכור התפוצץ'); return; }
        if (sab.kind === 'lights' && sab.t <= 0) clearSabotage(null);
      }

      // --- player ---
      if (mini) {
        mini.update?.(dt);
        if (mini.done) {
          const job = mini.job;
          if (job && !job.done) {
            job.done = true; me.done++; me.todo--;
            tasksDone = Math.min(tasksTotal, tasksDone + 1);
            FX.ring(job.s.x, job.s.y, '#46f08a', 8, 60, 3, .4);
            FX.float(S.cx, S.cy, 'משימה הושלמה', '#9dffc6', 20);
          }
          Sound.play('win'); haptic(18);
          mini = null;
          checkEnd();
        }
      } else {
        let vx = stick.dx, vy = stick.dy;
        if (!stick.active) { const k = keyVec(); vx = k.x; vy = k.y; }
        step(me, vx, vy, dt, me.dead ? 165 : 140);
        // holding position on a sabotage fix point repairs it
        if (atFix() && !me.dead) {
          me.taskT += dt;
          if (me.taskT > 2) { me.taskT = 0; clearSabotage(me); }
        } else me.taskT = 0;
      }

      // --- bots (ghosts keep working their lists) ---
      for (const p of ps) {
        if (p.me) continue;
        if (p.imp && !p.dead) botImp(p, dt); else botCrew(p, dt);
      }
      tick += dt;
      if (tick > .5) { tick = 0; for (const p of ps) if (!p.dead) remember(p); }
      checkEnd();

      const cool = Math.max(0, Math.ceil(killCool));
      api.hud(
        sab ? `<span style="color:#ff8a9d">${sab.kind === 'reactor' ? 'כור' : 'אורות'} ${Math.ceil(sab.t)}</span>`
          : `משימות ${tasksDone}/${tasksTotal}`,
        me.imp ? (cool ? `חיסול בעוד ${cool}` : 'חיסול מוכן')
          : `<span style="color:#9dffc6">${me.todo} משימות שלך</span>`);
    },

    draw(c) {
      c.fillStyle = '#04050c'; c.fillRect(0, 0, S.W, S.H);
      const scale = Math.min(S.W / MAPW, S.H / MAPH) * 1.55;
      const halfW = S.W / 2 / scale, halfH = S.H / 2 / scale;
      const camX = MAPW <= halfW * 2 ? MAPW / 2 : U.clamp(me.x, halfW, MAPW - halfW);
      const camY = MAPH <= halfH * 2 ? MAPH / 2 : U.clamp(me.y, halfH, MAPH - halfH);
      const meX = S.cx + (me.x - camX) * scale, meY = S.cy + (me.y - camY) * scale;

      c.save();
      c.translate(S.cx, S.cy); c.scale(scale, scale); c.translate(-camX, -camY);

      for (const h of HALLS) {
        c.fillStyle = '#12162a'; D.rr(c, h.x, h.y, h.w, h.h, 6); c.fill();
        c.strokeStyle = 'rgba(120,160,255,.13)'; c.lineWidth = 1.4; c.stroke();
      }
      for (const r of ROOMS) {
        c.fillStyle = alpha(r.c, lightsOn() ? .32 : .16);
        D.rr(c, r.x, r.y, r.w, r.h, 10); c.fill();
        c.strokeStyle = alpha(r.c, lightsOn() ? .85 : .4); c.lineWidth = 2; c.stroke();
        c.save(); c.globalAlpha = .55;
        D.text(c, r.n, r.x + r.w / 2, r.y + 16, 13, '#dbe6ff', 'center', 800);
        c.restore();
      }

      // emergency button
      c.save(); c.translate(460, 350);
      const canEm = emergencyLeft > 0 && gameT > 15;
      c.fillStyle = canEm ? '#ff3c5f' : '#40364a';
      c.shadowBlur = canEm ? 20 : 0; c.shadowColor = '#ff3c5f';
      c.beginPath(); c.arc(0, 0, 18, 0, TAU); c.fill();
      c.restore();

      // vents (the traitor sees them highlighted)
      for (const v of VENTS) {
        c.save(); c.translate(v.x, v.y);
        c.fillStyle = me.imp ? '#3a2a5a' : '#232a42';
        D.rr(c, -14, -10, 28, 20, 4); c.fill();
        c.strokeStyle = me.imp ? '#9b5cff' : 'rgba(150,170,220,.35)'; c.lineWidth = 2;
        if (me.imp) { c.shadowBlur = 12; c.shadowColor = '#9b5cff'; }
        c.stroke(); c.shadowBlur = 0;
        c.strokeStyle = 'rgba(220,230,255,.35)'; c.lineWidth = 1.4;
        c.beginPath();
        for (let i = -1; i <= 1; i++) { c.moveTo(-10, i * 5); c.lineTo(10, i * 5); }
        c.stroke(); c.restore();
      }

      // sabotage fix point
      if (sab) {
        const f = sab.kind === 'lights' ? FIX_LIGHTS : FIX_REACTOR;
        const pulse = 1 + .18 * Math.sin(Game.t * 6);
        D.ring(c, f.x, f.y, 26 * pulse, '#ff4d6d', 3, .9);
        D.text(c, sab.kind === 'lights' ? 'תקן אורות' : 'תקן כור', f.x, f.y - 40, 13, '#ff8a9d', 'center', 900, 8);
      }

      // your own task list only
      for (const job of me.list) {
        const s = job.s, done = job.done;
        c.save(); c.globalAlpha = done ? .3 : .95;
        c.strokeStyle = done ? '#4a5170' : '#ffc63a'; c.lineWidth = 2.5;
        if (!done) { c.shadowBlur = 14; c.shadowColor = '#ffc63a'; }
        c.beginPath(); c.arc(s.x, s.y, 14 + (done ? 0 : Math.sin(Game.t * 3) * 2), 0, TAU); c.stroke();
        c.fillStyle = done ? '#4a5170' : '#ffc63a';
        c.beginPath(); c.arc(s.x, s.y, 4, 0, TAU); c.fill();
        c.restore();
      }

      for (const b of bodies) {
        c.save(); c.translate(b.x, b.y); c.rotate(.6);
        c.fillStyle = b.c; c.globalAlpha = .85; c.shadowBlur = 12; c.shadowColor = '#ff2e5f';
        D.rr(c, -16, -8, 32, 16, 8); c.fill();
        c.fillStyle = '#ff2e5f'; c.globalAlpha = .5;
        c.beginPath(); c.arc(-10, 6, 9, 0, TAU); c.fill();
        c.restore();
        D.text(c, '☠', b.x, b.y - 20, 15, '#ff6b8a', 'center', 900, 8);
      }

      FX.draw(c);

      for (const p of ps) {
        // ghosts are visible only to other ghosts
        if (p.dead && !me.dead) continue;
        if (!p.me && !p.dead && !canSee(me, p) && !me.dead) continue;
        c.save(); c.translate(p.x, p.y);
        if (p.dead) c.globalAlpha = .45;
        const bob = Math.sin(Game.t * 9 + p.i) * (Math.hypot(p.vx, p.vy) > 8 ? 1.6 : 0);
        c.translate(0, bob);
        c.fillStyle = p.c; c.shadowBlur = 14; c.shadowColor = p.c;
        D.rr(c, -11, -14, 22, 28, 10); c.fill();
        c.shadowBlur = 0; c.fillStyle = '#bfe9ff';
        D.rr(c, -3, -9, 12, 9, 4); c.fill();
        c.restore();
        c.save(); if (p.dead) c.globalAlpha = .5;
        D.text(c, p.me ? 'אתה' : p.name, p.x, p.y - 26, 11, p.me ? '#fff' : '#cbd6f0', 'center', 800, 6);
        c.restore();
        if (p.imp && (me.imp || me.dead)) D.text(c, '☠', p.x + 14, p.y - 20, 12, '#ff2e88', 'center', 900, 8);
        if (p.taskT > 0 && !p.imp) D.bar(c, p.x - 14, p.y + 18, 28, 4, p.taskT / 4, '#ffc63a', 'rgba(0,0,0,.5)');
      }
      c.restore();

      // fog of war — dims the station rather than blacking it out
      if (!me.dead) {
        const V = (lightsOn() ? VISION : DARK_VISION) * scale;
        c.save();
        const g = c.createRadialGradient(meX, meY, V * .75, meX, meY, V * 2.1);
        g.addColorStop(0, 'rgba(2,3,8,0)');
        g.addColorStop(.55, 'rgba(2,3,8,.55)');
        g.addColorStop(1, 'rgba(2,3,8,.86)');
        c.fillStyle = g; c.fillRect(0, 0, S.W, S.H); c.restore();
      }

      // reactor alarm wash
      if (sab?.kind === 'reactor') {
        c.save(); c.globalAlpha = .1 + .09 * Math.sin(Game.t * 7);
        c.fillStyle = '#ff2e5f'; c.fillRect(0, 0, S.W, S.H); c.restore();
      }

      D.bar(c, 16, S.H - 22 - S.safeB, S.W - 32, 8, tasksDone / tasksTotal, '#46f08a');
      if (!mini) stick.draw(c, me.imp ? '#ff8ab0' : '#9dffc6');

      // buttons
      layoutButtons();
      if (!mini) for (const b of buttons) {
        c.save();
        c.globalAlpha = b.on ? 1 : .38;
        c.fillStyle = b.on ? b.color : 'rgba(255,255,255,.12)';
        if (b.on) { c.shadowBlur = 20; c.shadowColor = b.color; }
        c.beginPath(); c.arc(b.x, b.y, b.r, 0, TAU); c.fill();
        c.restore();
        D.text(c, b.label, b.x, b.y + 1, b.r * .58, b.on ? '#1a1000' : '#cbd6f0', 'center', 900);
        if (b.id === 'kill' && killCool > 0)
          D.text(c, Math.ceil(killCool), b.x, b.y + 1, 20, '#fff', 'center', 900, 8);
        if (b.id === 'sabotage' && sabCool > 0)
          D.text(c, Math.ceil(sabCool), b.x, b.y + 1, 17, '#fff', 'center', 900, 8);
      }

      if (mini) { mini.draw(c); }

      if (me.dead && !mini) {
        c.save(); c.globalAlpha = .8;
        D.text(c, 'אתה רוח — סיים את המשימות שלך', S.cx, 100 + S.safeT, 16, '#9fb3c9', 'center', 800, 8);
        c.restore();
      }
      if (hint > 0 && !mini) {
        c.save(); c.globalAlpha = U.clamp(hint, 0, 1) * .9;
        D.text(c, me.imp ? 'חסל, התחבא בפירים, חבל — ואל תיתפס'
          : 'עמוד על עיגול צהוב ולחץ על ⚙ כדי לבצע משימה',
          S.cx, S.H * .17, 14.5, '#dbe8ff', 'center', 700, 10);
        c.restore();
      }
    }
  };
}
