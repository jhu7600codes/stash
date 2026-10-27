// musixmatch for the web app (browsers can't call it directly: no cors). same as the android
// app: the iphone app's api with an anonymous token, word timing (richsync) when it has it.
// returns {lines: [[ms, text]], words?: [[[ms, piece]]], translated?: [text]} or 404.
let token = null;
const BASE = "https://apic.musixmatch.com/ws/1.1/";
const APP = "mac-ios-v2.0";

async function getToken(renew) {
  if (token && !renew) return token;
  const j = await (await fetch(`${BASE}token.get?app_id=${APP}`)).json();
  const t = j?.message?.body?.user_token;
  if (!t || /^0+$/.test(t)) throw new Error("no token");
  return (token = t);
}

module.exports = async (req, res) => {
  const { title = "", artist = "", duration = "", lang = "" } = req.query;
  try {
    for (let attempt = 0; attempt < 2; attempt++) {
      const u = new URL(BASE + "macro.subtitles.get");
      const p = { app_id: APP, format: "json", subtitle_format: "mxm", namespace: "lyrics_richsynched", optional_calls: "track.richsync",
        q_track: title, q_artist: artist.split(",")[0].trim(), usertoken: await getToken(attempt > 0) };
      if (+duration > 0) { p.q_duration = duration; p.f_subtitle_length = duration; }
      if (lang) { p.selected_language = lang; p.part = "subtitle_translated"; }
      Object.entries(p).forEach(([k, v]) => u.searchParams.set(k, v));
      const msg = (await (await fetch(u)).json()).message;
      if (msg?.header?.status_code === 401) continue;
      const calls = msg?.body?.macro_calls; if (!calls) break;
      const rich = calls["track.richsync.get"]?.message?.body?.richsync;
      const sub = calls["track.subtitles.get"]?.message?.body?.subtitle_list?.[0]?.subtitle;
      let lines, words;
      if (rich?.richsync_body && !rich.restricted) {
        const rs = JSON.parse(rich.richsync_body);
        lines = rs.map(l => [Math.round(l.ts * 1000), l.x]);
        words = rs.map(l => l.l.map(w => [Math.round((l.ts + w.o) * 1000), w.c]));
      } else if (sub?.subtitle_body) {
        lines = JSON.parse(sub.subtitle_body).map(l => [Math.round(l.time.total * 1000), l.text]);
      }
      if (!lines?.length) break;
      let translated;
      const tr = sub?.subtitle_translated?.subtitle_body;
      if (tr && sub.subtitle_language !== lang) {
        const map = new Map(JSON.parse(tr).map(l => [String(l.original || "").trim(), l.text]));
        translated = lines.map(([, x]) => map.get(String(x).trim()) || null);
      }
      res.setHeader("Cache-Control", "public, s-maxage=86400, max-age=3600");
      return res.status(200).json({ lines, words, translated, source: "Musixmatch" });
    }
  } catch (e) {}
  res.status(404).json({ error: "no lyrics" });
};
