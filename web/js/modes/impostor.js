/* ============================================================
   מצב 5 — בוגד בתחנה  (social deduction vs bots)
   7 crew, 1 traitor. Tasks, bodies, emergency meetings, voting.
   Bots remember what they saw and argue about it.
   ============================================================ */
import {
  S, U, TAU, FX, D, Sound, Save, haptic, alpha, shade, Game, Stick, keyVec
} from '../core.js';
import { UI, $ } from '../ui.js';

export const meta = {
  id: 'impostor', title: 'בוגד בתחנה', ico: '☠', color: '#ff2e88', music: 'impostor',
  desc: 'שבעה בתחנה, אחד בוגד. אולי אתה.<br>סיים משימות, מצא גופות, שכנע באספה.'
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
   wherever they intersect. The centre of that intersection is the doorway to steer through.
   Bots route room-to-room over this graph instead of walking into corners. */
const LINKS = AREAS.map(() => []);
for (let i = 0; i < AREAS.length; i++) {
  for (let j = i + 1; j < AREAS.length; j++) {
    const a = AREAS[i], b = AREAS[j];
    const x1 = Math.max(a.x, b.x), x2 = Math.min(a.x + a.w, b.x + b.w);
    const y1 = Math.max(a.y, b.y), y2 = Math.min(a.y + a.h, b.y + b.h);
    if (x2 - x1 > 12 && y2 - y1 > 12) {          // a real opening, not a shared edge
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

const TASK_SPOTS = [
  { x: 110, y: 110, r: 'מנועים' }, { x: 200, y: 170, r: 'מנועים' },
  { x: 110, y: 520, r: 'חשמל' }, { x: 200, y: 590, r: 'חשמל' },
  { x: 800, y: 110, r: 'ניווט' }, { x: 710, y: 170, r: 'ניווט' },
  { x: 800, y: 520, r: 'מעבדה' }, { x: 710, y: 590, r: 'מעבדה' },
  { x: 410, y: 610, r: 'מחסן' }, { x: 510, y: 610, r: 'מחסן' },
  { x: 410, y: 90, r: 'רפואה' }, { x: 510, y: 90, r: 'רפואה' },
  { x: 380, y: 300, r: 'קפיטריה' }, { x: 540, y: 400, r: 'קפיטריה' }
];

const CREW = [
  { n: 'ניר', c: '#21e6ff' },
  { n: 'רותם', c: '#ff4d6d' }, { n: 'עומר', c: '#46f08a' }, { n: 'שחר', c: '#ffc63a' },
  { n: 'ניצן', c: '#c46bff' }, { n: 'ליאם', c: '#ff8a3d' }, { n: 'דניאל', c: '#ffffff' }
];

const VISION = 175, DARK_VISION = 95, KILL_R = 46, REPORT_R = 80, TASK_R = 30;
const TASKS_EACH = 4;

export function create(api, opts = {}) {
  const stick = new Stick('any');
  let ps, me, bodies, tasksDone, tasksTotal, killCool, over, lights, sabT,
    meetingOn, hint, usedEmergency, phase, tick, gameT, impostor;

  const inArea = (x, y) => AREAS.some(a => x > a.x && x < a.x + a.w && y > a.y && y < a.y + a.h);
  const roomAt = (x, y) => ROOMS.find(a => x > a.x && x < a.x + a.w && y > a.y && y < a.y + a.h)?.n || 'מסדרון';
  const alive = () => ps.filter(p => !p.dead);
  const crewAlive = () => ps.filter(p => !p.dead && !p.imp);

  function reset() {
    const impIdx = Math.random() < .32 ? 0 : U.rndi(1, 7);
    ps = CREW.map((c, i) => ({
      i, name: c.n, c: c.c, x: 460 + Math.cos(i / 7 * TAU) * 70, y: 350 + Math.sin(i / 7 * TAU) * 70,
      vx: 0, vy: 0, r: 15, dead: false, imp: i === impIdx, me: i === 0,
      task: null, taskT: 0, done: 0, wait: 0, mem: [], votes: 0, sus: {}, walkT: 0
    }));
    me = ps[0]; impostor = ps[impIdx];
    // every crew member carries a personal list of TASKS_EACH jobs, so the station bar can only
    // fill if the player pulls their weight too — idling stalls it short of the goal
    for (const p of ps) { p.spot = U.pick(TASK_SPOTS); p.todo = p.imp ? 0 : TASKS_EACH; }
    me.list = U.shuffle([...TASK_SPOTS]).slice(0, TASKS_EACH).map(s => ({ s, done: false }));
    bodies = []; tasksDone = 0; killCool = 8; over = null;
    tasksTotal = ps.filter(p => !p.imp).length * TASKS_EACH;
    lights = true; sabT = 0; meetingOn = false; hint = 4; usedEmergency = false;
    gameT = 0; tick = 0;
    api.toast(me.imp ? 'אתה הבוגד!' : 'אתה איש צוות', me.imp ? '#ff2e88' : '#46f08a');
    Sound.play(me.imp ? 'alarm' : 'level');
    haptic(me.imp ? [40, 60, 40] : 20);
  }

  /* ---------------- movement ---------------- */
  function step(p, dx, dy, dt, spd = 128) {
    const nx = p.x + dx * spd * dt, ny = p.y + dy * spd * dt;
    if (inArea(nx, p.y)) p.x = nx;
    if (inArea(p.x, ny)) p.y = ny;
    p.vx = dx * spd; p.vy = dy * spd;
    if ((dx || dy) && Math.random() < dt * 6) Sound.play('step');
  }

  function canSee(a, b) {
    const d = U.dist(a.x, a.y, b.x, b.y);
    const V = lights ? VISION : DARK_VISION;
    if (d > V) return false;
    const steps = Math.ceil(d / 20);
    for (let i = 1; i < steps; i++) {
      const t = i / steps;
      if (!inArea(U.lerp(a.x, b.x, t), U.lerp(a.y, b.y, t))) return false;
    }
    return true;
  }

  /* ---------------- memory / suspicion ---------------- */
  function remember(p) {
    const seen = [];
    for (const o of ps) if (o !== p && !o.dead && canSee(p, o)) seen.push(o.i);
    p.mem.push({ t: gameT, seen, room: roomAt(p.x, p.y) });
    if (p.mem.length > 90) p.mem.shift();
  }

  function onKill(killer, victim) {
    for (const w of ps) {
      if (w.dead || w === killer) continue;
      if (canSee(w, victim) || canSee(w, killer)) {
        w.witness = w.witness || [];
        w.witness.push({ killer: killer.i, victim: victim.i, t: gameT });
        w.sus[killer.i] = (w.sus[killer.i] || 0) + 100;
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
        // alibi: continuously in sight over the last ~20s
        const recent = p.mem.filter(m => gameT - m.t < 20);
        const withMe = recent.filter(m => m.seen.includes(o.i)).length;
        if (recent.length > 4 && withMe / recent.length > .6) sus[o.i] -= 45;
        // last seen with the victim
        for (const b of bodies) {
          const around = p.mem.filter(m => Math.abs(m.t - b.t) < 12 && m.seen.includes(o.i) && m.room === b.room);
          if (around.length) sus[o.i] += 40;
        }
      }
      if (p.imp) { // the traitor frames someone innocent
        const inn = alive().filter(o => o !== p);
        const target = U.pick(inn);
        sus[target.i] = (sus[target.i] || 0) + 70;
        for (const o of ps) if (o.imp) sus[o.i] = -999;
      }
    }
  }

  /* ---------------- bot brains ---------------- */
  // next job: a random pick among the closest few spots — keeps bots busy nearby
  // while still covering the whole station over time
  function pickSpot(p) {
    const cands = TASK_SPOTS.filter(s => s !== p.spot)
      .sort((a, b) => U.dist2(p.x, p.y, a.x, a.y) - U.dist2(p.x, p.y, b.x, b.y));
    p.spot = U.pick(cands.slice(0, 4));
  }

  function botCrew(p, dt) {
    // report a body you can see
    for (const b of bodies) {
      if (!b.found && U.dist(p.x, p.y, b.x, b.y) < REPORT_R && inSight(p, b)) { report(p, b); return; }
    }
    const spot = p.spot;
    const d = U.dist(p.x, p.y, spot.x, spot.y);
    if (d < TASK_R) {
      p.taskT += dt;
      if (p.taskT > U.rnd(3.5, 6)) {
        p.taskT = 0; pickSpot(p);
        if (p.todo > 0) {            // finished bots keep wandering but stop filling the bar
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

    // while the kill is on cooldown, blend in by faking tasks
    if (killCool > 4) {
      const spot = p.spot;
      if (U.dist(p.x, p.y, spot.x, spot.y) < TASK_R) { p.taskT += dt; if (p.taskT > 3) { p.taskT = 0; pickSpot(p); } }
      else navigate(p, spot.x, spot.y, dt);
      return;
    }

    // hunting: prefer a target no third party can see. Getting impatient makes it sloppy —
    // a witnessed kill is how the traitor gets caught.
    p.frust = (p.frust || 0) + dt;
    const reckless = p.frust > 22;
    let best = null, bd = 1e9;
    for (const t of targets) {
      const watched = ps.some(w => w !== p && w !== t && !w.dead && canSee(w, t));
      if (watched && !reckless) continue;
      const d = U.dist(p.x, p.y, t.x, t.y);
      if (d < bd) { bd = d; best = t; }
    }
    if (!best) {   // everyone is covered — shadow the nearest crew member and wait
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
        // otherwise the bot arrives at the door with a zero-length heading and stalls
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
      // hug the wall toward the goal rather than stalling against it
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

  const inSight = (p, b) => {
    const steps = 8;
    for (let i = 1; i < steps; i++) {
      const t = i / steps;
      if (!inArea(U.lerp(p.x, b.x, t), U.lerp(p.y, b.y, t))) return false;
    }
    return true;
  };

  /* ---------------- actions ---------------- */
  function doKill(killer, victim) {
    victim.dead = true; killCool = 14;
    tasksTotal = Math.max(tasksDone, tasksTotal - (victim.todo || 0));
    victim.todo = 0;
    bodies.push({ x: victim.x, y: victim.y, i: victim.i, c: victim.c, t: gameT, room: roomAt(victim.x, victim.y), found: false });
    onKill(killer, victim);
    FX.burst(victim.x, victim.y, 22, '#ff2e5f', { speed: 260, life: .6, size: 4.5 });
    FX.ring(victim.x, victim.y, '#ff2e5f', 8, 90, 3, .4);
    Sound.play('kill');
    if (killer.me || victim.me) { FX.shake(12); FX.flash('#ff2e5f', .35); haptic([40, 60]); }
    if (victim.me) { api.toast('נרצחת!', '#ff6b8a'); }
    if (killer.me) api.toast('חיסלת את ' + victim.name, '#ff2e88');
    checkEnd();
  }

  function report(by, body) {
    if (meetingOn || over) return;
    if (body) body.found = true;
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
    Sound.play(win ? 'win' : 'die');
    FX.flash(win ? '#46f08a' : '#ff2e5f', .4);
    haptic(win ? [20, 40, 20] : [70]);
    const score = (win ? 500 : 100) + tasksDone * 20 + me.done * 30;
    Save.setBest('impostor', score);
    setTimeout(() => {
      UI.hideAll();
      api.end({
        ico: win ? '🏆' : '☠', title: win ? 'ניצחתם!' : 'הפסדתם',
        color: meta.color,
        stats: [['תפקיד', me.imp ? 'בוגד' : 'איש צוות'], ['סיבה', why],
        ['משימות שלך', me.done], ['משימות התחנה', `${tasksDone}/${tasksTotal}`]],
        coins: win ? 150 + me.done * 20 : 40 + me.done * 10
      });
    }, 900);
  }

  /* ---------------- meeting ---------------- */
  function openMeeting(by, body) {
    meetingOn = true; Game.paused = true;
    buildSuspicion();
    const chat = $('#meetChat'), votes = $('#meetVotes');
    $('#meetTitle').textContent = body ? 'נמצאה גופה!' : 'אספת חירום';
    chat.innerHTML = ''; votes.innerHTML = '';
    UI.hud(false); UI.show('meeting');

    const lines = [];
    const nameOf = i => ps[i].name;
    if (body) lines.push({ s: 1, txt: `${by.name} דיווח על הגופה של ${nameOf(body.i)} ב${body.room}.` });
    else lines.push({ s: 1, txt: `${by.name} לחץ על כפתור החירום.` });

    // each speaker takes a line nobody else used this meeting, so the room doesn't echo itself
    const used = new Set();
    const say = opts => {
      const fresh = opts.filter(t => !used.has(t));
      const txt = U.pick(fresh.length ? fresh : opts);
      used.add(txt); return txt;
    };

    for (const p of alive()) {
      if (p.me) continue;
      const w = (p.witness || []).find(w => !ps[w.killer].dead);
      const top = Object.entries(p.sus).filter(([i]) => !ps[+i].dead && +i !== p.i)
        .sort((a, b) => b[1] - a[1])[0];
      const where = body ? body.room : 'הקפיטריה';
      if (w) lines.push({ p, txt: `ראיתי את ${nameOf(w.killer)} הורג את ${nameOf(w.victim)}! זה הוא, בלי ספק.` });
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

    // staggered chat, then open the vote
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
      const foot = $('#meetFoot');
      const skip = $('#meetSkip');
      skip.onclick = () => { picked = 'skip'; Sound.play('vote'); votes.querySelectorAll('.vote').forEach(x => x.classList.remove('pick')); };
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
          bodies = []; killCool = Math.max(killCool, 10);
          for (const p of ps) { p.witness = []; p.sus = {}; p.mem = []; p.x = 460 + Math.cos(p.i / 7 * TAU) * 70; p.y = 350 + Math.sin(p.i / 7 * TAU) * 70; }
          checkEnd();
        }, 2600);
      }
    }
  }

  /* ---------------- scene ---------------- */
  let btnKill = { x: 0, y: 0, r: 42 }, btnAct = { x: 0, y: 0, r: 42 };

  return {
    debug: () => ({
      meImp: me.imp, impostor: impostor.name, killCool: Math.round(killCool),
      bodies: bodies.map(b => ({
        room: b.room, found: b.found,
        nearest: Math.round(Math.min(...alive().filter(p => !p.me).map(p => U.dist(p.x, p.y, b.x, b.y)))),
        sighted: alive().filter(p => !p.me).some(p => U.dist(p.x, p.y, b.x, b.y) < REPORT_R && inSight(p, b))
      })),
      tasks: tasksDone, alive: alive().map(p => p.name),
      gameT: Math.round(gameT), meetingOn, over
    }),
    enter() { reset(); },
    exit() { Game.paused = false; },
    onDown(p) {
      if (meetingOn || over || me.dead) { stick.claim(p); return; }
      if (me.imp && U.dist(p.x, p.y, btnKill.x, btnKill.y) < btnKill.r + 6) {
        const t = crewAlive().filter(o => o !== me).map(o => ({ o, d: U.dist(me.x, me.y, o.x, o.y) }))
          .sort((a, b) => a.d - b.d)[0];
        if (t && t.d < KILL_R && killCool <= 0) doKill(me, t.o);
        else { Sound.play('no'); haptic(20); }
        return;
      }
      if (U.dist(p.x, p.y, btnAct.x, btnAct.y) < btnAct.r + 6) {
        const b = bodies.find(b => U.dist(me.x, me.y, b.x, b.y) < REPORT_R);
        const atButton = U.dist(me.x, me.y, 460, 350) < 60;
        if (b) report(me, b);
        else if (atButton && !usedEmergency) { usedEmergency = true; report(me, null); }
        else { Sound.play('no'); haptic(18); }
        return;
      }
      stick.claim(p);
    },
    onMove(p) { stick.move(p); },
    onUp(p) { stick.release(p); },

    update(dt) {
      if (meetingOn || over) return;
      gameT += dt;
      if (hint > 0) hint -= dt;
      if (killCool > 0) killCool -= dt;
      if (sabT > 0) { sabT -= dt; if (sabT <= 0) { lights = true; api.toast('האורות חזרו', '#9dffc6'); } }

      // player
      if (!me.dead) {
        let vx = stick.dx, vy = stick.dy;
        if (!stick.active) { const k = keyVec(); vx = k.x; vy = k.y; }
        step(me, vx, vy, dt, 140);
        // player task progress (crew only)
        if (!me.imp) {
          const job = me.list.find(j => !j.done && U.dist(me.x, me.y, j.s.x, j.s.y) < TASK_R);
          if (job) {
            me.taskT += dt;
            if (me.taskT > 1.8) {
              me.taskT = 0; job.done = true; me.done++; me.todo--;
              tasksDone = Math.min(tasksTotal, tasksDone + 1);
              Sound.play('lock'); haptic(14);
              FX.ring(job.s.x, job.s.y, '#46f08a', 8, 60, 3, .4);
              FX.float(job.s.x, job.s.y - 20, 'משימה הושלמה', '#9dffc6', 15);
              checkEnd();
            }
          } else me.taskT = 0;
        }
      }

      // bots
      tick += dt;
      for (const p of ps) {
        if (p.dead || p.me) continue;
        if (p.imp) botImp(p, dt); else botCrew(p, dt);
      }
      if (tick > .5) { tick = 0; for (const p of ps) if (!p.dead) remember(p); }
      checkEnd();

      const cool = Math.max(0, Math.ceil(killCool));
      api.hud(
        me.imp ? `<span style="color:#ff2e88">בוגד</span>` : `משימות ${tasksDone}/${tasksTotal}`,
        me.imp ? (cool ? `חיסול בעוד ${cool}` : 'חיסול מוכן')
               : `<span style="color:#9dffc6">${me.todo} משימות שלך</span>`);
    },

    draw(c) {
      c.fillStyle = '#04050c'; c.fillRect(0, 0, S.W, S.H);
      const scale = Math.min(S.W / MAPW, S.H / MAPH) * 1.55;
      // when an axis of the station fits on screen, centre it — clamping would invert
      const halfW = S.W / 2 / scale, halfH = S.H / 2 / scale;
      const camX = MAPW <= halfW * 2 ? MAPW / 2 : U.clamp(me.x, halfW, MAPW - halfW);
      const camY = MAPH <= halfH * 2 ? MAPH / 2 : U.clamp(me.y, halfH, MAPH - halfH);
      const meX = S.cx + (me.x - camX) * scale, meY = S.cy + (me.y - camY) * scale;
      c.save();
      c.translate(S.cx, S.cy); c.scale(scale, scale); c.translate(-camX, -camY);

      // floors
      for (const h of HALLS) {
        c.fillStyle = '#12162a'; D.rr(c, h.x, h.y, h.w, h.h, 6); c.fill();
        c.strokeStyle = 'rgba(120,160,255,.13)'; c.lineWidth = 1.4; c.stroke();
      }
      for (const r of ROOMS) {
        c.fillStyle = alpha(r.c, .32); D.rr(c, r.x, r.y, r.w, r.h, 10); c.fill();
        c.strokeStyle = alpha(r.c, .85); c.lineWidth = 2; c.stroke();
        c.save(); c.globalAlpha = .55;
        D.text(c, r.n, r.x + r.w / 2, r.y + 16, 13, '#dbe6ff', 'center', 800);
        c.restore();
      }

      // emergency button
      c.save(); c.translate(460, 350);
      c.fillStyle = usedEmergency ? '#40364a' : '#ff3c5f';
      c.shadowBlur = usedEmergency ? 0 : 20; c.shadowColor = '#ff3c5f';
      c.beginPath(); c.arc(0, 0, 18, 0, TAU); c.fill();
      c.restore();

      // tasks (crew only see them)
      if (!me.imp) for (const job of me.list) {
        const s = job.s, done = job.done;
        c.save(); c.globalAlpha = done ? .3 : .95;
        c.strokeStyle = done ? '#4a5170' : '#ffc63a'; c.lineWidth = 2.5;
        if (!done) { c.shadowBlur = 14; c.shadowColor = '#ffc63a'; }
        c.beginPath(); c.arc(s.x, s.y, 14 + (done ? 0 : Math.sin(Game.t * 3) * 2), 0, TAU); c.stroke();
        c.fillStyle = done ? '#4a5170' : '#ffc63a';
        c.beginPath(); c.arc(s.x, s.y, 4, 0, TAU); c.fill();
        c.restore();
      }

      // bodies
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

      // crew
      for (const p of ps) {
        if (p.dead) continue;
        const vis = p.me || canSee(me, p) || me.dead;
        if (!vis) continue;
        c.save(); c.translate(p.x, p.y);
        const bob = Math.sin(Game.t * 9 + p.i) * (Math.hypot(p.vx, p.vy) > 8 ? 1.6 : 0);
        c.translate(0, bob);
        // body
        c.fillStyle = p.c; c.shadowBlur = 14; c.shadowColor = p.c;
        D.rr(c, -11, -14, 22, 28, 10); c.fill();
        // visor
        c.shadowBlur = 0; c.fillStyle = '#bfe9ff';
        D.rr(c, -3, -9, 12, 9, 4); c.fill();
        c.restore();
        D.text(c, p.me ? 'אתה' : p.name, p.x, p.y - 26, 11, p.me ? '#fff' : '#cbd6f0', 'center', 800, 6);
        if (p.imp && (me.imp || me.dead)) D.text(c, '☠', p.x + 14, p.y - 20, 12, '#ff2e88', 'center', 900, 8);
        if (p.taskT > 0 && !p.imp) D.bar(c, p.x - 14, p.y + 18, 28, 4, p.taskT / 1.8, '#ffc63a', 'rgba(0,0,0,.5)');
      }
      c.restore();

      // fog of war — dims the station rather than blacking it out, so you can still navigate
      if (!me.dead) {
        const V = (lights ? VISION : DARK_VISION) * scale;
        c.save();
        const g = c.createRadialGradient(meX, meY, V * .75, meX, meY, V * 2.1);
        g.addColorStop(0, 'rgba(2,3,8,0)');
        g.addColorStop(.55, 'rgba(2,3,8,.55)');
        g.addColorStop(1, 'rgba(2,3,8,.86)');
        c.fillStyle = g; c.fillRect(0, 0, S.W, S.H); c.restore();
      }

      // ---- HUD ----
      D.bar(c, 16, S.H - 22 - S.safeB, S.W - 32, 8, tasksDone / tasksTotal, '#46f08a');
      stick.draw(c, me.imp ? '#ff8ab0' : '#9dffc6');

      // action buttons
      btnAct.x = S.W - 66; btnAct.y = S.H - 100 - S.safeB;
      const bodyNear = bodies.some(b => U.dist(me.x, me.y, b.x, b.y) < REPORT_R);
      const atBtn = U.dist(me.x, me.y, 460, 350) < 60 && !usedEmergency;
      const actOn = (bodyNear || atBtn) && !me.dead;
      c.save(); c.globalAlpha = actOn ? 1 : .4;
      c.fillStyle = actOn ? '#ffc63a' : 'rgba(255,255,255,.12)';
      if (actOn) { c.shadowBlur = 20; c.shadowColor = '#ffc63a'; }
      c.beginPath(); c.arc(btnAct.x, btnAct.y, btnAct.r, 0, TAU); c.fill(); c.restore();
      D.text(c, bodyNear ? '☠' : '!', btnAct.x, btnAct.y, 24, actOn ? '#2a1d00' : '#cbd6f0', 'center', 900);

      if (me.imp && !me.dead) {
        btnKill.x = S.W - 66; btnKill.y = S.H - 196 - S.safeB;
        const t = crewAlive().filter(o => o !== me).map(o => U.dist(me.x, me.y, o.x, o.y)).sort((a, b) => a - b)[0];
        const ready = killCool <= 0 && t !== undefined && t < KILL_R;
        c.save(); c.globalAlpha = ready ? 1 : .45;
        c.fillStyle = ready ? '#ff2e5f' : 'rgba(255,255,255,.12)';
        if (ready) { c.shadowBlur = 22; c.shadowColor = '#ff2e5f'; }
        c.beginPath(); c.arc(btnKill.x, btnKill.y, btnKill.r, 0, TAU); c.fill(); c.restore();
        D.text(c, '🔪', btnKill.x, btnKill.y, 26, '#fff', 'center', 900);
        if (killCool > 0)
          D.text(c, Math.ceil(killCool), btnKill.x, btnKill.y + 2, 20, '#fff', 'center', 900, 8);
      }

      if (me.dead) {
        c.save(); c.globalAlpha = .8;
        D.text(c, 'אתה רוח — צפה עד הסוף', S.cx, 100 + S.safeT, 17, '#9fb3c9', 'center', 800, 8);
        c.restore();
      }
      if (hint > 0) {
        c.save(); c.globalAlpha = U.clamp(hint, 0, 1) * .9;
        D.text(c, me.imp ? 'תפוס אותם לבד — ואל תיתפס' : 'עמוד על העיגולים הצהובים כדי לבצע משימה',
          S.cx, S.H * .17, 15, '#dbe8ff', 'center', 700, 10);
        c.restore();
      }
    }
  };
}
