import Foundation

/// a song. same json as the android app's backup (`id, title, artist, album, duration, art, provider, source, hint`),
/// plus `missing` for songs an import couldn't find (kept so the backup stays whole, hidden in lists)
struct Track: Codable, Identifiable, Hashable {
    var id: String
    var title: String
    var artist: String
    var album: String? = nil
    var duration: Double = 0 // ms
    var art: String? = nil
    var provider: String = "youtube"
    var source: String = ""
    var hint: String? = nil
    var missing: Bool? = nil
    var videoId: String? = nil

    enum CodingKeys: String, CodingKey { case id, title, artist, album, duration, art, provider, source, hint, missing, videoId }

    init(id: String, title: String, artist: String, album: String? = nil, duration: Double = 0, art: String? = nil,
         provider: String = "youtube", source: String = "", videoId: String? = nil, missing: Bool? = nil) {
        self.id = id; self.title = title; self.artist = artist; self.album = album; self.duration = duration
        self.art = art; self.provider = provider; self.source = source; self.videoId = videoId; self.missing = missing
    }

    // tolerant: backups made by older versions miss fields, and "missing" rows have no id
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        title = (try? c.decode(String.self, forKey: .title)) ?? "Unknown"
        artist = (try? c.decode(String.self, forKey: .artist)) ?? ""
        album = try? c.decodeIfPresent(String.self, forKey: .album)
        duration = (try? c.decode(Double.self, forKey: .duration)) ?? 0
        art = try? c.decodeIfPresent(String.self, forKey: .art)
        provider = (try? c.decode(String.self, forKey: .provider)) ?? "youtube"
        source = (try? c.decode(String.self, forKey: .source)) ?? ""
        hint = try? c.decodeIfPresent(String.self, forKey: .hint)
        missing = try? c.decodeIfPresent(Bool.self, forKey: .missing)
        videoId = try? c.decodeIfPresent(String.self, forKey: .videoId)
        id = (try? c.decode(String.self, forKey: .id)) ?? "missing:\(artist)|\(title)"
    }

    var isMissing: Bool { missing == true }

    /// the youtube video behind it, when it's a youtube song
    var youtubeId: String? {
        if let videoId { return videoId }
        guard provider == "youtube" else { return nil }
        if let r = source.range(of: #"(?:[?&]v=|youtu\.be/)([\w-]{11})"#, options: .regularExpression) {
            let m = String(source[r])
            return String(m.suffix(11))
        }
        return nil
    }

    var artURL: URL? { art.flatMap(URL.init(string:)) }
    var mainArtist: String {
        artist.components(separatedBy: CharacterSet(charactersIn: ",&")).first?
            .components(separatedBy: " feat").first?.trimmingCharacters(in: .whitespaces) ?? artist
    }
}

/// a playlist (or the liked songs list), as stored in the stash account
struct Playlist: Codable, Identifiable, Hashable {
    var kind: String = "playlist" // "liked" | "playlist"
    var name: String
    var cover_url: String? = nil
    var origin: String? = "local"
    var origin_id: String? = nil
    var tracks: [Track] = []
    var id: String { kind == "liked" ? "__liked" : name }
    var visible: [Track] { tracks.filter { !$0.isMissing } }
    var coverURL: URL? { (cover_url ?? visible.first?.art).flatMap(URL.init(string:)) }
}

struct Lyrics: Decodable {
    var lines: [[LyricPiece]]? // unused shape guard
}

/// karaoke lyrics: lines with their start (ms), optional word timing, optional translation
struct SongLyrics {
    var lines: [(ms: Double, text: String)]
    var words: [[(ms: Double, text: String)]]?
    var translated: [String?]?
    var synced: Bool
}

enum LyricPiece: Decodable {
    case num(Double), str(String)
    init(from d: Decoder) throws {
        let c = try d.singleValueContainer()
        if let n = try? c.decode(Double.self) { self = .num(n) } else { self = .str((try? c.decode(String.self)) ?? "") }
    }
    var num: Double { if case .num(let n) = self { return n }; return 0 }
    var str: String { if case .str(let s) = self { return s }; return "" }
}

func fmt(_ ms: Double) -> String {
    guard ms.isFinite, ms > 0 else { return "0:00" }
    let s = Int(ms / 1000)
    return "\(s / 60):" + String(format: "%02d", s % 60)
}
