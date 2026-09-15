/* 『もう一杯！』 オフライン起動用 Service Worker。
   Play ストア(TWA)配信の前提と、仕様 §20「オフライン起動可能」のため。

   方針:
   - index.html と manifest・アイコンは install 時にまとめて取る(core)
   - index.html は「ネット優先、だめなら cache」。更新をすぐ届けつつ、圏外でも起動する
   - art/*.json と voice/* は「cache 優先、裏でネットから取り直す」。18人ぶん 20MB を
     毎回取りに行かない。更新は次回起動で反映される
   VER を上げると古い cache を捨てる。本体の版(#ver)を上げたら一緒に上げること */
const VER = 'mou-ippai-v3.36';
const CORE = ['./', './index.html', './manifest.webmanifest',
              './icon-192.png', './icon-512.png', './icon-maskable-512.png', './apple-touch-icon.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VER).then(c => c.addAll(CORE)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== VER).map(k => caches.delete(k))))
              .then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;
  const isPage = req.mode === 'navigate' || url.pathname.endsWith('/index.html') || url.pathname.endsWith('/');
  const isAsset = /\/(art|voice)\//.test(url.pathname);
  if (isPage) {
    e.respondWith(fetch(req).then(r => { const cp = r.clone(); caches.open(VER).then(c => c.put('./index.html', cp)); return r; })
                  .catch(() => caches.match('./index.html')));
  } else if (isAsset) {
    e.respondWith(caches.match(req).then(hit => {
      const net = fetch(req).then(r => { if (r.ok) caches.open(VER).then(c => c.put(req, r.clone())); return r; }).catch(() => hit);
      return hit || net;
    }));
  } else {
    e.respondWith(fetch(req).then(r => { if (r.ok) caches.open(VER).then(c => c.put(req, r.clone())); return r; })
                  .catch(() => caches.match(req)));
  }
});
