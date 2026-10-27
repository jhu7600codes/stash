// the community list of youtube frontends (http only, so the https web app reads it through here)
module.exports = async (req, res) => {
  try {
    const j = await (await fetch("http://144.31.189.129/notPipe.json", { signal: AbortSignal.timeout(6000) })).json();
    const piped = (j.piped || []).map(s => String(s).split(",")[0].trim().replace(/\/$/, "")).filter(u => u.startsWith("https://"));
    res.setHeader("Cache-Control", "public, s-maxage=21600");
    return res.status(200).json(piped);
  } catch (e) { res.status(200).json([]); }
};
