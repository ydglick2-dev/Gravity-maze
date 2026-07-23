const CACHE = 'maze-ultra-v157';
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

/* 🔔 Web Push — התראות גם כשהמשחק סגור. הדחיפה ריקה (בלי תוכן מוצפן),
   ולכן מוצגת הודעה כללית; הפרטים מחכים בתוך המשחק */
self.addEventListener('push', e => {
  e.waitUntil(self.registration.showNotification('Gravity Maze 🎮', {
    body: 'יש משהו חדש! 💬🎁 הודעה, מתנה או ברכה מחכה לך במשחק',
    icon: 'icon-192.png',
    badge: 'icon-192.png',
    tag: 'mzk-inbox',
    renotify: true,
    dir: 'rtl',
    lang: 'he',
    vibrate: [60, 40, 60]
  }));
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
