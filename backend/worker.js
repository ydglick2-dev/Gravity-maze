/* Gravity Maze — שרת בקנד רשמי
   Cloudflare Worker + Durable Object (חינמי, תמיד פעיל, HTTPS)
   פריסה: ראו backend/README-he.md (שלוש פקודות)

   מה השרת נותן מעבר ל-textdb:
   - דחיפת WebSocket מיידית: מתנות/באנים/זימונים מגיעים תוך פחות משנייה
   - כתיבות אטומיות: אין יותר דריסות בין שחקנים שכותבים בו-זמנית
   - הכרעת שמירות בצד השרת (מי מתקדם יותר) — בטוח למכשירים מרובים
*/

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type'
};
/* מקלדות (בעיקר אייפון) מוסיפות תווי כיווניות בלתי-נראים לשמות — ואז "אותו שם"
   לא נמצא. מנקים כל שם שנכנס, בשני הצדדים. */
const cleanName = v => String(v || '')
  .replace(/[\u00ad\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]/g, '')
  .replace(/\s+/g, ' ').trim();
/* \ud83d\udeab \u05e1\u05d9\u05e0\u05d5\u05df \u05e7\u05dc\u05dc\u05d5\u05ea \u05d1\u05e9\u05de\u05d5\u05ea \u2014 \u05d6\u05d4\u05d4 \u05dc\u05e8\u05e9\u05d9\u05de\u05d4 \u05d1\u05e7\u05dc\u05d9\u05d9\u05e0\u05d8 (index.html) */
const BAD_WORDS = ['\u05d6\u05d5\u05e0\u05d4','\u05d6\u05d5\u05e0\u05d5\u05ea', '\u05e9\u05e8\u05de\u05d5\u05d8', '\u05db\u05d5\u05e1\u05d0\u05de\u05e7', '\u05db\u05d5\u05e1\u05e2\u05de\u05e7', '\u05d6\u05d3\u05d9\u05d9\u05e0', '\u05dc\u05d6\u05d9\u05d9\u05e0', '\u05d6\u05d9\u05d5\u05e0', '\u05d7\u05e8\u05d0', '\u05de\u05e0\u05d9\u05d0\u05e7', '\u05e7\u05d5\u05e7\u05e1\u05d9\u05e0\u05dc', '\u05de\u05e4\u05d2\u05e8', '\u05e0\u05d0\u05e6\u05d9', '\u05d6\u05d9\u05e0', '\u05db\u05d5\u05e1', '\u05d4\u05d5\u05de\u05d5', 'fuck', 'shit', 'bitch', 'cunt', 'whore', 'slut', 'nigg', 'porn', 'dick', 'pussy', 'asshole', 'faggot', 'nazi', 'sex', 'סקס', 'אונס', 'סוטה', 'סוטימ', 'פדופיל', 'זרג', 'ציצ', 'אוננ', 'חרמנ', 'עירומ', 'בולבול', 'מטומטמ', 'דביל', 'אידיוט', 'זבל', 'מכוער', 'טיפש', 'penis', 'vagina', 'boobs', 'tits', 'dildo', 'hentai', 'milf', 'blowjob', 'handjob', 'orgasm', 'erotic', 'rape', 'nude', 'naked', 'sperm', 'xxx', 'zona', 'sharmuta', 'kusemek', 'kusamak', 'manyak', 'anal', 'horny'];
const nameBanned = v => {
  let n = String(v || '').toLowerCase();
  n = n.replace(/[ךםןףץ]/g, c => ({ 'ך': 'כ', 'ם': 'מ', 'ן': 'נ', 'ף': 'פ', 'ץ': 'צ' }[c] || c));
  const a = n.replace(/0/g, 'o').replace(/1/g, 'i').replace(/3/g, 'e').replace(/4/g, 'a').replace(/5/g, 's').replace(/7/g, 't').replace(/@/g, 'a').replace(/\$/g, 's').replace(/[^a-zא-ת]/g, '');
  const b = n.replace(/[^a-zא-ת]/g, '');
  const c = n.replace(/0/g, 'ו').replace(/1/g, 'י').replace(/[^a-zא-ת]/g, '');
  return BAD_WORDS.some(w => a.includes(w) || b.includes(w) || c.includes(w));
};
/* הענן הישן — שחקנים עם גרסה ישנה של המשחק עדיין נרשמים ושומרים שם */
const TDB = 'https://textdb.dev/api/data/mzk-glk-reg-7g2k9-v1';
const J = (o, s = 200) => new Response(JSON.stringify(o), {
  status: s, headers: { 'Content-Type': 'application/json', ...CORS }
});

