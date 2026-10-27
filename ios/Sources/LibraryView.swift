import SwiftUI

struct LibraryView: View {
    @EnvironmentObject var store: Store
    @State private var newShown = false
    @State private var importShown = false
    @State private var pairShown = false
    @State private var text = ""
    @State private var renaming: Playlist?
    @State private var renameShown = false
    @State private var coverShown = false

    var body: some View {
        List {
            NavigationLink(value: Route.playlist(store.liked.id)) {
                row(store.liked.name, "Playlist · \(store.liked.visible.count) songs") { Tile(icon: "heart.fill", colors: likedColors, size: 56) }
            }
            NavigationLink(value: Route.downloads) {
                row("Downloads", "\(store.downloads.count) songs · offline") { Tile(icon: "arrow.down", colors: dlColors, size: 56) }
            }
            ForEach(store.playlists) { p in
                NavigationLink(value: Route.playlist(p.id)) {
                    row(p.name, (store.prefs.pinned.contains(p.name) ? "Pinned · " : "") + "Playlist · \(p.visible.count) songs" + (p.origin.map { $0 != "local" ? " · from \($0)" : "" } ?? "")) {
                        Art(url: p.coverURL, size: 56)
                    }
                }
                .contextMenu { menu(p) }
                .swipeActions(edge: .leading) {
                    Button { store.togglePin(p.name) } label: { Image(systemName: store.prefs.pinned.contains(p.name) ? "pin.slash" : "pin") }.tint(accent)
                }
                .swipeActions {
                    Button(role: .destructive) { store.delete(p.id) } label: { Image(systemName: "trash") }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Library")
        .refreshable { await store.pull() }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button { text = ""; newShown = true } label: { Label("Create a playlist", systemImage: "plus") }
                    Button { text = ""; importShown = true } label: { Label("Move playlists from Spotify", systemImage: "arrow.down.doc") }
                    Button { text = ""; pairShown = true } label: { Label("Pair new device", systemImage: "tv") }
                } label: { Image(systemName: "plus") }
            }
        }
        .askText($newShown, "New playlist", placeholder: "Name", text: $text) { n in let name = store.create(n); store.go(.playlist(name)) }
        .askText($importShown, "Spotify playlist or album link", placeholder: "https://open.spotify.com/…", text: $text) { l in
            guard !l.isEmpty else { return }
            Task { await store.importSpotify(l) }
        }
        .askText($pairShown, "Code on the TV", placeholder: "ABC123", text: $text) { c in
            Task { do { try await store.pair(c); store.say("TV paired") } catch { store.say(error.localizedDescription) } }
        }
        .askText($renameShown, "Rename", placeholder: "Name", text: $text) { n in if let r = renaming { store.rename(r.id, to: n) } }
        .askText($coverShown, "Cover image link", placeholder: "Empty = first song's cover", text: $text) { u in if let r = renaming { store.setCover(r.id, u.isEmpty ? nil : u) } }
    }

    @ViewBuilder private func menu(_ p: Playlist) -> some View {
        Button { store.togglePin(p.name) } label: { Label(store.prefs.pinned.contains(p.name) ? "Unpin" : "Pin to the top", systemImage: "pin") }
        Button { renaming = p; text = p.name; renameShown = true } label: { Label("Rename", systemImage: "pencil") }
        Button { renaming = p; text = p.cover_url ?? ""; coverShown = true } label: { Label("Change cover", systemImage: "photo") }
        Button { store.download(p.visible) } label: { Label("Download all", systemImage: "arrow.down.circle") }
        Button(role: .destructive) { store.delete(p.id) } label: { Label("Delete", systemImage: "trash") }
    }

    private func row<C: View>(_ title: String, _ sub: String, @ViewBuilder cover: () -> C) -> some View {
        HStack(spacing: 12) {
            cover()
            VStack(alignment: .leading, spacing: 2) {
                Text(title).lineLimit(1)
                Text(sub).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
        }
    }
}

/// big header with cover, name, play and shuffle (liked songs, playlists, mixes)
struct ListHeader<Cover: View>: View {
    var title: String
    var subtitle: String
    var tracks: [Track]
    @ViewBuilder var cover: () -> Cover
    @EnvironmentObject var player: Player

    var body: some View {
        VStack(spacing: 14) {
            cover().frame(width: 220, height: 220).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .shadow(color: .black.opacity(0.5), radius: 20, y: 10)
            VStack(spacing: 4) {
                Text(title).font(.title2.weight(.bold)).multilineTextAlignment(.center)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
            }
            HStack(spacing: 12) {
                Button { player.shuffle = false; player.play(tracks) } label: {
                    Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity).padding(.vertical, 12)
                        .background(accent, in: Capsule()).foregroundStyle(.black)
                }
                Button { player.shuffle = true; player.play(tracks.shuffled()) } label: {
                    Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity).padding(.vertical, 12).glass(Capsule())
                }
            }
            .font(.headline).buttonStyle(.plain).disabled(tracks.isEmpty)
        }
        .padding(.horizontal).padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .listRowBackground(Color.clear).listRowSeparator(.hidden)
    }
}

struct PlaylistView: View {
    let id: String
    @EnvironmentObject var store: Store
    @State var filter = ""

