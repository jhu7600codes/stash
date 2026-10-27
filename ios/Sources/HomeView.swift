import SwiftUI

private let mixColors: [Color] = [.init(red: 0.91, green: 0.07, blue: 0.36), .init(red: 0.12, green: 0.2, blue: 0.39), .init(red: 0.55, green: 0.4, blue: 0.67),
                                  .init(red: 0.08, green: 0.54, blue: 0.03), .init(red: 0.73, green: 0.36, blue: 0.03), .init(red: 0.28, green: 0.49, blue: 0.58)]

struct HomeView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var player: Player

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        return h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                quick
                if store.session == nil {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Your playlists, here too").font(.headline)
                        Text("Log in with your Stash account in Settings to get the playlists and liked songs from your other devices.")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }.padding(.horizontal)
                }
                madeForYou
                if !store.recent.isEmpty {
                    section("Jump back in") {
                        ForEach(Array(store.recent.prefix(15).enumerated()), id: \.element.id) { i, t in
                            Button { player.play(Array(store.recent.prefix(15)), at: i) } label: {
                                Card(title: t.title, subtitle: t.artist) { Art(url: t.artURL, radius: 10) }
                            }.buttonStyle(.plain).contextMenu { TrackActions(track: t) }
                        }
                    }
                }
                if store.playlists.count > 6 {
                    section("Your playlists") {
                        ForEach(store.playlists) { p in
                            NavigationLink(value: Route.playlist(p.id)) {
                                Card(title: p.name, subtitle: "\(p.visible.count) songs") { Art(url: p.coverURL, radius: 10) }
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(.vertical)
        }
        .navigationTitle(greeting)
        .refreshable { await store.pull() }
    }

    private var quick: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
            NavigationLink(value: Route.playlist(store.liked.id)) { quickTile("Liked songs") { Tile(icon: "heart.fill", colors: likedColors, size: 56, radius: 0) } }
            NavigationLink(value: Route.downloads) { quickTile("Downloads") { Tile(icon: "arrow.down", colors: dlColors, size: 56, radius: 0) } }
            ForEach(store.playlists.prefix(6)) { p in
                NavigationLink(value: Route.playlist(p.id)) { quickTile(p.name) { Art(url: p.coverURL, size: 56, radius: 0) } }
            }
        }
        .buttonStyle(.plain)
        .padding(.horizontal)
    }

    private func quickTile<C: View>(_ name: String, @ViewBuilder cover: () -> C) -> some View {
        HStack(spacing: 10) {
            cover()
            Text(name).font(.subheadline.weight(.semibold)).lineLimit(2)
            Spacer(minLength: 0)
        }
        .background(Color.white.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    @ViewBuilder private var madeForYou: some View {
        let arts = store.topArtists
        if !arts.isEmpty {
            section("Made for you") {
                NavigationLink(value: Route.mix) {
                    Card(title: "Your mix", subtitle: "Songs like what you play") { mixCover("Your mix", top: "Stash", LinearGradient(colors: [accent, Color(red: 0.29, green: 0.17, blue: 0.84)], startPoint: .topLeading, endPoint: .bottomTrailing)) }
                }.buttonStyle(.plain)
                ForEach(Array(arts.enumerated()), id: \.element) { i, a in
                    NavigationLink(value: Route.artist(a)) {
                        Card(title: "This is \(a)", subtitle: "\(a) and more") { mixCover(a, top: "This is", mixColors[i % mixColors.count]) }
                    }.buttonStyle(.plain)
                }
            }
        }
    }

    private func mixCover<B: View>(_ title: String, top: String, _ bg: B) -> some View {
        ZStack(alignment: .bottomLeading) {
            bg
            VStack(alignment: .leading, spacing: 2) {
                Text(top).font(.caption.weight(.semibold)).opacity(0.85)
                Text(title).font(.title3.weight(.bold)).lineLimit(2).minimumScaleFactor(0.7)
            }.foregroundStyle(.white).padding(12)
        }
    }

    private func section<C: View>(_ title: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.title2.weight(.bold)).padding(.horizontal)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 14) { content() }.padding(.horizontal)
            }
        }
    }
}