export default {
  async fetch(req, env) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const id = env.REG.idFromName('main');
    return env.REG.get(id).fetch(req);
  }
};

export class Registry {
  constructor(state) {
    this.state = state;
    this.doc = null;
    this.snd = null; // 🔊 אינדקס סאונדים לסקינים {v, ks:[...]} — הקבצים עצמם במפתחות snd:<k>
    this.socks = new Map(); // user -> Set<WebSocket>
    this._liveT = 0;
    this._syncT = 0;
  }

  /* 🌉 גשר ל-textdb: כל ~2 דקות מושכים משם שחקנים חדשים ושמירות מתקדמות יותר,
     כדי שגם מי שנרשם/משחק בגרסה ישנה של המשחק "ייקלט" אצל האדמין. */
  async tdbSync() {
    if (Date.now() - this._syncT < 120000) return;
    this._syncT = Date.now();
    try {
      const r = await fetch(TDB + '?t=' + Date.now(), { signal: AbortSignal.timeout(5000) });
      if (!r.ok) return;
      const tx = await r.text();
      if (!tx || !tx.trim()) return;
      const src = JSON.parse(tx);
      const d = this.doc;
      let changed = false;
      for (const k0 in (src.users || {})) {
        const k = cleanName(k0);
        if (!k || nameBanned(k)) continue; // 🚫 שמות עם קללות לא נקלטים מהענן הישן
        if (!d.users[k]) { d.users[k] = src.users[k0]; changed = true; continue; }
        const sv2 = src.users[k0] && src.users[k0].sv;
        if (sv2 && this.score(sv2) > this.score(d.users[k].sv)) { d.users[k].sv = sv2; changed = true; }
      }
      if (changed) await this.saveDoc();
    } catch (e) { }
  }

  async load() {
    if (!this.doc) {
      this.doc = (await this.state.storage.get('doc')) ||
        { users: {}, gifts: {}, summon: {}, bans: {}, live: {}, mm: null };
      for (const k of ['users', 'gifts', 'summon', 'bans', 'live'])
        if (!this.doc[k]) this.doc[k] = {};
    }
    if (!this.snd) this.snd = (await this.state.storage.get('sndIdx')) || { v: 0, ks: [] };
    return this.doc;
  }

  async saveDoc() { await this.state.storage.put('doc', this.doc); }

  notify(u, msg) {
    const set = this.socks.get(u);
    if (set) for (const ws of set) { try { ws.send(JSON.stringify(msg)); } catch (e) { } }
  }

  // זהה ל-saveScore בקליינט: מדדים שרק עולים
  score(sv) {
    if (!sv) return -1;
    let sc = 0;
    if (Array.isArray(sv.s)) for (const a of sv.s) sc += (a[0] ? 50 : 0) + (a[1] | 0) * 5;
    sc += (sv.tl | 0) * 3 + (sv.tm | 0) / 60 + (sv.rb | 0) / 10 + (sv.fd | 0) / 20;
    if (Array.isArray(sv.ow)) sc += sv.ow.length * 10;
    if (Array.isArray(sv.ac)) sc += sv.ac.length * 10;
    return sc;
  }

