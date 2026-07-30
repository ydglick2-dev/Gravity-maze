/* ============================================================
   מצב 1 — קפיצת כבידה  (one-tap gravity flip ascent)
   Tap = flip horizontal gravity. Climb forever. Don't touch anything.
   ============================================================ */
import { S, U, TAU, FX, D, Sound, Save, Stars, haptic, skinColor, alpha, Game, Input } from '../core.js';

export const meta = {
  id: 'tap', title: 'קפיצת כבידה', ico: '⇅', color: '#21e6ff', music: 'tap',
  desc: 'לחיצה אחת הופכת את הכבידה. טפס כמה שיותר גבוה,<br>אל תיגע בכלום. חלוף קרוב = בונוס.'
};

const PX_PER_M = 26;
const PR = 12;                      // player radius

export function create(api) {
  let tunW, tunX;                   // tunnel width / left edge
  let px, pvx, grav, pWorld, speed, dist, coins, combo, comboT, slowT, alive, startT, best;
  let obs = [], gems = [], nextY, chunkI, flipT, shockT, milestone;
  const trailT = { v: 0 };

  function layout() {
    tunW = Math.min(S.W * .92, 430);
    tunX = (S.W - tunW) / 2;
  }
  const playerScreenY = () => S.H * .64;
  const sy = worldY => playerScreenY() - (worldY - pWorld);

  function reset() {
    layout();
    px = tunX + tunW * .5; pvx = 0; grav = 1;
    pWorld = 0; speed = 230; dist = 0; coins = 0; combo = 1; comboT = 0; slowT = 0;
    alive = true; startT = 0; flipT = 0; shockT = 0; milestone = 0;
    obs = []; gems = []; nextY = 420; chunkI = 0;
    best = Save.d.best.tap || 0;
    hud();
  }

  function hud() {
    api.hud(`${Math.floor(dist)} <span style="font-size:13px;opacity:.6">מ׳</span>`,
      `<span style="color:#ffc63a">◈ ${coins}</span>${combo > 1 ? ` <span style="color:#46f08a">×${combo}</span>` : ''}`);
  }

  /* ---------------- generation ---------------- */
  function gen() {
    const guard = pWorld + S.H * 1.6;
    while (nextY < guard) {
      const d = Math.min(1, dist / 900);              // 0..1 difficulty
      const roll = Math.random();
      let gap = 300 - 120 * d + U.rnd(-20, 30);       // vertical breathing room

      if (chunkI < 3) { spikes(nextY, Math.random() < .5 ? -1 : 1, 120); gap = 330; }
      else if (roll < .3) {
        const side = Math.random() < .5 ? -1 : 1;
        spikes(nextY, side, U.rnd(110, 150 + 90 * d));
        if (d > .45 && Math.random() < .35) spikes(nextY + U.rnd(150, 240), -side, U.rnd(100, 150));
      }
      else if (roll < .55) {
        const gw = U.rnd(tunW * (.44 - .14 * d), tunW * (.56 - .12 * d));
        obs.push({ t: 'gate', y: nextY, gx: U.rnd(tunX + 14, tunX + tunW - gw - 14), gw, h: 16 });
      }
      else if (roll < .74) {
        obs.push({
          t: 'spin', y: nextY, a: U.rnd(TAU), sp: U.rnd(.9, 1.5 + d) * (Math.random() < .5 ? -1 : 1),
          len: tunW * U.rnd(.3, .38 + .08 * d)
        });
        gap += 60;
      }
      else if (roll < .88) {
        obs.push({ t: 'laser', y: nextY, per: U.rnd(1.5, 2.4), ph: U.rnd(2), on: false });
      }
      else {
        // slalom: two offset half-walls
        obs.push({ t: 'gate', y: nextY, gx: tunX + 10, gw: tunW * (.5 - .08 * d), h: 14 });
        obs.push({ t: 'gate', y: nextY + 190, gx: tunX + tunW * (.5 + .08 * d) - 10, gw: tunW * (.5 - .08 * d), h: 14 });
        gap += 210;
      }

      // collectibles ride the empty space
      if (Math.random() < .8) {
        const n = U.rndi(2, 5), cx = U.rnd(tunX + 40, tunX + tunW - 40);
        for (let i = 0; i < n; i++)
          gems.push({ x: cx + Math.sin(i * .9) * 34, y: nextY + gap * .45 + i * 30, got: false, ph: U.rnd(TAU) });
      }
      if (chunkI > 6 && Math.random() < .12)
        gems.push({ x: U.rnd(tunX + 40, tunX + tunW - 40), y: nextY + gap * .5, got: false, ph: 0, power: true });

      nextY += gap; chunkI++;
    }
    const cut = pWorld - S.H * .6;
    obs = obs.filter(o => o.y > cut);
    gems = gems.filter(g => g.y > cut && !g.got);
  }
  function spikes(y, side, h) { obs.push({ t: 'spike', y, side, h }); }

  /* ---------------- collision ---------------- */
  function segDist(cx, cy, x1, y1, x2, y2) {
    const dx = x2 - x1, dy = y2 - y1, L = dx * dx + dy * dy;
    let t = L ? ((cx - x1) * dx + (cy - y1) * dy) / L : 0;
    t = U.clamp(t, 0, 1);
    return U.dist(cx, cy, x1 + dx * t, y1 + dy * t);
  }

  function hitTest() {
    const py = pWorld;
    for (const o of obs) {
      if (Math.abs(o.y - py) > 400) continue;
      if (o.t === 'spike') {
        const w = 26;
        const inY = py > o.y - PR && py < o.y + o.h + PR;
        if (!inY) continue;
        if (o.side < 0 && px - PR < tunX + w) return o;
        if (o.side > 0 && px + PR > tunX + tunW - w) return o;
        // near miss
        const clr = o.side < 0 ? px - PR - (tunX + w) : (tunX + tunW - w) - (px + PR);
        if (clr < 22 && py > o.y && py < o.y + o.h) nearMiss(o);
      }
      else if (o.t === 'gate') {
        if (Math.abs( py - o.y) < o.h / 2 + PR) {
          if (px - PR < o.gx || px + PR > o.gx + o.gw) return o;
          const clr = Math.min(px - PR - o.gx, o.gx + o.gw - (px + PR));
          if (clr < 20) nearMiss(o);
        }
      }
      else if (o.t === 'spin') {
        const cx = tunX + tunW / 2, cy = o.y;
        const ex = Math.cos(o.a) * o.len, ey = Math.sin(o.a) * o.len;
        const dd = segDist(px, py, cx - ex, cy - ey, cx + ex, cy + ey);
        if (dd < PR + 7) return o;
        if (dd < PR + 26) nearMiss(o);
      }
      else if (o.t === 'laser') {
        if (o.on && Math.abs( py - o.y) < PR + 5) return o;
        if (!o.on && Math.abs( py - o.y) < PR + 24) nearMiss(o);
      }
    }
    return null;
  }

  let nmCool = 0;
  function nearMiss(o) {
    if (nmCool > 0 || o._nm) return;
    o._nm = true; nmCool = .25;
    combo = Math.min(9, combo + 1); comboT = 2.4;
    FX.float(px, playerScreenY() - 34, 'כמעט! ×' + combo, '#46f08a', 17);
    Sound.tone(700 + combo * 90, .07, 'square', .18);
    haptic(8);
  }

  /* ---------------- flow ---------------- */
  function flip() {
    if (!alive) return;
    grav *= -1; flipT = .18;
    Sound.play('jump'); haptic(11);
    FX.ring(px, playerScreenY(), skinColor(Game.t), 6, 46, 3, .3);
    for (let i = 0; i < 8; i++)
      FX.burst(px, playerScreenY(), 1, skinColor(Game.t), { speed: 150, life: .3, size: 3, dir: grav > 0 ? Math.PI : 0, spread: .9 });
  }

  function die(o) {
    if (!alive) return;
    alive = false;
    Sound.play('boom'); haptic([30, 40, 60]);
    FX.stop(.13); FX.shake(20); FX.flash('#ff4d6d', .55);
    FX.burst(px, playerScreenY(), 46, '#ff6b8a', { speed: 400, life: .8, size: 6 });
    FX.burst(px, playerScreenY(), 22, skinColor(Game.t), { speed: 250, life: 1, size: 5 });
    FX.ring(px, playerScreenY(), '#ff4d6d', 10, 220, 6, .6);

    const m = Math.floor(dist);
    const isBest = Save.setBest('tap', m);
    const earn = coins + Math.floor(m / 8);
    setTimeout(() => api.end({
      ico: isBest ? '🏆' : '💥',
      title: isBest ? 'שיא חדש!' : 'התרסקת',
      color: meta.color,
      stats: [['גובה', Math.floor(dist) + ' מ׳'], ['שיא', Math.max(best, m) + ' מ׳'], ['אנרגיה', '◈ ' + coins]],
      coins: earn
    }), 700);
  }

  /* ---------------- scene ---------------- */
  return {
    resize: layout,
    enter() { reset(); gen(); },
    onDown() { flip(); },
    onKey(code) { if (['Space', 'ArrowLeft', 'ArrowRight', 'KeyA', 'KeyD'].includes(code)) flip(); },

    update(dt) {
      if (!alive) { Stars.update(dt, 0, 30); return; }
      startT += dt;
      const slow = slowT > 0 ? .45 : 1;
      if (slowT > 0) slowT -= dt;
      const d = dt * slow;

      speed = Math.min(760, 230 + dist * .42);
      pWorld += speed * d;
      dist = pWorld / PX_PER_M;

      // horizontal physics
      pvx += grav * 1750 * d;
      pvx = U.clamp(pvx, -900, 900);
      px += pvx * d;

      const L = tunX + PR + 6, R = tunX + tunW - PR - 6;
      if (px < L) {
        px = L; if (Math.abs(pvx) > 120) { FX.ring(px, playerScreenY(), '#8fd9ff', 4, 34, 2, .22); haptic(6); Sound.tone(200, .05, 'sine', .12); }
        pvx = Math.abs(pvx) * .18;
      }
      if (px > R) {
        px = R; if (Math.abs(pvx) > 120) { FX.ring(px, playerScreenY(), '#8fd9ff', 4, 34, 2, .22); haptic(6); Sound.tone(200, .05, 'sine', .12); }
        pvx = -Math.abs(pvx) * .18;
      }

      if (flipT > 0) flipT -= dt;
      if (nmCool > 0) nmCool -= dt;
      if (comboT > 0) { comboT -= dt; if (comboT <= 0) combo = 1; }

      // spinners + lasers
      for (const o of obs) {
        if (o.t === 'spin') o.a += o.sp * d;
        if (o.t === 'laser') { const k = (Game.t / o.per + o.ph) % 1; o.on = k > .45; o.warn = k > .3 && k <= .45; }
      }

      // gems
      for (const g of gems) {
        if (g.got) continue;
        g.ph += dt * 3;
        if (U.dist2(px, pWorld, g.x, g.y) < (PR + 16) ** 2) {
          g.got = true;
          if (g.power) {
            slowT = 3.2; Sound.play('super'); haptic(24);
            FX.flash('#9b5cff', .3); FX.ring(g.x, sy(g.y), '#9b5cff', 8, 180, 5, .5);
            FX.float(px, playerScreenY() - 50, 'האטת זמן!', '#c9a3ff', 20);
          } else {
            coins += combo; Sound.play('coin'); haptic(7);
            FX.burst(g.x, sy(g.y), 8, '#ffc63a', { speed: 160, life: .4, size: 3.5 });
            FX.float(g.x, sy(g.y), '+' + combo, '#ffc63a', 15);
          }
        }
      }

      // milestones
      if (Math.floor(dist / 250) > milestone) {
        milestone = Math.floor(dist / 250);
        api.toast(milestone * 250 + ' מטר!', '#21e6ff');
        Sound.play('level'); FX.flash('#21e6ff', .18);
      }

      // trail
      trailT.v += dt;
      if (trailT.v > .016) {
        trailT.v = 0;
        FX.trail(px + U.rnd(-3, 3), playerScreenY() + U.rnd(-2, 2), skinColor(Game.t), U.rnd(3, 6.5), .32);
      }

      Stars.update(dt, 0, -speed * .25);
      gen();
      const h = hitTest();
      if (h) die(h);
      hud();
    },

    draw(c) {
      const sc = skinColor(Game.t);
      // backdrop
      const g = c.createLinearGradient(0, 0, 0, S.H);
      g.addColorStop(0, '#080d1c'); g.addColorStop(1, '#04060e');
      c.fillStyle = g; c.fillRect(0, 0, S.W, S.H);
      Stars.draw(c);

      // scrolling tunnel rails
      c.save();
      c.fillStyle = 'rgba(10,16,34,.85)';
      c.fillRect(tunX, 0, tunW, S.H);
      c.strokeStyle = alpha('#21e6ff', .5); c.lineWidth = 2;
      c.shadowBlur = 16; c.shadowColor = '#21e6ff';
      c.beginPath(); c.moveTo(tunX, 0); c.lineTo(tunX, S.H);
      c.moveTo(tunX + tunW, 0); c.lineTo(tunX + tunW, S.H); c.stroke();
      c.shadowBlur = 0;
      c.strokeStyle = 'rgba(80,140,220,.13)'; c.lineWidth = 1;
      const step = 60, off = pWorld % step;
      c.beginPath();
      for (let y = off; y < S.H + step; y += step) { c.moveTo(tunX, y); c.lineTo(tunX + tunW, y); }
      c.stroke();
      c.restore();

      // obstacles
      for (const o of obs) {
        const y = sy(o.y);
        if (y < -260 || y > S.H + 260) continue;
        if (o.t === 'spike') {
          const w = 26, x0 = o.side < 0 ? tunX : tunX + tunW - w;
          c.save(); c.fillStyle = '#ff3c6a'; c.shadowBlur = 18; c.shadowColor = '#ff3c6a';
          const n = Math.max(2, Math.round(o.h / 26));
          for (let i = 0; i < n; i++) {
            const yy = y - (i * o.h / n);
            c.beginPath();
            if (o.side < 0) { c.moveTo(x0, yy); c.lineTo(x0 + w, yy - o.h / n / 2); c.lineTo(x0, yy - o.h / n); }
            else { c.moveTo(x0 + w, yy); c.lineTo(x0, yy - o.h / n / 2); c.lineTo(x0 + w, yy - o.h / n); }
            c.closePath(); c.fill();
          }
          c.restore();
        }
        else if (o.t === 'gate') {
          c.save(); c.shadowBlur = 16; c.shadowColor = '#ff8a3d'; c.fillStyle = '#ff8a3d';
          D.rr(c, tunX, y - o.h / 2, o.gx - tunX, o.h, 5); c.fill();
          D.rr(c, o.gx + o.gw, y - o.h / 2, tunX + tunW - (o.gx + o.gw), o.h, 5); c.fill();
          c.restore();
        }
        else if (o.t === 'spin') {
          const cx = tunX + tunW / 2;
          c.save(); c.translate(cx, y); c.rotate(o.a);
          c.strokeStyle = '#c46bff'; c.lineWidth = 13; c.lineCap = 'round';
          c.shadowBlur = 20; c.shadowColor = '#c46bff';
          c.beginPath(); c.moveTo(-o.len, 0); c.lineTo(o.len, 0); c.stroke();
          c.restore();
          D.glowCircle(c, cx, y, 6, '#ffd0ff', .9);
        }
        else if (o.t === 'laser') {
          c.save();
          if (o.on) {
            c.strokeStyle = '#ff2e5f'; c.lineWidth = 7; c.shadowBlur = 26; c.shadowColor = '#ff2e5f';
            c.beginPath(); c.moveTo(tunX, y); c.lineTo(tunX + tunW, y); c.stroke();
            c.globalAlpha = .35; c.lineWidth = 16; c.stroke();
          } else {
            c.globalAlpha = o.warn ? .55 + .35 * Math.sin(Game.t * 40) : .22;
            c.strokeStyle = '#ff2e5f'; c.lineWidth = 2; c.setLineDash([9, 9]);
            c.beginPath(); c.moveTo(tunX, y); c.lineTo(tunX + tunW, y); c.stroke();
          }
          c.restore();
          D.glowCircle(c, tunX + 6, y, 5, '#ff2e5f', .9);
          D.glowCircle(c, tunX + tunW - 6, y, 5, '#ff2e5f', .9);
        }
      }

      // gems
      for (const g of gems) {
        if (g.got) continue;
        const y = sy(g.y);
        if (y < -30 || y > S.H + 30) continue;
        const pop = 1 + .16 * Math.sin(g.ph);
        if (g.power) {
          c.save(); c.translate(g.x, y); c.rotate(Game.t * 2);
          c.fillStyle = '#9b5cff'; c.shadowBlur = 24; c.shadowColor = '#9b5cff';
          c.beginPath();
          for (let i = 0; i < 5; i++) {
            const a = -Math.PI / 2 + i * TAU / 5;
            c.lineTo(Math.cos(a) * 15 * pop, Math.sin(a) * 15 * pop);
            const a2 = a + TAU / 10;
            c.lineTo(Math.cos(a2) * 7 * pop, Math.sin(a2) * 7 * pop);
          }
          c.closePath(); c.fill(); c.restore();
        } else {
          c.save(); c.translate(g.x, y); c.rotate(Math.PI / 4);
          c.fillStyle = '#ffc63a'; c.shadowBlur = 16; c.shadowColor = '#ffc63a';
          c.fillRect(-6 * pop, -6 * pop, 12 * pop, 12 * pop); c.restore();
        }
      }

      FX.draw(c);

      // player
      if (alive) {
        const y = playerScreenY();
        const sq = 1 + Math.min(.3, Math.abs(pvx) / 2600);
        c.save(); c.translate(px, y); c.scale(sq, 2 - sq);
        D.orb(c, 0, 0, PR, sc, 1.3);
        c.restore();
        if (flipT > 0) D.ring(c, px, y, PR + 10 + (1 - flipT / .18) * 18, sc, 3, flipT / .18);
        // gravity indicator arrow
        c.save(); c.globalAlpha = .5; c.fillStyle = sc;
        const ax = px + grav * 26;
        c.beginPath(); c.moveTo(ax + grav * 9, y); c.lineTo(ax, y - 6); c.lineTo(ax, y + 6);
        c.closePath(); c.fill(); c.restore();
      }

      // combo aura
      if (combo > 1) {
        c.save(); c.globalAlpha = .5 * (comboT / 2.4);
        D.text(c, '×' + combo, px, playerScreenY() - 46, 26, '#46f08a', 'center', 900, 14);
        c.restore();
      }

      if (slowT > 0) {
        c.save(); c.globalAlpha = .1 + .05 * Math.sin(Game.t * 8);
        c.fillStyle = '#9b5cff'; c.fillRect(0, 0, S.W, S.H); c.restore();
      }
      D.vignette(c, .55);

      // first-run hint
      if (startT < 2.4 && alive) {
        c.save(); c.globalAlpha = U.clamp(2.4 - startT, 0, 1) * .9;
        D.text(c, 'הקש כדי להפוך כבידה', S.cx, S.H * .32, 19, '#dbe8ff', 'center', 800, 12);
        c.restore();
      }
    }
  };
}
