// offline: the app itself is cached, so it opens without a connection (downloads live in indexeddb)
const V = "stash-v2";
const SHELL = ["/app", "/app/index.html", "/app.js", "/style.css", "/icon.svg", "/manifest.webmanifest", "/icon-192.png", "/icon-512.png", "/icon-180.png"];
self.addEventListener("install", e => e.waitUntil(caches.open(V).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())));
self.addEventListener("activate", e => e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== V && k !== "art").map(k => caches.delete(k)))).then(() => self.clients.claim())));
self.addEventListener("fetch", e => {
  const u = new URL(e.request.url);
  if (e.request.method !== "GET") return;
  // the app: network first (stays fresh), cache when offline
  if (u.origin === location.origin && !u.pathname.startsWith("/api/")) {
    e.respondWith(fetch(e.request).then(r => { const c = r.clone(); caches.open(V).then(x => x.put(e.request, c)); return r; })
      .catch(() => caches.match(e.request).then(r => r || caches.match("/app/index.html"))));
    return;
  }
  // covers: cache first, so downloaded songs keep their art offline
  if (e.request.destination === "image") {
    e.respondWith(caches.open("art").then(c => c.match(e.request).then(hit => hit || fetch(e.request).then(r => { c.put(e.request, r.clone()); return r; }).catch(() => hit))));
  }
});
