/* NOVA-9 service worker — offline-first, cache-busted by version */
const V = 'nova9-v1';
const ASSETS = [
  './', './index.html', './manifest.webmanifest',
  './css/style.css',
  './js/core.js', './js/ui.js', './js/main.js',
  './js/modes/tap.js', './js/modes/puzzle.js', './js/modes/rogue.js',
  './js/modes/impostor.js', './js/modes/arena.js',
  './icon-192.png', './icon-512.png', './icon-maskable-512.png'
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(V).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys => Promise.all(keys.filter(k => k !== V).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  e.respondWith(
    caches.match(req).then(hit => hit || fetch(req).then(res => {
      // keep the cache warm for anything same-origin we fetched at runtime
      if (res.ok && new URL(req.url).origin === location.origin) {
        const copy = res.clone();
        caches.open(V).then(c => c.put(req, copy));
      }
      return res;
    }).catch(() => caches.match('./index.html')))
  );
});
