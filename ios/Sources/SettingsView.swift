import SwiftUI

private let langs: [(String, String)] = [("", "Phone's language"), ("en", "English"), ("ru", "Russian"), ("ja", "Japanese"), ("uk", "Ukrainian"), ("de", "German"), ("es", "Spanish")]

struct SettingsView: View {
    @EnvironmentObject var store: Store
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var devices: Int?
    @State private var pairShown = false
    @State private var code = ""

    var body: some View {
        Form {
            Section {
                if let s = store.session {
                    HStack {
                        Image(systemName: "person.crop.circle.fill").font(.largeTitle).foregroundStyle(accent)
                        VStack(alignment: .leading) {
                            Text(s.email).font(.headline)
                            Text(store.syncing ? "Syncing…" : "Playlists and likes sync").font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    if let devices { LabeledContent("Signed in on", value: "\(devices) device\(devices == 1 ? "" : "s")") }
                    Button("Sync now") { Task { await store.pull(); store.say("Synced") } }
                    Button("Pair new device") { code = ""; pairShown = true }
                    Button("Log out everywhere else") { Task { await store.logoutOthers(); devices = await store.sessionCount() } }
                    Button("Log out", role: .destructive) { store.logout() }
                } else {
                    TextField("Email", text: $email).textContentType(.emailAddress).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Password", text: $password).textContentType(.password)
                    HStack {
                        Button("Log in") { auth(signup: false) }.buttonStyle(.borderedProminent)
                        Spacer()
                        Button("Sign up") { auth(signup: true) }.buttonStyle(.bordered)
                    }
                    .disabled(busy || email.isEmpty || password.count < 6)
                }
            } header: { Text("Stash account") } footer: {
                if store.session == nil { Text("The same account as the Android app and the website: your playlists and likes everywhere.") }
            }

            Section("Lyrics") {
                Toggle("Show translation", isOn: Binding(get: { store.prefs.translate }, set: { store.prefs.translate = $0; store.savePrefs() }))
                Picker("Translate to", selection: Binding(get: { store.prefs.lang }, set: { store.prefs.lang = $0; store.savePrefs() })) {
                    ForEach(langs, id: \.0) { Text($0.1).tag($0.0) }
                }
            }

            Section {
                Picker("The heart adds songs to", selection: Binding(get: { store.prefs.heart ?? "" }, set: { store.prefs.heart = $0.isEmpty ? nil : $0; store.savePrefs() })) {
                    Text("Liked songs").tag("")
                    ForEach(store.playlists) { Text($0.name).tag($0.name) }
                }
            } header: { Text("Heart button") } footer: { Text("Shows filled when the song is already in that playlist.") }

            Section("Storage") {
                LabeledContent("Downloads", value: "\(store.downloads.count) songs")
                Button("Delete all downloads", role: .destructive) { store.downloadedTracks.forEach(store.deleteDownload) }
                    .disabled(store.downloads.isEmpty)
            }

            Section {
                Link("Stash on the web", destination: URL(string: "https://stashmusic.vercel.app")!)
                LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
            }
        }
        .navigationTitle("Settings")
        .task(id: store.session?.email) { devices = await store.sessionCount() }
        .askText($pairShown, "Code on the TV", placeholder: "ABC123", text: $code) { c in
            Task { do { try await store.pair(c); store.say("TV paired") } catch { store.say(error.localizedDescription) } }
        }
    }

    private func auth(signup: Bool) {
        busy = true
        Task {
            do {
                if signup { try await store.signup(email: email, password: password) } else { try await store.login(email: email, password: password) }
                password = ""
            } catch { store.say(error.localizedDescription) }
            busy = false
        }
    }
}
