/* ============================================================
   משימות התחנה — mini-games played on a panel overlay
   Each returns a controller: update(dt) / draw(ctx) / onDown / onMove / onUp,
   and flips `done` when finished. The station keeps running underneath —
   you can be killed while your head is down in a task.
   ============================================================ */
import { S, U, TAU, FX, D, Sound, haptic, alpha, Game } from '../core.js';

export const TASK_TYPES = ['wires', 'download', 'swipe', 'calibrate', 'asteroids'];
export const TASK_NAMES = {
  wires: 'חיבור חיווט',
  download: 'הורדת נתונים',
  swipe: 'העברת כרטיס',
  calibrate: 'כיול מפזר',
  asteroids: 'ניקוי אסטרואידים'
};

/* panel geometry shared by every mini-game */
function panel() {
  const w = Math.min(S.W - 32, 380);
  const h = Math.min(S.H - 200, 420);
  return { x: (S.W - w) / 2, y: (S.H - h) / 2, w, h };
}

function frame(c, title, sub) {
  const p = panel();
  c.save();
  c.fillStyle = 'rgba(3,5,12,.82)'; c.fillRect(0, 0, S.W, S.H);
  c.fillStyle = 'rgba(18,24,44,.98)';
  D.rr(c, p.x, p.y, p.w, p.h, 20); c.fill();
  c.strokeStyle = alpha('#ffc63a', .45); c.lineWidth = 2; c.stroke();
  D.text(c, title, S.cx, p.y + 28, 19, '#ffe08a', 'center', 900, 10);
  if (sub) D.text(c, sub, S.cx, p.y + 52, 13, '#9fb3c9', 'center', 600);
  c.restore();
  return p;
}

/* close button, top-left of the panel (RTL: away from the title) */
function closeBtn() {
  const p = panel();
  return { x: p.x + 26, y: p.y + 26, r: 17 };
}
function drawClose(c) {
  const b = closeBtn();
  c.save();
  c.fillStyle = 'rgba(255,255,255,.1)';
  c.beginPath(); c.arc(b.x, b.y, b.r, 0, TAU); c.fill();
  D.text(c, '✕', b.x, b.y + 1, 15, '#cbd6f0', 'center', 800);
  c.restore();
}

/* ============================================================
   חיווט — drag each colour to its match
   ============================================================ */
function wires() {
  const COLORS = ['#ff4d6d', '#46f08a', '#21e6ff', '#ffc63a'];
  const right = U.shuffle([0, 1, 2, 3]);
  const links = new Map();       // left index -> right slot
  let drag = null;

  const rowY = (p, i) => p.y + 110 + i * ((p.h - 150) / 3);
  const leftX = p => p.x + p.w - 54;      // RTL: sources on the right
  const rightX = p => p.x + 54;

  return {
    done: false,
    draw(c) {
      const p = frame(c, TASK_NAMES.wires, 'גרור כל צבע אל הצבע הזהה');
      c.save();
      // finished links
      for (const [li, ri] of links) {
        c.strokeStyle = COLORS[li]; c.lineWidth = 9; c.lineCap = 'round';
        c.shadowBlur = 14; c.shadowColor = COLORS[li];
        c.beginPath(); c.moveTo(leftX(p), rowY(p, li)); c.lineTo(rightX(p), rowY(p, ri)); c.stroke();
      }
      if (drag) {
        c.strokeStyle = COLORS[drag.i]; c.lineWidth = 7; c.globalAlpha = .8;
        c.shadowBlur = 12; c.shadowColor = COLORS[drag.i];
        c.beginPath(); c.moveTo(leftX(p), rowY(p, drag.i)); c.lineTo(drag.x, drag.y); c.stroke();
        c.globalAlpha = 1;
      }
      c.shadowBlur = 0;
      for (let i = 0; i < 4; i++) {
        c.fillStyle = COLORS[i];
        D.rr(c, leftX(p) - 4, rowY(p, i) - 15, 26, 30, 6); c.fill();
        c.fillStyle = COLORS[right[i]];
        D.rr(c, rightX(p) - 22, rowY(p, i) - 15, 26, 30, 6); c.fill();
      }
      c.restore();
      drawClose(c);
    },
    onDown(pt) {
      const p = panel();
      for (let i = 0; i < 4; i++) {
        if (links.has(i)) continue;
        if (U.dist(pt.x, pt.y, leftX(p), rowY(p, i)) < 34) {
          drag = { i, x: pt.x, y: pt.y }; Sound.play('click'); haptic(6); return;
        }
      }
    },
    onMove(pt) { if (drag) { drag.x = pt.x; drag.y = pt.y; } },
    onUp(pt) {
      if (!drag) return;
      const p = panel();
      for (let s = 0; s < 4; s++) {
        if (U.dist(pt.x, pt.y, rightX(p), rowY(p, s)) < 40 && right[s] === drag.i) {
          links.set(drag.i, s);
          Sound.play('lock'); haptic(12);
          FX.burst(rightX(p), rowY(p, s), 8, COLORS[drag.i], { speed: 120, life: .35, size: 3 });
          if (links.size === 4) this.done = true;
          break;
        }
      }
      drag = null;
    }
  };
}

