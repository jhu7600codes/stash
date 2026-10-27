import Foundation
import SwiftUI

/// settings that stay on this phone
struct Prefs: Codable {
    var translate = true
    var lang = "" // "" = the phone's language
    var heart: String? = nil // playlist the heart adds to, nil = liked songs
    var pinned: [String] = []
}

/// where to go inside a tab
enum Route: Hashable {
    case playlist(String) // Playlist.id
    case downloads
    case artist(String)
    case similar(Track)
    case ytPlaylist(String, String) // id, name
    case mix
}

/// the library, the account and everything saved on the phone
@MainActor
final class Store: ObservableObject {
    static let shared = Store()

    @Published var library: [Playlist] = []
    @Published var prefs = Prefs()
    @Published var session: Supabase.Session?
    @Published var recent: [Track] = []
    @Published var downloads: [String: Track] = [:] // file name -> song
    @Published var downloading: Set<String> = []
    @Published var toast: String?
    @Published var syncing = false
    @Published var tab = 0
    @Published var paths: [[Route]] = [[], [], [], []]

    private let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    var dlDir: URL { docs.appendingPathComponent("Downloads", isDirectory: true) }
    private var saveTask: Task<Void, Never>?

    init() {
        try? FileManager.default.createDirectory(at: dlDir, withIntermediateDirectories: true)
        library = load("library.json") ?? []
        prefs = load("prefs.json") ?? Prefs()
        session = load("session.json")
        recent = load("recent.json") ?? []
        downloads = load("downloads.json") ?? [:]
        // forget downloads whose file got deleted in the files app
        downloads = downloads.filter { FileManager.default.fileExists(atPath: dlDir.appendingPathComponent($0.key).path) }
        ensureLiked()
        Task { await refreshSession(); await pull() }
    }

    // MARK: files

    private func load<T: Decodable>(_ name: String) -> T? {
        guard let d = try? Data(contentsOf: docs.appendingPathComponent(".stash-" + name)) else { return nil }
        return try? JSONDecoder().decode(T.self, from: d)
    }
    private func save<T: Encodable>(_ v: T, _ name: String) {
        if let d = try? JSONEncoder().encode(v) { try? d.write(to: docs.appendingPathComponent(".stash-" + name), options: .atomic) }
    }
    func savePrefs() { save(prefs, "prefs.json") }

    func say(_ s: String) {
        withAnimation(.spring(response: 0.35)) { toast = s }
        Task { try? await Task.sleep(nanoseconds: 2_200_000_000); if toast == s { withAnimation { toast = nil } } }
    }

    // MARK: library

    private func ensureLiked() {
        if !library.contains(where: { $0.kind == "liked" }) { library.insert(Playlist(kind: "liked", name: "Liked songs"), at: 0) }
    }
    var liked: Playlist { library.first { $0.kind == "liked" } ?? Playlist(kind: "liked", name: "Liked songs") }
    var playlists: [Playlist] {
        let p = library.filter { $0.kind != "liked" }
        return p.filter { prefs.pinned.contains($0.name) } + p.filter { !prefs.pinned.contains($0.name) }
    }
    func playlist(_ id: String) -> Playlist? { library.first { $0.id == id } }
    func index(_ id: String) -> Int? { library.firstIndex { $0.id == id } }

    /// the playlist the heart button fills (settings), liked songs by default
    var heartTarget: Playlist { prefs.heart.flatMap { n in library.first { $0.kind != "liked" && $0.name == n } } ?? liked }
    func isHearted(_ t: Track) -> Bool { heartTarget.tracks.contains { $0.id == t.id } }
    func toggleHeart(_ t: Track) {
        guard let i = index(heartTarget.id) else { return }
        if let k = library[i].tracks.firstIndex(where: { $0.id == t.id }) {
            library[i].tracks.remove(at: k); say("Removed from \(library[i].name)")
        } else {
            library[i].tracks.insert(clean(t), at: 0); say("Added to \(library[i].name)")
        }
        changed()
    }

