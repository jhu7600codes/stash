// vocadb for the web app (no cors on vocadb itself): ?q= songs by name, ?tag= songs by voicebank / tag.
// songs come back as their youtube upload, so they play like any youtube song.
module.exports = async (req, res) => {
  try {
    const q = String(req.query.q || ""), tag = String(req.query.tag || "");
    const base = "https://vocadb.net/api/";
    const common = "&fields=PVs&lang=Romaji&onlyWithPvs=true&pvServices=Youtube&sort=RatingScore&maxResults=40";
    let url = base + "songs?nameMatchMode=Auto&query=" + encodeURIComponent(q) + common;
    if (tag) {
      // a voicebank ("miku", "teto") first, else a vocadb tag
      const a = await (await fetch(base + "artists?nameMatchMode=Words&maxResults=1&sort=FollowerCount&artistTypes=Vocaloid,UTAU,SynthesizerV,CeVIO&query=" + encodeURIComponent(tag))).json();
      const id = a.items?.[0]?.id;
      url = id ? base + "songs?artistId[]=" + id + "&childVoicebanks=true" + common : base + "songs?tagName[]=" + encodeURIComponent(tag) + common;
    }
    const j = await (await fetch(url)).json();
    const out = (j.items || []).map(s => {
      const pv = (s.pvs || []).filter(p => p.service === "Youtube" && !p.disabled).sort((a, b) => (a.pvType === "Original" ? 0 : 1) - (b.pvType === "Original" ? 0 : 1))[0];
      if (!pv) return null;
      return { id: "yt:" + pv.pvId, videoId: pv.pvId, title: s.name, artist: s.artistString, art: `https://i.ytimg.com/vi/${pv.pvId}/hqdefault.jpg`,
        duration: (s.lengthSeconds || 0) * 1000, provider: "youtube", source: "https://www.youtube.com/watch?v=" + pv.pvId };
    }).filter(Boolean);
    res.setHeader("Cache-Control", "public, s-maxage=3600");
    res.status(200).json(out);
  } catch (e) { res.status(502).json({ error: "VocaDB didn't answer" }); }
};
