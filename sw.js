const CACHE = 'maze-ultra-v174';
const ASSETS = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png'
];

self.addEventListener('install', e => {
  // מתקינים מיד — כדי ששחקנים לא ייתקעו על גרסה מקושרת ישנה שנתקעה במטמון
  self.skipWaiting();
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)));
});

self.addEventListener('message', e => {
  if (e.data === 'SKIP_WAITING') self.skipWaiting();
});

/* 🔔 Web Push — התראות גם כשהמשחק סגור. הדחיפה עצמה ריקה (בלי תוכן מוצפן),
   אז שואלים את השרת "מה חדש?" ומציגים את תוכן ההתראה האמיתי; אם אין
   חיבור (או שהחדשות כבר נאספו) — נופלים להודעה הכללית */
const PUSH_BE = 'https://gravity-maze-backend.gravity-maze.workers.dev';
self.addEventListener('push', e => {
  e.waitUntil((async () => {
    let title = 'Gravity Maze 🎮';
    let body = 'יש משהו חדש! 💬🎁 הודעה, מתנה או ברכה מחכה לך במשחק';
    try {
      const sub = await self.registration.pushManager.getSubscription();
      if (sub) {
        const r = await fetch(PUSH_BE + '/api/pushpeek', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ ep: sub.endpoint })
        });
        const j = await r.json();
        if (j && j.body) { body = j.body; if (j.title) title = j.title; }
      }
    } catch (err) {}
    return self.registration.showNotification(title, {
      body,
      icon: 'icon-192.png',
      badge: 'icon-192.png',
      tag: 'mzk-inbox',
      renotify: true,
      dir: 'rtl',
      lang: 'he',
      vibrate: [60, 40, 60]
    });
  })());
});
self.addEventListener('notificationclick', e => {
  e.notification.close();
  e.waitUntil(clients.matchAll({ type: 'window', includeUncontrolled: true }).then(cs => {
    for (const c of cs) { if ('focus' in c) return c.focus(); }
    return clients.openWindow('./index.html');
  }));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  // ניווט: קודם רשת (כדי לקבל עדכונים), נפילה למטמון באופליין
  if (e.request.mode === 'navigate') {
    e.respondWith(
      fetch(e.request).then(res => {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put('./index.html', copy));
        return res;
      }).catch(() => caches.match('./index.html'))
    );
    return;
  }
  // נכסים: קודם מטמון
  e.respondWith(
    caches.match(e.request).then(hit => {
      if (hit) return hit;
      return fetch(e.request).then(res => {
        if (res.ok && new URL(e.request.url).origin === self.location.origin) {
          const copy = res.clone();
          caches.open(CACHE).then(c => c.put(e.request, copy));
        }
        return res;
      });
    })
  );
});