/* ============================================================
   הורדת נתונים — hold to transfer
   ============================================================ */
function download() {
  let pct = 0, holding = false, beep = 0;
  return {
    done: false,
    update(dt) {
      if (!holding) return;
      pct = Math.min(1, pct + dt * .34);
      beep -= dt;
      if (beep <= 0) { beep = .18; Sound.tone(500 + pct * 700, .05, 'square', .12); }
      if (pct >= 1) { this.done = true; Sound.play('lock'); haptic(16); }
    },
    draw(c) {
      const p = frame(c, TASK_NAMES.download, holding ? 'מוריד…' : 'החזק את האצבע כדי להוריד');
      const bw = p.w - 80, bx = p.x + 40, by = p.y + p.h / 2 - 16;
      D.bar(c, bx, by, bw, 26, pct, '#46f08a');
      D.text(c, Math.round(pct * 100) + '%', S.cx, by + 13, 15, '#04120a', 'center', 900);
      c.save();
      c.globalAlpha = holding ? 1 : .6 + .25 * Math.sin(Game.t * 4);
      c.fillStyle = holding ? '#46f08a' : 'rgba(255,255,255,.12)';
      D.rr(c, S.cx - 80, p.y + p.h - 92, 160, 52, 16); c.fill();
      c.restore();
      D.text(c, 'החזק', S.cx, p.y + p.h - 66, 18, holding ? '#04120a' : '#e9eefb', 'center', 900);
      drawClose(c);
    },
    onDown(pt) {
      const p = panel();
      if (pt.y > p.y + p.h - 100 && pt.y < p.y + p.h - 30 && Math.abs(pt.x - S.cx) < 90) {
        holding = true; haptic(8);
      }
    },
    onUp() { holding = false; }
  };
}

/* ============================================================
   העברת כרטיס — swipe at the right speed (and yes, it can fail)
   ============================================================ */
function swipe() {
  let cardX = null, t0 = 0, msg = '', msgT = 0, startX = 0;
  const slotY = () => panel().y + 150;
  return {
    done: false,
    update(dt) { if (msgT > 0) msgT -= dt; },
    draw(c) {
      const p = frame(c, TASK_NAMES.swipe, 'העבר את הכרטיס — לא מהר מדי, לא לאט מדי');
      c.save();
      // reader slot
      c.fillStyle = '#0e1428'; D.rr(c, p.x + 30, slotY() - 6, p.w - 60, 64, 10); c.fill();
      c.strokeStyle = 'rgba(255,255,255,.16)'; c.lineWidth = 1.5; c.stroke();
      // card
      const cx = cardX ?? p.x + p.w - 90;
      const g = c.createLinearGradient(cx - 46, 0, cx + 46, 0);
      g.addColorStop(0, '#ffe08a'); g.addColorStop(1, '#ffab3d');
      c.fillStyle = g; c.shadowBlur = 14; c.shadowColor = '#ffc63a';
      D.rr(c, cx - 46, slotY(), 92, 52, 8); c.fill();
      c.shadowBlur = 0;
      c.fillStyle = 'rgba(0,0,0,.25)'; D.rr(c, cx - 34, slotY() + 12, 46, 9, 4); c.fill();
      c.restore();
      if (msgT > 0) D.text(c, msg, S.cx, p.y + p.h - 70, 17, msg.includes('אושר') ? '#9dffc6' : '#ff8a9d', 'center', 900, 10);
      else D.text(c, 'החלק שמאלה', S.cx, p.y + p.h - 70, 14, '#9fb3c9', 'center', 700);
      drawClose(c);
    },
    onDown(pt) {
      const p = panel();
      if (pt.y > slotY() - 30 && pt.y < slotY() + 82 && pt.x > p.x + p.w - 170) {
        cardX = pt.x; startX = pt.x; t0 = Game.t; haptic(6);
      }
    },
    onMove(pt) {
      if (cardX === null) return;
      const p = panel();
      cardX = U.clamp(pt.x, p.x + 60, p.x + p.w - 60);
    },
    onUp() {
      if (cardX === null) return;
      const p = panel();
      const travelled = startX - cardX;
      const dur = Game.t - t0;
      if (travelled > p.w * .5) {
        if (dur < .3) { msg = 'מהר מדי — נסה שוב'; msgT = 1.8; Sound.play('no'); haptic(20); }
        else if (dur > 1.4) { msg = 'לאט מדי — נסה שוב'; msgT = 1.8; Sound.play('no'); haptic(20); }
        else {
          msg = 'הכרטיס אושר'; msgT = 1.2; this.done = true;
          Sound.play('lock'); haptic(14);
        }
      }
      cardX = null;
    }
  };
}

/* ============================================================
   כיול מפזר — stop the sweep inside the gate, three times
   ============================================================ */
