import SwiftUI

let accent = Color(red: 0.486, green: 0.784, blue: 0.973) // #7CC8F8

extension View {
    /// liquid glass on ios 26, frosted material before
    @ViewBuilder func glass<S: Shape>(_ shape: S, interactive: Bool = false) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            self.glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
        } else {
            self.background(.ultraThinMaterial, in: shape)
        }
        #else
        self.background(.ultraThinMaterial, in: shape)
        #endif
    }

    /// the round glass button style for player controls
    @ViewBuilder func glassButton() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) { self.buttonStyle(.glass) } else { self.buttonStyle(.plain) }
        #else
        self.buttonStyle(.plain)
        #endif
    }
}

/// cover art with a soft placeholder
struct Art: View {
    var url: URL?
    var size: CGFloat? = nil
    var radius: CGFloat = 6
    var body: some View {
        AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.2))) { phase in
            if let img = phase.image { img.resizable().scaledToFill() }
            else { ZStack { Color.white.opacity(0.08); Image(systemName: "music.note").foregroundStyle(.secondary) } }
        }
        .frame(width: size, height: size)
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

/// a gradient square for liked songs, downloads and mixes
struct Tile: View {
    var icon: String
    var colors: [Color]
    var size: CGFloat? = nil
    var radius: CGFloat = 6
    var body: some View {
        LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
            .overlay(Image(systemName: icon).font(.system(size: (size ?? 60) * 0.38, weight: .semibold)).foregroundStyle(.white))
            .frame(width: size, height: size)
            .aspectRatio(1, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}
let likedColors = [Color(red: 0.29, green: 0.17, blue: 0.84), accent]
let dlColors = [Color(red: 0.05, green: 0.48, blue: 0.37), Color(red: 0.37, green: 0.86, blue: 0.79)]

/// one song in a list
struct TrackRow: View {
    let track: Track
    var list: [Track] = []
    var index: Int = 0
    var showArt = true
    @EnvironmentObject var player: Player
    @EnvironmentObject var store: Store

    var body: some View {
        let now = player.current?.id == track.id
        Button {
            if list.isEmpty { player.play([track]) } else { player.play(list, at: index) }
        } label: {
            HStack(spacing: 12) {
                if showArt { Art(url: track.artURL, size: 48) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title).font(.body).lineLimit(1).foregroundStyle(now ? accent : .primary)
                    HStack(spacing: 4) {
                        if store.isDownloaded(track) { Image(systemName: "arrow.down.circle.fill").font(.caption2).foregroundStyle(accent) }
                        if track.provider == "soundcloud" { Text("SoundCloud ·").font(.subheadline).foregroundStyle(.orange) }
                        Text(track.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                if store.downloading.contains(track.id) { ProgressView().controlSize(.small) }
                TrackMenu(track: track) { Image(systemName: "ellipsis").foregroundStyle(.secondary).frame(width: 32, height: 40).contentShape(Rectangle()) }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu { TrackActions(track: track) }
    }
}

/// the "…" menu of a song
struct TrackMenu<L: View>: View {
    let track: Track
    @ViewBuilder var label: () -> L
    var body: some View { Menu { TrackActions(track: track) } label: { label() } }
}

struct TrackActions: View {
    let track: Track
    var inPlaylist: String? = nil
    @EnvironmentObject var player: Player
    @EnvironmentObject var store: Store

    var body: some View {
        Button { store.toggleHeart(track) } label: {
            Label(store.isHearted(track) ? "Remove from \(store.heartTarget.name)" : "Add to \(store.heartTarget.name)",
                  systemImage: store.isHearted(track) ? "heart.fill" : "heart")
        }
        Button { player.playNext(track) } label: { Label("Play next", systemImage: "text.insert") }
        Button { player.addToQueue(track) } label: { Label("Add to queue", systemImage: "text.append") }
        Menu {
            Button { store.add(track, to: store.liked.id) } label: { Label("Liked songs", systemImage: "heart") }
            ForEach(store.playlists) { p in Button(p.name) { store.add(track, to: p.id) } }
            Button { let n = store.create(track.title); store.add(track, to: n) } label: { Label("New playlist", systemImage: "plus") }
        } label: { Label("Add to playlist", systemImage: "music.note.list") }
        if store.isDownloaded(track) {
            Button(role: .destructive) { store.deleteDownload(track) } label: { Label("Remove download", systemImage: "trash") }
        } else {
            Button { store.download([track]) } label: { Label("Download", systemImage: "arrow.down.circle") }
        }
        Button { player.showFull = false; store.go(.artist(track.mainArtist)) } label: { Label("Go to \(track.mainArtist)", systemImage: "person") }
        if track.youtubeId != nil {
            Button { player.showFull = false; store.go(.similar(track)) } label: { Label("Similar songs", systemImage: "sparkles") }
        }
        if let id = inPlaylist {
            Button(role: .destructive) { store.remove(track, from: id) } label: { Label("Remove from this playlist", systemImage: "minus.circle") }
        }
    }
}

extension Store {
    /// opens a page in the current tab (from anywhere, the player too)
    func go(_ r: Route) {
        let t = tab == 3 ? 0 : tab
        tab = t
        paths[t].append(r)
    }
}

/// big square card (made for you, jump back in)
struct Card<Cover: View>: View {
    var title: String
    var subtitle: String
    var width: CGFloat = 150
    @ViewBuilder var cover: () -> Cover
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            cover().frame(width: width, height: width).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
            Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .frame(width: width)
    }
}

/// placeholder rows while loading
struct Skeleton: View {
    var rows = 8
    @State private var on = false
    var body: some View {
        VStack(spacing: 14) {
            ForEach(0..<rows, id: \.self) { _ in
                HStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: 6).frame(width: 48, height: 48)
                    VStack(alignment: .leading, spacing: 6) {
                        RoundedRectangle(cornerRadius: 4).frame(width: 180, height: 12)
                        RoundedRectangle(cornerRadius: 4).frame(width: 110, height: 10)
                    }
                    Spacer()
                }
            }
        }
        .foregroundStyle(.white.opacity(on ? 0.10 : 0.05))
        .padding(.horizontal)
        .onAppear { withAnimation(.easeInOut(duration: 0.8).repeatForever()) { on = true } }
    }
}

struct EmptyState: View {
    var icon: String
    var title: String
    var text: String = ""
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon).font(.system(size: 40)).foregroundStyle(.secondary)
            Text(title).font(.headline)
            if !text.isEmpty { Text(text).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center) }
        }
        .padding(40).frame(maxWidth: .infinity)
    }
}

/// asks for one line of text (new playlist, rename, links)
struct TextPrompt: ViewModifier {
    @Binding var shown: Bool
    var title: String
    var placeholder: String
    @Binding var text: String
    var action: (String) -> Void
    func body(content: Content) -> some View {
        content.alert(title, isPresented: $shown) {
            TextField(placeholder, text: $text)
            Button("Cancel", role: .cancel) {}
            Button("OK") { action(text.trimmingCharacters(in: .whitespacesAndNewlines)) }
        }
    }
}
extension View {
    func askText(_ shown: Binding<Bool>, _ title: String, placeholder: String = "", text: Binding<String>, action: @escaping (String) -> Void) -> some View {
        modifier(TextPrompt(shown: shown, title: title, placeholder: placeholder, text: text, action: action))
    }
}
