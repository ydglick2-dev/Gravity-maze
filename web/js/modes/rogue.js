/* ============================================================
   מצב 3 — ריצת נובה  (roguelite survival: waves, XP, upgrades)
   Move with the stick, the cannon aims itself. Level up, draft, survive.
   ============================================================ */
import {
  S, U, TAU, FX, D, Sound, Save, Stars, haptic, skinColor, alpha, shade,
  Game, Input, Stick, keyVec
} from '../core.js';

export const meta = {
  id: 'rogue', title: 'ריצת נובה', ico: '⚔', color: '#46f08a', music: 'rogue',
  desc: 'זוז עם הג׳ויסטיק — הנשק יורה לבד.<br>אסוף אנרגיה, בחר שדרוג, שרוד גל אחרי גל.'
};

const ARENA = 1500;   // half-size of the playfield

const UPGRADES = [
  { id: 'dmg', n: 'נזק +25%', d: 'כל פגיעה חזקה יותר', i: '🗡', f: p => p.dmg *= 1.25 },
  { id: 'rate', n: 'קצב אש +20%', d: 'יורה מהר יותר', i: '⚡', f: p => p.rate *= 1.2 },
  { id: 'proj', n: 'קליע נוסף', d: 'עוד קליע במניפה', i: '⁂', f: p => p.proj++ },
  { id: 'pierce', n: 'חדירה +1', d: 'הקליע ממשיך דרך אויבים', i: '➤', f: p => p.pierce++ },
  { id: 'spd', n: 'מהירות +15%', d: 'תנועה זריזה יותר', i: '👟', f: p => p.spd *= 1.15 },
  { id: 'hp', n: 'חיים מרביים +25', d: 'ומתמלא מיד', i: '❤', f: p => { p.maxHp += 25; p.hp += 25; } },
  { id: 'heal', n: 'ריפוי מלא', d: 'חוזר למלוא החיים', i: '✚', f: p => p.hp = p.maxHp },
  { id: 'mag', n: 'מגנט +40%', d: 'שואב אנרגיה מרחוק', i: '🧲', f: p => p.mag *= 1.4 },
  { id: 'crit', n: 'קריטי +10%', d: 'נזק כפול אקראי', i: '✷', f: p => p.crit += .1 },
  { id: 'orb', n: 'מגן מקיף', d: 'כדור אנרגיה סובב סביבך', i: '◍', f: p => p.orbs++ },
  { id: 'boom', n: 'קליעים נפיצים', d: 'פיצוץ קטן בכל פגיעה', i: '💥', f: p => p.boom += 1 },
  { id: 'vamp', n: 'מציצת חיים', d: 'כל הרג מחזיר חיים', i: '🩸', f: p => p.vamp += 1.5 },
  { id: 'slow', n: 'שדה האטה', d: 'אויבים קרובים מואטים', i: '❄', f: p => p.slow += .18 },
  { id: 'bspd', n: 'קליעים מהירים', d: 'טווח ומהירות ירי', i: '➹', f: p => p.bspd *= 1.25 }
];

