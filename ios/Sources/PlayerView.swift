import SwiftUI

/// the full screen player: cover, controls, lyrics, queue
struct PlayerView: View {
    @EnvironmentObject var player: Player
    @EnvironmentObject var store: Store
    @State private var page = 0 // 0 cover, 1 lyrics, 2 queue
    @State private var scrub: Double?
    @State private var lyrics: SongLyrics?
    @State private var lyricsFor = ""
    @State private var lyricsLoading = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let t = player.current
        ZStack {
            background(t)
            VStack(spacing: 0) {
                grabber
                Group {
                    switch page {
                    case 1: LyricsView(lyrics: lyrics, loading: lyricsLoading)
                    case 2: QueueView()
                    default: cover(t)
                    }
                }
                .frame(maxHeight: .infinity)
                if let t { info(t) }
                progress
                controls
                bottomBar
            }
            .padding(.horizontal, 24)
        }
        .gesture(DragGesture().onEnded { v in if v.translation.height > 120 && abs(v.translation.width) < 80 && page == 0 { dismiss() } })
        .task(id: "\(t?.id ?? "")|\(store.prefs.translate)|\(store.prefs.lang)") { await loadLyrics() }
    }

    private func background(_ t: Track?) -> some View {
        ZStack {
            Color.black
            Art(url: t?.artURL, radius: 0).scaledToFill().blur(radius: 60).opacity(0.55).scaleEffect(1.4)
            LinearGradient(colors: [.clear, .black.opacity(0.6)], startPoint: .top, endPoint: .bottom)
        }
        .ignoresSafeArea()
    }

    private var grabber: some View {
        HStack {
            Button { dismiss() } label: { Image(systemName: "chevron.down").font(.title3.weight(.semibold)).frame(width: 44, height: 44) }
                .glassButton()
            Spacer()
            Text(page == 1 ? "Lyrics" : page == 2 ? "Up next" : "Now playing").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
            Spacer()
            if let t = player.current {
                TrackMenu(track: t) { Image(systemName: "ellipsis").font(.title3.weight(.semibold)).frame(width: 44, height: 44) }
            } else { Color.clear.frame(width: 44, height: 44) }
        }
        .foregroundStyle(.white)
        .padding(.top, 8)
    }

    private func cover(_ t: Track?) -> some View {
        Art(url: t?.artURL, radius: 14)
            .shadow(color: .black.opacity(0.5), radius: 30, y: 16)
            .scaleEffect(player.playing ? 1 : 0.88)
            .animation(.spring(response: 0.5, dampingFraction: 0.75), value: player.playing)
            .padding(.vertical, 20)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func info(_ t: Track) -> some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 3) {
                Text(t.title).font(.title3.weight(.bold)).lineLimit(1)
                Button { player.showFull = false; store.go(.artist(t.mainArtist)) } label: {
                    Text(t.artist).font(.body).foregroundStyle(.secondary).lineLimit(1)
                }.buttonStyle(.plain)
            }
            Spacer()
            Button { store.toggleHeart(t) } label: {
                Image(systemName: store.isHearted(t) ? "heart.fill" : "heart").font(.title2)
                    .foregroundStyle(store.isHearted(t) ? accent : .white)
                    .frame(width: 44, height: 44)
            }.buttonStyle(.plain)
        }
        .padding(.top, 12)
    }

    private var progress: some View {
        let dur = max(player.duration, 1)
        let shown = scrub ?? player.time
        return VStack(spacing: 4) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.2))
                    Capsule().fill(.white).frame(width: g.size.width * min(1, max(0, shown / dur)))
                }
                .frame(height: scrub == nil ? 5 : 9)
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { v in scrub = min(1, max(0, v.location.x / g.size.width)) * dur }
                    .onEnded { _ in if let s = scrub { player.seek(s) }; scrub = nil })
                .animation(.easeOut(duration: 0.15), value: scrub == nil)
            }
            .frame(height: 22)
            HStack {
                Text(fmt(shown)); Spacer()
                if player.error != nil { Text(player.error!).foregroundStyle(.orange) }
                Spacer(); Text("-" + fmt(max(0, dur - shown)))
            }
            .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
        }
        .padding(.top, 6)
    }

    private var controls: some View {
        HStack {
            Button { player.toggleShuffle() } label: { Image(systemName: "shuffle").foregroundStyle(player.shuffle ? accent : .white) }
                .frame(width: 44, height: 44)
            Spacer()
            Button { player.previous() } label: { Image(systemName: "backward.fill").font(.title) }.frame(width: 60, height: 60)
            Spacer()
            Button { player.toggle() } label: {
                ZStack {
                    if player.loading { ProgressView().tint(.white).scaleEffect(1.3) }
                    else { Image(systemName: player.playing ? "pause.fill" : "play.fill").font(.system(size: 34)) }
                }
                .frame(width: 76, height: 76)
                .glass(Circle(), interactive: true)
            }
            Spacer()
            Button { player.next() } label: { Image(systemName: "forward.fill").font(.title) }.frame(width: 60, height: 60)
            Spacer()
            Button { player.cycleRepeat() } label: {
                Image(systemName: player.repeatMode == 2 ? "repeat.1" : "repeat").foregroundStyle(player.repeatMode > 0 ? accent : .white)
            }.frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .padding(.vertical, 10)
    }

    private var bottomBar: some View {
        HStack(spacing: 0) {
            tabButton(1, "quote.bubble")
            Spacer()
            tabButton(0, "music.note")
            Spacer()
            tabButton(2, "list.bullet")
        }
        .padding(.horizontal, 30)
        .padding(.vertical, 6)
        .glass(Capsule())
        .padding(.bottom, 8)
    }

    private func tabButton(_ i: Int, _ icon: String) -> some View {
        Button { withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { page = page == i ? 0 : i } } label: {
            Image(systemName: icon).font(.title3).foregroundStyle(page == i ? accent : .white).frame(width: 52, height: 40)
        }.buttonStyle(.plain)
    }

    private func loadLyrics() async {
        guard let t = player.current else { lyrics = nil; return }
        let key = "\(t.id)|\(store.prefs.translate)|\(store.prefs.lang)"
        if lyricsFor == key { return }
        lyrics = nil; lyricsLoading = true
        let lang = store.prefs.translate ? (store.prefs.lang.isEmpty ? (Locale.current.languageCode ?? "en") : store.prefs.lang) : nil
        let l = await StashAPI.lyrics(for: t, translateTo: lang)
        if Task.isCancelled { return }
        lyrics = l; lyricsFor = key; lyricsLoading = false
    }
}