    func add(_ t: Track, to id: String) {
        guard let i = index(id) else { return }
        if library[i].tracks.contains(where: { $0.id == t.id }) { return say("Already in \(library[i].name)") }
        library[i].tracks.append(clean(t)); changed(); say("Added to \(library[i].name)")
    }
    func remove(_ t: Track, from id: String) {
        guard let i = index(id) else { return }
        library[i].tracks.removeAll { $0.id == t.id }; changed()
    }
    func move(in id: String, from: IndexSet, to: Int) {
        guard let i = index(id) else { return }
        var v = library[i].visible
        v.move(fromOffsets: from, toOffset: to)
        library[i].tracks = v + library[i].tracks.filter(\.isMissing); changed()
    }
    @discardableResult
    func create(_ name: String, tracks: [Track] = [], cover: String? = nil, origin: String = "local", originId: String? = nil) -> String {
        var n = name.trimmingCharacters(in: .whitespaces).isEmpty ? "My playlist" : name
        while library.contains(where: { $0.name == n }) { n += " (2)" }
        library.append(Playlist(name: n, cover_url: cover, origin: origin, origin_id: originId, tracks: tracks.map(clean)))
        changed(); return n
    }
    func rename(_ id: String, to name: String) {
        guard let i = index(id), !name.isEmpty, !library.contains(where: { $0.name == name }) else { return }
        let old = library[i].name
        library[i].name = name
        prefs.pinned = prefs.pinned.map { $0 == old ? name : $0 }
        if prefs.heart == old { prefs.heart = name }
        savePrefs(); changed()
    }
    func delete(_ id: String) {
        guard let i = index(id), library[i].kind != "liked" else { return }
        let name = library[i].name
        library.remove(at: i)
        prefs.pinned.removeAll { $0 == name }
        if prefs.heart == name { prefs.heart = nil }
        savePrefs(); changed()
    }
    func togglePin(_ name: String) {
        if prefs.pinned.contains(name) { prefs.pinned.removeAll { $0 == name } } else { prefs.pinned.insert(name, at: 0) }
        savePrefs()
    }
    func setCover(_ id: String, _ url: String?) {
        guard let i = index(id) else { return }
        library[i].cover_url = url; changed()
    }

    /// what gets saved: no local-only bits
    private func clean(_ t: Track) -> Track { var c = t; c.videoId = nil; return c }

    func played(_ t: Track) {
        recent.removeAll { $0.id == t.id }
        recent.insert(t, at: 0)
        if recent.count > 40 { recent.removeLast(recent.count - 40) }
        save(recent, "recent.json")
    }

