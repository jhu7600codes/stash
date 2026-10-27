import AVFoundation
import MediaPlayer
import SwiftUI
import UIKit

/// plays the queue: AVPlayer + lock screen controls + background audio
@MainActor
final class Player: ObservableObject {
    static let shared = Player()

    @Published var queue: [Track] = []
    @Published var index = 0
    @Published var playing = false
    @Published var loading = false
    @Published var time: Double = 0 // ms
    @Published var duration: Double = 0 // ms
    @Published var shuffle = false
    @Published var repeatMode = 0 // 0 off, 1 all, 2 one
    @Published var showFull = false
    @Published var error: String?
    @Published var upNext: [Track] = [] // recommendations after the queue

    private let av = AVPlayer()
    private var timeObs: Any?
    private var endObs: NSObjectProtocol?
    private var statusObs: NSKeyValueObservation?
    private var loadTask: Task<Void, Never>?
    private var artwork: (String, MPMediaItemArtwork)?

    var current: Track? { queue.indices.contains(index) ? queue[index] : nil }
    /// the exact position right now (ms), for karaoke
    var exactTime: Double { let s = av.currentTime().seconds; return s.isFinite ? s * 1000 : time }

    init() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        av.automaticallyWaitsToMinimizeStalling = false
        timeObs = av.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 4), queue: .main) { [weak self] t in
            Task { @MainActor in
                guard let self else { return }
                self.time = t.seconds * 1000
                if let d = self.av.currentItem?.duration.seconds, d.isFinite, d > 0 { self.duration = d * 1000 }
                self.playing = self.av.rate > 0
            }
        }
        remote()
    }

    // MARK: queue

    func play(_ list: [Track], at i: Int = 0) {
        let list = list.filter { !$0.isMissing }
        guard !list.isEmpty else { return }
        var start = min(max(i, 0), list.count - 1)
        if let t = list.indices.contains(i) ? list[i] : nil, let k = list.firstIndex(of: t) { start = k }
        queue = list; index = start; upNext = []
        if shuffle { shuffleRest() }
        load()
    }
    func playNext(_ t: Track) {
        if queue.isEmpty { return play([t]) }
        queue.insert(t, at: index + 1); Store.shared.say("Plays next")
    }
    func addToQueue(_ t: Track) {
        if queue.isEmpty { return play([t]) }
        queue.append(t); Store.shared.say("Added to the queue")
    }
    func jump(_ i: Int) { guard queue.indices.contains(i) else { return }; index = i; load() }
    func removeFromQueue(_ offsets: IndexSet) {
        // offsets are relative to the songs after the current one
        let real = IndexSet(offsets.map { $0 + index + 1 })
        queue.remove(atOffsets: real)
    }
    func moveInQueue(_ from: IndexSet, _ to: Int) {
        var rest = Array(queue[(index + 1)...])
        rest.move(fromOffsets: from, toOffset: to)
        queue = Array(queue[...index]) + rest
    }
    func toggleShuffle() {
        shuffle.toggle()
        if shuffle { shuffleRest() }
    }
    private func shuffleRest() {
        guard index + 1 < queue.count else { return }
        queue = Array(queue[...index]) + queue[(index + 1)...].shuffled()
    }
    func cycleRepeat() { repeatMode = (repeatMode + 1) % 3 }

    func next(auto: Bool = false) {
        if auto && repeatMode == 2 { seek(0); av.play(); return }
        if index + 1 < queue.count { index += 1; load() }
        else if repeatMode == 1, !queue.isEmpty { index = 0; load() }
        else if let t = upNext.first { // keep going with songs like it
            queue.append(contentsOf: upNext); upNext = []; index += 1; load(); _ = t
        } else { av.pause(); seek(0) }
    }
    func previous() {
        if time > 3000 || index == 0 { seek(0) } else { index -= 1; load() }
    }
    func toggle() {
        if current == nil { return }
        if av.currentItem == nil { load(); return }
        if av.rate > 0 { av.pause() } else { av.play() }
        playing = av.rate > 0
        nowPlaying()
    }
    func seek(_ ms: Double) {
        time = ms
        av.seek(to: CMTime(seconds: ms / 1000, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        nowPlaying()
    }

    // MARK: loading

    private func load() {
        guard let t = current else { return }
        loadTask?.cancel()
        av.pause()
        time = 0; duration = t.duration; loading = true; error = nil
        Store.shared.played(t)
        nowPlaying()
        loadTask = Task {
            do {
                let url = try await resolve(t)
                if Task.isCancelled { return }
                start(url)
            } catch {
                if Task.isCancelled { return }
                loading = false
                self.error = "Song can't be loaded"
                Store.shared.say("Couldn't play \(t.title), skipping")
                try? await Task.sleep(nanoseconds: 1_200_000_000)
                if !Task.isCancelled, current?.id == t.id, index + 1 < queue.count { next() }
            }
        }
        if upNext.isEmpty || index + 1 >= queue.count { Task { await recommend() } }
    }

    private func resolve(_ t: Track) async throws -> URL {
        if let f = Store.shared.localFile(t) { return f }
        if let v = t.youtubeId { return try await Piped.audio(v) }
        if t.provider == "soundcloud" { return try await StashAPI.soundcloudStream(t.source) }
        // something the app knows by name only: find it on youtube
        let hit = try await Piped.search("\(t.mainArtist) \(StashAPI.songName(t.title))").first
        guard let v = hit?.youtubeId else { throw NetError.message("Not found") }
        return try await Piped.audio(v)
    }

    private func start(_ url: URL) {
        let item = AVPlayerItem(url: url)
        item.preferredForwardBufferDuration = 8
        statusObs = item.observe(\.status) { [weak self] it, _ in
            Task { @MainActor in
                guard let self else { return }
                if it.status == .readyToPlay { self.loading = false }
                if it.status == .failed { self.loading = false; self.error = "Song can't be loaded"; Store.shared.say("Playback failed") }
            }
        }
        if let endObs { NotificationCenter.default.removeObserver(endObs) }
        endObs = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.next(auto: true) }
        }
        try? AVAudioSession.sharedInstance().setActive(true)
        av.replaceCurrentItem(with: item)
        av.play()
        playing = true
        nowPlaying()
    }

    /// songs like the last one, for when the queue runs out and for "up next"
    private func recommend() async {
        guard let t = queue.last, let v = t.youtubeId else { return }
        let seen = Set(queue.map(\.id))
        let r = (try? await Piped.related(v)) ?? []
        upNext = Array(r.filter { !seen.contains($0.id) }.prefix(15))
    }

    // MARK: lock screen

    private func remote() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in Task { @MainActor in self?.av.play(); self?.nowPlaying() }; return .success }
        c.pauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.av.pause(); self?.nowPlaying() }; return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.toggle() }; return .success }
        c.nextTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.next() }; return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.previous() }; return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            if let e = e as? MPChangePlaybackPositionCommandEvent { Task { @MainActor in self?.seek(e.positionTime * 1000) } }
            return .success
        }
    }

    private func nowPlaying() {
        guard let t = current else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: t.title,
            MPMediaItemPropertyArtist: t.artist,
            MPMediaItemPropertyPlaybackDuration: duration / 1000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: time / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: av.rate,
        ]
        if let a = artwork, a.0 == t.id { info[MPMediaItemPropertyArtwork] = a.1 }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        if artwork?.0 != t.id, let u = t.artURL {
            let id = t.id
            Task {
                guard let r = try? await URLSession.shared.data(from: u), let img = UIImage(data: r.0) else { return }
                let art = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
                artwork = (id, art)
                if current?.id == id { nowPlaying() }
            }
        }
    }
}
