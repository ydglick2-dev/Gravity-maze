/* Gravity Maze — שרת פיתוח מקומי (Node.js)
   מיישם את אותו API בדיוק כמו worker.js — לבדיקות מקומיות,
   או כחלופה אם מעדיפים להריץ על שרת Node משלכם.

   הרצה:  npm i ws   ואז   node dev-server.js [port]
   ברירת מחדל: http://127.0.0.1:8787
*/
const http = require('http');
const fs = require('fs');
const path = require('path');
let WebSocketServer = null;
try { WebSocketServer = require('ws').WebSocketServer; } catch (e) {
  console.log('להתקנה עם WebSocket: npm i ws (רץ בינתיים בלי דחיפה מיידית)');
}

const cleanName = v => String(v || '')
  .replace(/[\u00ad\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]/g, '')
  .replace(/\s+/g, ' ').trim();
const BAD_WORDS = ['זונה','זונות', 'שרמוט', 'כוסאמק', 'כוסעמק', 'זדיינ', 'לזיינ', 'זיונ', 'חרא', 'מניאק', 'קוקסינל', 'מפגר', 'נאצי', 'זינ', 'כוס', 'הומו', 'fuck', 'shit', 'bitch', 'cunt', 'whore', 'slut', 'nigg', 'porn', 'dick', 'pussy', 'asshole', 'faggot', 'nazi', 'sex', 'סקס', 'אונס', 'סוטה', 'סוטימ', 'פדופיל', 'זרג', 'ציצ', 'אוננ', 'חרמנ', 'עירומ', 'בולבול', 'מטומטמ', 'דביל', 'אידיוט', 'זבל', 'מכוער', 'טיפש', 'penis', 'vagina', 'boobs', 'tits', 'dildo', 'hentai', 'milf', 'blowjob', 'handjob', 'orgasm', 'erotic', 'rape', 'nude', 'naked', 'sperm', 'xxx', 'zona', 'sharmuta', 'kusemek', 'kusamak', 'manyak', 'anal', 'horny'];
const nameBanned = v => {
  let n = String(v || '').toLowerCase();
  n = n.replace(/[ךםןףץ]/g, c => ({ 'ך': 'כ', 'ם': 'מ', 'ן': 'נ', 'ף': 'פ', 'ץ': 'צ' }[c] || c));
  const a = n.replace(/0/g, 'o').replace(/1/g, 'i').replace(/3/g, 'e').replace(/4/g, 'a').replace(/5/g, 's').replace(/7/g, 't').replace(/@/g, 'a').replace(/\$/g, 's').replace(/[^a-zא-ת]/g, '');
  const b = n.replace(/[^a-zא-ת]/g, '');
  const c = n.replace(/0/g, 'ו').replace(/1/g, 'י').replace(/[^a-zא-ת]/g, '');
  return BAD_WORDS.some(w => a.includes(w) || b.includes(w) || c.includes(w));
};
const PORT = +(process.argv[2] || 8787);
const FILE = path.join(__dirname, 'dev-data.json');
let doc = { users: {}, gifts: {}, summon: {}, bans: {}, live: {}, mm: null };
try { doc = Object.assign(doc, JSON.parse(fs.readFileSync(FILE, 'utf8'))); } catch (e) { }
if (!doc.snd) doc.snd = { v: 0, m: {} }; // 🔊 סאונדים לסקינים
const persist = () => { try { fs.writeFileSync(FILE, JSON.stringify(doc)); } catch (e) { } };

const socks = new Map(); // user -> Set<ws>
const notify = u => {
  const s = socks.get(u);
  if (s) for (const ws of s) { try { ws.send(JSON.stringify({ t: 'inbox' })); } catch (e) { } }
};

const score = sv => {
  if (!sv) return -1;
  let sc = 0;
  if (Array.isArray(sv.s)) for (const a of sv.s) sc += (a[0] ? 50 : 0) + (a[1] | 0) * 5;
  sc += (sv.tl | 0) * 3 + (sv.tm | 0) / 60 + (sv.rb | 0) / 10 + (sv.fd | 0) / 20;
  if (Array.isArray(sv.ow)) sc += sv.ow.length * 10;
  if (Array.isArray(sv.ac)) sc += sv.ac.length * 10;
  return sc;
};
const applyReducing = (sv, g) => {
  if (g.rst) return undefined;
  if (!sv) return sv;
  if (g.dnl) sv.un = Math.min(sv.un | 0, Math.max(0, (g.dnl | 0) - 1));
  if (g.clr !== undefined && sv.cl) delete sv.cl[Math.max(0, g.clr | 0)];
  if (g.dtro) { const n = g.dtro | 0, p = sv.pt | 0, u = Math.min(p, n); sv.pt = p - u; const e = sv.et === undefined ? (sv.tl | 0) : (sv.et | 0); sv.tro = Math.max(0, (sv.tro | 0) - n); sv.et = Math.max(0, e - (n - u)); }
  if (g.dco) { const n = g.dco | 0, p = sv.pc | 0, u = Math.min(p, n); sv.pc = p - u; const e = sv.ec === undefined ? (sv.co | 0) : (sv.ec | 0); sv.co = Math.max(0, (sv.co | 0) - n); sv.ec = Math.max(0, e - (n - u)); }
  if (g.dgm) sv.gm = Math.max(0, (sv.gm | 0) - (g.dgm | 0));
  if (Array.isArray(g.skx)) {
    sv.ow = Array.isArray(sv.ow) ? sv.ow : [0];
    g.skx.forEach(ix => { ix |= 0; if (ix <= 0) return; const k = sv.ow.indexOf(ix); if (k >= 0) sv.ow.splice(k, 1); });
    if (!sv.ow.includes(0)) sv.ow.unshift(0);
    if (!sv.ow.includes(sv.sk | 0)) sv.sk = 0;
  }
  return sv;
};

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type'
};