    /// saves on the phone now, to the account a moment later (several quick edits = one upload)
    func changed() {
        save(library, "library.json")
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if Task.isCancelled { return }
            await self?.push()
        }
    }

    // MARK: account

    func token() async -> String? {
        guard let s = session else { return nil }
        if s.exp > Date() { return s.access }
        await refreshSession()
        return session?.access
    }

    func refreshSession() async {
        guard let s = session, s.exp <= Date() else { return }
        do {
            let r = try await Supabase.call("/auth/v1/token?grant_type=refresh_token", body: ["refresh_token": s.refresh])
            if let n = Supabase.session(from: r, email: s.email) { session = n; save(n, "session.json") }
        } catch {
            if let e = error as? NetError, case .message(let m) = e, m.lowercased().contains("refresh") { logout(local: true) }
        }
    }

    func login(email: String, password: String) async throws {
        let r = try await Supabase.call("/auth/v1/token?grant_type=password", body: ["email": email, "password": password])
        guard let s = Supabase.session(from: r, email: email) else { throw NetError.message("Couldn't log in") }
        session = s; save(s, "session.json")
        await pull(merge: true)
    }

    func signup(email: String, password: String) async throws {
        let r = try await Supabase.call("/auth/v1/signup?redirect_to=https://stashmusic.vercel.app/app", body: ["email": email, "password": password])
        if let s = Supabase.session(from: r, email: email) {
            session = s; save(s, "session.json"); await push()
        } else { say("Check your email to confirm, then log in") }
    }

    func logout(local: Bool = false) {
        if !local, let t = session?.access { Task { _ = try? await Supabase.call("/auth/v1/logout", body: [String: Any](), token: t) } }
        session = nil
        try? FileManager.default.removeItem(at: docs.appendingPathComponent(".stash-session.json"))
    }

    func logoutOthers() async {
        guard let t = await token() else { return }
        do { _ = try await Supabase.call("/auth/v1/logout?scope=others", body: [String: Any](), token: t); say("Logged out everywhere else") }
        catch { say(error.localizedDescription) }
    }

    func sessionCount() async -> Int? {
        guard let t = await token() else { return nil }
        let r = try? await Supabase.call("/rest/v1/rpc/my_session_count", body: [String: Any](), token: t)
        return (r as? Int) ?? (r as? NSNumber)?.intValue
    }

    func pair(_ code: String) async throws {
        guard let t = await token() else { throw NetError.message("Log in first") }
        _ = try await Supabase.call("/functions/v1/pair", body: ["action": "claim", "code": code.uppercased().trimmingCharacters(in: .whitespaces)], token: t)
    }

    /// the account's library -> here. merge = first login: keep what was made here too
    func pull(merge: Bool = false) async {
        guard let t = await token() else { return }
        syncing = true; defer { syncing = false }
        guard let any = try? await Supabase.call("/rest/v1/library_items?select=kind,name,cover_url,origin,origin_id,tracks&order=sort_index", method: "GET", token: t),
              let data = try? JSONSerialization.data(withJSONObject: any),
              let rows = try? JSONDecoder().decode([Playlist].self, from: data) else { return }
        if rows.isEmpty { if merge { await push() }; return }
        if merge {
            var out = rows
            for p in library {
                if p.kind == "liked" {
                    if let i = out.firstIndex(where: { $0.kind == "liked" }) {
                        for t in p.tracks where !out[i].tracks.contains(where: { $0.id == t.id }) { out[i].tracks.append(t) }
                    }
                } else if !p.tracks.isEmpty, !out.contains(where: { $0.name == p.name }) { out.append(p) }
            }
            library = out; ensureLiked(); changed()
        } else {
            library = rows; ensureLiked(); save(library, "library.json")
        }
    }

    func push() async {
        guard let t = await token() else { return }
        var k = 0
        let items: [[String: Any]] = library.map { p in
            k += 1
            let tracks = (try? JSONSerialization.jsonObject(with: JSONEncoder().encode(p.tracks))) ?? [Any]()
            return ["kind": p.kind, "sort_index": p.kind == "liked" ? 0 : k, "name": p.name, "cover_url": orNull(p.cover_url),
                    "origin": p.origin ?? "local", "origin_id": orNull(p.origin_id), "tracks": tracks]
        }
        do { _ = try await Supabase.call("/rest/v1/rpc/replace_library", body: ["items": items], token: t) }
        catch { say("Couldn't sync: " + error.localizedDescription) }
    }

    // MARK: downloads

    private func fileName(_ t: Track) -> String { t.id.replacingOccurrences(of: #"[^\w-]"#, with: "_", options: .regularExpression) + ".m4a" }
    func localFile(_ t: Track) -> URL? {
        let n = fileName(t)
        return downloads[n] != nil ? dlDir.appendingPathComponent(n) : nil
    }
    func isDownloaded(_ t: Track) -> Bool { localFile(t) != nil }

    func download(_ tracks: [Track]) {
        let todo = tracks.filter { !$0.isMissing && !isDownloaded($0) && !downloading.contains($0.id) }
        guard !todo.isEmpty else { return say("Already downloaded") }
        todo.forEach { downloading.insert($0.id) }
        say(todo.count == 1 ? "Downloading…" : "Downloading \(todo.count) songs…")
        Task {
            var failed = 0
            // two at a time, like the app
            await withTaskGroup(of: Bool.self) { g in
                var it = todo.makeIterator()
                func next() -> Bool {
                    guard let t = it.next() else { return false }
                    g.addTask { await self.downloadOne(t) }
                    return true
                }
                _ = next(); _ = next()
                while let ok = await g.next() { if !ok { failed += 1 }; _ = next() }
            }
            say(failed == 0 ? "Downloaded" : "\(failed) couldn't download")
        }
    }

    private func downloadOne(_ t: Track) async -> Bool {
        defer { downloading.remove(t.id) }
        do {
            let src: URL
            if let v = t.youtubeId { src = try await Piped.audio(v) }
            else if t.provider == "soundcloud" { src = try await StashAPI.soundcloudStream(t.source) }
            else { return false }
            if src.absoluteString.contains(".m3u8") { return false } // hls can't be saved as one file
            let (tmp, resp) = try await URLSession.shared.download(from: src)
            guard (resp as? HTTPURLResponse)?.statusCode ?? 500 < 400 else { return false }
            let n = fileName(t), dest = dlDir.appendingPathComponent(n)
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: tmp, to: dest)
            downloads[n] = t; save(downloads, "downloads.json")
            return true
        } catch { return false }
    }

    func deleteDownload(_ t: Track) {
        let n = fileName(t)
        try? FileManager.default.removeItem(at: dlDir.appendingPathComponent(n))
        downloads[n] = nil; save(downloads, "downloads.json")
    }
    var downloadedTracks: [Track] { Array(downloads.values).sorted { $0.title < $1.title } }

    // MARK: spotify import (every song looked up on youtube, like the app)

    func importSpotify(_ link: String) async {
        say("Reading the playlist…")
        do {
            let j = try await StashAPI.spotify(link)
            let name = create(j.name, cover: j.cover, origin: "spotify", originId: j.id)
            let id = name
            var out = [Track?](repeating: nil, count: j.tracks.count)
            var found = 0
            await withTaskGroup(of: (Int, Track).self) { g in
                var next = 0
                func add() {
                    guard next < j.tracks.count else { return }
                    let k = next; next += 1
                    let s = j.tracks[k]
                    g.addTask {
                        let first = s.artist.components(separatedBy: ",").first ?? s.artist
                        let hits = (try? await Piped.search("\(first) \(s.title)")) ?? []
                        if var h = hits.first(where: { s.duration == 0 || $0.duration == 0 || abs($0.duration - s.duration) < 30000 }) {
                            h.album = s.album; h.art = s.art ?? h.art
                            return (k, h)
                        }
                        return (k, Track(id: "missing:\(s.artist)|\(s.title)", title: s.title, artist: s.artist, album: s.album, duration: s.duration, missing: true))
                    }
                }
                add(); add(); add()
                var done = 0
                while let r = await g.next() {
                    let (k, t) = r
                    out[k] = t; done += 1
                    if !t.isMissing { found += 1 }
                    if done % 10 == 0, let i = index(id) { library[i].tracks = out.compactMap { $0 }; say("Moving \(j.name): \(done)/\(j.tracks.count)") }
                    add()
                }
            }
            if let i = index(id) { library[i].tracks = out.compactMap { $0 }.map(clean); changed() }
            say("\(j.name): \(found) of \(j.tracks.count) songs found")
        } catch { say(error.localizedDescription) }
    }

    /// "this is" artists: who you play and like the most
    var topArtists: [String] {
        var count: [String: Int] = [:]
        for t in recent + liked.visible { let a = t.mainArtist; if !a.isEmpty { count[a, default: 0] += 1 } }
        return count.sorted { $0.value > $1.value }.prefix(6).map(\.key)
    }
}

private func orNull(_ s: String?) -> Any { if let s { return s }; return NSNull() }