function calibrate() {
  let ring = 0, a = 0, spd = 2.2, gate = U.rnd(TAU), flash = 0, bad = 0;
  const R = () => Math.min(panel().w, panel().h) * .27;
  return {
    done: false,
    update(dt) {
      if (this.done) return;
      a = (a + spd * dt) % TAU;
      if (flash > 0) flash -= dt;
      if (bad > 0) bad -= dt;
    },
    draw(c) {
      const p = frame(c, TASK_NAMES.calibrate, `הקש כשהסמן בתוך השער — ${ring}/3`);
      const cy = p.y + p.h / 2 + 10, r = R();
      c.save();
      D.ring(c, S.cx, cy, r, '#2f3c66', 10, 1);
      // gate
      c.strokeStyle = bad > 0 ? '#ff4d6d' : '#46f08a'; c.lineWidth = 12; c.lineCap = 'round';
      c.shadowBlur = 16; c.shadowColor = c.strokeStyle;
      c.beginPath(); c.arc(S.cx, cy, r, gate - .34, gate + .34); c.stroke();
      c.restore();
      // sweep
      const hx = S.cx + Math.cos(a) * r, hy = cy + Math.sin(a) * r;
      D.glowCircle(c, hx, hy, 11, flash > 0 ? '#ffffff' : '#ffc63a', 1);
      D.text(c, `${ring} / 3`, S.cx, cy, 30, '#e9eefb', 'center', 900, 8);
      drawClose(c);
    },
    onDown(pt) {
      const p = panel();
      if (pt.y < p.y + 60) return;               // let the close button through
      const diff = Math.abs(U.angDiff(a, gate));
      if (diff < .36) {
        ring++; flash = .2; spd += .55; gate = U.rnd(TAU);
        Sound.play('lock'); haptic(12);
        const cy = p.y + p.h / 2 + 10;
        FX.burst(S.cx + Math.cos(a) * R(), cy + Math.sin(a) * R(), 10, '#46f08a', { speed: 150, life: .4, size: 3.5 });
        if (ring >= 3) { this.done = true; }
      } else {
        bad = .35; ring = 0; spd = 2.2;
        Sound.play('no'); haptic(22); FX.shake(4);
      }
    }
  };
}

/* ============================================================
   ניקוי אסטרואידים — tap them out of the sky
   ============================================================ */
function asteroids() {
  const rocks = [];
  let left = 8, spawn = 0;
  const P = () => panel();
  return {
    done: false,
    update(dt) {
      spawn -= dt;
      const p = P();
      if (spawn <= 0 && rocks.length < 5 && left > rocks.length) {
        spawn = U.rnd(.35, .8);
        const edge = Math.random() < .5;
        rocks.push({
          x: edge ? p.x + 20 : p.x + p.w - 20,
          y: U.rnd(p.y + 90, p.y + p.h - 50),
          vx: (edge ? 1 : -1) * U.rnd(35, 80), vy: U.rnd(-26, 26),
          r: U.rnd(15, 24), rot: U.rnd(TAU), spin: U.rnd(-1.6, 1.6)
        });
      }
      for (let i = rocks.length - 1; i >= 0; i--) {
        const a = rocks[i];
        a.x += a.vx * dt; a.y += a.vy * dt; a.rot += a.spin * dt;
        if (a.y < p.y + 80 || a.y > p.y + p.h - 34) a.vy *= -1;
        if (a.x < p.x + 14 || a.x > p.x + p.w - 14) a.vx *= -1;
      }
    },
    draw(c) {
      const p = frame(c, TASK_NAMES.asteroids, `הקש על האסטרואידים — נותרו ${left}`);
      c.save();
      for (const a of rocks) {
        c.save(); c.translate(a.x, a.y); c.rotate(a.rot);
        c.fillStyle = '#6b7391'; c.strokeStyle = '#aab3d0'; c.lineWidth = 2;
        c.beginPath();
        for (let i = 0; i < 7; i++) {
          const ang = i * TAU / 7;
          const rr = a.r * (i % 2 ? .78 : 1);
          c.lineTo(Math.cos(ang) * rr, Math.sin(ang) * rr);
        }
        c.closePath(); c.fill(); c.stroke();
        c.restore();
      }
      c.restore();
      drawClose(c);
    },
    onDown(pt) {
      for (let i = rocks.length - 1; i >= 0; i--) {
        const a = rocks[i];
        if (U.dist(pt.x, pt.y, a.x, a.y) < a.r + 12) {
          rocks.splice(i, 1); left--;
          Sound.play('shoot'); haptic(8);
          FX.burst(a.x, a.y, 12, '#cbd6f0', { speed: 190, life: .4, size: 3.4 });
          FX.ring(a.x, a.y, '#ffc63a', 6, 40, 2.5, .25);
          if (left <= 0) { this.done = true; Sound.play('lock'); }
          return;
        }
      }
    }
  };
}

const BUILDERS = { wires, download, swipe, calibrate, asteroids };

/** Opens a mini-game controller for a task type. */
export function openTask(type) {
  const build = BUILDERS[type] || BUILDERS.download;
  const g = build();
  g.type = type;
  g.hitClose = pt => U.dist(pt.x, pt.y, closeBtn().x, closeBtn().y) < closeBtn().r + 10;
  return g;
}