    var body: some View {
        if let p = store.playlist(id) {
            let all = p.visible
            let shown = filter.isEmpty ? all : all.filter { ($0.title + " " + $0.artist).localizedCaseInsensitiveContains(filter) }
            let missing = p.tracks.count - all.count
            List {
                ListHeader(title: p.name, subtitle: "\(all.count) songs" + (missing > 0 ? " · \(missing) not found" : ""), tracks: all) {
                    if p.kind == "liked" { Tile(icon: "heart.fill", colors: likedColors, radius: 12) } else { Art(url: p.coverURL, radius: 12) }
                }
                if all.isEmpty { EmptyState(icon: "music.note.list", title: "No songs yet", text: "Add songs from search with the … menu").listRowBackground(Color.clear) }
                ForEach(Array(shown.enumerated()), id: \.element.id) { i, t in
                    TrackRow(track: t, list: shown, index: i)
                        .contextMenu { TrackActions(track: t, inPlaylist: p.id) }
                        .swipeActions { Button(role: .destructive) { store.remove(t, from: p.id) } label: { Image(systemName: "trash") } }
                }
                .onMove(perform: filter.isEmpty ? { store.move(in: p.id, from: $0, to: $1) } : nil)
            }
            .listStyle(.plain)
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $filter, prompt: "Find in playlist")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button { store.download(all) } label: { Label("Download all", systemImage: "arrow.down.circle") }
                        if p.kind != "liked" {
                            Button { store.togglePin(p.name) } label: { Label(store.prefs.pinned.contains(p.name) ? "Unpin" : "Pin to the top", systemImage: "pin") }
                        }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
        } else {
            EmptyState(icon: "questionmark.folder", title: "This playlist is gone")
        }
    }
}

struct DownloadsView: View {
    @EnvironmentObject var store: Store
    var body: some View {
        let list = store.downloadedTracks
        List {
            ListHeader(title: "Downloads", subtitle: "\(list.count) songs · play with no internet", tracks: list) { Tile(icon: "arrow.down", colors: dlColors, radius: 12) }
            if list.isEmpty { EmptyState(icon: "arrow.down.circle", title: "Nothing downloaded", text: "Use Download in a song's … menu, or Download all on a playlist").listRowBackground(Color.clear) }
            ForEach(Array(list.enumerated()), id: \.element.id) { i, t in
                TrackRow(track: t, list: list, index: i)
                    .swipeActions { Button(role: .destructive) { store.deleteDownload(t) } label: { Image(systemName: "trash") } }
            }
        }
        .listStyle(.plain)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// lists that come from the internet: artist ("this is"), similar songs, youtube playlists, your mix
struct RemoteListView: View {
    enum Kind { case artist(String), similar(Track), yt(String, String), mix }
    let kind: Kind
    @EnvironmentObject var store: Store
    @State var tracks: [Track] = []
    @State var cover: String?
    @State var loading = true

    private var title: String {
        switch kind {
        case .artist(let a): return a
        case .similar(let t): return "Like " + StashAPI.songName(t.title)
        case .yt(_, let n): return n
        case .mix: return "Your mix"
        }
    }

    var body: some View {
        List {
            ListHeader(title: title, subtitle: loading ? "Loading…" : "\(tracks.count) songs", tracks: tracks) {
                if let c = cover ?? tracks.first?.art { Art(url: URL(string: c), radius: 12) } else { Tile(icon: "sparkles", colors: likedColors, radius: 12) }
            }
            if loading { Skeleton().listRowBackground(Color.clear).listRowSeparator(.hidden).listRowInsets(EdgeInsets()) }
            else if tracks.isEmpty { EmptyState(icon: "wifi.exclamationmark", title: "Couldn't load this").listRowBackground(Color.clear) }
            ForEach(Array(tracks.enumerated()), id: \.element.id) { i, t in TrackRow(track: t, list: tracks, index: i) }
        }
        .listStyle(.plain)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("Save") {
                    let o: String
                    var oid: String? = nil
                    if case .yt(let id, _) = kind { o = "youtube"; oid = id } else { o = "local" }
                    let n = store.create(title, tracks: tracks, cover: cover, origin: o, originId: oid)
                    store.say("Saved as \(n)")
                }.disabled(tracks.isEmpty)
            }
        }
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        loading = true; defer { loading = false }
        switch kind {
        case .artist(let a):
            // their songs first, then songs that sound like them
            let r = (try? await Piped.search(a)) ?? []
            let own = r.filter { $0.artist.localizedCaseInsensitiveContains(a) }
            tracks = own.isEmpty ? r : own
            if let s = own.first?.youtubeId, let rel = try? await Piped.related(s) {
                let seen = Set(tracks.map(\.id))
                tracks += rel.filter { !seen.contains($0.id) }.prefix(10)
            }
        case .similar(let t):
            if let v = t.youtubeId { tracks = (try? await Piped.related(v)) ?? [] }
        case .yt(let id, _):
            if let p = try? await Piped.playlist(id) { tracks = p.tracks; cover = p.cover }
        case .mix:
            let seeds = Array(store.recent.prefix(3)) + Array(store.liked.visible.prefix(2))
            var lists: [[Track]] = []
            await withTaskGroup(of: [Track].self) { g in
                for s in seeds { if let v = s.youtubeId { g.addTask { (try? await Piped.related(v)) ?? [] } } }
                for await l in g { lists.append(l) }
            }
            var seen = Set(seeds.map(\.id)), out: [Track] = []
            for k in 0..<12 { for l in lists where k < l.count && !seen.contains(l[k].id) { seen.insert(l[k].id); out.append(l[k]) } }
            tracks = out
        }
    }
}
