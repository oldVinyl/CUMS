// Only public shell assets are eligible. API, receipts and authenticated documents always use the network.
const CACHE = "harbour-public-shell-v1";
const SHELL = ["/offline.html", "/offline.css", "/app-icon.svg", "/manifest.webmanifest"];
self.addEventListener("install", event => { event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(SHELL))); });
self.addEventListener("activate", event => { event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith("harbour-public-shell-") && key !== CACHE).map(key => caches.delete(key)))).then(() => self.clients.claim())); });
self.addEventListener("fetch", event => {
 const req = event.request, url = new URL(req.url);
 if (req.method !== "GET" || url.origin !== self.location.origin || url.pathname.startsWith("/api/") || url.pathname.startsWith("/actuator/")) return;
 if (req.mode === "navigate") { event.respondWith(fetch(req).catch(() => caches.match("/offline.html"))); return; }
 if (!SHELL.includes(url.pathname) && !(url.pathname.startsWith("/assets/") && ["script", "style", "font", "image"].includes(req.destination))) return;
 event.respondWith(caches.match(req).then(hit => hit || fetch(req).then(response => { if (response.ok && response.type === "basic") { const copy=response.clone(); event.waitUntil(caches.open(CACHE).then(cache => cache.put(req, copy))); } return response; })));
});
