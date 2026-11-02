const C = 'sayim-v11';
self.addEventListener('install', e => { e.waitUntil(caches.open(C).then(c => c.add('/')).catch(() => {})); self.skipWaiting(); });
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(k => Promise.all(k.filter(x => x !== C).map(x => caches.delete(x)))).then(() => clients.claim()));
});
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (e.request.mode === 'navigate' && u.pathname === '/') {
    e.respondWith(fetch(e.request).then(r => {
      const kopya = r.clone();
      caches.open(C).then(c => c.put('/', kopya));
      return r;
    }).catch(() => caches.match('/')));
  }
});
