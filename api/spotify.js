// a spotify playlist / album link -> its songs, read from spotify's public embed page
// (same as the android app's importer). {name, cover, tracks: [{title, artist, album, duration, art}]}
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
module.exports = async (req, res) => {
  try {
    let link = String(req.query.link || "");
    if (link.includes("spotify.link")) link = (await fetch(link, { headers: { "User-Agent": UA } })).url;
    const m = /(?:open\.spotify\.com\/(?:intl-[a-z]+\/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]+)/.exec(link);
    if (!m) return res.status(400).json({ error: "That doesn't look like a Spotify playlist or album link" });
    const [, kind, id] = m;
    const html = await (await fetch(`https://open.spotify.com/embed/${kind}/${id}`, { headers: { "User-Agent": UA } })).text();
    const json = /<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)<\/script>/.exec(html)?.[1];
    const e = json && JSON.parse(json)?.props?.pageProps?.state?.data?.entity;
    if (!e) return res.status(404).json({ error: "Couldn't read that playlist (is it private?)" });
    const name = e.name || e.title || "Spotify " + kind, cover = (e.coverArt?.sources || []).slice(-1)[0]?.url || null;
    const tracks = (e.trackList || []).map(t => ({
      title: t.title, artist: String(t.subtitle || "").replace(/ /g, " "), album: kind === "album" ? name : null,
      duration: t.duration || 0, art: kind === "album" ? cover : null,
    })).filter(t => t.title);
    res.setHeader("Cache-Control", "public, s-maxage=3600");
    res.status(200).json({ name, cover, origin: "spotify", origin_id: id, tracks });
  } catch (e) { res.status(500).json({ error: "Couldn't reach Spotify" }); }
};
