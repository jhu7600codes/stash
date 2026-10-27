import SwiftUI

@main
struct StashApp: App {
    @StateObject private var store = Store.shared
    @StateObject private var player = Player.shared
    @State private var splash = true

    var body: some Scene {
        WindowGroup {
            ZStack {
                RootView()
                if splash { Splash().transition(.opacity.combined(with: .scale(scale: 1.08))).zIndex(2) }
            }
            .environmentObject(store)
            .environmentObject(player)
            .preferredColorScheme(.dark)
            .tint(accent)
            .task {
                Task { await Piped.loadMore() }
                try? await Task.sleep(nanoseconds: 650_000_000)
                withAnimation(.easeOut(duration: 0.35)) { splash = false }
            }
        }
    }
}

/// the icon, then the app fades in under it
struct Splash: View {
    @State private var pop = false
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            Image("Logo").resizable().frame(width: 110, height: 110)
                .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
                .scaleEffect(pop ? 1 : 0.85).opacity(pop ? 1 : 0)
        }
        .onAppear { withAnimation(.spring(response: 0.45, dampingFraction: 0.7)) { pop = true } }
    }
}

struct RootView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var player: Player

    var body: some View {
        tabs
            .overlay(alignment: .top) {
                if let t = store.toast {
                    Text(t).font(.subheadline.weight(.medium)).padding(.horizontal, 16).padding(.vertical, 10)
                        .glass(Capsule()).padding(.top, 6)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .fullScreenCover(isPresented: $player.showFull) { PlayerView() }
    }

    private func tab<V: View>(_ i: Int, _ v: V) -> some View {
        NavigationStack(path: $store.paths[i]) {
            v.navigationDestination(for: Route.self) { r in RouteView(route: r) }
        }
    }

    @ViewBuilder var tabs: some View {
        let base = TabView(selection: $store.tab) {
            tab(0, HomeView()).tabItem { Label("Home", systemImage: "house.fill") }.tag(0).miniInset()
            tab(1, SearchView()).tabItem { Label("Search", systemImage: "magnifyingglass") }.tag(1).miniInset()
            tab(2, LibraryView()).tabItem { Label("Library", systemImage: "square.stack.fill") }.tag(2).miniInset()
            tab(3, SettingsView()).tabItem { Label("Settings", systemImage: "gearshape.fill") }.tag(3).miniInset()
        }
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            base.tabViewBottomAccessory { MiniPlayer(compact: true) }
                .tabBarMinimizeBehavior(.onScrollDown)
        } else {
            base.overlay(alignment: .bottom) { if player.current != nil { MiniPlayer().padding(.horizontal, 8).padding(.bottom, 54) } }
        }
        #else
        base.overlay(alignment: .bottom) { if player.current != nil { MiniPlayer().padding(.horizontal, 8).padding(.bottom, 54) } }
        #endif
    }
}

private struct MiniInset: ViewModifier {
    @EnvironmentObject var player: Player
    func body(content: Content) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) { content } else { pad(content) }
        #else
        pad(content)
        #endif
    }
    private func pad(_ c: Content) -> some View {
        c.safeAreaInset(edge: .bottom) { Color.clear.frame(height: player.current == nil ? 0 : 64) }
    }
}
extension View { func miniInset() -> some View { modifier(MiniInset()) } }

/// the bar above the tabs
struct MiniPlayer: View {
    var compact = false
    @EnvironmentObject var player: Player
    @EnvironmentObject var store: Store

    var body: some View {
        let t = player.current
        HStack(spacing: 10) {
            if let t { Art(url: t.artURL, size: compact ? 32 : 40, radius: compact ? 16 : 6) }
            else { Tile(icon: "music.note", colors: [.gray.opacity(0.4), .gray.opacity(0.2)], size: compact ? 32 : 40, radius: compact ? 16 : 6) }
            VStack(alignment: .leading, spacing: 0) {
                Text(t?.title ?? "Not playing").font(.subheadline.weight(.semibold)).lineLimit(1)
                if let t { Text(t.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            }
            Spacer(minLength: 0)
            if let t {
                Button { store.toggleHeart(t) } label: {
                    Image(systemName: store.isHearted(t) ? "heart.fill" : "heart").foregroundStyle(store.isHearted(t) ? accent : .primary)
                }.frame(width: 34, height: 34)
            }
            Button { player.toggle() } label: {
                ZStack {
                    if player.loading { ProgressView().controlSize(.small) }
                    else { Image(systemName: player.playing ? "pause.fill" : "play.fill").font(.title3) }
                }.frame(width: 34, height: 34)
            }
            Button { player.next() } label: { Image(systemName: "forward.fill").frame(width: 30, height: 34) }
        }
        .buttonStyle(.plain)
        .padding(.horizontal, compact ? 12 : 10)
        .padding(.vertical, compact ? 0 : 8)
        .background(alignment: .bottom) {
            if !compact, player.duration > 0 {
                GeometryReader { g in
                    Capsule().fill(accent).frame(width: g.size.width * min(1, player.time / player.duration), height: 2)
                }.frame(height: 2).padding(.horizontal, 12)
            }
        }
        .modifier(MiniGlass(compact: compact))
        .contentShape(Rectangle())
        .onTapGesture { if t != nil { player.showFull = true } }
    }
}
private struct MiniGlass: ViewModifier {
    var compact: Bool
    func body(content: Content) -> some View {
        if compact { content } else { content.glass(RoundedRectangle(cornerRadius: 16, style: .continuous)).shadow(color: .black.opacity(0.3), radius: 12, y: 4) }
    }
}

struct RouteView: View {
    let route: Route
    var body: some View {
        switch route {
        case .playlist(let id): PlaylistView(id: id)
        case .downloads: DownloadsView()
        case .artist(let name): RemoteListView(kind: .artist(name))
        case .similar(let t): RemoteListView(kind: .similar(t))
        case .ytPlaylist(let id, let name): RemoteListView(kind: .yt(id, name))
        case .mix: RemoteListView(kind: .mix)
        }
    }
}
