// Offline support. Network-first for the app's own files (revalidating past the HTTP cache so
// updates arrive as soon as the phone is online), falling back to the saved copy offline.
// Bump VERSION on every release.
const VERSION = '8';
const CACHE = 'ten-eight-v' + VERSION;
const FILES = ['./', 'index.html', 'js/standalone.js', 'js/vendor/jszip.min.js', 'js/vendor/jspdf.umd.min.js', 'js/vendor/jspdf.plugin.autotable.min.js', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png', 'icons/icon-32.png', 'manifest.webmanifest'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(FILES.map((f) => new Request(f, { cache: 'reload' })))));
  self.skipWaiting();
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) {
    // Fonts: cache after first use so the app looks right offline.
    if (/fonts\.(googleapis|gstatic)\.com$/.test(url.host)) {
      e.respondWith(caches.open(CACHE).then((c) => c.match(req).then((hit) => hit || fetch(req).then((res) => { c.put(req, res.clone()); return res; }))));
    }
    return;
  }
  e.respondWith(fetch(req.url, { cache: 'no-cache', credentials: 'same-origin' }).then((res) => {
    if (res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); }
    return res;
  }).catch(() => caches.match(req, { ignoreSearch: true }).then((r) => r || (req.mode === 'navigate' ? caches.match('./') : undefined))));
});
