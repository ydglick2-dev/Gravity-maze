/* ============================================================
   מצב 4 — זירת כוכבים  (3v3 gem-grab brawl vs bots)
   Left half = move. Right half = aim & shoot. Charge your super.
   ============================================================ */
import {
  S, U, TAU, FX, D, Sound, Save, haptic, skinColor, alpha, shade,
  Game, Input, Stick, keyVec
} from '../core.js';
import { UI } from '../ui.js';

export const meta = {
  id: 'arena', title: 'זירת כוכבים', ico: '✹', color: '#9b5cff', music: 'arena',
  desc: 'שלושה נגד שלושה. אספו 10 גבישים והחזיקו 15 שניות.<br>חצי מסך שמאל = תנועה, ימין = כיוון וירי.'
};

export const CHARS = [
  {
    id: 'boom', n: 'רועם', i: '💥', c: '#ff8a3d', hp: 220, spd: 205, range: 250,
    pellets: 5, spread: .42, dmg: 26, bspd: 620, reload: 1.5, sup: 'דחיפה',
    desc: 'רסס קצר טווח, הרבה חיים. סופר: הסתערות הודפת.'
  },
  {
    id: 'snipe', n: 'צליפה', i: '🎯', c: '#21e6ff', hp: 130, spd: 225, range: 620,
    pellets: 1, spread: 0, dmg: 90, bspd: 1000, reload: 1.9, sup: 'קרן',
    desc: 'ירייה אחת חזקה מרחוק. סופר: קרן חודרת.'
  },
  {
    id: 'bomb', n: 'מרסס', i: '☄', c: '#46f08a', hp: 170, spd: 215, range: 380,
    pellets: 1, spread: 0, dmg: 60, bspd: 430, reload: 1.7, lob: true, sup: 'מטח',
    desc: 'פצצות מתפוצצות מעל מכשולים. סופר: מטח משולש.'
  }
];

const W = 1180, H = 1180;      // arena size
const GOAL = 10, HOLD = 15;

export function intro(api, start) {
  let sel = 0;
  const cards = CHARS.map((ch, i) => `<button class="chip ${i === 0 ? 'on' : ''}" data-ch="${i}">${ch.i} ${ch.n}</button>`).join('');
  UI.intro({
    ico: meta.ico, title: meta.title, color: meta.color,
    text: meta.desc,
    extraHTML: `<div class="chiprow">${cards}</div><p class="muted" id="chDesc">${CHARS[0].desc}</p>`,
    playLabel: 'לקרב',
    onPlay: () => start({ char: sel }),
    onMount(el) {
      el.querySelectorAll('.chip').forEach(b => b.onclick = () => {
        sel = +b.dataset.ch;
        el.querySelectorAll('.chip').forEach(x => x.classList.toggle('on', x === b));
        el.querySelector('#chDesc').textContent = CHARS[sel].desc;
        Sound.play('click'); haptic(8);
      });
    }
  });
}