export function create(api) {
  const stick = new Stick('any');
  let p, cam, enemies, bullets, ebullets, gems, wave, waveT, kills, time, alive,
    lvl, xp, xpNext, drafting, draft, spawnT, bossAlive, dmgFlash, hint;

  function reset() {
    p = {
      x: 0, y: 0, vx: 0, vy: 0, r: 15, hp: 100, maxHp: 100, spd: 250,
      dmg: 12, rate: 2.6, proj: 1, pierce: 0, crit: .05, mag: 90, orbs: 0,
      boom: 0, vamp: 0, slow: 0, bspd: 1, cool: 0, iframe: 0, orbA: 0, aim: 0
    };
    cam = { x: 0, y: 0 };
    enemies = []; bullets = []; ebullets = []; gems = [];
    wave = 0; waveT = 0; kills = 0; time = 0; alive = true;
    lvl = 1; xp = 0; xpNext = 5; drafting = false; draft = [];
    spawnT = 0; bossAlive = false; dmgFlash = 0; hint = 3.2;
    nextWave();
  }

  /* ---------------- waves ---------------- */
  function nextWave() {
    wave++; waveT = 0;
    api.toast('גל ' + wave, wave % 5 === 0 ? '#ff2e88' : '#46f08a');
    Sound.play(wave % 5 === 0 ? 'alarm' : 'level');
    if (wave % 5 === 0) { spawnBoss(); bossAlive = true; }
  }
  function edgePos() {
    const a = U.rnd(TAU), d = Math.max(S.W, S.H) * .62;   // just off-screen, so waves arrive fast
    return { x: p.x + Math.cos(a) * d, y: p.y + Math.sin(a) * d };
  }
  function spawn() {
    const { x, y } = edgePos();
    const t = Math.random();
    const w = wave;
    const scale = 1 + w * .16;
    let e;
    if (t < .12 + w * .01 && w > 2) e = { k: 'tank', r: 26, hp: 70 * scale, spd: 52, dmg: 18, c: '#ff8a3d', xp: 4 };
    else if (t < .35 && w > 1) e = { k: 'fast', r: 10, hp: 14 * scale, spd: 150 + w * 4, dmg: 7, c: '#ffe14d', xp: 2 };
    else if (t < .5 && w > 3) e = { k: 'shooter', r: 14, hp: 22 * scale, spd: 62, dmg: 9, c: '#c46bff', xp: 3, cool: U.rnd(1, 2.4) };
    else e = { k: 'grunt', r: 15, hp: 26 * scale, spd: 78 + w * 2, dmg: 10, c: '#ff4d6d', xp: 2 };
    Object.assign(e, { x, y, vx: 0, vy: 0, maxHp: e.hp, hurt: 0, ph: U.rnd(TAU) });
    enemies.push(e);
  }
  function spawnBoss() {
    const { x, y } = edgePos();
    enemies.push({
      k: 'boss', x, y, vx: 0, vy: 0, r: 46, hp: 380 * (1 + wave * .5), maxHp: 380 * (1 + wave * .5),
      spd: 46, dmg: 26, c: '#ff2e88', xp: 40, cool: 2, ph: 0, boss: true
    });
    api.toast('בוס!', '#ff2e88'); FX.flash('#ff2e88', .3); FX.shake(10); haptic([40, 60, 40]);
  }

  /* ---------------- combat ---------------- */
  function fire() {
    let best = null, bd = 1e9;
    for (const e of enemies) {
      const d = U.dist2(p.x, p.y, e.x, e.y);
      if (d < bd) { bd = d; best = e; }
    }
    if (!best || bd > 700 * 700) return;
    const base = U.ang(p.x, p.y, best.x, best.y);
    p.aim = base;
    const spread = .1 + p.proj * .045;
    for (let i = 0; i < p.proj; i++) {
      const a = base + (p.proj === 1 ? 0 : U.lerp(-spread, spread, i / (p.proj - 1)));
      const crit = Math.random() < p.crit;
      bullets.push({
        x: p.x + Math.cos(a) * 16, y: p.y + Math.sin(a) * 16,
        vx: Math.cos(a) * 620 * p.bspd, vy: Math.sin(a) * 620 * p.bspd,
        dmg: p.dmg * (crit ? 2 : 1), crit, life: 1.1, pierce: p.pierce, hit: new Set()
      });
    }
    Sound.play('shoot');
    FX.burst(p.x + Math.cos(base) * 18, p.y + Math.sin(base) * 18, 3, '#bffcd8',
      { speed: 90, life: .18, size: 2.6, dir: base, spread: .4 });
  }

  function damageEnemy(e, dmg, bx, by, crit) {
    e.hp -= dmg; e.hurt = .12;
    FX.burst(bx, by, crit ? 9 : 5, crit ? '#fff0a0' : '#ffd0d8',
      { speed: crit ? 220 : 130, life: .3, size: crit ? 4 : 3 });
    if (crit) FX.float(bx, by - 14, Math.round(dmg), '#ffe14d', 16);
    Sound.play('hit');
    if (e.hp <= 0) killEnemy(e, bx, by);
  }
  function killEnemy(e, bx, by) {
    const i = enemies.indexOf(e); if (i < 0) return;
    enemies.splice(i, 1);
    kills++;
    if (p.vamp) p.hp = Math.min(p.maxHp, p.hp + p.vamp);
    FX.burst(e.x, e.y, e.boss ? 60 : 14, e.c, { speed: e.boss ? 420 : 200, life: e.boss ? 1 : .5, size: e.boss ? 7 : 4 });
    FX.ring(e.x, e.y, e.c, e.r * .5, e.r * (e.boss ? 6 : 3), e.boss ? 6 : 3, e.boss ? .7 : .35);
    if (e.boss) {
      bossAlive = false; FX.shake(18); FX.flash('#ff2e88', .4); FX.stop(.1);
      Sound.play('boom'); haptic([30, 50, 80]);
      api.toast('בוס הושמד!', '#ffc63a');
      for (let i = 0; i < 14; i++) dropGem(e.x + U.rnd(-40, 40), e.y + U.rnd(-40, 40), 3);
    } else {
      Sound.play('kill'); haptic(9);
      dropGem(e.x, e.y, e.xp);
      if (Math.random() < .045) gems.push({ x: e.x, y: e.y, v: 0, heal: true, ph: 0 });
    }
  }
  function dropGem(x, y, v) { gems.push({ x, y, v, ph: U.rnd(TAU) }); }

  function hurtPlayer(dmg, sx, sy) {
    if (p.iframe > 0 || !alive) return;
    p.hp -= dmg; p.iframe = .55; dmgFlash = 1;
    FX.shake(9); FX.flash('#ff2e5f', .3); haptic(26); Sound.play('hurt');
    FX.burst(p.x, p.y, 12, '#ff6b8a', { speed: 200, life: .45, size: 4 });
    if (p.hp <= 0) die();
  }

  function die() {
    alive = false;
    FX.stop(.15); FX.shake(24); FX.flash('#ff2e5f', .6);
    FX.burst(p.x, p.y, 50, skinColor(Game.t), { speed: 420, life: 1, size: 6 });
    Sound.play('boom'); Sound.play('die'); haptic([40, 60, 100]);
    const score = wave * 100 + kills * 10;
    const isBest = Save.setBest('rogue', score);
    setTimeout(() => api.end({
      ico: isBest ? '🏆' : '☠',
      title: isBest ? 'שיא חדש!' : 'נפלת',
      color: meta.color,
      stats: [['גל', wave], ['הריגות', kills], ['רמה', lvl],
      ['זמן', Math.floor(time) + ' שנ׳'], ['ניקוד', score]],
      coins: Math.floor(kills * .8 + wave * 12)
    }), 800);
  }

  /* ---------------- level up / draft ---------------- */
  function gainXp(v) {
    xp += v;
    while (xp >= xpNext) {
      xp -= xpNext; lvl++; xpNext = Math.round(xpNext * 1.35 + 3);
      openDraft();
    }
  }
  function openDraft() {
    if (drafting) { pendingLevels++; return; }
    drafting = true;
    const pool = U.shuffle([...UPGRADES]).filter(u => !(u.id === 'heal' && p.hp === p.maxHp));
    draft = pool.slice(0, 3).map(u => ({ ...u, sc: 0 }));
    Sound.play('level'); haptic([15, 30, 15]);
    FX.flash('#46f08a', .22);
    Game.timeScale = 0;
  }
  let pendingLevels = 0;
  function pick(i) {
    const u = draft[i]; if (!u) return;
    u.f(p);
    Sound.play('win'); haptic(20);
    FX.ring(p.x, p.y, '#46f08a', 10, 160, 5, .5);
    FX.float(p.x, p.y - 40, u.n, '#9dffc6', 18);
    drafting = false; Game.timeScale = 1;
    if (pendingLevels > 0) { pendingLevels--; openDraft(); }
  }
  function draftRects() {
    const w = Math.min(S.W - 40, 380), h = 76, gap = 12;
    const y0 = S.cy - (h * 3 + gap * 2) / 2 + 20;
    return draft.map((_, i) => ({ x: S.cx - w / 2, y: y0 + i * (h + gap), w, h }));
  }

  /* ---------------- scene ---------------- */
  return {
    enter() { reset(); },
    exit() { Game.timeScale = 1; },

    onDown(p2) {
      if (drafting) {
        draftRects().forEach((r, i) => {
          if (p2.x > r.x && p2.x < r.x + r.w && p2.y > r.y && p2.y < r.y + r.h) pick(i);
        });
        return;
      }
      stick.claim(p2);
    },
    onMove(p2) { stick.move(p2); },
    onUp(p2) { stick.release(p2); },

    update(dt) {
      if (drafting || !alive) { if (!alive) Stars.update(dt, 0, 20); return; }
      time += dt; waveT += dt;
      if (hint > 0) hint -= dt;
      if (dmgFlash > 0) dmgFlash -= dt * 2;

      // --- input ---
      let vx = stick.dx, vy = stick.dy, mag = stick.mag;
      if (!stick.active) { const k = keyVec(); vx = k.x; vy = k.y; mag = k.mag; }
      p.vx = U.damp(p.vx, vx * p.spd, 14, dt);
      p.vy = U.damp(p.vy, vy * p.spd, 14, dt);
      p.x = U.clamp(p.x + p.vx * dt, -ARENA, ARENA);
      p.y = U.clamp(p.y + p.vy * dt, -ARENA, ARENA);
      if (mag > .1 && Math.random() < dt * 30)
        FX.trail(p.x - vx * 12, p.y - vy * 12, skinColor(Game.t), U.rnd(2, 4.5), .3);

      cam.x = U.damp(cam.x, p.x, 8, dt);
      cam.y = U.damp(cam.y, p.y, 8, dt);

      if (p.iframe > 0) p.iframe -= dt;

      // --- weapon ---
      p.cool -= dt;
      if (p.cool <= 0) { fire(); p.cool = 1 / p.rate; }

      // --- orbiting shields ---
      p.orbA += dt * 2.4;
      if (p.orbs) {
        for (let i = 0; i < p.orbs; i++) {
          const a = p.orbA + i * TAU / p.orbs;
          const ox = p.x + Math.cos(a) * 62, oy = p.y + Math.sin(a) * 62;
          for (const e of [...enemies]) {
            if (U.dist2(ox, oy, e.x, e.y) < (e.r + 12) ** 2) {
              if (!e.orbCool || e.orbCool <= 0) {
                damageEnemy(e, p.dmg * .7, ox, oy, false); e.orbCool = .35;
              }
            }
          }
          if (Math.random() < dt * 20) FX.trail(ox, oy, '#7ff0ff', 3, .25);
        }
      }

      // --- spawning ---
      const rate = U.clamp(.95 - wave * .05, .22, .95);
      spawnT -= dt;
      if (spawnT <= 0 && enemies.length < 70) { spawn(); spawnT = rate * U.rnd(.7, 1.3); }
      const waveLen = 14 + wave * 1.2;
      if (waveT > waveLen && !bossAlive) nextWave();

      // --- enemies ---
      for (const e of enemies) {
        if (e.orbCool > 0) e.orbCool -= dt;
        if (e.hurt > 0) e.hurt -= dt;
        const d = U.dist(e.x, e.y, p.x, p.y) || 1;
        const slowK = p.slow && d < 160 ? Math.max(.25, 1 - p.slow) : 1;
        const ax = (p.x - e.x) / d, ay = (p.y - e.y) / d;
        let tvx = ax * e.spd * slowK, tvy = ay * e.spd * slowK;
        if (e.k === 'shooter' && d < 260) { tvx *= -.5; tvy *= -.5; }
        if (e.k === 'fast') { e.ph += dt * 4; tvx += Math.cos(e.ph) * 40; tvy += Math.sin(e.ph) * 40; }
        // separation so they don't stack into one blob
        let sx = 0, sy = 0;
        for (const o of enemies) {
          if (o === e) continue;
          const dd = U.dist2(e.x, e.y, o.x, o.y), rr = (e.r + o.r) ** 2;
          if (dd < rr && dd > .01) { const k = 1 - Math.sqrt(dd) / (e.r + o.r); sx += (e.x - o.x) * k; sy += (e.y - o.y) * k; }
        }
        e.vx = U.damp(e.vx, tvx + sx * 2.2, 8, dt);
        e.vy = U.damp(e.vy, tvy + sy * 2.2, 8, dt);
        e.x += e.vx * dt; e.y += e.vy * dt;

        if ((e.k === 'shooter' || e.boss)) {
          e.cool -= dt;
          if (e.cool <= 0 && d < 620) {
            if (e.boss) {
              const n = 10, base = U.ang(e.x, e.y, p.x, p.y);
              for (let i = 0; i < n; i++) {
                const a = base + i * TAU / n;
                ebullets.push({ x: e.x, y: e.y, vx: Math.cos(a) * 210, vy: Math.sin(a) * 210, r: 8, dmg: e.dmg, life: 4, c: '#ff6bb0' });
              }
              e.cool = 2.4; Sound.play('shoot'); FX.ring(e.x, e.y, '#ff2e88', 20, 90, 3, .3);
            } else {
              const a = U.ang(e.x, e.y, p.x, p.y);
              ebullets.push({ x: e.x, y: e.y, vx: Math.cos(a) * 250, vy: Math.sin(a) * 250, r: 6, dmg: e.dmg, life: 3, c: '#d9a0ff' });
              e.cool = U.rnd(1.6, 2.6); Sound.play('shoot');
            }
          }
        }
        if (U.dist2(e.x, e.y, p.x, p.y) < (e.r + p.r) ** 2) hurtPlayer(e.dmg, e.x, e.y);
      }

      // --- bullets ---
      for (let i = bullets.length - 1; i >= 0; i--) {
        const b = bullets[i];
        b.x += b.vx * dt; b.y += b.vy * dt; b.life -= dt;
        if (Math.random() < dt * 40) FX.trail(b.x, b.y, b.crit ? '#ffe14d' : '#9dffc6', 2.4, .18);
        if (b.life <= 0) { bullets.splice(i, 1); continue; }
        for (const e of enemies) {
          if (b.hit.has(e)) continue;
          if (U.dist2(b.x, b.y, e.x, e.y) < (e.r + 5) ** 2) {
            damageEnemy(e, b.dmg, b.x, b.y, b.crit);
            if (p.boom) {
              FX.ring(b.x, b.y, '#ffb347', 6, 60, 3, .3);
              for (const o of [...enemies])
                if (o !== e && U.dist2(b.x, b.y, o.x, o.y) < 62 * 62) damageEnemy(o, b.dmg * .45, o.x, o.y, false);
            }
            b.hit.add(e);
            if (b.pierce-- <= 0) { bullets.splice(i, 1); break; }
          }
        }
      }

      // --- enemy bullets ---
      for (let i = ebullets.length - 1; i >= 0; i--) {
        const b = ebullets[i];
        b.x += b.vx * dt; b.y += b.vy * dt; b.life -= dt;
        if (b.life <= 0) { ebullets.splice(i, 1); continue; }
        if (U.dist2(b.x, b.y, p.x, p.y) < (b.r + p.r) ** 2) { hurtPlayer(b.dmg, b.x, b.y); ebullets.splice(i, 1); }
      }

      // --- gems ---
      for (let i = gems.length - 1; i >= 0; i--) {
        const g = gems[i];
        g.ph += dt * 4;
        const d = U.dist(g.x, g.y, p.x, p.y);
        if (d < p.mag) {
          const k = U.clamp(1 - d / p.mag, 0, 1);
          g.x = U.lerp(g.x, p.x, k * dt * 9); g.y = U.lerp(g.y, p.y, k * dt * 9);
        }
        if (d < p.r + 12) {
          gems.splice(i, 1);
          if (g.heal) {
            p.hp = Math.min(p.maxHp, p.hp + 25); Sound.play('win');
            FX.float(p.x, p.y - 30, '+25', '#ff8ab0', 17);
          } else {
            gainXp(g.v); Sound.play('gem');
            FX.burst(p.x, p.y, 4, '#7ff0ff', { speed: 90, life: .25, size: 2.6 });
          }
        }
      }

      Stars.update(dt, p.vx * .04, p.vy * .04);
      api.hud(`גל ${wave}`, `<span style="color:#7ff0ff">רמה ${lvl}</span> · ${kills} הרג`);
    },

    draw(c) {
      const sc = skinColor(Game.t);
      c.fillStyle = '#05070f'; c.fillRect(0, 0, S.W, S.H);
      const camx = cam.x - S.cx, camy = cam.y - S.cy;
      D.grid(c, camx, camy, 70, 'rgba(70,240,138,.07)');
      Stars.draw(c);

      c.save(); c.translate(-camx, -camy);

      // arena boundary
      c.strokeStyle = alpha('#46f08a', .35); c.lineWidth = 3;
      c.shadowBlur = 20; c.shadowColor = '#46f08a';
      c.strokeRect(-ARENA, -ARENA, ARENA * 2, ARENA * 2); c.shadowBlur = 0;

      // gems
      for (const g of gems) {
        const s = 5 + Math.sin(g.ph) * 1.2;
        D.glowCircle(c, g.x, g.y, s, g.heal ? '#ff6b9d' : '#7ff0ff', .95);
      }

      // enemy bullets
      for (const b of ebullets) D.glowCircle(c, b.x, b.y, b.r, b.c, .95);

      // enemies
      for (const e of enemies) {
        const hurt = e.hurt > 0;
        c.save(); c.translate(e.x, e.y);
        const col = hurt ? '#ffffff' : e.c;
        if (e.boss) {
          c.rotate(Game.t * .6);
          c.fillStyle = col; c.shadowBlur = 34; c.shadowColor = e.c;
          c.beginPath();
          for (let i = 0; i < 8; i++) {
            const a = i * TAU / 8, r = i % 2 ? e.r * .62 : e.r;
            c.lineTo(Math.cos(a) * r, Math.sin(a) * r);
          }
          c.closePath(); c.fill();
          c.restore();
          // boss hp bar
          D.bar(c, e.x - 60, e.y - e.r - 22, 120, 8, e.hp / e.maxHp, '#ff2e88');
          c.save(); c.translate(e.x, e.y);
        } else if (e.k === 'tank') {
          c.fillStyle = col; c.shadowBlur = 20; c.shadowColor = e.c;
          D.rr(c, -e.r, -e.r, e.r * 2, e.r * 2, 7); c.fill();
        } else if (e.k === 'fast') {
          c.rotate(Math.atan2(e.vy, e.vx));
          c.fillStyle = col; c.shadowBlur = 16; c.shadowColor = e.c;
          c.beginPath(); c.moveTo(e.r * 1.4, 0); c.lineTo(-e.r, e.r * .8); c.lineTo(-e.r, -e.r * .8);
          c.closePath(); c.fill();
        } else if (e.k === 'shooter') {
          c.rotate(Game.t * 1.6);
          c.fillStyle = col; c.shadowBlur = 20; c.shadowColor = e.c;
          c.beginPath();
          for (let i = 0; i < 3; i++) { const a = i * TAU / 3; c.lineTo(Math.cos(a) * e.r * 1.3, Math.sin(a) * e.r * 1.3); }
          c.closePath(); c.fill();
        } else {
          D.orb(c, 0, 0, e.r, col, .9);
        }
        c.restore();
        if (!e.boss && e.hp < e.maxHp)
          D.bar(c, e.x - e.r, e.y - e.r - 11, e.r * 2, 4, e.hp / e.maxHp, '#ff6b8a', 'rgba(0,0,0,.5)');
      }

      // player bullets
      for (const b of bullets) {
        c.save(); c.translate(b.x, b.y); c.rotate(Math.atan2(b.vy, b.vx));
        c.fillStyle = b.crit ? '#ffe14d' : '#c9ffe2';
        c.shadowBlur = 16; c.shadowColor = b.crit ? '#ffe14d' : '#46f08a';
        D.rr(c, -9, -3, 18, 6, 3); c.fill(); c.restore();
      }

      // orbs
      if (p.orbs) for (let i = 0; i < p.orbs; i++) {
        const a = p.orbA + i * TAU / p.orbs;
        D.glowCircle(c, p.x + Math.cos(a) * 62, p.y + Math.sin(a) * 62, 9, '#7ff0ff', .9);
      }
      // slow field
      if (p.slow) D.ring(c, p.x, p.y, 160, '#6fd0ff', 1.5, .18 + .07 * Math.sin(Game.t * 3));

      FX.draw(c);

      // player
      if (alive) {
        const blink = p.iframe > 0 && Math.floor(Game.t * 20) % 2 === 0;
        if (!blink) {
          D.orb(c, p.x, p.y, p.r, sc, 1.2);
          c.save(); c.translate(p.x, p.y); c.rotate(p.aim);
          c.fillStyle = shade(sc, .35); c.shadowBlur = 12; c.shadowColor = sc;
          D.rr(c, 8, -4, 16, 8, 3); c.fill(); c.restore();
        }
      }
      c.restore();

      // ---- HUD ----
      const barW = Math.min(S.W - 40, 320);
      D.bar(c, S.cx - barW / 2, S.H - 46 - S.safeB, barW, 12, p.hp / p.maxHp, '#ff4d6d');
      D.text(c, `${Math.max(0, Math.ceil(p.hp))} / ${p.maxHp}`, S.cx, S.H - 40 - S.safeB, 11, '#fff');
      D.bar(c, S.cx - barW / 2, S.H - 28 - S.safeB, barW, 7, xp / xpNext, '#7ff0ff');
      stick.draw(c, '#9dffc6');

      if (dmgFlash > 0) {
        c.save(); c.globalAlpha = dmgFlash * .35;
        const g = c.createRadialGradient(S.cx, S.cy, Math.min(S.W, S.H) * .3, S.cx, S.cy, Math.max(S.W, S.H) * .7);
        g.addColorStop(0, 'rgba(255,0,60,0)'); g.addColorStop(1, 'rgba(255,0,60,.9)');
        c.fillStyle = g; c.fillRect(0, 0, S.W, S.H); c.restore();
      }
      D.vignette(c, .45);

      if (hint > 0 && !drafting) {
        c.save(); c.globalAlpha = U.clamp(hint, 0, 1) * .85;
        D.text(c, 'החזק בכל מקום כדי לזוז — הירי אוטומטי', S.cx, S.H * .18, 15, '#cbd6f0', 'center', 700, 8);
        c.restore();
      }

      // ---- draft overlay ----
      if (drafting) {
        c.save();
        c.fillStyle = 'rgba(4,8,14,.86)'; c.fillRect(0, 0, S.W, S.H);
        D.text(c, 'רמה ' + lvl, S.cx, S.cy - 190, 34, '#9dffc6', 'center', 900, 20);
        D.text(c, 'בחר שדרוג', S.cx, S.cy - 152, 17, '#cbd6f0', 'center', 700);
        draftRects().forEach((r, i) => {
          const u = draft[i];
          const pulse = .5 + .5 * Math.sin(Game.t * 3 + i);
          c.fillStyle = 'rgba(20,32,44,.95)';
          D.rr(c, r.x, r.y, r.w, r.h, 18); c.fill();
          c.strokeStyle = alpha('#46f08a', .5 + .3 * pulse); c.lineWidth = 2;
          c.shadowBlur = 16; c.shadowColor = '#46f08a'; c.stroke(); c.shadowBlur = 0;
          D.text(c, u.i, r.x + r.w - 42, r.y + r.h / 2, 30, '#fff', 'center', 400);
          D.text(c, u.n, r.x + r.w - 76, r.y + 26, 19, '#eafff2', 'right', 900);
          D.text(c, u.d, r.x + r.w - 76, r.y + 52, 13.5, '#9fb3c9', 'right', 600);
        });
        c.restore();
      }
    }
  };
}
