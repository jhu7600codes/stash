import SwiftUI

struct SearchView: View {
    @EnvironmentObject var store: Store
    @State private var query = ""
    @State private var mode = 0 // songs, playlists, soundcloud
    @State private var songs: [Track] = []
    @State private var lists: [Piped.RemotePlaylist] = []
    @State private var sc: [Track] = []
    @State private var loading = false
    @State private var err: String?
    @State private var task: Task<Void, Never>?

    var body: some View {
        List {
            if !query.isEmpty && !query.hasPrefix("#") {
                Picker("", selection: $mode) {
                    Text("Songs").tag(0); Text("Playlists").tag(1); Text("SoundCloud").tag(2)
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear).listRowSeparator(.hidden)
            }
            if loading { Skeleton().listRowBackground(Color.clear).listRowSeparator(.hidden).listRowInsets(EdgeInsets()) }
            else if let err { EmptyState(icon: "wifi.exclamationmark", title: err, text: "Check the internet and try again").listRowBackground(Color.clear) }
            else if query.isEmpty { hints }
            else if mode == 1 && !query.hasPrefix("#") {
                ForEach(lists) { p in
                    NavigationLink(value: Route.ytPlaylist(p.id, p.name)) {
                        HStack(spacing: 12) {
                            Art(url: p.art.flatMap(URL.init(string:)), size: 48)
                            VStack(alignment: .leading) { Text(p.name).lineLimit(1); Text("Playlist · " + p.owner).font(.subheadline).foregroundStyle(.secondary).lineLimit(1) }
                        }
                    }
                }
            } else {
                let list = mode == 2 && !query.hasPrefix("#") ? sc : songs
                if list.isEmpty { EmptyState(icon: "magnifyingglass", title: "Nothing found").listRowBackground(Color.clear) }
                ForEach(Array(list.enumerated()), id: \.element.id) { i, t in TrackRow(track: t, list: list, index: i) }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Search")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Songs, artists, #miku")
        .onSubmit(of: .search) { run() }
        .onChange(of: query) { _ in run(debounce: true) }
        .onChange(of: mode) { _ in run() }
    }

    private var hints: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Try").font(.headline)
            ForEach(["#miku", "#teto", "#vocarock", "Sigur Rós", "Kanye West"], id: \.self) { s in
                Button(s) { query = s; run() }.foregroundStyle(accent)
            }
            Text("A # searches VocaDB tags and voicebanks.").font(.footnote).foregroundStyle(.secondary).padding(.top, 6)
        }
        .listRowBackground(Color.clear).listRowSeparator(.hidden)
    }

    private func run(debounce: Bool = false) {
        task?.cancel()
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { songs = []; lists = []; sc = []; loading = false; err = nil; return }
        let m = mode
        task = Task {
            if debounce { try? await Task.sleep(nanoseconds: 450_000_000) }
            if Task.isCancelled { return }
            loading = true; err = nil
            defer { if !Task.isCancelled { loading = false } }
            if q.hasPrefix("#") {
                let r = await StashAPI.vocadb(tag: String(q.dropFirst()))
                if !Task.isCancelled { songs = r }
                return
            }
            switch m {
            case 1: let r = await Piped.searchPlaylists(q); if !Task.isCancelled { lists = r }
            case 2: let r = await StashAPI.soundcloud(q); if !Task.isCancelled { sc = r }
            default:
                do { let r = try await Piped.search(q); if !Task.isCancelled { songs = r } }
                catch { if !Task.isCancelled { err = error.localizedDescription } }
            }
        }
    }
}