  // פעולות מפחיתות מוחלות מיד גם על השמירה שבשרת
  applyReducing(sv, g) {
    if (g.rst) return undefined;
    if (!sv) return sv;
    if (g.dnl) sv.un = Math.min(sv.un | 0, Math.max(0, (g.dnl | 0) - 1));
    if (g.clr !== undefined && sv.cl) delete sv.cl[Math.max(0, g.clr | 0)];
    if (g.dtro) { const n = g.dtro | 0, p = sv.pt | 0, u = Math.min(p, n); sv.pt = p - u; const e = sv.et === undefined ? (sv.tl | 0) : (sv.et | 0); sv.tro = Math.max(0, (sv.tro | 0) - n); sv.et = Math.max(0, e - (n - u)); }
    if (g.dco) { const n = g.dco | 0, p = sv.pc | 0, u = Math.min(p, n); sv.pc = p - u; const e = sv.ec === undefined ? (sv.co | 0) : (sv.ec | 0); sv.co = Math.max(0, (sv.co | 0) - n); sv.ec = Math.max(0, e - (n - u)); }
    if (g.dgm) sv.gm = Math.max(0, (sv.gm | 0) - (g.dgm | 0));
    if (Array.isArray(g.skx)) { // 🗑 הסרת סקין ספציפי — מיד גם בשמירה שבשרת
      sv.ow = Array.isArray(sv.ow) ? sv.ow : [0];
      g.skx.forEach(ix => { ix |= 0; if (ix <= 0) return; const k = sv.ow.indexOf(ix); if (k >= 0) sv.ow.splice(k, 1); });
      if (!sv.ow.includes(0)) sv.ow.unshift(0);
      if (!sv.ow.includes(sv.sk | 0)) sv.sk = 0;
    }
    return sv;
  }