const server = http.createServer((req, res) => {
  const J = (o, s = 200) => { res.writeHead(s, { 'Content-Type': 'application/json', ...CORS }); res.end(JSON.stringify(o)); };
  if (req.method === 'OPTIONS') { res.writeHead(204, CORS); return res.end(); }
  const u2 = new URL(req.url, 'http://x');
  const p = u2.pathname;
  let raw = '';
  req.on('data', c => raw += c);
  req.on('end', () => {
    let b = {};
    try { b = JSON.parse(raw || '{}'); } catch (e) { }
    if ((p === '/' || p === '/api/ping') && req.method === 'GET')
      return J({ ok: 1, name: 'gravity-maze-backend', users: Object.keys(doc.users).length });
    if (p === '/api/doc' && req.method === 'GET') return J(doc);
    if (p === '/api/doc' && req.method === 'POST') {
      if (!b || !b.users) return J({ err: 'bad' }, 400);
      const snd0 = doc.snd;
      doc = b;
      for (const k of ['users', 'gifts', 'summon', 'bans', 'live']) if (!doc[k]) doc[k] = {};
      if (!doc.snd) doc.snd = snd0 || { v: 0, m: {} };
      persist(); return J({ ok: 1 });
    }
    if (p === '/api/import' && req.method === 'POST') {
      const src = b.doc || {}; let n = 0;
      for (const k in (src.users || {})) if (!doc.users[k]) { doc.users[k] = src.users[k]; n++; }
      for (const k in (src.gifts || {})) doc.gifts[k] = Object.assign(doc.gifts[k] || {}, src.gifts[k]);
      for (const k in (src.bans || {})) if (!doc.bans[k]) doc.bans[k] = src.bans[k];
      persist(); return J({ ok: 1, imported: n });
    }
    if (p === '/api/register' && req.method === 'POST') {
      const u = cleanName(b.u).slice(0, 14);
      if (!u || !b.rec || !b.rec.s || !b.rec.h) return J({ err: 'bad' }, 400);
      if (nameBanned(u)) return J({ err: 'badname' }, 400);
      if (doc.users[u]) return J({ err: 'taken' }, 409);
      doc.users[u] = { s: b.rec.s, h: b.rec.h, c: Date.now() };
      persist(); return J({ ok: 1 });
    }
    if (p === '/api/deluser' && req.method === 'POST') {
      const t = cleanName(b.t);
      if (!doc.users[t]) return J({ err: 'nouser' }, 404);
      for (const sect of ['users', 'gifts', 'summon', 'bans', 'live']) delete doc[sect][t];
      persist(); return J({ ok: 1 });
    }
    if (p === '/api/pw' && req.method === 'POST') {
      const u = cleanName(b.u);
      if (!doc.users[u]) return J({ err: 'nouser' }, 404);
      if (!b.rec || !b.rec.s || !b.rec.h) return J({ err: 'bad' }, 400);
      doc.users[u].s = b.rec.s; doc.users[u].h = b.rec.h;
      persist(); return J({ ok: 1 });
    }
    if (p === '/api/save' && req.method === 'POST') {
      const u = cleanName(b.u);
      if (!doc.users[u]) return J({ err: 'nouser' }, 404);
      const cur = doc.users[u].sv;
      if (!b.force && cur && score(cur) > score(b.sv)) return J({ ok: 1, kept: 'server', sv: cur });
      doc.users[u].sv = b.sv;
      persist(); return J({ ok: 1, kept: 'client' });
    }
    if (p === '/api/bcast' && req.method === 'POST') {
      const m = { f: cleanName(b.f).slice(0, 14) || 'גליקי', t: String(b.t || '').slice(0, 90), co: 0 };
      if (!m.t) return J({ err: 'bad' }, 400);
      let n = 0;
      for (const u in doc.users) {
        if (u === m.f) continue;
        const q = doc.gifts[u] || {};
        q.bmsg = [...(Array.isArray(q.bmsg) ? q.bmsg : []), m].slice(-20);
        doc.gifts[u] = q; n++;
      }
      persist();
      for (const u in doc.users) notify(u);
      return J({ ok: 1, sent: n });
    }
    if (p === '/api/gift' && req.method === 'POST') {
      const t = cleanName(b.t);
      if (!doc.users[t]) return J({ err: 'nouser' }, 404);
      const g = b.g || {}; const q = doc.gifts[t] || {};
      if (g.tro) q.tro = (q.tro | 0) + (g.tro | 0);
      if (g.co) q.co = (q.co | 0) + (g.co | 0);
      if (g.gm) q.gm = (q.gm | 0) + (g.gm | 0);
      if (g.ch) q.ch = (q.ch | 0) + (g.ch | 0);
      if (g.dtro) q.dtro = (q.dtro | 0) + (g.dtro | 0);
      if (g.dco) q.dco = (q.dco | 0) + (g.dco | 0);
      if (g.dgm) q.dgm = (q.dgm | 0) + (g.dgm | 0);
      if (g.un) q.un = 1; if (g.sk) q.sk = 1; if (g.st) q.st = 1;
      if (Array.isArray(g.ski)) q.ski = [...new Set([...(Array.isArray(q.ski) ? q.ski : []), ...g.ski.map(n => n | 0)])].slice(0, 64);
      if (Array.isArray(g.skx)) q.skx = [...new Set([...(Array.isArray(q.skx) ? q.skx : []), ...g.skx.map(n => n | 0)])].slice(0, 64);
      if (g.unl) q.unl = Math.max(q.unl | 0, g.unl | 0);
      if (g.rlk !== undefined) q.rlk = g.rlk ? 1 : 0;
      if (Array.isArray(g.bmsg)) q.bmsg = [...(Array.isArray(q.bmsg) ? q.bmsg : []), ...g.bmsg.map(m => ({ f: cleanName(m && m.f).slice(0, 14), t: String((m && m.t) || '').slice(0, 90), co: (m && m.co) | 0 }))].slice(-20);
      if (g.dnl) q.dnl = g.dnl | 0;
      if (g.clv) q.clv = g.clv;
      if ('clr' in g) q.clr = g.clr | 0;
      if (g.rst) q.rst = 1;
      doc.gifts[t] = q;
      doc.users[t].sv = applyReducing(doc.users[t].sv, g);
      persist(); notify(t); return J({ ok: 1 });
    }
    if (p === '/api/claim' && req.method === 'POST') {
      const u = cleanName(b.u);
      const out = { gift: doc.gifts[u] || null, summon: doc.summon[u] || null, ban: doc.bans[u] || null, season: doc.season || null, snv: doc.snd.v || 0 };
      if (out.gift || out.summon) { delete doc.gifts[u]; delete doc.summon[u]; persist(); }
      return J(out);
    }
    if (p === '/api/ban' && req.method === 'POST') {
      const t = cleanName(b.t);
      if (b.b) doc.bans[t] = b.b; else delete doc.bans[t];
      persist(); notify(t); return J({ ok: 1 });
    }
    if (p === '/api/summon' && req.method === 'POST') {
      const t = cleanName(b.t);
      doc.summon[t] = b.s;
      persist(); notify(t); return J({ ok: 1 });
    }
    if (p === '/api/live' && req.method === 'POST') {
      doc.live[cleanName(b.u)] = b.e;
      return J({ ok: 1 });
    }
    if (p === '/api/mm' && req.method === 'POST') { doc.mm = b.mm || null; persist(); return J({ ok: 1 }); }
    /* 🔊 סאונדים לסקינים — זהה ל-worker.js */
    if (p === '/api/sounds' && req.method === 'GET') return J({ v: doc.snd.v || 0, m: doc.snd.m || {} });
    if (p === '/api/sounds' && req.method === 'POST') {
      const k = String(b.k || '').toLowerCase().replace(/[^a-z0-9_-]/g, '').slice(0, 40);
      if (!k) return J({ err: 'bad' }, 400);
      if (b.dat === null || b.dat === undefined) delete doc.snd.m[k];
      else {
        const dat = String(b.dat);
        if (!/^data:audio\//.test(dat)) return J({ err: 'bad' }, 400);
        if (dat.length > 126000) return J({ err: 'big' }, 400);
        if (!(k in doc.snd.m) && Object.keys(doc.snd.m).length >= 48) return J({ err: 'full' }, 400);
        doc.snd.m[k] = dat;
      }
      doc.snd.v = Date.now();
      persist();
      for (const s of socks.values()) for (const ws of s) { try { ws.send(JSON.stringify({ t: 'inbox' })); } catch (e) { } }
      return J({ ok: 1, v: doc.snd.v });
    }
    J({ err: 'notfound' }, 404);
  });
});

if (WebSocketServer) {
  const wss = new WebSocketServer({ server, path: '/ws' });
  wss.on('connection', (ws, req) => {
    const u = cleanName(new URL(req.url, 'http://x').searchParams.get('u') || '');
    if (!socks.has(u)) socks.set(u, new Set());
    socks.get(u).add(ws);
    ws.on('close', () => { const s = socks.get(u); if (s) s.delete(ws); });
  });
}

server.listen(PORT, () => console.log('Gravity Maze backend (dev) → http://127.0.0.1:' + PORT));
