import Foundation

enum NetError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let m) = self { return m }; return nil }
}

private func getJSON(_ url: URL, timeout: Double = 12) async throws -> Any {
    var req = URLRequest(url: url, timeoutInterval: timeout)
    req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)", forHTTPHeaderField: "User-Agent")
    let (data, resp) = try await URLSession.shared.data(for: req)
    guard (resp as? HTTPURLResponse)?.statusCode ?? 500 < 400 else { throw NetError.message("Server said no") }
    return try JSONSerialization.jsonObject(with: data)
}

private func q(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&+=?#"))) ?? s }

/// piped: public youtube frontends with cors-free json (the same servers the web app uses)
enum Piped {
    static var servers = ["https://pipedapi.stellar.afs.ovh", "https://api.piped.private.coffee", "https://pipedapi.adminforge.de", "https://pipedapi.kavin.rocks"]

    static func get(_ path: String) async throws -> [String: Any] {
        for base in servers {
            guard let url = URL(string: base + path) else { continue }
            if let j = try? await getJSON(url, timeout: 9) as? [String: Any] {
                servers.removeAll { $0 == base }; servers.insert(base, at: 0) // the one that answered goes first
                return j
            }
        }
        throw NetError.message("No search server answered")
    }

    /// more servers from the community list, through the stash site
    static func loadMore() async {
        guard let list = try? await getJSON(URL(string: StashAPI.base + "/instances")!) as? [String] else { return }
        for s in list where !servers.contains(s) { servers.append(s) }
    }

    static func track(_ i: [String: Any]) -> Track? {
        guard let u = i["url"] as? String, u.contains("watch"),
              let v = URLComponents(string: "https://x.com" + u)?.queryItems?.first(where: { $0.name == "v" })?.value else { return nil }
        let artist = ((i["uploaderName"] as? String) ?? "").replacingOccurrences(of: " - Topic", with: "")
        return Track(id: "yt:" + v, title: (i["title"] as? String) ?? "", artist: artist,
                     duration: ((i["duration"] as? Double) ?? 0) * 1000, art: i["thumbnail"] as? String,
                     provider: "youtube", source: "https://www.youtube.com/watch?v=" + v, videoId: v)
    }

    static func search(_ query: String) async throws -> [Track] {
        var items = ((try await get("/search?filter=music_songs&q=" + q(query)))["items"] as? [[String: Any]] ?? []).compactMap(track)
        if items.count < 3 { items += ((try? await get("/search?filter=videos&q=" + q(query)))?["items"] as? [[String: Any]] ?? []).compactMap(track) }
        return items
    }

    struct RemotePlaylist: Identifiable, Hashable { let id: String; let name: String; let art: String?; let owner: String }
    static func searchPlaylists(_ query: String) async -> [RemotePlaylist] {
        let items = (try? await get("/search?filter=playlists&q=" + q(query)))?["items"] as? [[String: Any]] ?? []
        return items.compactMap { i in
            guard let u = i["url"] as? String, let id = URLComponents(string: "https://x.com" + u)?.queryItems?.first(where: { $0.name == "list" })?.value else { return nil }
            return RemotePlaylist(id: id, name: (i["name"] as? String) ?? "Playlist", art: i["thumbnail"] as? String, owner: (i["uploaderName"] as? String) ?? "")
        }
    }

    static func playlist(_ id: String) async throws -> (name: String, cover: String?, tracks: [Track]) {
        let j = try await get("/playlists/" + q(id))
        return ((j["name"] as? String) ?? "Playlist", j["thumbnailUrl"] as? String, (j["relatedStreams"] as? [[String: Any]] ?? []).compactMap(track))
    }

    private static var cache: [String: (URL, Date)] = [:]
    /// a playable audio url (m4a first: plays everywhere) for a youtube video
    static func audio(_ videoId: String) async throws -> URL {
        if let c = cache[videoId], c.1 > Date() { return c.0 }
        let j = try await get("/streams/" + videoId)
        let streams = (j["audioStreams"] as? [[String: Any]] ?? []).sorted { (($0["bitrate"] as? Double) ?? 0) > (($1["bitrate"] as? Double) ?? 0) }
        let pick = streams.first { (($0["mimeType"] as? String) ?? "").contains("mp4") } ?? streams.first
        guard let s = pick?["url"] as? String, let url = URL(string: s) else { throw NetError.message("No audio") }
        cache[videoId] = (url, Date().addingTimeInterval(3 * 3600))
        return url
    }

    static func related(_ videoId: String) async throws -> [Track] {
        let j = try await get("/streams/" + videoId)
        return (j["relatedStreams"] as? [[String: Any]] ?? []).filter { (($0["duration"] as? Double) ?? 0) > 60 && (($0["duration"] as? Double) ?? 0) < 900 }.compactMap(track)
    }
}

/// the stash site's small server functions (soundcloud, vocadb, spotify import, musixmatch lyrics)
enum StashAPI {
    static let base = "https://stashmusic.vercel.app/api"

    static func soundcloud(_ query: String) async -> [Track] {
        guard let url = URL(string: base + "/sc?q=" + q(query)), let list = try? await getJSON(url) as? [[String: Any]] else { return [] }
        return list.compactMap { o in
            guard let id = o["id"] as? String, let src = o["source"] as? String else { return nil }
            return Track(id: id, title: (o["title"] as? String) ?? "", artist: (o["artist"] as? String) ?? "", duration: (o["duration"] as? Double) ?? 0,
                         art: o["art"] as? String, provider: "soundcloud", source: src)
        }
    }

    static func soundcloudStream(_ link: String) async throws -> URL {
        guard let o = try await getJSON(URL(string: base + "/sc?stream=" + q(link))!) as? [String: Any],
              let s = o["url"] as? String, let url = URL(string: s) else { throw NetError.message("SoundCloud didn't answer") }
        return url // hls or mp3: AVPlayer plays both
    }

    static func vocadb(tag: String) async -> [Track] {
        guard let list = try? await getJSON(URL(string: base + "/vocadb?tag=" + q(tag))!) as? [[String: Any]] else { return [] }
        return list.compactMap { o in
            guard let v = o["videoId"] as? String else { return nil }
            return Track(id: "yt:" + v, title: (o["title"] as? String) ?? "", artist: (o["artist"] as? String) ?? "",
                         duration: (o["duration"] as? Double) ?? 0, art: o["art"] as? String, source: "https://www.youtube.com/watch?v=" + v, videoId: v)
        }
    }

    struct SpotifyList { let name: String; let cover: String?; let id: String?; let tracks: [(title: String, artist: String, album: String?, duration: Double, art: String?)] }
    static func spotify(_ link: String) async throws -> SpotifyList {
        let any = try await getJSON(URL(string: base + "/spotify?link=" + q(link))!, timeout: 25)
        guard let o = any as? [String: Any], let name = o["name"] as? String else { throw NetError.message("Couldn't read that link") }
        let tracks = (o["tracks"] as? [[String: Any]] ?? []).map { t in
            ((t["title"] as? String) ?? "", (t["artist"] as? String) ?? "", t["album"] as? String, (t["duration"] as? Double) ?? 0, t["art"] as? String)
        }
        return SpotifyList(name: name, cover: o["cover"] as? String, id: o["origin_id"] as? String, tracks: tracks)
    }

    /// musixmatch (word timing + translation) through the site, lrclib straight from here otherwise
    static func lyrics(for t: Track, translateTo lang: String?) async -> SongLyrics? {
        let title = songName(t.title), artist = t.mainArtist
        var s = base + "/lyrics?title=\(q(title))&artist=\(q(artist))&duration=\(Int(t.duration / 1000))"
        if let lang { s += "&lang=" + lang }
        if let o = try? await getJSON(URL(string: s)!, timeout: 15) as? [String: Any], let raw = o["lines"] as? [[Any]], !raw.isEmpty {
            let lines = raw.map { (ms: ($0.first as? Double) ?? 0, text: ($0.last as? String) ?? "") }
            let words = (o["words"] as? [[[Any]]])?.map { line in line.map { (ms: ($0.first as? Double) ?? 0, text: ($0.last as? String) ?? "") } }
            let tr = (o["translated"] as? [Any])?.map { $0 as? String }
            return SongLyrics(lines: lines, words: words, translated: tr, synced: true)
        }
        // lrclib
        let d = Int(t.duration / 1000)
        var hit = try? await getJSON(URL(string: "https://lrclib.net/api/get?artist_name=\(q(artist))&track_name=\(q(title))" + (d > 0 ? "&duration=\(d)" : ""))!) as? [String: Any]
        if hit == nil { hit = (try? await getJSON(URL(string: "https://lrclib.net/api/search?q=" + q(artist + " " + title))!) as? [[String: Any]])?.first }
        guard let r = hit else { return nil }
        if let synced = r["syncedLyrics"] as? String {
            let re = try! NSRegularExpression(pattern: #"^\[(\d+):(\d+(?:\.\d+)?)\](.*)"#)
            let lines: [(ms: Double, text: String)] = synced.split(separator: "\n").compactMap { l in
                let s = String(l)
                guard let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
                      let a = Range(m.range(at: 1), in: s), let b = Range(m.range(at: 2), in: s), let c = Range(m.range(at: 3), in: s) else { return nil }
                return (ms: (Double(s[a])! * 60 + Double(s[b])!) * 1000, text: String(s[c]).trimmingCharacters(in: .whitespaces))
            }
            if !lines.isEmpty { return SongLyrics(lines: lines, words: nil, translated: nil, synced: true) }
        }
        if let plain = r["plainLyrics"] as? String {
            return SongLyrics(lines: plain.split(separator: "\n", omittingEmptySubsequences: false).map { (ms: 0, text: String($0)) }, words: nil, translated: nil, synced: false)
        }
        return nil
    }

    /// "うっせぇわ - Usseewa (Live At …) [MV]" -> the song's own name
    static func songName(_ title: String) -> String {
        var s = title.replacingOccurrences(of: #"\s*[(\[【].*?[)\]】]"#, with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\s+(feat|ft)\.?\s.*$"#, with: "", options: [.regularExpression, .caseInsensitive])
        s = s.trimmingCharacters(in: .whitespaces)
        return s.isEmpty ? title : s
    }
}

/// the stash account (supabase): login, the library backup, devices, pairing
enum Supabase {
    static let url = "https://mcmnovtqupckpylrnobp.supabase.co"
    static let key = "sb_publishable_uexeqwWBLSc9xTTCDrOUog_TcUe2Or7"

    struct Session: Codable { var email: String; var access: String; var refresh: String; var exp: Date }

    static func call(_ path: String, method: String = "POST", body: Any? = nil, token: String? = nil) async throws -> Any? {
        var req = URLRequest(url: URL(string: url + path)!, timeoutInterval: 20)
        req.httpMethod = method
        req.setValue(key, forHTTPHeaderField: "apikey")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token { req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if let body { req.httpBody = try JSONSerialization.data(withJSONObject: body) }
        let (data, resp) = try await URLSession.shared.data(for: req)
        let j = try? JSONSerialization.jsonObject(with: data)
        if (resp as? HTTPURLResponse)?.statusCode ?? 500 >= 400 {
            let o = j as? [String: Any]
            throw NetError.message((o?["error_description"] ?? o?["msg"] ?? o?["message"] ?? o?["error"]) as? String ?? "Something went wrong")
        }
        return j
    }

    static func session(from o: Any?, email: String) -> Session? {
        guard let o = o as? [String: Any], let a = o["access_token"] as? String, let r = o["refresh_token"] as? String else { return nil }
        let e = ((o["user"] as? [String: Any])?["email"] as? String) ?? email
        return Session(email: e, access: a, refresh: r, exp: Date().addingTimeInterval(((o["expires_in"] as? Double) ?? 3600) - 60))
    }
}
