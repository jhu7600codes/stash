// stash on the web: a pwa that behaves like the app. no build step, no framework.
// - search + audio through public piped servers (they fetch youtube, cors-friendly, no vpn needed)
// - your stash account's playlists and liked songs (supabase, same backup the phone makes)
// - downloads kept in indexeddb, so they play offline
// - karaoke lyrics (musixmatch word timing via /api/lyrics, lrclib otherwise)
"use strict";

const SUPABASE = "https://mcmnovtqupckpylrnobp.supabase.co";
const SB_KEY = "sb_publishable_uexeqwWBLSc9xTTCDrOUog_TcUe2Or7";
let PIPED = ["https://pipedapi.stellar.afs.ovh", "https://api.piped.private.coffee", "https://pipedapi.adminforge.de", "https://pipedapi.kavin.rocks"];

const $ = (s, el = document) => el.querySelector(s);
const h = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmt = ms => { if (!ms || ms < 0 || !isFinite(ms)) return "0:00"; const s = Math.floor(ms / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
const ls = { get: (k, d) => { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } }, set: (k, v) => localStorage.setItem(k, JSON.stringify(v)) };
function toast(msg) { const t = $("#toast"); t.textContent = msg; t.hidden = false; clearTimeout(toast.t); toast.t = setTimeout(() => t.hidden = true, 2400); }

// ---------- indexeddb: downloads ----------
const db = new Promise((ok, fail) => {
  const r = indexedDB.open("stash", 1);
  r.onupgradeneeded = () => r.result.createObjectStore("downloads", { keyPath: "id" });
  r.onsuccess = () => ok(r.result); r.onerror = () => fail(r.error);
});
async function idb(mode, fn) {
  const d = await db;
  return new Promise((ok, fail) => { const tx = d.transaction("downloads", mode); const req = fn(tx.objectStore("downloads")); tx.oncomplete = () => ok(req?.result); tx.onerror = () => fail(tx.error); });
}
let downloaded = new Set();
async function refreshDownloaded() { downloaded = new Set(await idb("readonly", s => s.getAllKeys())); }

// ---------- tracks ----------
// a track: {id, title, artist, art, duration, provider, source}
const videoIdOf = t => {
  if (t.videoId) return t.videoId;
  const m = /(?:[?&]v=|youtu\.be\/)([\w-]{11})/.exec(t.source || "");
  return t.provider === "youtube" && m ? m[1] : null;
};
const fromPiped = i => {
  const v = new URL(i.url, location.href).searchParams.get("v");
  return { id: "yt:" + v, videoId: v, title: i.title, artist: (i.uploaderName || "").replace(/ - Topic$/, ""), art: i.thumbnail, duration: (i.duration || 0) * 1000, provider: "youtube", source: "https://www.youtube.com/watch?v=" + v };
};

// ---------- piped ----------
async function piped(path) {
  for (const base of PIPED) {
    try {
      const r = await fetch(base + path, { signal: AbortSignal.timeout(9000) });
      if (r.ok) { // the one that answered goes first next time
        PIPED = [base, ...PIPED.filter(b => b !== base)];
        return await r.json();
      }
    } catch {}
  }
  throw new Error("No search server answered");
}
// more servers from the community list (http only, so through our own /api)
fetch("/api/instances").then(r => r.ok ? r.json() : []).then(list => { PIPED = [...new Set([...PIPED, ...list])]; }).catch(() => {});

async function search(q) {
  const d = await piped("/search?filter=music_songs&q=" + encodeURIComponent(q));
  let items = (d.items || []).filter(i => i.url?.includes("watch"));
  if (items.length < 3) items = items.concat(((await piped("/search?filter=videos&q=" + encodeURIComponent(q))).items || []).filter(i => i.url?.includes("watch")));
  return items.map(fromPiped);
}

const streamCache = new Map();
async function streamUrl(t) {
  let v = videoIdOf(t);
  if (!v) { // a soundcloud / bandcamp song from the app: find it on youtube
    const hits = await search(`${t.artist} ${t.title}`);
    if (!hits.length) throw new Error("Not found");
    v = t.videoId = hits[0].videoId;
  }
  const c = streamCache.get(v);
  if (c && c.until > Date.now()) return c.url;
  const s = await piped("/streams/" + v);
  const a = (s.audioStreams || []).slice().sort((x, y) => (y.bitrate || 0) - (x.bitrate || 0));
  // m4a first: plays everywhere, iphones included
  const pick = a.find(x => (x.mimeType || "").includes("mp4")) || a[0];
  if (!pick) throw new Error("No audio");
  streamCache.set(v, { url: pick.url, until: Date.now() + 3 * 3600e3 });
  if (!t.duration && s.duration) t.duration = s.duration * 1000;
  return pick.url;
}

// ---------- account (supabase) ----------
let session = ls.get("session", null);
async function auth(path, body) {
  const r = await fetch(SUPABASE + path, { method: "POST", headers: { apikey: SB_KEY, "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error_description || j.msg || j.message || "Login failed");
  return j;
}
function saveSession(j, email) {
  session = { email: j.user?.email || email, access: j.access_token, refresh: j.refresh_token, exp: Date.now() + (j.expires_in - 60) * 1000 };
  ls.set("session", session);
}
async function token() {
  if (!session) return null;
  if (Date.now() < session.exp) return session.access;
  try { saveSession(await auth("/auth/v1/token?grant_type=refresh_token", { refresh_token: session.refresh }), session.email); return session.access; }
  catch { return null; }
}
let library = ls.get("library", null); // [{kind, name, cover_url, tracks}]
async function syncLibrary() {
  const t = await token(); if (!t) return;
  const r = await fetch(SUPABASE + "/rest/v1/library_items?select=kind,name,cover_url,origin,tracks&order=sort_index", { headers: { apikey: SB_KEY, Authorization: "Bearer " + t } });
  if (!r.ok) return;
  library = (await r.json()).map(p => ({ ...p, tracks: (p.tracks || []).filter(x => !x.missing) }));
  ls.set("library", library);
  route();
}
const cloudLiked = () => library?.find(p => p.kind === "liked")?.tracks || [];
const playlists = () => (library || []).filter(p => p.kind !== "liked");
let localLikes = ls.get("likes", []);
const likedTracks = () => { const ids = new Set(localLikes.map(t => t.id)); return [...localLikes, ...cloudLiked().filter(t => !ids.has(t.id))]; };
const isLiked = t => likedTracks().some(x => x.id === t.id);
function toggleLike(t) {
  if (localLikes.some(x => x.id === t.id)) localLikes = localLikes.filter(x => x.id !== t.id);
  else localLikes.unshift(clean(t));
  ls.set("likes", localLikes); paintPlayer();
}
const clean = t => ({ id: t.id, title: t.title, artist: t.artist, art: t.art, duration: t.duration, provider: t.provider, source: t.source, videoId: t.videoId });

// ---------- player ----------
const audio = $("#audio");
let queue = [], qi = -1, shuffle = false, repeat = 0; // repeat: 0 off, 1 all, 2 one
let objectUrl = null, loadToken = 0;
const cur = () => queue[qi];

async function playList(list, i = 0, shuf = false) {
  queue = list.map(clean); shuffle = shuf;
  if (shuf) { for (let k = queue.length - 1; k > 0; k--) { const j = Math.floor(Math.random() * (k + 1)); [queue[k], queue[j]] = [queue[j], queue[k]]; } i = 0; }
  await load(i);
}
async function load(i) {
  if (i < 0 || i >= queue.length) return;
  qi = i; const t = cur(); const my = ++loadToken;
  const recent = ls.get("recent", []).filter(x => x.id !== t.id); recent.unshift(clean(t)); ls.set("recent", recent.slice(0, 30));
  paintPlayer(); setStatus("Loading…");
  try {
    let src;
    const saved = downloaded.has(t.id) ? await idb("readonly", s => s.get(t.id)) : null;
    if (saved) { if (objectUrl) URL.revokeObjectURL(objectUrl); src = objectUrl = URL.createObjectURL(saved.blob); }
    else src = await streamUrl(t);
    if (my !== loadToken) return;
    audio.src = src; await audio.play().catch(() => {});
    setStatus(""); prefetch();
  } catch (e) {
    if (my !== loadToken) return;
    setStatus("Couldn't play this one"); toast(navigator.onLine ? `Couldn't play ${t.title}, skipping` : "Offline: only downloaded songs play");
    setTimeout(() => { if (my === loadToken && qi < queue.length - 1) load(qi + 1); }, 1500);
  }
}
function prefetch() { const n = queue[qi + 1]; if (n && !downloaded.has(n.id)) streamUrl(n).catch(() => {}); }
const next = () => { if (qi < queue.length - 1) load(qi + 1); else if (repeat === 1) load(0); };
const prev = () => { if (audio.currentTime > 3 || qi === 0) audio.currentTime = 0; else load(qi - 1); };
audio.onended = () => repeat === 2 ? (audio.currentTime = 0, audio.play()) : next();
audio.onplay = audio.onpause = () => { paintPlay(); if ("mediaSession" in navigator) navigator.mediaSession.playbackState = audio.paused ? "paused" : "playing"; };
audio.ontimeupdate = () => {
  const d = audio.duration * 1000 || cur()?.duration || 0, p = audio.currentTime * 1000;
  $("#miniProgress div").style.width = d ? (p / d * 100) + "%" : "0";
  if (!seeking) { const v = d ? p / d * 1000 : 0; $("#pSeek").value = v; $("#pSeek").style.setProperty("--p", v / 10 + "%"); }
  $("#pPos").textContent = fmt(p); $("#pDur").textContent = fmt(d);
};
audio.onwaiting = () => setStatus("Buffering…"); audio.onplaying = () => setStatus("");
const setStatus = s => $("#pStatus").textContent = s;
const togglePlay = () => audio.src ? (audio.paused ? audio.play() : audio.pause()) : null;

if ("mediaSession" in navigator) {
  const ms = navigator.mediaSession;
  ms.setActionHandler("play", () => audio.play()); ms.setActionHandler("pause", () => audio.pause());
  ms.setActionHandler("nexttrack", next); ms.setActionHandler("previoustrack", prev);
  try { ms.setActionHandler("seekto", e => audio.currentTime = e.seekTime); } catch {}
}

function paintPlay() {
  const icon = `<span class="i ${audio.paused ? "i-play" : "i-pause"}"></span>`;
  $("#miniPlay").innerHTML = icon; $("#pPlay").innerHTML = icon;
  $("#pArt").classList.toggle("paused", audio.paused);
}
function paintPlayer() {
  const t = cur(); if (!t) return;
  $("#mini").hidden = false;
  $("#miniArt").src = $("#pArt").src = t.art || "/icon.svg";
  $("#miniTitle").textContent = $("#pTitle").textContent = t.title;
  $("#miniArtist").textContent = $("#pArtist").textContent = t.artist;
  $("#playerBg").style.backgroundImage = t.art ? `url("${t.art}")` : "";
  $("#pLike").classList.toggle("on", isLiked(t));
  $("#playerDl").innerHTML = `<span class="i ${downloaded.has(t.id) ? "i-done" : "i-dl"}"></span>`;
  $("#pShuffle").classList.toggle("on", shuffle); $("#pRepeat").classList.toggle("on", repeat > 0);
  $("#pRepeat").style.opacity = repeat === 2 ? .6 : 1;
  if ("mediaSession" in navigator) navigator.mediaSession.metadata = new MediaMetadata({ title: t.title, artist: t.artist, artwork: t.art ? [{ src: t.art, sizes: "512x512" }] : [] });
  paintPlay(); paintRows();
  if (!$("#lyrics").hidden) openLyrics(); if (!$("#queue").hidden) openQueue();
}
function paintRows() { document.querySelectorAll(".row[data-id]").forEach(r => r.classList.toggle("cur", r.dataset.id === cur()?.id)); }

// player sheet
const openPlayer = () => { $("#player").hidden = false; $("#player").classList.remove("closing"); };
function closePlayer() { const p = $("#player"); p.classList.add("closing"); setTimeout(() => { p.hidden = true; p.classList.remove("closing"); }, 260); }
$("#mini").onclick = e => { if (!e.target.closest("#miniPlay")) openPlayer(); };
$("#miniPlay").onclick = togglePlay; $("#pPlay").onclick = togglePlay;
$("#playerClose").onclick = closePlayer; $("#pNext").onclick = next; $("#pPrev").onclick = prev;
$("#pLike").onclick = () => cur() && toggleLike(cur());
$("#playerDl").onclick = () => cur() && download([cur()]);
$("#pShuffle").onclick = () => { shuffle = !shuffle; if (shuffle) { const rest = queue.splice(qi + 1); rest.sort(() => Math.random() - .5); queue.push(...rest); } paintPlayer(); };
$("#pRepeat").onclick = () => { repeat = (repeat + 1) % 3; paintPlayer(); toast(["Repeat off", "Repeat all", "Repeat one"][repeat]); };
let seeking = false;
$("#pSeek").oninput = e => { seeking = true; e.target.style.setProperty("--p", e.target.value / 10 + "%"); };
$("#pSeek").onchange = e => { seeking = false; if (audio.duration) audio.currentTime = e.target.value / 1000 * audio.duration; };
// swipe the sheet down to close
let sy = null;
$("#player").addEventListener("touchstart", e => { if (e.target.closest("#lyrics,#queue,#pSeek")) return; sy = e.touches[0].clientY; }, { passive: true });
$("#player").addEventListener("touchend", e => { if (sy !== null && e.changedTouches[0].clientY - sy > 120) closePlayer(); sy = null; });

// ---------- lyrics (karaoke) ----------
let lyr = null, lyrFor = null, raf = 0;
$("#pLyricsBtn").onclick = () => { if ($("#lyrics").hidden) openLyrics(); else showMain(); };
$("#pQueueBtn").onclick = () => { if ($("#queue").hidden) openQueue(); else showMain(); };
function showMain() { $("#lyrics").hidden = $("#queue").hidden = true; $("#playerMain").hidden = false; cancelAnimationFrame(raf); $("#pLyricsBtn").classList.remove("on"); $("#pQueueBtn").classList.remove("on"); }
async function openLyrics() {
  const t = cur(); if (!t) return;
  $("#playerMain").hidden = true; $("#queue").hidden = true; $("#lyrics").hidden = false; $("#pLyricsBtn").classList.add("on"); $("#pQueueBtn").classList.remove("on");
  if (lyrFor !== t.id) {
    lyrFor = t.id; lyr = null; $("#lyrics").innerHTML = `<p class="dim">Loading lyrics…</p>`;
    lyr = await getLyrics(t).catch(() => null);
    if (lyrFor !== t.id) return;
  }
  if (!lyr) { $("#lyrics").innerHTML = `<p class="dim">No lyrics for this one</p>`; return; }
  $("#lyrics").innerHTML = lyr.lines.map((l, i) => `<div class="line" data-i="${i}">${
    (lyr.words?.[i] || [[l[0], l[1]]]).map(([, w]) => [...w].map(c => `<span>${h(c)}</span>`).join("")).join("") || "♪"
  }${lyr.translated?.[i] ? `<span class="tr">${h(lyr.translated[i])}</span>` : ""}</div>`).join("");
  $("#lyrics").querySelectorAll(".line").forEach(el => el.onclick = () => { if (lyr.synced) audio.currentTime = lyr.lines[el.dataset.i][0] / 1000; });
  if (lyr.synced) karaoke();
}
async function getLyrics(t) {
  const q = `lang=${(navigator.language || "en").slice(0, 2)}&title=${encodeURIComponent(t.title)}&artist=${encodeURIComponent(t.artist)}&duration=${Math.round((t.duration || audio.duration * 1000 || 0) / 1000)}`;
  try { const r = await fetch("/api/lyrics?" + q); if (r.ok) { const j = await r.json(); if (j?.lines?.length) return { ...j, synced: true }; } } catch {}
  // lrclib straight from the browser
  const d = Math.round((t.duration || 0) / 1000);
  let r = await fetch(`https://lrclib.net/api/get?artist_name=${encodeURIComponent(t.artist)}&track_name=${encodeURIComponent(t.title)}${d ? "&duration=" + d : ""}`).then(x => x.ok ? x.json() : null).catch(() => null);
  if (!r) r = (await fetch(`https://lrclib.net/api/search?q=${encodeURIComponent(t.artist + " " + t.title)}`).then(x => x.json()).catch(() => []))[0];
  if (!r) return null;
  if (r.syncedLyrics) {
    const lines = r.syncedLyrics.split("\n").map(s => /^\[(\d+):(\d+(?:\.\d+)?)\](.*)/.exec(s)).filter(Boolean).map(m => [(+m[1] * 60 + +m[2]) * 1000, m[3].trim()]);
    return { lines, synced: true };
  }
  if (r.plainLyrics) return { lines: r.plainLyrics.split("\n").map(s => [0, s]), synced: false };
  return null;
}
function karaoke() {
  cancelAnimationFrame(raf);
  const box = $("#lyrics"), els = [...box.querySelectorAll(".line")];
  let last = -1;
  const frame = () => {
    if (box.hidden || !lyr) return;
    const ms = audio.currentTime * 1000;
    let i = -1; for (let k = 0; k < lyr.lines.length; k++) if (lyr.lines[k][0] <= ms + 250) i = k; else break;
    if (i !== last) {
      els.forEach((el, k) => { el.classList.toggle("past", k < i); el.classList.toggle("now", k === i); if (k !== i) el.querySelectorAll(":scope>span:not(.tr)").forEach(s => s.style.color = ""); });
      els[i]?.scrollIntoView({ behavior: "smooth", block: "center" }); last = i;
    }
    if (i >= 0) { // light the sung line letter by letter
      const start = lyr.lines[i][0], end = lyr.lines[i + 1]?.[0] ?? start + 5000;
      const words = lyr.words?.[i] || estimate(lyr.lines[i][1], start, end);
      const spans = els[i].querySelectorAll(":scope>span:not(.tr)"); let n = 0;
      words.forEach(([ws, w], wi) => {
        const we = words[wi + 1]?.[0] ?? end, per = Math.max(1, (we - ws) / Math.max(1, w.length));
        for (let c = 0; c < w.length; c++, n++) {
          const p = Math.min(1, Math.max(0, (ms - (ws + per * c)) / Math.min(400, Math.max(80, per * 2.5))));
          if (spans[n]) spans[n].style.color = `rgba(255,255,255,${.35 + .65 * p})`;
        }
      });
    }
    raf = requestAnimationFrame(frame);
  };
  frame();
}
function estimate(text, start, end) {
  const span = Math.min(8000, Math.max(600, (end - start) * .85)), parts = text.match(/\S+\s*/g) || [text];
  const total = parts.reduce((a, w) => a + Math.max(1, w.trim().length), 0); let t = start;
  return parts.map(w => { const r = [t, w]; t += span * Math.max(1, w.trim().length) / total; return r; });
}
function openQueue() {
  $("#playerMain").hidden = true; $("#lyrics").hidden = true; $("#queue").hidden = false; $("#pQueueBtn").classList.add("on"); $("#pLyricsBtn").classList.remove("on");
  $("#queue").innerHTML = `<h2 style="margin-top:0">Up next</h2>` + queue.map((t, i) => rowHtml(t, i)).join("");
  bindRows($("#queue"), queue, true);
}

// ---------- downloads ----------
async function download(tracks) {
  const todo = tracks.filter(t => !downloaded.has(t.id));
  if (!todo.length) return toast("Already downloaded");
  toast(todo.length === 1 ? "Downloading…" : `Downloading ${todo.length} songs…`);
  let ok = 0;
  for (const t of todo) {
    try {
      const r = await fetch(await streamUrl(t)); if (!r.ok) throw 0;
      const blob = await r.blob();
      await idb("readwrite", s => s.put({ id: t.id, track: clean(t), blob, at: Date.now() }));
      downloaded.add(t.id); ok++; paintPlayer(); if (location.hash.startsWith("#/downloads")) route();
    } catch {}
  }
  toast(ok === todo.length ? (ok === 1 ? "Downloaded" : `Downloaded ${ok} songs`) : `Downloaded ${ok} of ${todo.length}`);
  if (navigator.storage?.persist) navigator.storage.persist();
}
async function removeDownload(id) { await idb("readwrite", s => s.delete(id)); downloaded.delete(id); route(); }

// ---------- views ----------
const view = $("#view");
const rowHtml = (t, i) => `<div class="row${t.id === cur()?.id ? " cur" : ""}" data-id="${h(t.id)}" data-i="${i}">
  <img loading="lazy" src="${h(t.art || "/icon.svg")}" alt=""><div class="txt"><div class="t1">${h(t.title)}</div><div class="t2">${downloaded.has(t.id) ? "↓ " : ""}${h(t.artist)}${t.duration ? " · " + fmt(t.duration) : ""}</div></div>
  <button class="act" data-more aria-label="More"><span class="i i-more"></span></button></div>`;
function bindRows(el, list, inQueue) {
  el.querySelectorAll(".row[data-i]").forEach(r => r.onclick = e => {
    const t = list[+r.dataset.i];
    if (e.target.closest("[data-more]")) return trackMenu(t);
    if (inQueue) load(+r.dataset.i); else playList(list, +r.dataset.i);
  });
}
function trackMenu(t) {
  sheet(t.title, [
    ["Play next", () => { if (!queue.length) playList([t]); else { queue.splice(qi + 1, 0, clean(t)); toast("Playing next"); } }],
    [isLiked(t) ? "Remove from liked songs" : "Like", () => toggleLike(t)],
    [downloaded.has(t.id) ? "Remove download" : "Download", () => downloaded.has(t.id) ? removeDownload(t.id) : download([t])],
  ]);
}
/** an ios-style action sheet */
function sheet(title, actions) {
  const o = document.createElement("div"); o.className = "actOverlay";
  o.innerHTML = `<div class="act"><div class="actTitle">${h(title)}</div>${actions.map((a, i) => `<button data-a="${i}">${h(a[0])}</button>`).join("")}<button class="cancel">Cancel</button></div>`;
  const close = () => { o.classList.add("closing"); setTimeout(() => o.remove(), 200); };
  o.onclick = e => { const b = e.target.closest("button"); if (b?.dataset.a) actions[+b.dataset.a][1](); if (b || e.target === o) close(); };
  document.body.appendChild(o);
}
const likedCover = `<div class="cov liked"><span class="i i-heart" style="width:40%;height:40%;color:#fff"></span></div>`;

function home() {
  const recent = ls.get("recent", []), pls = playlists();
  const hr = new Date().getHours(), hi = hr < 5 ? "Good night" : hr < 12 ? "Good morning" : hr < 18 ? "Good afternoon" : "Good evening";
  view.innerHTML = `<div><h1>${hi}</h1>
    <div class="quick">
      <a class="q" href="#/liked"><div class="liked"><span class="i i-heart" style="color:#fff"></span></div><span>Liked songs</span></a>
      <a class="q" href="#/downloads"><div class="liked" style="background:linear-gradient(135deg,#0e7a5f,#5fdbc9)"><span class="i i-dl" style="color:#fff"></span></div><span>Downloads</span></a>
      ${pls.slice(0, 6).map((p, i) => `<a class="q" href="#/pl/${library.indexOf(p)}"><img src="${h(p.cover_url || p.tracks[0]?.art || "/icon.svg")}" alt=""><span>${h(p.name)}</span></a>`).join("")}
    </div>
    ${session ? "" : `<h2>Your playlists, here too</h2><p class="dim">Log in with your Stash account (Library tab) to get the playlists and liked songs from your phone.</p>`}
    ${recent.length ? `<h2>Recently played</h2><div id="rec">${recent.slice(0, 12).map(rowHtml).join("")}</div>` : `<h2>Get started</h2><p class="dim">Search for anything, or add Stash to your home screen for the full app feel (Share → Add to Home Screen on iPhone).</p>`}
    <h2>Get the app</h2><div class="btns"><a class="btn" href="https://github.com/jhu7600codes/stash/raw/main/dist/stash.apk">Android</a><a class="btn" href="https://github.com/jhu7600codes/stash/raw/main/dist/stash-armv7.apk">Android TV (32-bit)</a></div>
  </div>`;
  if (recent.length) bindRows($("#rec"), recent);
  view.querySelectorAll("a.q").forEach(a => a.style.color = "inherit");
}

let lastQuery = "", lastResults = [];
function searchView() {
  view.innerHTML = `<div><h1>Search</h1><form class="searchBox" id="sf"><input class="field" id="sq" type="search" placeholder="Songs or artists" value="${h(lastQuery)}" autocomplete="off" enterkeyhint="search"></form><div id="sr"></div></div>`;
  const paint = () => { $("#sr").innerHTML = lastResults.map(rowHtml).join(""); bindRows($("#sr"), lastResults); };
  if (lastResults.length) paint();
  let timer;
  const run = async () => {
    const q = $("#sq").value.trim(); if (!q || q === lastQuery) return; lastQuery = q;
    $("#sr").innerHTML = `<p class="dim">Searching…</p>`;
    try { lastResults = await search(q); if (q === lastQuery) lastResults.length ? paint() : ($("#sr").innerHTML = `<p class="dim">Nothing found</p>`); }
    catch { $("#sr").innerHTML = `<p class="dim">${navigator.onLine ? "Search servers are busy, try again" : "You're offline. Downloads still play (Library → Downloads)"}</p>`; }
  };
  $("#sq").oninput = () => { clearTimeout(timer); timer = setTimeout(run, 450); };
  $("#sf").onsubmit = e => { e.preventDefault(); $("#sq").blur(); run(); };
  if (!lastResults.length) setTimeout(() => $("#sq").focus(), 50);
}

function libraryView() {
  const pls = playlists();
  view.innerHTML = `<div><h1>Library</h1>
    ${session ? `<p class="dim small">Logged in as ${h(session.email)} · <a href="#" id="sync" style="color:var(--accent)">sync</a> · <a href="#" id="logout" style="color:var(--accent)">log out</a></p>` : `
      <h2 style="margin-top:0">Log in</h2><p class="dim small">Your Stash account, the same one as in the app.</p>
      <form id="lf"><input class="field" id="le" type="email" placeholder="Email" autocomplete="email"><input class="field" id="lp" type="password" placeholder="Password" autocomplete="current-password"><button class="btn primary" style="width:100%;margin-top:6px">Log in</button></form>`}
    <div class="grid" style="margin-top:20px">
      <a class="card" href="#/liked">${likedCover}<div class="t1">Liked songs</div><div class="t2">${likedTracks().length} songs</div></a>
      <a class="card" href="#/downloads"><div class="cov liked" style="background:linear-gradient(135deg,#0e7a5f,#5fdbc9)"><span class="i i-dl" style="width:40%;height:40%;color:#fff"></span></div><div class="t1">Downloads</div><div class="t2">${downloaded.size} songs</div></a>
      ${pls.map(p => `<a class="card" href="#/pl/${library.indexOf(p)}"><img class="cov" loading="lazy" src="${h(p.cover_url || p.tracks[0]?.art || "/icon.svg")}" alt=""><div class="t1">${h(p.name)}</div><div class="t2">${p.tracks.length} songs</div></a>`).join("")}
    </div></div>`;
  view.querySelectorAll("a.card").forEach(a => a.style.color = "inherit");
  $("#lf")?.addEventListener("submit", async e => {
    e.preventDefault();
    try { saveSession(await auth("/auth/v1/token?grant_type=password", { email: $("#le").value.trim(), password: $("#lp").value }), $("#le").value.trim()); toast("Logged in"); await syncLibrary(); }
    catch (err) { toast(err.message); }
  });
  $("#sync")?.addEventListener("click", e => { e.preventDefault(); syncLibrary().then(() => toast("Synced")); });
  $("#logout")?.addEventListener("click", e => { e.preventDefault(); session = null; library = null; localStorage.removeItem("session"); localStorage.removeItem("library"); route(); });
}

function collection(title, sub, cover, tracks, extra = "") {
  view.innerHTML = `<div><a class="back" href="javascript:history.back()"><span class="i i-back"></span>Back</a>
    <div class="colHead">${cover}<h1>${h(title)}</h1><div class="dim small">${h(sub)}</div></div>
    <div class="btns" style="justify-content:center"><button class="btn primary" id="cPlay">Play</button><button class="btn" id="cShuf">Shuffle</button><button class="btn" id="cDl">${extra || "Download"}</button></div>
    <div id="cl">${tracks.map(rowHtml).join("")}</div>${tracks.length ? "" : `<p class="dim" style="text-align:center">Nothing here yet</p>`}</div>`;
  bindRows($("#cl"), tracks);
  $("#cPlay").onclick = () => tracks.length && playList(tracks, 0);
  $("#cShuf").onclick = () => tracks.length && playList(tracks, 0, true);
  return $("#cDl");
}
async function downloadsView() {
  const all = (await idb("readonly", s => s.getAll())).sort((a, b) => b.at - a.at).map(x => x.track);
  const size = (await navigator.storage?.estimate?.())?.usage;
  const btn = collection("Downloads", `${all.length} songs${size ? " · " + (size / 1048576).toFixed(0) + " MB" : ""} · play offline`, `<div class="cov liked" style="background:linear-gradient(135deg,#0e7a5f,#5fdbc9)"><span class="i i-dl" style="width:40%;height:40%;color:#fff"></span></div>`, all, "Remove all");
  btn.onclick = async () => { if (confirm("Remove every download?")) { await idb("readwrite", s => s.clear()); downloaded.clear(); route(); } };
}

// ---------- router ----------
function route() {
  const hsh = location.hash || "#/home", [, page, arg] = hsh.split("/");
  document.querySelectorAll("#tabs button").forEach(b => b.classList.toggle("on", b.dataset.tab === (["pl", "liked", "downloads"].includes(page) ? "library" : page)));
  if (page === "search") searchView();
  else if (page === "library") libraryView();
  else if (page === "liked") collection("Liked songs", `${likedTracks().length} songs`, likedCover, likedTracks()).onclick = () => download(likedTracks());
  else if (page === "downloads") downloadsView();
  else if (page === "pl" && library?.[arg]) { const p = library[arg]; collection(p.name, `${p.tracks.length} songs`, `<img class="cov" src="${h(p.cover_url || p.tracks[0]?.art || "/icon.svg")}" alt="">`, p.tracks).onclick = () => download(p.tracks); }
  else home();
  view.scrollTop = 0;
}
document.querySelectorAll("#tabs button").forEach(b => b.onclick = () => location.hash = "#/" + b.dataset.tab);
addEventListener("hashchange", route);

// ---------- start ----------
refreshDownloaded().then(route);
if (session) syncLibrary();
if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js");