/// karaoke: the line being sung in the middle, its words lighting up letter by letter
struct LyricsView: View {
    let lyrics: SongLyrics?
    let loading: Bool
    @EnvironmentObject var player: Player
    @State private var active = -1

    var body: some View {
        if loading { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
        else if let l = lyrics, !l.lines.isEmpty {
            ScrollViewReader { proxy in
                ScrollView(showsIndicators: false) {
                    TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !player.playing || !l.synced)) { _ in
                        let now = player.exactTime
                        VStack(alignment: .leading, spacing: 22) {
                            Color.clear.frame(height: 120)
                            ForEach(l.lines.indices, id: \.self) { i in
                                line(l, i, now).id(i)
                                    .onTapGesture { if l.synced { player.seek(l.lines[i].ms) } }
                            }
                            Color.clear.frame(height: 220)
                        }
                        .onChange(of: current(l, now)) { a in active = a }
                    }
                }
                .onChange(of: active) { a in
                    guard a >= 0 else { return }
                    withAnimation(.spring(response: 0.5, dampingFraction: 0.9)) { proxy.scrollTo(a, anchor: UnitPoint(x: 0.5, y: 0.35)) }
                }
                .onAppear { if active >= 0 { proxy.scrollTo(active, anchor: UnitPoint(x: 0.5, y: 0.35)) } }
            }
            .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.1), .init(color: .black, location: 0.85), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
        } else {
            EmptyState(icon: "quote.bubble", title: "No lyrics for this song").frame(maxHeight: .infinity)
        }
    }

    private func current(_ l: SongLyrics, _ now: Double) -> Int {
        guard l.synced else { return -1 }
        return (l.lines.lastIndex { $0.ms <= now + 150 }) ?? -1
    }

    @ViewBuilder private func line(_ l: SongLyrics, _ i: Int, _ now: Double) -> some View {
        let a = current(l, now)
        let start = l.lines[i].ms
        let end = i + 1 < l.lines.count ? l.lines[i + 1].ms : start + 5000
        VStack(alignment: .leading, spacing: 4) {
            Group {
                if !l.synced { Text(l.lines[i].text).foregroundColor(.white) }
                else if i < a { Text(l.lines[i].text).foregroundColor(.white.opacity(0.55)) }
                else if i > a { Text(l.lines[i].text).foregroundColor(.white.opacity(0.3)) }
                else { karaoke(l, i, now, start, end) }
            }
            .font(.system(size: 28, weight: .bold))
            .fixedSize(horizontal: false, vertical: true)
            if let tr = l.translated, i < tr.count, let s = tr[i], !s.isEmpty, s != l.lines[i].text {
                Text(s).font(.system(size: 18, weight: .semibold)).foregroundColor(.white.opacity(i == a ? 0.75 : 0.3))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .scaleEffect(i == a ? 1 : 0.97, anchor: .leading)
        .animation(.easeOut(duration: 0.25), value: a)
    }

    /// sung letters white, the rest dim. with word timing when there is some, else spread over the line
    private func karaoke(_ l: SongLyrics, _ i: Int, _ now: Double, _ start: Double, _ end: Double) -> Text {
        var pieces: [(ms: Double, text: String)]
        if let w = l.words, i < w.count, !w[i].isEmpty { pieces = w[i] }
        else {
            // no word timing: words spread over the line, longer words take longer
            let words = l.lines[i].text.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
            let total = Double(max(1, words.reduce(0) { $0 + $1.count + 1 }))
            let span = min(end - start, Double(words.count) * 700) // sung lines rarely drag
            var at = start
            pieces = []
            for (k, w) in words.enumerated() {
                pieces.append((ms: at, text: w + (k < words.count - 1 ? " " : "")))
                at += span * Double(w.count + 1) / total
            }
        }
        var out = Text("")
        for (k, p) in pieces.enumerated() {
            let pEnd = k + 1 < pieces.count ? pieces[k + 1].ms : min(end, p.ms + 1500)
            if now >= pEnd { out = out + Text(p.text).foregroundColor(.white) }
            else if now <= p.ms { out = out + Text(p.text).foregroundColor(.white.opacity(0.35)) }
            else {
                let f = (now - p.ms) / max(1, pEnd - p.ms)
                let chars = Array(p.text)
                let n = Int((Double(chars.count) * f).rounded(.down))
                out = out + Text(String(chars[..<n])).foregroundColor(.white) + Text(String(chars[n...])).foregroundColor(.white.opacity(0.35))
            }
        }
        return out
    }
}

struct QueueView: View {
    @EnvironmentObject var player: Player
    var body: some View {
        List {
            if let t = player.current {
                Section("Now playing") { row(t, playing: true) }
            }
            let rest = player.index + 1 < player.queue.count ? Array(player.queue[(player.index + 1)...]) : []
            Section("Next in queue") {
                if rest.isEmpty { Text("Nothing queued").foregroundStyle(.secondary) }
                ForEach(Array(rest.enumerated()), id: \.offset) { k, t in
                    Button { player.jump(player.index + 1 + k) } label: { row(t) }.buttonStyle(.plain)
                }
                .onDelete { player.removeFromQueue($0) }
                .onMove { player.moveInQueue($0, $1) }
            }
            if !player.upNext.isEmpty {
                Section("Recommended") {
                    ForEach(player.upNext) { t in
                        HStack {
                            row(t)
                            Button { player.addToQueue(t) } label: { Image(systemName: "plus.circle").font(.title3) }.buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .tint(accent)
    }

    private func row(_ t: Track, playing: Bool = false) -> some View {
        HStack(spacing: 12) {
            Art(url: t.artURL, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(t.title).lineLimit(1).foregroundStyle(playing ? accent : .white)
                Text(t.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .listRowBackground(Color.clear)
    }
}