export function create(api, opts = {}) {
  const move = new Stick('left'), aim = new Stick('right');
  let units, bullets, gems, walls, cam, me, teamGems, holdT, over, matchT, respawnQ, hint, supBtn;

  const NAMES = [['ניצן', 'רותם', 'שחר'], ['עומר', 'ליאם', 'דניאל']];

  function reset() {
    walls = buildMap();
    units = []; bullets = []; gems = [];
    teamGems = [0, 0]; holdT = [0, 0]; over = null; matchT = 180; hint = 3.4;
    respawnQ = [];

    const chIdx = opts.char ?? 0;
    for (let t = 0; t < 2; t++) {
      for (let i = 0; i < 3; i++) {
        const ch = CHARS[t === 0 && i === 0 ? chIdx : U.rndi(0, CHARS.length)];
        const u = {
          team: t, ch, name: t === 0 && i === 0 ? 'אתה' : NAMES[t][i],
          x: t === 0 ? 90 : W - 90, y: H / 2 + (i - 1) * 130,
          vx: 0, vy: 0, r: 17, hp: ch.hp, maxHp: ch.hp, ammo: 3, ammoT: 0,
          cool: 0, sup: 0, gems: 0, dead: false, resp: 0, aim: t === 0 ? 0 : Math.PI,
          isMe: t === 0 && i === 0, hurt: 0, brain: { t: 0, mode: 'gem', tx: 0, ty: 0, strafe: 1 }
        };
        u.hx = u.x; u.hy = u.y;    // home / spawn
        units.push(u);
        if (u.isMe) me = u;
      }
    }
    cam = { x: me.x, y: me.y };
    supBtn = { x: 0, y: 0, r: 40 };
  }

  function buildMap() {
    const w = [];
    const add = (x, y, ww, hh) => w.push({ x, y, w: ww, h: hh });
    // symmetric cover
    add(W / 2 - 24, 130, 48, 210);
    add(W / 2 - 24, H - 340, 48, 210);
    add(300, H / 2 - 24, 200, 48);
    add(W - 500, H / 2 - 24, 200, 48);
    add(250, 250, 90, 90); add(W - 340, 250, 90, 90);
    add(250, H - 340, 90, 90); add(W - 340, H - 340, 90, 90);
    add(W / 2 - 150, H / 2 - 150, 60, 60); add(W / 2 + 90, H / 2 - 150, 60, 60);
    add(W / 2 - 150, H / 2 + 90, 60, 60); add(W / 2 + 90, H / 2 + 90, 60, 60);
    return w;
  }

  const inWall = (x, y, pad = 0) => walls.some(w =>
    x > w.x - pad && x < w.x + w.w + pad && y > w.y - pad && y < w.y + w.h + pad);

  function los(x1, y1, x2, y2) {
    const steps = Math.ceil(U.dist(x1, y1, x2, y2) / 22);
    for (let i = 1; i < steps; i++) {
      const t = i / steps;
      if (inWall(U.lerp(x1, x2, t), U.lerp(y1, y2, t))) return false;
    }
    return true;
  }

  function moveUnit(u, dx, dy, dt) {
    const nx = u.x + dx * dt, ny = u.y + dy * dt;
    if (!inWall(nx, u.y, u.r)) u.x = U.clamp(nx, u.r, W - u.r);
    if (!inWall(u.x, ny, u.r)) u.y = U.clamp(ny, u.r, H - u.r);
  }

  /* ---------------- combat ---------------- */
  function shoot(u, ang) {
    if (u.ammo < 1 || u.cool > 0 || u.dead) return;
    u.ammo--; u.cool = .34; u.aim = ang;
    const ch = u.ch;
    for (let i = 0; i < ch.pellets; i++) {
      const a = ang + (ch.pellets > 1 ? U.lerp(-ch.spread, ch.spread, i / (ch.pellets - 1)) : 0);
      bullets.push({
        x: u.x + Math.cos(a) * 20, y: u.y + Math.sin(a) * 20,
        vx: Math.cos(a) * ch.bspd, vy: Math.sin(a) * ch.bspd,
        dmg: ch.dmg, team: u.team, owner: u, r: ch.lob ? 9 : 6,
        life: ch.range / ch.bspd, lob: ch.lob, c: ch.c, pierce: false
      });
    }
    Sound.play('shoot');
    if (u.isMe) haptic(6);
    FX.burst(u.x + Math.cos(ang) * 20, u.y + Math.sin(ang) * 20, 4, ch.c,
      { speed: 120, life: .2, size: 3, dir: ang, spread: .5 });
  }

  function useSuper(u) {
    if (u.sup < 100 || u.dead) return;
    u.sup = 0;
    Sound.play('super'); FX.flash(u.ch.c, .22);
    if (u.isMe) { haptic([20, 30, 20]); FX.shake(7); }
    const a = u.aim;
    if (u.ch.id === 'boom') {
      u.dashT = .32; u.dashA = a;
      FX.ring(u.x, u.y, u.ch.c, 12, 130, 5, .45);
    } else if (u.ch.id === 'snipe') {
      bullets.push({
        x: u.x, y: u.y, vx: Math.cos(a) * 1300, vy: Math.sin(a) * 1300,
        dmg: 140, team: u.team, owner: u, r: 11, life: .9, c: '#7ff0ff', pierce: true, big: true
      });
    } else {
      for (let i = -1; i <= 1; i++) {
        const aa = a + i * .28;
        bullets.push({
          x: u.x, y: u.y, vx: Math.cos(aa) * 430, vy: Math.sin(aa) * 430,
          dmg: 70, team: u.team, owner: u, r: 10, life: .9, lob: true, c: '#9dffc6', big: true
        });
      }
    }
    FX.ring(u.x, u.y, u.ch.c, 10, 90, 4, .35);
  }

  function boom(x, y, dmg, team, owner, R = 62) {
    FX.ring(x, y, '#ffb347', 8, R * 1.8, 4, .35);
    FX.burst(x, y, 16, '#ffb347', { speed: 260, life: .5, size: 4.5 });
    Sound.play('boom');
    for (const u of units) {
      if (u.dead || u.team === team) continue;
      if (U.dist2(x, y, u.x, u.y) < R * R) hurt(u, dmg, owner, x, y);
    }
  }

  function hurt(u, dmg, from, bx, by) {
    if (u.dead) return;
    u.hp -= dmg; u.hurt = .12;
    FX.burst(bx, by, 6, '#ffd0d8', { speed: 150, life: .3, size: 3 });
    if (from && !from.dead) from.sup = Math.min(100, from.sup + dmg * .38);
    if (u.isMe) { FX.shake(5); haptic(16); Sound.play('hurt'); }
    else Sound.play('hit');
    if (u.hp <= 0) kill(u, from);
  }

  function kill(u, by) {
    u.dead = true; u.resp = 3;
    FX.burst(u.x, u.y, 26, u.ch.c, { speed: 320, life: .7, size: 5 });
    FX.ring(u.x, u.y, u.ch.c, 10, 120, 4, .45);
    Sound.play(u.isMe ? 'die' : 'kill');
    if (u.isMe) { FX.shake(14); FX.flash('#ff2e5f', .35); haptic([30, 50]); api.toast('הודחת!', '#ff6b8a'); }
    else if (by?.isMe) { api.toast('הדחת את ' + u.name + '!', '#9dffc6'); FX.stop(.05); }
    // drop the gems
    for (let i = 0; i < u.gems; i++)
      gems.push({ x: u.x + U.rnd(-30, 30), y: u.y + U.rnd(-30, 30), vx: U.rnd(-60, 60), vy: U.rnd(-60, 60), ph: U.rnd(TAU), t: .5 });
    teamGems[u.team] -= u.gems; u.gems = 0;
    if (teamGems[u.team] < GOAL) holdT[u.team] = 0;
  }

  /* ---------------- bot brain ---------------- */
  function think(u, dt) {
    const b = u.brain;
    b.t -= dt;
    const foes = units.filter(o => !o.dead && o.team !== u.team);
    const near = foes.map(o => ({ o, d: U.dist(u.x, u.y, o.x, o.y) })).sort((a, z) => a.d - z.d)[0];
    const lowHp = u.hp / u.maxHp < .32;

    // shoot whenever a foe is visible and in range
    if (near && near.d < u.ch.range * .95 && los(u.x, u.y, near.o.x, near.o.y)) {
      const lead = near.d / u.ch.bspd;
      const tx = near.o.x + near.o.vx * lead, ty = near.o.y + near.o.vy * lead;
      const a = U.ang(u.x, u.y, tx, ty) + U.rnd(-.09, .09);
      if (u.ammo >= 1) shoot(u, a);
      if (u.sup >= 100 && near.d < u.ch.range * .7) useSuper(u);
    }

    if (b.t <= 0) {
      b.t = U.rnd(.35, .8);
      b.strafe = Math.random() < .5 ? 1 : -1;
      const loose = gems.filter(g => g.t <= 0);
      const myTeamWinning = teamGems[u.team] >= GOAL;

      if (lowHp && near && near.d < 320) { b.mode = 'flee'; b.tx = u.hx; b.ty = u.hy; }
      else if (loose.length && !myTeamWinning) {
        const g = loose.map(g => ({ g, d: U.dist(u.x, u.y, g.x, g.y) })).sort((a, z) => a.d - z.d)[0];
        b.mode = 'gem'; b.tx = g.g.x; b.ty = g.g.y;
      }
      else if (myTeamWinning && u.gems > 0) { b.mode = 'hold'; b.tx = u.hx; b.ty = u.hy; }
      else if (near) { b.mode = 'fight'; b.tx = near.o.x; b.ty = near.o.y; }
      else { b.mode = 'gem'; b.tx = W / 2 + U.rnd(-90, 90); b.ty = H / 2 + U.rnd(-90, 90); }
    }

    let ax = b.tx - u.x, ay = b.ty - u.y;
    const d = Math.hypot(ax, ay) || 1;
    ax /= d; ay /= d;
    if (b.mode === 'fight' && near) {
      const ideal = u.ch.range * .62;
      const k = near.d < ideal ? -1 : 1;
      ax *= k; ay *= k;
      // strafe so bots don't stand still trading shots
      ax += -ay * .8 * b.strafe; ay += ax * .8 * b.strafe;
    }
    if (b.mode === 'flee') { }
    // nudge around walls
    if (inWall(u.x + ax * 34, u.y + ay * 34, u.r)) {
      const t = Math.atan2(ay, ax) + (b.strafe > 0 ? 1.1 : -1.1);
      ax = Math.cos(t); ay = Math.sin(t);
    }
    const m = Math.hypot(ax, ay) || 1;
    moveUnit(u, ax / m * u.ch.spd, ay / m * u.ch.spd, dt);
    u.vx = ax / m * u.ch.spd; u.vy = ay / m * u.ch.spd;
    if (near) u.aim = U.ang(u.x, u.y, near.o.x, near.o.y);
  }

  /* ---------------- end ---------------- */
  function finish(win) {
    if (over) return;
    over = win;
    Sound.play(win ? 'win' : 'die');
    FX.flash(win ? '#46f08a' : '#ff2e5f', .4); haptic(win ? [20, 40, 20, 40] : [60]);
    const score = teamGems[0] * 20 + (win ? 300 : 0);
    Save.setBest('arena', score);
    setTimeout(() => api.end({
      ico: win ? '🏆' : '💀',
      title: win ? 'ניצחון!' : 'הפסד',
      color: meta.color,
      stats: [['גבישי הקבוצה', teamGems[0]], ['גבישים של היריב', teamGems[1]],
      ['הגבישים שלך', me.gems], ['דמות', me.ch.n]],
      coins: win ? 120 + teamGems[0] * 8 : 30 + teamGems[0] * 4
    }), 1100);
  }

  /* ---------------- scene ---------------- */
  let gemT = 0;
  return {
    enter() { reset(); },
    onDown(p) {
      if (over) return;
      // super button first
      if (U.dist(p.x, p.y, supBtn.x, supBtn.y) < supBtn.r + 8) {
        if (me.sup >= 100) useSuper(me); else { Sound.play('no'); haptic(20); }
        return;
      }
      if (!move.claim(p)) aim.claim(p);
    },
    onMove(p) { move.move(p); aim.move(p); },
    onUp(p) {
      if (aim.id === p.id && aim.mag < .12 && !me.dead) {
        // quick tap on the right = snap-shot at the closest visible foe
        const foes = units.filter(o => !o.dead && o.team !== me.team)
          .map(o => ({ o, d: U.dist(me.x, me.y, o.x, o.y) })).sort((a, z) => a.d - z.d)[0];
        if (foes) shoot(me, U.ang(me.x, me.y, foes.o.x, foes.o.y));
        else shoot(me, me.aim);
      }
      move.release(p); aim.release(p);
    },
    onKey(code) { if (code === 'Space') useSuper(me); },

    update(dt) {
      if (over) return;
      matchT -= dt;
      if (hint > 0) hint -= dt;

      // gem spawner
      gemT -= dt;
      if (gemT <= 0 && gems.length < 14) {
        gemT = 2.4;
        gems.push({ x: W / 2, y: H / 2, vx: U.rnd(-110, 110), vy: U.rnd(-110, 110), ph: 0, t: .6 });
        Sound.tone(880, .08, 'triangle', .16);
      }

      // ---- player ----
      if (!me.dead) {
        let vx = move.dx, vy = move.dy, mag = move.mag;
        if (!move.active) { const k = keyVec(); vx = k.x; vy = k.y; mag = k.mag; }
        if (me.dashT > 0) {
          me.dashT -= dt;
          moveUnit(me, Math.cos(me.dashA) * 900, Math.sin(me.dashA) * 900, dt);
          FX.trail(me.x, me.y, me.ch.c, 7, .3);
          for (const o of units) if (!o.dead && o.team !== me.team && U.dist2(me.x, me.y, o.x, o.y) < 2500) {
            hurt(o, 40, me, o.x, o.y);
            o.x += Math.cos(me.dashA) * 40; o.y += Math.sin(me.dashA) * 40;
          }
        } else {
          moveUnit(me, vx * me.ch.spd, vy * me.ch.spd, dt);
          me.vx = vx * me.ch.spd; me.vy = vy * me.ch.spd;
        }
        if (aim.active && aim.mag > .12) {
          me.aim = Math.atan2(aim.dy, aim.dx);
          shoot(me, me.aim);
        }
      }

      // ---- units ----
      for (const u of units) {
        if (u.hurt > 0) u.hurt -= dt;
        if (u.cool > 0) u.cool -= dt;
        if (u.dashT > 0 && !u.isMe) u.dashT -= dt;
        if (u.ammo < 3) { u.ammoT += dt; if (u.ammoT >= u.ch.reload) { u.ammoT = 0; u.ammo++; if (u.isMe) Sound.tone(660, .05, 'sine', .12); } }
        if (u.dead) {
          u.resp -= dt;
          if (u.resp <= 0) {
            u.dead = false; u.hp = u.maxHp; u.ammo = 3; u.x = u.hx; u.y = u.hy;
            FX.ring(u.x, u.y, u.ch.c, 6, 90, 3, .4);
            if (u.isMe) api.toast('חזרת!', '#9dffc6');
          }
          continue;
        }
        if (!u.isMe) think(u, dt);

        // pick up gems
        for (let i = gems.length - 1; i >= 0; i--) {
          const g = gems[i];
          if (g.t > 0) continue;
          if (U.dist2(u.x, u.y, g.x, g.y) < (u.r + 16) ** 2) {
            gems.splice(i, 1); u.gems++; teamGems[u.team]++;
            Sound.play('gem');
            if (u.isMe) { haptic(10); FX.float(u.x, u.y - 28, '+1', '#c9a3ff', 17); }
            FX.burst(u.x, u.y, 7, '#c9a3ff', { speed: 130, life: .35, size: 3 });
          }
        }
      }

      // ---- gems physics ----
      for (const g of gems) {
        if (g.t > 0) g.t -= dt;
        g.ph += dt * 3;
        g.x += g.vx * dt; g.y += g.vy * dt;
        g.vx *= Math.pow(.02, dt); g.vy *= Math.pow(.02, dt);
        g.x = U.clamp(g.x, 20, W - 20); g.y = U.clamp(g.y, 20, H - 20);
      }

      // ---- bullets ----
      for (let i = bullets.length - 1; i >= 0; i--) {
        const b = bullets[i];
        b.x += b.vx * dt; b.y += b.vy * dt; b.life -= dt;
        if (Math.random() < dt * 30) FX.trail(b.x, b.y, b.c, b.big ? 5 : 3, .2);
        let gone = b.life <= 0;
        if (!gone && !b.lob && inWall(b.x, b.y)) {
          gone = true;
          FX.burst(b.x, b.y, 5, '#9fb3c9', { speed: 110, life: .25, size: 2.6 });
        }
        if (!gone) {
          for (const u of units) {
            if (u.dead || u.team === b.team) continue;
            if (U.dist2(b.x, b.y, u.x, u.y) < (u.r + b.r) ** 2) {
              if (b.lob) boom(b.x, b.y, b.dmg, b.team, b.owner);
              else hurt(u, b.dmg, b.owner, b.x, b.y);
              if (!b.pierce) { gone = true; }
              break;
            }
          }
        }
        if (gone) {
          if (b.lob && b.life <= 0) boom(b.x, b.y, b.dmg, b.team, b.owner);
          bullets.splice(i, 1);
        }
      }

      // ---- score / win ----
      for (let t = 0; t < 2; t++) {
        if (teamGems[t] >= GOAL) {
          holdT[t] += dt;
          if (holdT[t] >= HOLD) finish(t === 0);
        } else holdT[t] = 0;
      }
      if (matchT <= 0) finish(teamGems[0] > teamGems[1]);

      cam.x = U.damp(cam.x, me.x, 7, dt);
      cam.y = U.damp(cam.y, me.y, 7, dt);
      api.hud(
        `<span style="color:#9dffc6">${teamGems[0]}</span> — <span style="color:#ff8a9d">${teamGems[1]}</span>`,
        `<span style="opacity:.8">${Math.max(0, Math.ceil(matchT))}״</span>`);
    },

    draw(c) {
      c.fillStyle = '#070510'; c.fillRect(0, 0, S.W, S.H);
      const ox = W <= S.W ? (W - S.W) / 2 : U.clamp(cam.x, S.W / 2, W - S.W / 2) - S.cx;
      const oy = H <= S.H ? (H - S.H) / 2 : U.clamp(cam.y, S.H / 2, H - S.H / 2) - S.cy;
      c.save(); c.translate(-ox, -oy);

      // floor
      const g = c.createRadialGradient(W / 2, H / 2, 100, W / 2, H / 2, W * .75);
      g.addColorStop(0, '#191233'); g.addColorStop(1, '#0a0718');
      c.fillStyle = g; c.fillRect(0, 0, W, H);
      c.strokeStyle = 'rgba(155,92,255,.09)'; c.lineWidth = 1; c.beginPath();
      for (let x = 0; x <= W; x += 60) { c.moveTo(x, 0); c.lineTo(x, H); }
      for (let y = 0; y <= H; y += 60) { c.moveTo(0, y); c.lineTo(W, y); }
      c.stroke();
      // borders + spawn pads
      c.strokeStyle = alpha('#9b5cff', .6); c.lineWidth = 4;
      c.shadowBlur = 22; c.shadowColor = '#9b5cff'; c.strokeRect(0, 0, W, H); c.shadowBlur = 0;
      D.ring(c, 90, H / 2, 60, '#46f08a', 2, .35);
      D.ring(c, W - 90, H / 2, 60, '#ff4d6d', 2, .35);
      D.ring(c, W / 2, H / 2, 46 + Math.sin(Game.t * 2) * 4, '#c9a3ff', 2, .5);

      // walls
      for (const w of walls) {
        c.save(); c.fillStyle = '#2b2450';
        D.rr(c, w.x, w.y, w.w, w.h, 8); c.fill();
        c.strokeStyle = alpha('#9b5cff', .55); c.lineWidth = 2; c.stroke();
        c.fillStyle = 'rgba(255,255,255,.05)';
        D.rr(c, w.x + 4, w.y + 4, w.w - 8, w.h * .35, 6); c.fill();
        c.restore();
      }

      // gems
      for (const gm of gems) {
        const s = 11 + Math.sin(gm.ph) * 1.6;
        c.save(); c.translate(gm.x, gm.y); c.rotate(gm.ph * .5);
        c.fillStyle = '#c9a3ff'; c.shadowBlur = 20; c.shadowColor = '#9b5cff';
        c.globalAlpha = gm.t > 0 ? .5 : 1;
        c.beginPath();
        c.moveTo(0, -s); c.lineTo(s * .8, 0); c.lineTo(0, s); c.lineTo(-s * .8, 0);
        c.closePath(); c.fill(); c.restore();
      }

      // bullets
      for (const b of bullets) {
        c.save(); c.translate(b.x, b.y);
        if (b.lob) { D.glowCircle(c, 0, 0, b.r, b.c, .95); }
        else {
          c.rotate(Math.atan2(b.vy, b.vx));
          c.fillStyle = b.c; c.shadowBlur = 16; c.shadowColor = b.c;
          D.rr(c, -b.r * 1.6, -b.r * .5, b.r * 3.2, b.r, b.r * .5); c.fill();
        }
        c.restore();
      }

      FX.draw(c);

      // units
      for (const u of units) {
        if (u.dead) {
          c.save(); c.globalAlpha = .35;
          D.text(c, Math.ceil(u.resp), u.x, u.y, 22, u.team === 0 ? '#9dffc6' : '#ff8a9d');
          c.restore(); continue;
        }
        const col = u.hurt > 0 ? '#fff' : u.ch.c;
        // team ring
        D.ring(c, u.x, u.y, u.r + 7, u.team === 0 ? '#46f08a' : '#ff4d6d', 2.5, .85);
        D.orb(c, u.x, u.y, u.r, u.isMe ? skinColor(Game.t) : col, 1);
        // barrel
        c.save(); c.translate(u.x, u.y); c.rotate(u.aim);
        c.fillStyle = shade(col, .3); c.shadowBlur = 8; c.shadowColor = col;
        D.rr(c, 10, -4, 18, 8, 3); c.fill(); c.restore();
        // hp + gems
        D.bar(c, u.x - 24, u.y - u.r - 16, 48, 5, u.hp / u.maxHp, u.team === 0 ? '#46f08a' : '#ff4d6d', 'rgba(0,0,0,.55)');
        if (u.gems) {
          D.text(c, '◆' + u.gems, u.x, u.y - u.r - 27, 13, '#c9a3ff', 'center', 900, 8);
        }
        if (u.isMe) D.text(c, '▼', u.x, u.y - u.r - 34, 14, '#fff', 'center', 900, 6);
        if (u.sup >= 100) D.ring(c, u.x, u.y, u.r + 13 + Math.sin(Game.t * 6) * 2, '#ffe14d', 2, .8);
      }
      c.restore();

      // ---------- HUD ----------
      // score bar
      const bw = Math.min(S.W - 60, 300), bx = S.cx - bw / 2, by = 62 + S.safeT;
      c.save();
      c.fillStyle = 'rgba(8,6,18,.6)'; D.rr(c, bx, by, bw, 26, 13); c.fill();
      // RTL: your team fills from the right, the enemy from the left
      for (let t = 0; t < 2; t++) {
        const wpx = Math.max(4, bw / 2 * U.clamp(teamGems[t] / GOAL, 0, 1));
        c.fillStyle = t === 0 ? '#46f08a' : '#ff4d6d';
        c.globalAlpha = .85;
        if (t === 0) D.rr(c, bx + bw - wpx, by, wpx, 26, 13);
        else D.rr(c, bx, by, wpx, 26, 13);
        c.fill();
      }
      c.globalAlpha = 1;
      D.text(c, `${teamGems[0]}`, bx + bw - 22, by + 13, 15, '#04120a', 'center', 900);
      D.text(c, `${teamGems[1]}`, bx + 22, by + 13, 15, '#170406', 'center', 900);
      D.text(c, `${GOAL}`, S.cx, by + 13, 13, '#cbd6f0', 'center', 800);
      c.restore();

      // countdown when a team is holding the goal
      for (let t = 0; t < 2; t++) if (teamGems[t] >= GOAL) {
        const left = Math.ceil(HOLD - holdT[t]);
        D.text(c, (t === 0 ? 'ניצחון בעוד ' : 'הפסד בעוד ') + left, S.cx, by + 52, 20,
          t === 0 ? '#9dffc6' : '#ff8a9d', 'center', 900, 14);
      }

      // ammo pips
      const ay0 = S.H - 30 - S.safeB;
      for (let i = 0; i < 3; i++) {
        const filled = i < me.ammo;
        const x = S.cx - 34 + i * 34;
        c.save();
        c.globalAlpha = filled ? 1 : .3;
        c.fillStyle = filled ? me.ch.c : '#4a5170';
        if (filled) { c.shadowBlur = 12; c.shadowColor = me.ch.c; }
        D.rr(c, x - 13, ay0 - 6, 26, 12, 6); c.fill();
        c.restore();
      }
      // super button (physical right side)
      supBtn.x = S.W - 66; supBtn.y = S.H - 96 - S.safeB;
      const ready = me.sup >= 100;
      c.save();
      c.globalAlpha = ready ? 1 : .55;
      c.fillStyle = ready ? '#ffe14d' : 'rgba(255,255,255,.1)';
      if (ready) { c.shadowBlur = 22; c.shadowColor = '#ffe14d'; }
      c.beginPath(); c.arc(supBtn.x, supBtn.y, supBtn.r, 0, TAU); c.fill();
      c.restore();
      c.save();
      c.strokeStyle = '#ffe14d'; c.lineWidth = 4; c.globalAlpha = .9;
      c.beginPath(); c.arc(supBtn.x, supBtn.y, supBtn.r, -Math.PI / 2, -Math.PI / 2 + TAU * U.clamp(me.sup / 100, 0, 1)); c.stroke();
      c.restore();
      D.text(c, me.ch.i, supBtn.x, supBtn.y, 26, ready ? '#3a2a00' : '#e9eefb', 'center', 900);

      move.draw(c, '#9dffc6'); aim.draw(c, '#ffd0a0');

      if (hint > 0) {
        c.save(); c.globalAlpha = U.clamp(hint, 0, 1) * .9;
        D.text(c, 'שמאל = תנועה · ימין = ירי · העיגול = סופר', S.cx, S.H * .8, 14.5, '#cbd6f0', 'center', 700, 8);
        c.restore();
      }
      D.vignette(c, .45);

      if (over !== null) {
        c.save(); c.fillStyle = 'rgba(4,4,12,.6)'; c.fillRect(0, 0, S.W, S.H);
        D.text(c, over ? 'ניצחון!' : 'הפסד', S.cx, S.cy, 46, over ? '#9dffc6' : '#ff8a9d', 'center', 900, 24);
        c.restore();
      }
    }
  };
}
