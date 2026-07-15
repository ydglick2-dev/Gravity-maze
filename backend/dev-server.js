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

const PORT = +(process.argv[2] || 8787);
const FILE = path.join(__dirname, 'dev-data.json');
let doc = { users: {}, gifts: {}, summon: {}, bans: {}, live: {}, mm: null };
try { doc = Object.assign(doc, JSON.parse(fs.readFileSync(FILE, 'utf8'))); } catch (e) { }
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
  if (g.dtro) { const n = g.dtro | 0, e = sv.et === undefined ? (sv.tl | 0) : (sv.et | 0); sv.tro = Math.max(0, (sv.tro | 0) - n); sv.et = Math.max(0, e - n); }
  if (g.dco) { const n = g.dco | 0, e = sv.ec === undefined ? (sv.co | 0) : (sv.ec | 0); sv.co = Math.max(0, (sv.co | 0) - n); sv.ec = Math.max(0, e - n); }
  if (g.dgm) sv.gm = Math.max(0, (sv.gm | 0) - (g.dgm | 0));
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
      doc = b;
      for (const k of ['users', 'gifts', 'summon', 'bans', 'live']) if (!doc[k]) doc[k] = {};
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
      const u = String(b.u || '').slice(0, 14);
      if (!u || !b.rec || !b.rec.s || !b.rec.h) return J({ err: 'bad' }, 400);
      if (doc.users[u]) return J({ err: 'taken' }, 409);
      doc.users[u] = { s: b.rec.s, h: b.rec.h, c: Date.now() };
      persist(); return J({ ok: 1 });
    }
    if (p === '/api/pw' && req.method === 'POST') {
      const u = String(b.u || '');
      if (!doc.users[u]) return J({ err: 'nouser' }, 404);
      if (!b.rec || !b.rec.s || !b.rec.h) return J({ err: 'bad' }, 400);
      doc.users[u].s = b.rec.s; doc.users[u].h = b.rec.h;
      persist(); return J({ ok: 1 });
    }
    if (p === '/api/save' && req.method === 'POST') {
      const u = String(b.u || '');
      if (!doc.users[u]) return J({ err: 'nouser' }, 404);
      const cur = doc.users[u].sv;
      if (!b.force && cur && score(cur) > score(b.sv)) return J({ ok: 1, kept: 'server', sv: cur });
      doc.users[u].sv = b.sv;
      persist(); return J({ ok: 1, kept: 'client' });
    }
    if (p === '/api/gift' && req.method === 'POST') {
      const t = String(b.t || '');
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
      if (g.unl) q.unl = Math.max(q.unl | 0, g.unl | 0);
      if (g.dnl) q.dnl = g.dnl | 0;
      if (g.clv) q.clv = g.clv;
      if ('clr' in g) q.clr = g.clr | 0;
      if (g.rst) q.rst = 1;
      doc.gifts[t] = q;
      doc.users[t].sv = applyReducing(doc.users[t].sv, g);
      persist(); notify(t); return J({ ok: 1 });
    }
    if (p === '/api/claim' && req.method === 'POST') {
      const u = String(b.u || '');
      const out = { gift: doc.gifts[u] || null, summon: doc.summon[u] || null, ban: doc.bans[u] || null };
      if (out.gift || out.summon) { delete doc.gifts[u]; delete doc.summon[u]; persist(); }
      return J(out);
    }
    if (p === '/api/ban' && req.method === 'POST') {
      const t = String(b.t || '');
      if (b.b) doc.bans[t] = b.b; else delete doc.bans[t];
      persist(); notify(t); return J({ ok: 1 });
    }
    if (p === '/api/summon' && req.method === 'POST') {
      const t = String(b.t || '');
      doc.summon[t] = b.s;
      persist(); notify(t); return J({ ok: 1 });
    }
    if (p === '/api/live' && req.method === 'POST') {
      doc.live[String(b.u || '')] = b.e;
      return J({ ok: 1 });
    }
    if (p === '/api/mm' && req.method === 'POST') { doc.mm = b.mm || null; persist(); return J({ ok: 1 }); }
    J({ err: 'notfound' }, 404);
  });
});

if (WebSocketServer) {
  const wss = new WebSocketServer({ server, path: '/ws' });
  wss.on('connection', (ws, req) => {
    const u = new URL(req.url, 'http://x').searchParams.get('u') || '';
    if (!socks.has(u)) socks.set(u, new Set());
    socks.get(u).add(ws);
    ws.on('close', () => { const s = socks.get(u); if (s) s.delete(ws); });
  });
}

server.listen(PORT, () => console.log('Gravity Maze backend (dev) → http://127.0.0.1:' + PORT));
