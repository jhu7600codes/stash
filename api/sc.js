// soundcloud for the web app: ?q= searches, ?stream=<song link> gives a playable url.
// the client id is scraped from soundcloud's own scripts, like the android app.
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
let cid = null;
async function clientId(renew) {
  if (cid && !renew) return cid;
  const home = await (await fetch("https://soundcloud.com/discover", { headers: { "User-Agent": UA } })).text();
  const scripts = [...home.matchAll(/https:\/\/a-v2\.sndcdn\.com\/assets\/[^"]+\.js/g)].map(m => m[0]).reverse();
  for (const s of scripts) {
    const js = await (await fetch(s)).text().catch(() => "");
    const m = /client_id\s*:\s*"([A-Za-z0-9]{32})"/.exec(js);
    if (m) return (cid = m[1]);
  }
  throw new Error("no client id");
}
async function api(path, params = {}) {
  for (let i = 0; i < 2; i++) {
    const u = new URL("https://api-v2.soundcloud.com" + path);
    Object.entries({ ...params, client_id: await clientId(i > 0) }).forEach(([k, v]) => u.searchParams.set(k, v));
    const r = await fetch(u, { headers: { "User-Agent": UA } });
    if (r.status === 401 || r.status === 403) continue;
    return r.json();
  }
  throw new Error("refused");
}
module.exports = async (req, res) => {
  try {
    if (req.query.q) {
      const j = await api("/search/tracks", { q: req.query.q, limit: 20 });
      const tracks = (j.collection || []).filter(t => t.streamable !== false).map(t => ({
        id: "sc:" + t.permalink_url, title: t.title, artist: t.publisher_metadata?.artist || t.user?.username || "",
        art: (t.artwork_url || t.user?.avatar_url || "").replace("-large.", "-t500x500."), duration: t.duration || 0,
        provider: "soundcloud", source: t.permalink_url, plays: t.playback_count,
      }));
      res.setHeader("Cache-Control", "public, s-maxage=600");
      return res.status(200).json(tracks);
    }
    if (req.query.stream) {
      const t = await api("/resolve", { url: req.query.stream });
      const tc = t.media?.transcodings || [];
      const pick = tc.find(x => x.format?.protocol === "progressive") || tc.find(x => x.format?.protocol === "hls" && /mp4a|mpeg/.test(x.format?.mime_type)) || tc[0];
      if (!pick) return res.status(404).json({ error: "no stream" });
      const u = new URL(pick.url); u.searchParams.set("client_id", await clientId());
      if (t.track_authorization) u.searchParams.set("track_authorization", t.track_authorization);
      const s = await (await fetch(u, { headers: { "User-Agent": UA } })).json();
      return res.status(200).json({ url: s.url, hls: pick.format.protocol === "hls" });
    }
    res.status(400).json({ error: "q or stream" });
  } catch (e) { res.status(502).json({ error: "SoundCloud didn't answer" }); }
};
