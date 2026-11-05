const CACHE = 'upi-demo-shell-v3';
self.addEventListener('install', event => event.waitUntil((async () => {
  const cache = await caches.open(CACHE);
  await cache.add('/');
  await self.skipWaiting();
})()));
self.addEventListener('activate', event => event.waitUntil((async () => {
  for (const key of await caches.keys()) if (key !== CACHE) await caches.delete(key);
  await self.clients.claim();
})()));
self.addEventListener('fetch', event => {
  const request = event.request;
  if (request.method !== 'GET' || new URL(request.url).origin !== self.location.origin) return;
  if (request.mode === 'navigate') event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    try { const response = await fetch(request); if (response.ok) await cache.put('/', response.clone()); return response; }
    catch { return (await cache.match('/')) || Response.error(); }
  })());
});
