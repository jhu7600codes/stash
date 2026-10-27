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
// soundcloud: its server-side helper gives an hls playlist; safari plays it, others get hls.js
async function scStream(t) {
  const r = await fetch("/api/sc?stream=" + encodeURIComponent(t.source));
  if (!r.ok) throw new Error("SoundCloud didn't answer");
  return r.json();
}
let hlsPlayer = null;
async function setSource(src, hls) {
  hlsPlayer?.destroy(); hlsPlayer = null;
  if (!hls || audio.canPlayType("application/vnd.apple.mpegurl")) { audio.src = src; return; }
  if (!window.Hls) await new Promise((ok, fail) => { const sc = document.createElement("script"); sc.src = "https://cdnjs.cloudflare.com/ajax/libs/hls.js/1.5.17/hls.min.js"; sc.onload = ok; sc.onerror = fail; document.head.appendChild(sc); });
  hlsPlayer = new Hls(); hlsPlayer.loadSource(src); hlsPlayer.attachMedia(audio);
  await new Promise(ok => hlsPlayer.on(Hls.Events.MANIFEST_PARSED, ok));
}
async function streamUrl(t) {
  let v = videoIdOf(t);
  if (!v) { // a soundcloud / bandcamp song: find it on youtube (downloads need a plain file)
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
let prefs = Object.assign({ translate: true, lang: "", heart: null, pinned: [] }, ls.get("prefs", {}));
const savePrefs = () => ls.set("prefs", prefs);
async function auth(path, body, method = "POST") {
  const r = await fetch(SUPABASE + path, { method, headers: { apikey: SB_KEY, "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error_description || j.msg || j.message || "Login failed");
  return j;
}
function saveSession(j, email) {
  session = { email: j.user?.email || email, access: j.access_token, refresh: j.refresh_token, exp: Date.now() + ((j.expires_in || 3600) - 60) * 1000 };
  ls.set("session", session);
}
async function token() {
  if (!session) return null;
  if (Date.now() < session.exp) return session.access;
  try { saveSession(await auth("/auth/v1/token?grant_type=refresh_token", { refresh_token: session.refresh }), session.email); return session.access; }
  catch { return null; }
}
async function api(path, { method = "GET", body } = {}) {
  const t = await token(); if (!t) throw new Error("Log in first");
  const r = await fetch(SUPABASE + path, { method, headers: { apikey: SB_KEY, Authorization: "Bearer " + t, "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
  const text = await r.text(); let j = null; try { j = JSON.parse(text); } catch {}
  if (!r.ok) throw new Error(j?.error || j?.message || "Request failed (" + r.status + ")");
  return j;
}

// the library: same shape as the app's backup. [{kind: "liked"|"playlist", name, cover_url, origin, origin_id, tracks}]
// tracks can be {missing: true, ...} (songs the app couldn't find): kept so the backup stays whole, hidden here
let library = ls.get("library", null) || [{ kind: "liked", name: "liked songs", tracks: ls.get("likes", []) }];
const visible = list => (list || []).filter(t => !t.missing);
const likedPl = () => { let l = library.find(p => p.kind === "liked"); if (!l) { l = { kind: "liked", name: "liked songs", tracks: [] }; library.unshift(l); } return l; };
const likedTracks = () => visible(likedPl().tracks);
const playlists = () => library.filter(p => p.kind !== "liked");
const pinnedFirst = list => [...list.filter(p => prefs.pinned.includes(p.name)), ...list.filter(p => !prefs.pinned.includes(p.name))];
const plIndex = p => library.indexOf(p);
const plCover = p => p.cover_url || visible(p.tracks)[0]?.art || "/icon.svg";

let saveTimer;
function saveLibrary() {
  ls.set("library", library);
  if (!session) return;
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => api("/rest/v1/rpc/replace_library", { method: "POST", body: { items: library.map((p, i) => ({
    kind: p.kind, sort_index: p.kind === "liked" ? 0 : i + 1, name: p.name, cover_url: p.cover_url ?? null,
    origin: p.origin || (p.kind === "liked" ? null : "local"), origin_id: p.origin_id ?? null, tracks: p.tracks,
  })) } }).catch(() => toast("Couldn't back up, will retry on the next change")), 2500);
}
async function syncLibrary() {
  try {
    const rows = await api("/rest/v1/library_items?select=kind,name,cover_url,origin,origin_id,tracks&order=sort_index");
    // likes made on the web before logging in come along
    const before = likedTracks();
    library = rows.map(p => ({ ...p, tracks: p.tracks || [] }));
    const lp = likedPl(), ids = new Set(lp.tracks.map(t => t.id));
    const extra = before.filter(t => !ids.has(t.id));
    if (extra.length) { lp.tracks.unshift(...extra); saveLibrary(); }
    ls.set("library", library); route();
  } catch {}
}
const toTrackJson = t => ({ id: t.id, title: t.title, artist: t.artist, album: t.album ?? null, duration: t.duration || 0, art: t.art ?? null, provider: t.provider || "youtube", source: t.source, hint: null });

// the heart: liked songs, or a playlist picked in settings (like the app)
const heartPl = () => (prefs.heart && library.find(p => p.kind !== "liked" && p.name === prefs.heart)) || likedPl();
const inPl = (p, t) => p.tracks.some(x => x.id === t.id);
const isLiked = t => inPl(heartPl(), t);
function toggleLike(t) {
  const p = heartPl();
  if (inPl(p, t)) { p.tracks = p.tracks.filter(x => x.id !== t.id); toast(p.kind === "liked" ? "Removed from liked songs" : "Removed from " + p.name); }
  else { p.kind === "liked" ? p.tracks.unshift(toTrackJson(t)) : p.tracks.push(toTrackJson(t)); toast(p.kind === "liked" ? "Added to liked songs" : "Added to " + p.name); }
  saveLibrary(); paintPlayer();
}
function addToPlaylist(p, tracks) {
  const ids = new Set(p.tracks.map(x => x.id)), add = tracks.filter(t => !ids.has(t.id)).map(toTrackJson);
  p.tracks.push(...add); saveLibrary(); toast(add.length ? `Added to ${p.name}` : `Already in ${p.name}`);
}
function newPlaylist(then) {
  const name = prompt("Playlist name"); if (!name?.trim()) return;
  if (library.some(p => p.name === name.trim())) return toast("You already have a playlist with that name");
  const p = { kind: "playlist", name: name.trim(), origin: "local", tracks: [] }; library.push(p); saveLibrary(); then?.(p);
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
    let hls = false;
    if (saved) { if (objectUrl) URL.revokeObjectURL(objectUrl); src = objectUrl = URL.createObjectURL(saved.blob); }
    else if (t.provider === "soundcloud") { const sc = await scStream(t); src = sc.url; hls = sc.hls; }
    else src = await streamUrl(t);
    if (my !== loadToken) return;
    await setSource(src, hls); await audio.play().catch(() => {});
    setStatus(""); prefetch();
  } catch (e) {
    if (my !== loadToken) return;
    setStatus("Couldn't play this one"); toast(navigator.onLine ? `Couldn't play ${t.title}, skipping` : "Offline: only downloaded songs play");
    setTimeout(() => { if (my === loadToken && qi < queue.length - 1) load(qi + 1); }, 1500);
  }
}
function prefetch() { const n = queue[qi + 1]; if (n && !downloaded.has(n.id) && n.provider !== "soundcloud") streamUrl(n).catch(() => {}); }
const next = () => { if (qi < queue.length - 1) load(qi + 1); else if (repeat === 1) load(0); };
const prev = () => { if (audio.currentTime > 3 || qi === 0) audio.currentTime = 0; else load(qi - 1); };
audio.onended = () => repeat === 2 ? (audio.currentTime = 0, audio.play()) : next();
audio.onplay = audio.onpause = () => { paintPlay(); if ("mediaSession" in navigator) navigator.mediaSession.playbackState = audio.paused ? "paused" : "playing"; };
audio.ontimeupdate = () => {
  const d = audio.duration * 1000 || cur()?.duration || 0, p = audio.currentTime * 1000;
  $("#miniProgress div").style.width = d ? (p / d * 100) + "%" : "0";
  if (!seeking) { const v = d ? p / d * 1000 : 0; for (const el of [$("#pSeek"), $("#miniSeek")]) { el.value = v; el.style.setProperty("--p", v / 10 + "%"); } }
  $("#pPos").textContent = $("#miniPos").textContent = fmt(p); $("#pDur").textContent = $("#miniDur").textContent = fmt(d);
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
  $("#pLike").classList.toggle("on", isLiked(t)); $("#miniLike").classList.toggle("on", isLiked(t));
  $("#miniShuffle").classList.toggle("on", shuffle); $("#miniRepeat").classList.toggle("on", repeat > 0);
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
$("#miniPlay").onclick = togglePlay;
$("#miniPrev").onclick = () => prev(); $("#miniNext").onclick = () => next();
$("#miniShuffle").onclick = () => $("#pShuffle").click(); $("#miniRepeat").onclick = () => $("#pRepeat").click();
$("#miniLike").onclick = () => cur() && toggleLike(cur());
$("#miniLyrics").onclick = () => { openPlayer(); openLyrics(); };
$("#miniQueue").onclick = () => { openPlayer(); openQueue(); };
$("#vol").oninput = e => { audio.volume = e.target.value / 100; ls.set("vol", +e.target.value); };
$("#vol").value = ls.get("vol", 100); audio.volume = $("#vol").value / 100;
$("#miniSeek").oninput = e => { seeking = true; e.target.style.setProperty("--p", e.target.value / 10 + "%"); };
$("#miniSeek").onchange = e => { seeking = false; if (audio.duration) audio.currentTime = e.target.value / 1000 * audio.duration; }; $("#pPlay").onclick = togglePlay;
$("#playerClose").onclick = closePlayer; $("#pNext").onclick = next; $("#pPrev").onclick = prev;
$("#pLike").onclick = () => cur() && toggleLike(cur());
$("#pArtist").onclick = () => { if (!cur()) return; closePlayer(); location.hash = "#/artist/" + encodeURIComponent(cur().artist.split(",")[0].trim()); };
$("#pSimilar").onclick = () => { if (!cur()) return; closePlayer(); location.hash = "#/similar/" + encodeURIComponent(JSON.stringify(clean(cur()))); };
$("#pMore").onclick = () => cur() && trackMenu(cur());
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
/** "うっせぇわ - Usseewa (Live At …) [MV]" -> the song's name, for finding its lyrics */
const songName = title => title.replace(/\s*[(\[【].*?[)\]】]/g, "").replace(/\s+(feat|ft)\.?\s.*$/i, "").replace(/\s+[-–]\s+(remaster|live|official|lyric|audio).*$/i, "").trim() || title;
async function getLyrics(t0) {
  const t = { ...t0, title: songName(t0.title), artist: t0.artist.split(/,|&| feat\.? /i)[0].trim() };
  const q = `lang=${prefs.translate ? (prefs.lang || (navigator.language || "en").slice(0, 2)) : ""}&title=${encodeURIComponent(t.title)}&artist=${encodeURIComponent(t.artist)}&duration=${Math.round((t.duration || audio.duration * 1000 || 0) / 1000)}`;
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
function trackMenu(t, fromPl) {
  const acts = [
    ["Play next", () => { if (!queue.length) playList([t]); else { queue.splice(qi + 1, 0, clean(t)); toast("Playing next"); } }],
    ["Add to queue", () => { if (!queue.length) playList([t]); else { queue.push(clean(t)); toast("Added to queue"); } }],
    [isLiked(t) ? "Remove from " + (heartPl().kind === "liked" ? "liked songs" : heartPl().name) : "Add to " + (heartPl().kind === "liked" ? "liked songs" : heartPl().name), () => toggleLike(t)],
    ["Add to playlist…", () => pickPlaylist([t])],
    ["Similar songs", () => { location.hash = "#/similar/" + encodeURIComponent(JSON.stringify(clean(t))); }],
    ["Go to artist", () => { location.hash = "#/artist/" + encodeURIComponent(t.artist.split(",")[0].trim()); }],
    [downloaded.has(t.id) ? "Remove download" : "Download", () => downloaded.has(t.id) ? removeDownload(t.id) : download([t])],
  ];
  if (fromPl) acts.push(["Remove from this playlist", () => { fromPl.tracks = fromPl.tracks.filter(x => x.id !== t.id); saveLibrary(); route(); }]);
  sheet(t.title, acts);
}
function pickPlaylist(tracks) {
  sheet("Add to playlist", [["New playlist…", () => newPlaylist(p => addToPlaylist(p, tracks))], ...pinnedFirst(playlists()).map(p => [p.name, () => addToPlaylist(p, tracks)])]);
}
/** an ios-style action sheet */
function sheet(title, actions) {
  const o = document.createElement("div"); o.className = "actOverlay";
  o.innerHTML = `<div class="act"><div class="actTitle">${h(title)}</div><div class="actList">${actions.map((a, i) => `<button data-a="${i}"${a[2] ? ' class="red"' : ""}>${h(a[0])}</button>`).join("")}</div><button class="cancel">Cancel</button></div>`;
  const close = () => { o.classList.add("closing"); setTimeout(() => o.remove(), 200); };
  o.onclick = e => { const b = e.target.closest("button"); if (b || e.target === o) close(); if (b?.dataset.a) setTimeout(() => actions[+b.dataset.a][1](), 10); };
  document.body.appendChild(o);
}
const likedCover = `<div class="cov liked"><span class="i i-heart" style="width:40%;height:40%;color:#fff"></span></div>`;
const dlCover = `<div class="cov liked" style="background:linear-gradient(135deg,#0e7a5f,#5fdbc9)"><span class="i i-dl" style="width:40%;height:40%;color:#fff"></span></div>`;
const pin = p => prefs.pinned.includes(p.name) ? `<span class="pin">PINNED</span> ` : "";

/** made for you: a mix from what you play, plus "this is" mixes of your top artists */
function topArtists() {
  const count = new Map();
  [...ls.get("recent", []), ...likedTracks()].forEach(t => { const a = (t.artist || "").split(/,|&| feat\.? | ft\.? | x | × /i)[0].trim(); if (a) count.set(a, (count.get(a) || 0) + 1); });
  return [...count.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6).map(e => e[0]);
}
function madeForYou() {
  const arts = topArtists(); if (!arts.length) return "";
  const colors = ["#e8115b", "#1e3264", "#8d67ab", "#148a08", "#ba5d07", "#477d95"];
  return `<h2>Made for you</h2><div class="hscroll">
    <a class="card" href="#/mix"><div class="cov mix" style="background:linear-gradient(135deg,#7CC8F8,#4b2bd6)"><b>Your mix</b><small>Stash</small></div><div class="t2">Songs like what you play</div></a>
    ${arts.map((a, i) => `<a class="card" href="#/artist/${encodeURIComponent(a)}"><div class="cov mix" style="background:${colors[i % colors.length]}"><small>This is</small><b>${h(a)}</b></div><div class="t2">${h(a)} and more</div></a>`).join("")}</div>`;
}
async function yourMix() {
  const seeds = [...ls.get("recent", []).slice(0, 3), ...likedTracks().slice(0, 2)];
  const lists = await Promise.all(seeds.map(t => similar(t).catch(() => [])));
  const seen = new Set(seeds.map(t => t.id)), out = [];
  for (let k = 0; k < 12; k++) lists.forEach(l => { const t = l[k]; if (t && !seen.has(t.id)) { seen.add(t.id); out.push(t); } });
  return out;
}
const skeleton = (n = 8) => `<div class="skel">${'<div class="row"><div class="ph sk"></div><div class="txt"><div class="sk l1"></div><div class="sk l2"></div></div></div>'.repeat(n)}</div>`;
const coverCard = (t, i) => `<div class="card tcard" data-i="${i}"><img class="cov" loading="lazy" src="${h(t.art || "/icon.svg")}" alt=""><div class="t1">${h(t.title)}</div><div class="t2">${h(t.artist)}</div></div>`;
function home() {
  const recent = ls.get("recent", []), pls = pinnedFirst(playlists());
  const hr = new Date().getHours(), hi = hr < 5 ? "Good night" : hr < 12 ? "Good morning" : hr < 18 ? "Good afternoon" : "Good evening";
  view.innerHTML = `<div><div class="titleRow"><h1>${hi}</h1><a class="icon" href="#/settings" aria-label="Settings"><span class="i i-gear"></span></a></div>
    <div class="quick">
      <a class="q" href="#/liked"><div class="liked"><span class="i i-heart" style="color:#fff"></span></div><span>Liked songs</span></a>
      <a class="q" href="#/downloads"><div class="liked" style="background:linear-gradient(135deg,#0e7a5f,#5fdbc9)"><span class="i i-dl" style="color:#fff"></span></div><span>Downloads</span></a>
      ${pls.slice(0, 6).map(p => `<a class="q" href="#/pl/${plIndex(p)}"><img src="${h(plCover(p))}" alt=""><span>${h(p.name)}</span></a>`).join("")}
    </div>
    ${session ? "" : `<h2>Your playlists, here too</h2><p class="dim">Log in with your Stash account (Library tab) to get the playlists and liked songs from your phone. Or just start: everything you like and make here is saved on this device.</p>`}
    ${madeForYou()}
    ${recent.length ? `<h2>Jump back in</h2><div class="hscroll" id="rec">${recent.slice(0, 15).map(coverCard).join("")}</div>` : `<h2>Get started</h2><p class="dim">Search for anything. On iPhone: Share → Add to Home Screen for the full app.</p>`}
    ${pls.length > 6 ? `<h2>Your playlists</h2><div class="hscroll">${pls.map(p => `<a class="card" href="#/pl/${plIndex(p)}"><img class="cov" loading="lazy" src="${h(plCover(p))}" alt=""><div class="t1">${h(p.name)}</div><div class="t2">${visible(p.tracks).length} songs</div></a>`).join("")}</div>` : ""}
  </div>`;
  view.querySelectorAll("#rec .tcard").forEach(c => c.onclick = () => playList(recent.slice(0, 15), +c.dataset.i));
}

let lastQuery = "", lastRes = null;
async function searchAll(q) {
  if (q.startsWith("#")) { // #miku, #teto, #vocarock: vocadb
    const r = await fetch("/api/vocadb?tag=" + encodeURIComponent(q.slice(1))); return { songs: r.ok ? await r.json() : [], sc: [], pls: [] };
  }
  const [songs, sc, pls] = await Promise.all([
    search(q).catch(() => []),
    fetch("/api/sc?q=" + encodeURIComponent(q)).then(r => r.ok ? r.json() : []).catch(() => []),
    piped("/search?filter=playlists&q=" + encodeURIComponent(q)).then(d => (d.items || []).filter(i => i.url?.includes("list="))).catch(() => []),
  ]);
  if (!songs.length && !sc.length) throw new Error("nothing");
  return { songs, sc: sc.slice(0, 8), pls: pls.slice(0, 10) };
}
function searchView() {
  view.innerHTML = `<div><h1>Search</h1><form class="searchBox" id="sf"><input class="field" id="sq" type="search" placeholder="Songs, artists or #tags (#miku)" value="${h(lastQuery)}" autocomplete="off" enterkeyhint="search"></form><div id="sr"></div>
    ${lastRes ? "" : `<div id="tags"><h2>Browse</h2>${["#miku", "#teto", "#gumi", "#len", "#rin", "#kaito", "#meiko", "#luka", "#vocarock", "#utau"].map(t => `<button class="chip">${t}</button>`).join("")}</div>`}</div>`;
  const paint = () => {
    const r = lastRes;
    $("#sr").innerHTML = (r.songs.length ? `<h2>Songs</h2><div id="s1">${r.songs.map(rowHtml).join("")}</div>` : "")
      + (r.pls.length ? `<h2>Playlists</h2><div class="hscroll">${r.pls.map(p => `<a class="card" href="#/ytpl/${encodeURIComponent(new URL(p.url, location.href).searchParams.get("list"))}"><img class="cov" loading="lazy" src="${h(p.thumbnail)}" alt=""><div class="t1">${h(p.name)}</div><div class="t2">${h(p.uploaderName || "")}</div></a>`).join("")}</div>` : "")
      + (r.sc.length ? `<h2>SoundCloud</h2><div id="s2">${r.sc.map(rowHtml).join("")}</div>` : "");
    if (r.songs.length) bindRows($("#s1"), r.songs); if (r.sc.length) bindRows($("#s2"), r.sc);
  };
  if (lastRes) paint();
  let timer;
  const run = async (force) => {
    const q = $("#sq").value.trim(); if (!q || (q === lastQuery && !force)) return; lastQuery = q; $("#tags")?.remove();
    $("#sr").innerHTML = skeleton();
    try { const r = await searchAll(q); if (q === lastQuery) { lastRes = r; paint(); } }
    catch { $("#sr").innerHTML = `<p class="dim">${navigator.onLine ? "Nothing found (or the search servers are busy, try again)" : "You're offline. Downloads still play (Library → Downloads)"}</p>`; }
  };
  $("#sq").oninput = () => { clearTimeout(timer); timer = setTimeout(run, 500); };
  $("#sf").onsubmit = e => { e.preventDefault(); $("#sq").blur(); run(true); };
  view.querySelectorAll("#tags .chip").forEach(c => c.onclick = () => { $("#sq").value = c.textContent; run(true); });
  if (!lastRes) setTimeout(() => $("#sq").focus(), 50);
}
// a spotify playlist / album link -> a stash playlist, every song found on youtube (like the app's import)
async function importSpotify() {
  const link = prompt("Paste a Spotify playlist or album link"); if (!link?.trim()) return;
  toast("Reading the playlist…");
  const r = await fetch("/api/spotify?link=" + encodeURIComponent(link.trim()));
  const j = await r.json().catch(() => ({}));
  if (!r.ok) return toast(j.error || "Couldn't read that link");
  let name = j.name; while (library.some(p => p.name === name)) name += " (2)";
  const p = { kind: "playlist", name, cover_url: j.cover, origin: "spotify", origin_id: j.origin_id, tracks: [] };
  library.push(p); location.hash = "#/pl/" + plIndex(p);
  const out = new Array(j.tracks.length); let done = 0, found = 0, next = 0;
  const worker = async () => {
    while (next < j.tracks.length) {
      const k = next++, s = j.tracks[k];
      const hit = (await search(`${s.artist.split(",")[0]} ${s.title}`).catch(() => []))
        .find(x => !s.duration || !x.duration || Math.abs(x.duration - s.duration) < 30000);
      out[k] = hit ? toTrackJson({ ...hit, album: s.album, art: s.art || hit.art }) : { missing: true, title: s.title, artist: s.artist, album: s.album, duration: s.duration };
      if (hit) found++; done++;
      if (done % 10 === 0 || done === j.tracks.length) { p.tracks = out.filter(Boolean); toast(`Moving ${name}: ${done}/${j.tracks.length}`); if (location.hash === "#/pl/" + plIndex(p)) route(); }
    }
  };
  await Promise.all([worker(), worker(), worker()]);
  p.tracks = out.filter(Boolean); saveLibrary(); route();
  toast(`${name}: ${found} of ${j.tracks.length} songs found`);
}
async function ytPlaylist(id) {
  const d = await piped("/playlists/" + encodeURIComponent(id));
  return { name: d.name, cover: d.thumbnailUrl, tracks: (d.relatedStreams || []).filter(i => i.url?.includes("watch")).map(fromPiped) };
}
function libraryView() {
  const pls = pinnedFirst(playlists());
  view.innerHTML = `<div><div class="titleRow"><h1>Library</h1><div><button class="icon" id="newPl" aria-label="New playlist"><span class="i i-plus"></span></button><a class="icon" href="#/settings" aria-label="Settings"><span class="i i-gear"></span></a></div></div>
    ${session ? "" : `<div class="card2"><b>Log in or sign up</b><p class="dim small" style="margin:4px 0 8px">Your Stash account, the same one as in the app: your playlists and likes everywhere.</p>
      <form id="lf"><input class="field" id="le" type="email" placeholder="Email" autocomplete="email"><input class="field" id="lp" type="password" placeholder="Password" autocomplete="current-password">
      <div class="btns"><button class="btn primary" style="flex:1">Log in</button><button class="btn" type="button" id="su" style="flex:1">Sign up</button></div></form></div>`}
    <div class="list">
      <a class="row" href="#/liked">${likedCover.replace('class="cov liked"', 'class="ph liked"')}<div class="txt"><div class="t1">Liked songs</div><div class="t2">Playlist · ${likedTracks().length} songs</div></div></a>
      <a class="row" href="#/downloads">${dlCover.replace('class="cov liked"', 'class="ph liked"')}<div class="txt"><div class="t1">Downloads</div><div class="t2">${downloaded.size} songs · offline</div></div></a>
      ${pls.map(p => `<a class="row" href="#/pl/${plIndex(p)}"><img loading="lazy" src="${h(plCover(p))}" alt=""><div class="txt"><div class="t1">${h(p.name)}</div><div class="t2">${pin(p)}Playlist · ${visible(p.tracks).length} songs${p.origin && p.origin !== "local" ? " · from " + h(p.origin) : ""}</div></div></a>`).join("")}
    </div></div>`;
  $("#newPl").onclick = () => sheet("Create", [
    ["New playlist", () => newPlaylist(p => location.hash = "#/pl/" + plIndex(p))],
    ["Import from Spotify (link)", importSpotify],
    ["Pair a TV or tablet", () => location.hash = "#/settings"],
  ]);
  const creds = () => [$("#le").value.trim(), $("#lp").value];
  $("#lf")?.addEventListener("submit", async e => {
    e.preventDefault(); const [email, password] = creds();
    try { saveSession(await auth("/auth/v1/token?grant_type=password", { email, password }), email); toast("Logged in"); await syncLibrary(); }
    catch (err) { toast(err.message); }
  });
  $("#su")?.addEventListener("click", async () => {
    const [email, password] = creds(); if (!email || password.length < 6) return toast("Email and a password of 6+ characters");
    try {
      const j = await auth("/auth/v1/signup?redirect_to=" + encodeURIComponent(location.origin + "/app"), { email, password });
      if (j.access_token) { saveSession(j, email); toast("Account made"); syncLibrary(); } else toast("Check your email and tap the link, it brings you back here logged in");
    } catch (err) { toast(err.message); }
  });
}

function collection(title, sub, cover, tracks, { pl, extraBtn } = {}) {
  view.innerHTML = `<div><div class="titleRow"><a class="back" href="javascript:history.back()"><span class="i i-back"></span>Back</a>${pl ? `<button class="icon" id="plMore" aria-label="More"><span class="i i-more"></span></button>` : ""}</div>
    <div class="colHead">${cover}<h1>${h(title)}</h1><div class="dim small">${h(sub)}</div></div>
    <div class="btns" style="justify-content:center"><button class="btn primary" id="cPlay"><span class="i i-play"></span>Play</button><button class="btn" id="cShuf"><span class="i i-shuffle"></span>Shuffle</button><button class="btn" id="cDl">${extraBtn || '<span class="i i-dl"></span>'}</button></div>
    <div id="cl">${tracks.map(rowHtml).join("")}</div>${tracks.length ? "" : `<p class="dim" style="text-align:center">Nothing here yet</p>`}</div>`;
  $("#cl").querySelectorAll(".row[data-i]").forEach(r => r.onclick = e => {
    const t = tracks[+r.dataset.i];
    if (e.target.closest("[data-more]")) return trackMenu(t, pl);
    playList(tracks, +r.dataset.i);
  });
  $("#cPlay").onclick = () => tracks.length && playList(tracks, 0);
  $("#cShuf").onclick = () => tracks.length && playList(tracks, 0, true);
  if (pl) $("#plMore").onclick = () => sheet(pl.name, [
    [prefs.pinned.includes(pl.name) ? "Unpin" : "Pin to the top", () => { prefs.pinned = prefs.pinned.includes(pl.name) ? prefs.pinned.filter(n => n !== pl.name) : [pl.name, ...prefs.pinned]; savePrefs(); toast(prefs.pinned.includes(pl.name) ? "Pinned" : "Unpinned"); }],
    ["Rename", () => { const n = prompt("New name", pl.name)?.trim(); if (n && n !== pl.name) { prefs.pinned = prefs.pinned.map(x => x === pl.name ? n : x); if (prefs.heart === pl.name) prefs.heart = n; savePrefs(); pl.name = n; saveLibrary(); route(); } }],
    ["Change cover (image link)", () => { const u = prompt("Image link (empty = first song's cover)", pl.cover_url || ""); if (u !== null) { pl.cover_url = u.trim() || null; saveLibrary(); route(); } }],
    ["Add all to queue", () => { queue.push(...tracks.map(clean)); if (qi < 0) load(0); toast("Added to queue"); }],
    ["Delete playlist", () => { if (confirm(`Delete ${pl.name}?`)) { library.splice(plIndex(pl), 1); saveLibrary(); location.hash = "#/library"; } }, true],
  ]);
  return $("#cDl");
}
async function downloadsView() {
  const all = (await idb("readonly", s => s.getAll())).sort((a, b) => b.at - a.at).map(x => x.track);
  const size = (await navigator.storage?.estimate?.())?.usage;
  const btn = collection("Downloads", `${all.length} songs${size ? " · " + (size / 1048576).toFixed(0) + " MB" : ""} · play offline`, dlCover, all, { extraBtn: "Remove all" });
  btn.onclick = async () => { if (confirm("Remove every download?")) { await idb("readwrite", s => s.clear()); downloaded.clear(); route(); } };
}
async function listView(title, sub, loader) {
  view.innerHTML = `<div><a class="back" href="javascript:history.back()"><span class="i i-back"></span>Back</a><h1>${h(title)}</h1>${skeleton()}</div>`;
  try { const list = await loader(); const btn = collection(title, sub(list), `<img class="cov" src="${h(list[0]?.art || "/icon.svg")}" alt="">`, list, { extraBtn: "Add to queue" }); btn.onclick = () => { queue.push(...list.map(clean)); if (qi < 0) load(0); toast("Added to queue"); }; }
  catch { view.querySelector("p").textContent = "Couldn't load that, try again"; }
}
async function similar(t) {
  let v = videoIdOf(t) || (await search(`${t.artist} ${t.title}`))[0]?.videoId;
  const s = await piped("/streams/" + v);
  return (s.relatedStreams || []).filter(i => i.url?.includes("watch") && i.duration > 60 && i.duration < 900).map(fromPiped);
}
async function settingsView() {
  const langs = [["", "Phone's language"], ["en", "English"], ["ru", "Russian"], ["ja", "Japanese"], ["uk", "Ukrainian"], ["de", "German"], ["es", "Spanish"]];
  view.innerHTML = `<div><a class="back" href="javascript:history.back()"><span class="i i-back"></span>Back</a><h1>Settings</h1>
    <div class="group"><div class="gt">Account</div>
      ${session ? `<div class="gi"><span>${h(session.email)}</span></div><div class="gi"><span>Devices</span><span class="dim" id="devs">…</span></div>
      <button class="gi" id="pair"><span>Pair a TV or tablet</span><span class="dim">›</span></button>
      <button class="gi" id="sync"><span>Sync now</span><span class="dim">›</span></button>
      <button class="gi" id="others"><span>Log out other devices</span></button>
      <button class="gi red" id="logout"><span>Log out</span></button>` : `<a class="gi" href="#/library"><span>Log in or sign up</span><span class="dim">›</span></a>`}
    </div>
    <div class="group"><div class="gt">Lyrics</div>
      <label class="gi"><span>Show translation</span><input type="checkbox" id="tr" ${prefs.translate ? "checked" : ""}></label>
      <button class="gi" id="lang"><span>Translate to</span><span class="dim">${h((langs.find(l => l[0] === prefs.lang) || langs[0])[1])} ›</span></button>
    </div>
    <div class="group"><div class="gt">Heart button</div>
      <button class="gi" id="heart"><span>Adds songs to</span><span class="dim">${h(heartPl().kind === "liked" ? "Liked songs" : heartPl().name)} ›</span></button>
    </div>
    <div class="group"><div class="gt">Get the app</div>
      <a class="gi" href="https://github.com/jhu7600codes/stash/raw/main/dist/stash.apk"><span>Android</span><span class="dim">›</span></a>
      <a class="gi" href="https://github.com/jhu7600codes/stash/raw/main/dist/stash-armv7.apk"><span>Android TV (32-bit)</span><span class="dim">›</span></a>
    </div><p class="dim small" style="text-align:center">Stash web</p></div>`;
  $("#tr").onchange = e => { prefs.translate = e.target.checked; savePrefs(); lyrFor = null; };
  $("#lang").onclick = () => sheet("Translate to", langs.map(([c, n]) => [n, () => { prefs.lang = c; savePrefs(); lyrFor = null; route(); }]));
  $("#heart").onclick = () => sheet("The heart adds songs to", [["Liked songs", () => { prefs.heart = null; savePrefs(); route(); }], ...playlists().map(p => [p.name, () => { prefs.heart = p.name; savePrefs(); route(); }])]);
  if (!session) return;
  api("/rest/v1/rpc/my_session_count", { method: "POST", body: {} }).then(n => $("#devs").textContent = n <= 1 ? "Just this one" : n + " logged in").catch(() => $("#devs").textContent = "?");
  $("#sync").onclick = () => syncLibrary().then(() => toast("Synced"));
  $("#pair").onclick = async () => {
    const code = prompt("The 6-character code on the TV")?.toUpperCase().replace(/[^A-Z0-9]/g, ""); if (!code) return;
    try { const t = await token(); const r = await fetch(SUPABASE + "/functions/v1/pair", { method: "POST", headers: { apikey: SB_KEY, Authorization: "Bearer " + t, "Content-Type": "application/json" }, body: JSON.stringify({ action: "claim", code }) });
      const j = await r.json(); if (!r.ok || j.error) throw new Error(j.error || "Couldn't pair"); toast(`Paired ${j.device_name || "your device"}`); }
    catch (e) { toast(e.message); }
  };
  $("#others").onclick = async () => { try { const t = await token(); await fetch(SUPABASE + "/auth/v1/logout?scope=others", { method: "POST", headers: { apikey: SB_KEY, Authorization: "Bearer " + t } }); toast("Logged out every other device"); route(); } catch { toast("Couldn't"); } };
  $("#logout").onclick = () => { session = null; localStorage.removeItem("session"); library = [{ kind: "liked", name: "liked songs", tracks: [] }]; ls.set("library", library); toast("Logged out"); location.hash = "#/home"; };
}

// ---------- router ----------
function route() {
  const hsh = location.hash || "#/home", parts = hsh.split("/"), page = parts[1], arg = decodeURIComponent(parts.slice(2).join("/"));
  document.querySelectorAll("#tabs button").forEach(b => b.classList.toggle("on", b.dataset.tab === (["pl", "liked", "downloads", "settings"].includes(page) ? "library" : ["artist", "similar"].includes(page) ? "search" : page)));
  if (page === "search") searchView();
  else if (page === "library") libraryView();
  else if (page === "settings") settingsView();
  else if (page === "liked") collection("Liked songs", `${likedTracks().length} songs`, likedCover, likedTracks()).onclick = () => download(likedTracks());
  else if (page === "downloads") downloadsView();
  else if (page === "pl" && library[arg]) { const p = library[arg], tr = visible(p.tracks); collection(p.name, `${tr.length} songs`, `<img class="cov" src="${h(plCover(p))}" alt="">`, tr, { pl: p }).onclick = () => download(tr); }
  else if (page === "artist") listView(arg, l => `Artist · ${l.length} songs`, () => search(arg));
  else if (page === "ytpl") listView("Playlist", l => `${l.length} songs · YouTube Music`, async () => {
    const d = await ytPlaylist(arg); setTimeout(() => { const t = view.querySelector(".colHead h1"); if (t) t.textContent = d.name;
      const b = document.createElement("button"); b.className = "btn"; b.textContent = "Save to library";
      b.onclick = () => { if (library.some(p => p.name === d.name)) return toast("Already in your library"); library.push({ kind: "playlist", name: d.name, cover_url: d.cover, origin: "youtube", origin_id: arg, tracks: d.tracks.map(toTrackJson) }); saveLibrary(); toast("Saved to your library"); };
      view.querySelector(".btns")?.appendChild(b); }, 0); return d.tracks; })
  else if (page === "mix") listView("Your mix", l => `Made for you · ${l.length} songs`, yourMix)
  else if (page === "similar") { const t = JSON.parse(arg); listView("Similar to " + t.title, l => `${l.length} songs`, () => similar(t)); }
  else home();
  view.scrollTop = 0; topbar();
  // sidebar (desktop): sections + every playlist, the current one lit
  document.querySelectorAll("#side [data-side]").forEach(a => a.classList.toggle("on", a.dataset.side === (["pl", "liked", "downloads", "settings"].includes(page) ? "library" : ["artist", "similar", "ytpl"].includes(page) ? "search" : page)));
  $("#sideList").innerHTML = `<a href="#/liked" class="${page === "liked" ? "on" : ""}">${'<span class="sq liked"><span class="i i-heart"></span></span>'}Liked songs</a><a href="#/downloads" class="${page === "downloads" ? "on" : ""}"><span class="sq dl"><span class="i i-dl"></span></span>Downloads</a>`
    + pinnedFirst(playlists()).map(p => `<a href="#/pl/${plIndex(p)}" class="${page === "pl" && +arg === plIndex(p) ? "on" : ""}"><img src="${h(plCover(p))}" alt="">${h(p.name)}</a>`).join("");
}
// ios large titles: once the big title scrolls away, a small one shows in a blurred bar
function topbar() {
  const t = view.querySelector("h1");
  $("#topTitle").textContent = t?.textContent || "";
  document.body.classList.toggle("scrolled", !!t && view.scrollTop > (t.offsetTop + t.offsetHeight - 10));
}
view.addEventListener("scroll", topbar, { passive: true });
document.querySelectorAll("#tabs button").forEach(b => b.onclick = () => location.hash = "#/" + b.dataset.tab);
addEventListener("hashchange", route);

// ---------- start ----------
if (location.hash.includes("access_token=")) {
  const q = new URLSearchParams(location.hash.slice(1));
  saveSession({ access_token: q.get("access_token"), refresh_token: q.get("refresh_token"), expires_in: +q.get("expires_in") || 3600 }, "");
  history.replaceState(null, "", "/app#/library"); toast("Logged in");
  api("/auth/v1/user").then(u => { session.email = u.email; ls.set("session", session); route(); }).catch(() => {});
}
refreshDownloaded().then(route);
if (session) syncLibrary();
if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js");