  async fetch(req) {
    const url = new URL(req.url);
    const p = url.pathname;
    const d = await this.load();

    /* 🔌 WebSocket: דחיפה מיידית של דואר נכנס לשחקן */
    if (p === '/ws') {
      if (req.headers.get('Upgrade') !== 'websocket') return J({ err: 'ws' }, 400);
      const u = cleanName(url.searchParams.get('u') || '');
      const pair = new WebSocketPair();
      const client = pair[0], server = pair[1];
      server.accept();
      if (!this.socks.has(u)) this.socks.set(u, new Set());
      this.socks.get(u).add(server);
      const drop = () => { const s = this.socks.get(u); if (s) s.delete(server); };
      server.addEventListener('close', drop);
      server.addEventListener('error', drop);
      return new Response(null, { status: 101, webSocket: client });
    }

    let b = {};
    if (req.method === 'POST') { try { b = await req.json(); } catch (e) { } }

    if ((p === '/' || p === '/api/ping') && req.method === 'GET') {
      await this.tdbSync();
      return J({ ok: 1, name: 'gravity-maze-backend', users: Object.keys(d.users).length });
    }

    if (p === '/api/doc' && req.method === 'GET') { await this.tdbSync(); return J(d); }

    // תאימות לאחור: כתיבת מסמך מלא (משמש למעט מסלולים ישנים כמו matchmaking).
    // מחטאים מפתחות (שמות עם תווים סמויים ממכשירים ישנים) ולא נותנים לכתיבה
    // מיושנת למחוק משתמשים קיימים (?force=1 עוקף — לתחזוקת אדמין בלבד).
    if (p === '/api/doc' && req.method === 'POST') {
      if (!b || !b.users) return J({ err: 'bad' }, 400);
      for (const sect of ['users', 'gifts', 'summon', 'bans', 'live']) {
        const m = b[sect] || {};
        const out = {};
        for (const k0 in m) {
          const k = cleanName(k0);
          if (!k || nameBanned(k)) continue; // 🚫 קללות לא נכנסות גם בכתיבת מסמך מלא
          if (out[k] && sect === 'users') {
            const a = out[k], c = m[k0];
            out[k] = (this.score(c && c.sv) > this.score(a && a.sv)) ? c : a;
          } else out[k] = m[k0];
        }
        b[sect] = out;
      }
      if (url.searchParams.get('force') !== '1')
        for (const k in d.users) if (!b.users[k]) b.users[k] = d.users[k];
      this.doc = b;
      await this.saveDoc();
      return J({ ok: 1 });
    }

    // ייבוא חד-פעמי מ-textdb — לא דורס משתמשים קיימים
    if (p === '/api/import' && req.method === 'POST') {
      const src = b.doc || {};
      let n = 0;
      for (const k in (src.users || {})) if (!d.users[k] && !nameBanned(k)) { d.users[k] = src.users[k]; n++; }
      for (const k in (src.gifts || {})) d.gifts[k] = Object.assign(d.gifts[k] || {}, src.gifts[k]);
      for (const k in (src.bans || {})) if (!d.bans[k]) d.bans[k] = src.bans[k];
      await this.saveDoc();
      return J({ ok: 1, imported: n });
    }

    if (p === '/api/register' && req.method === 'POST') {
      const u = cleanName(b.u).slice(0, 14);
      if (!u || !b.rec || !b.rec.s || !b.rec.h) return J({ err: 'bad' }, 400);
      if (nameBanned(u)) return J({ err: 'badname' }, 400); // 🚫 שם לא הולם
      if (d.users[u]) return J({ err: 'taken' }, 409);
      d.users[u] = { s: b.rec.s, h: b.rec.h, c: Date.now() };
      await this.saveDoc();
      return J({ ok: 1 });
    }

    if (p === '/api/pw' && req.method === 'POST') {
      const u = cleanName(b.u);
      if (!d.users[u]) return J({ err: 'nouser' }, 404);
      if (!b.rec || !b.rec.s || !b.rec.h) return J({ err: 'bad' }, 400);
      d.users[u].s = b.rec.s;
      d.users[u].h = b.rec.h;
      await this.saveDoc();
      return J({ ok: 1 });
    }

    // שמירה: השרת מכריע — הגרסה עם יותר התקדמות מנצחת (force עוקף, לאיפוסים)
    if (p === '/api/save' && req.method === 'POST') {
      const u = cleanName(b.u);
      if (!d.users[u]) return J({ err: 'nouser' }, 404);
      const cur = d.users[u].sv;
      if (!b.force && cur && this.score(cur) > this.score(b.sv))
        return J({ ok: 1, kept: 'server', sv: cur });
      d.users[u].sv = b.sv;
      await this.saveDoc();
      return J({ ok: 1, kept: 'client' });
    }

    // מתנה/עונש: מיזוג אטומי לתור + דחיפה מיידית ב-WS
    if (p === '/api/gift' && req.method === 'POST') {
      const t = cleanName(b.t);
      if (!d.users[t]) return J({ err: 'nouser' }, 404);
      const g = b.g || {};
      const q = d.gifts[t] || {};
      if (g.tro) q.tro = (q.tro | 0) + (g.tro | 0);
      if (g.co) q.co = (q.co | 0) + (g.co | 0);
      if (g.gm) q.gm = (q.gm | 0) + (g.gm | 0);
      if (g.ch) q.ch = (q.ch | 0) + (g.ch | 0);
      if (g.dtro) q.dtro = (q.dtro | 0) + (g.dtro | 0);
      if (g.dco) q.dco = (q.dco | 0) + (g.dco | 0);
      if (g.dgm) q.dgm = (q.dgm | 0) + (g.dgm | 0);
      if (g.un) q.un = 1;
      if (g.sk) q.sk = 1;
      if (g.st) q.st = 1;
      if (Array.isArray(g.ski)) q.ski = [...new Set([...(Array.isArray(q.ski) ? q.ski : []), ...g.ski.map(n => n | 0)])].slice(0, 64);
      if (Array.isArray(g.skx)) q.skx = [...new Set([...(Array.isArray(q.skx) ? q.skx : []), ...g.skx.map(n => n | 0)])].slice(0, 64);
      if (g.unl) q.unl = Math.max(q.unl | 0, g.unl | 0);
      if (g.rlk !== undefined) q.rlk = g.rlk ? 1 : 0; // 🔒 נעילת יצירת חשבונות במכשיר היעד
      if (g.dnl) q.dnl = g.dnl | 0;
      if (g.clv) q.clv = g.clv;
      if ('clr' in g) q.clr = g.clr | 0;
      if (g.rst) q.rst = 1;
      d.gifts[t] = q;
      d.users[t].sv = this.applyReducing(d.users[t].sv, g);
      await this.saveDoc();
      this.notify(t, { t: 'inbox' });
      return J({ ok: 1 });
    }

    // משיכת הדואר: אטומי — אין סיכוי לאבד מתנה
    if (p === '/api/claim' && req.method === 'POST') {
      await this.tdbSync();
      const u = cleanName(b.u);
      const out = { gift: d.gifts[u] || null, summon: d.summon[u] || null, ban: d.bans[u] || null, season: d.season || null, snv: this.snd.v || 0 };
      if (out.gift || out.summon) {
        delete d.gifts[u];
        delete d.summon[u];
        await this.saveDoc();
      }
      return J(out);
    }

    if (p === '/api/ban' && req.method === 'POST') {
      const t = cleanName(b.t);
      if (b.b) d.bans[t] = b.b; else delete d.bans[t];
      await this.saveDoc();
      this.notify(t, { t: 'inbox' });
      return J({ ok: 1 });
    }

    if (p === '/api/summon' && req.method === 'POST') {
      const t = cleanName(b.t);
      d.summon[t] = b.s;
      await this.saveDoc();
      this.notify(t, { t: 'inbox' });
      return J({ ok: 1 });
    }

    if (p === '/api/live' && req.method === 'POST') {
      const u = cleanName(b.u);
      d.live[u] = b.e;
      if (Date.now() - this._liveT > 60000) { this._liveT = Date.now(); await this.saveDoc(); }
      return J({ ok: 1 });
    }

    if (p === '/api/mm' && req.method === 'POST') {
      d.mm = b.mm || null;
      await this.saveDoc();
      return J({ ok: 1 });
    }

    /* 🔊 סאונדים לסקינים — קליפים קצרים שהאדמין מעלה (הקלטות שלו);
       כל שחקן מוריד פעם אחת לפי גרסה (snv ב-claim) ושומר מקומית.
       כל קליפ במפתח אחסון משלו — מגבלת Durable Object היא 128KB לערך. */
    if (p === '/api/sounds' && req.method === 'GET') {
      const m = {};
      for (const k of this.snd.ks) {
        const v = await this.state.storage.get('snd:' + k);
        if (v) m[k] = v;
      }
      return J({ v: this.snd.v || 0, m });
    }
    if (p === '/api/sounds' && req.method === 'POST') {
      const k = String(b.k || '').toLowerCase().replace(/[^a-z0-9_-]/g, '').slice(0, 40);
      if (!k) return J({ err: 'bad' }, 400);
      if (b.dat === null || b.dat === undefined) {
        await this.state.storage.delete('snd:' + k);
        this.snd.ks = this.snd.ks.filter(x => x !== k);
      } else {
        const dat = String(b.dat);
        if (!/^data:audio\//.test(dat)) return J({ err: 'bad' }, 400);
        if (dat.length > 126000) return J({ err: 'big' }, 400);
        if (!this.snd.ks.includes(k)) {
          if (this.snd.ks.length >= 48) return J({ err: 'full' }, 400);
          this.snd.ks.push(k);
        }
        await this.state.storage.put('snd:' + k, dat);
      }
      this.snd.v = Date.now();
      await this.state.storage.put('sndIdx', this.snd);
      // כולם מקבלים דחיפה — הסאונד החדש נטען אצלם תוך שניות
      for (const set of this.socks.values())
        for (const ws of set) { try { ws.send(JSON.stringify({ t: 'inbox' })); } catch (e) { } }
      return J({ ok: 1, v: this.snd.v });
    }

    return J({ err: 'notfound' }, 404);
  }
}
