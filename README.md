# stash

A personal music app for Android in Kotlin and Jetpack Compose. It streams from youtube and a few other sources, downloads for offline, and imports your Spotify / Apple Music playlists. Personal use only, not for the Play Store.

## What's in it

All six milestones from the plan are in this first build:

1. **Player + youtube search**: Media3/ExoPlayer in a `MediaSessionService`, so you get background playback, a media notification and lockscreen controls. Search shows one list of songs from every source (duplicates removed, best matches first). YouTube search uses YouTube Music's own search API (well under a second), with yt-dlp's `ytsearch` as the fallback. The next song's stream is looked up while the current one plays, so there's no gap between songs.
2. **Library**: Room db with saved songs, liked songs, playlists, and albums/artists grouped from your saved songs.
3. **Downloads**: a download button on every song, album and playlist. They run on a WorkManager queue with resume, retries (3 tries with exponential backoff), a wi-fi only toggle and a foreground progress notification. Files go to app storage (`files/music/`). The player always uses the local file if there is one.
4. **Fallback providers**: youtube → vocadb → soundcloud → bandcamp → jamendo → internet archive → free music archive.
5. **Playlist import**: Spotify (public playlist/album link, or CSV/zip files from exportify.net) and Apple Music (public playlist link, or the catalog API if you add a MusicKit token). There's a progress screen and a "couldn't match" list you can fix by hand.
6. **Polish**: Material You dynamic color (wallpaper colors on Android 12+, lavender fallback below that), light/dark/system theme, and an adaptive icon with a monochrome layer for themed icons.

## UI

- The bottom nav works like Spotify's: **home / search / your library**, with a mini player above the nav bar.
- Home has Spotify-style quick-pick tiles, plus Google Play Music style "listen now" carousels: recently played, your playlists, on repeat, ready offline.
- Search has a "browse all" grid (tonal Material You tiles instead of Spotify's fixed colors), provider filter chips, and results grouped by source. Long-press any song (or tap ⋮) for the Spotify-style action sheet.
- Your library has filter chips: playlists, songs, albums, artists, downloaded.
- Playlist/album pages are Spotify style: the cover's color fades down the page, big cover, left-aligned title, download / more on the left, shuffle and a big round play button on the right. Liked songs has a gradient heart tile.
- The library has Spotify's Recents / Alphabetical sort and a list / grid switch.
- Playlists can be pinned (playlist ⋮ menu, or long-press in the library): pinned ones come first in home's quick picks and at the top of the library.
- The mini player is tinted with the cover's color, with a heart, play, and a thin progress line.
- Now playing is a full-height sheet tinted with colors from the album art (androidx palette). The art shrinks with a bounce when paused, and the play button morphs between pill and squircle. Along the bottom, Apple Music style, three plain icons: lyrics, similar songs (YouTube Music's radio for the song: play one or add them all) and the queue.

## Moving playlists (background)

Imports are a queue saved in the database (`import_jobs`). `ImportWorker` runs it in the background with a foreground notification, so 25 playlists keep going with the app closed, and it picks up where it stopped if Android kills it. It matches 4 songs at a time, and a song that shows up in several playlists is only searched once (`match_cache`).

- **Order is kept exactly**: playlist rows are keyed by position, so duplicates are allowed like on Spotify, and songs that couldn't be found show up greyed out in their spot. Fixing one by hand puts it back in that spot.
- **Spotify liked songs** (exportify's `Liked_Songs.csv`) go into stash's liked songs in the same order, not into a new playlist.
- **Missing songs**: the import screen lists them as soon as the first one isn't found, and the notification expands to show the latest ones.

## Account / backup (Supabase)

Optional. Settings → account, then email + password; Supabase sends a confirmation email the first time. After tapping the link, coming back to Stash logs you in by itself. To make the link open Stash directly, add `stash://auth-callback` under Supabase → Authentication → URL Configuration → Redirect URLs (and set it as the Site URL); the app handles that link. Playlists (in order, missing songs included) and liked songs are saved to the `stash` Supabase project (`library_items`, row level security so every account only sees its own rows). It backs up by itself about 20 seconds after anything changes. Logging in on a new phone restores playlists it doesn't have yet.

## Made for you

Everything runs on the phone. Your plays and likes pick the seeds, and YouTube Music's radio for a song (the "up next" list its own app builds) supplies similar songs.

- **daily mix 1–4**: one per top artist. Your songs by them and by artists the radio calls similar, alternating with new ones (max 2 per artist). Rebuilt once a day.
- **discover weekly**: 30 songs you don't have, from 5 of your favourite songs' radios. Songs several radios agree on come first. New every Monday.
- **repeat rewind**: songs you played 3+ times but not in the last 30 days.
- **because you liked …**: a home row of songs similar to your latest like.
- **start radio** on any song (long-press), and **autoplay** (settings → playback) keeps the music going with similar songs when the queue ends.

- **genre mixes** ("For you by Stash"): every artist's main genre comes from Deezer's public API (cached), and the genres you listen to most each get a mix of your favourite artists from that genre plus similar songs.
- **artist mixes** ("Overtonight Mix"): the artist's songs, yours and their popular ones, mixed with similar artists.

Mix covers are drawn by the app, Spotify style (`MixArt`): daily mixes are the artist's photo with a colored "Daily Mix 2" band, artist mixes are "This is <artist>" over their photo, genre mixes put the genre's name over a top artist's photo with "For you by Stash", and Discover Weekly / Repeat Rewind are colored gradients. Artist photos come from Deezer's public API (exact name only). Every mix keeps its own color.

Mixes are normal playlists (origin `mix`), so they can be downloaded. They aren't part of the cloud backup, since every phone rebuilds them.

## Live lyrics

Now playing → Lyrics. Time-synced lyrics from lrclib.net (free, open, no key): looked up by artist + title + length, then by search, then by title alone with a matching length (for artists spelled another way, "サツキ" for "Satsuki"). The line being sung is lit in the cover's color and kept in view; tap a line to jump there. Songs with only unsynced lyrics show the text. Cached per song for the session.

- **Musixmatch** (Settings → Lyrics, off by default): through Musixmatch's desktop-app API, the way Spotify lyrics plugins use it (an anonymous user token it hands out, kept and renewed when refused, `macro.subtitles.get` for synced lyrics). LRCLIB is used whenever it has nothing.
- **Translation** (toggle in the lyrics screen or settings, language: the phone's or one picked): Musixmatch's community translation (`crowd.track.translations.get`) when the song came from Musixmatch and has one, otherwise Google Translate's free web endpoint for the whole song in one request, shown under each line.

## Home greetings

The title follows the time of day, with a few variants each. With "greet me by place" on (settings, asks for location), the line under it follows where you are: airport, cafe, station or train (offers your most common playlist artist), driving, gym, library, and so on. The place comes from one location fix plus OpenStreetMap's Overpass API, cached for 10 minutes.

## Search

- One list of songs from every source. Library hits come first, then the best matches by title, artist and tags. The same song from two sources shows once. Each result shows its play count and a tag when the source has them.
- **#tags**: `#phonk` (or `tag:phonk`) searches by tag. SoundCloud uses its own tag filter, Audius filters on its track tags, and YouTube Music gets a plain search. The browse tiles are tag searches.
- **Underground** switch: drops anything known to have more than 200K plays/views and lists the least played first. There's an Underground browse section too (hyperpop, breakcore, memphis rap…).
- **Artists** row: artist results from YouTube Music, SoundCloud and Audius, with fan counts and an "Underground" badge under 20K. Tapping one opens their uploads.
- **Audius** (independent artists, open API, plain MP3 streams) is a source now. SoundCloud search and playback go through SoundCloud's own web API (client id read from their site), which is much faster than yt-dlp. SoundCloud only serves HLS now, so its songs are queued as HLS (`stash://track/<id>.m3u8`).
- Recent searches show when the box is empty.

## YouTube Music playlists

Search shows a Playlists row (YouTube Music's community playlists for the query, like Spotify's). A playlist opens on its own page with play, shuffle and download, and "Save to your library" makes a normal Stash playlist from it with its cover. YouTube / YouTube Music playlist links pasted (or shared) in the importer open the same page; they're YouTube songs already, so nothing needs matching. Pages of 100 are followed up to 1000 songs.

## Artist pages

Any artist (from search, "go to artist", or your library) gets a full page: banner, round profile picture, fans, monthly listeners, location, an expandable bio, a link to them on the source, play / shuffle / artist radio, your own songs by them, their popular songs, and "fans also like". It comes from YouTube Music's artist page, SoundCloud (user, visuals, top tracks, related artists) or Audius (profile, cover photo, tracks, related). Library artists are looked up by exact name on those three, in that order.

## Rescan failed songs

Songs that couldn't be found can be searched for again against all current sources, including ones added later like Audius. It first forgets old "not found" answers, runs in the background with a notification, and puts every song it finds back into its exact spot. It has its own screen: big progress bar, the song it's looking for right now, the songs it found, and what's still missing (tap one to search by hand). The button is on the import screen, in playlists that have missing songs, and as a library row that only appears while something is missing. After an update that improves matching, it runs once by itself.

## How imported songs are matched

`Resolver.match` searches "first artist + title" on every source in order. YouTube looks at YouTube Music's songs **and** videos together, since underground music (game soundtracks, vocaloid, SoundCloud rappers) is often only a video, and the songs tab doesn't exist in some countries. If nothing fits, it tries "title + album" on YouTube and SoundCloud. `Matcher.score` then weighs:

- title words, with "cover"/"covered" style prefixes allowed
- any credited artist, found in the artist, the channel name or the raw upload title (so "DOORS ORIGINAL SOUNDTRACK VOL. 1 - Elevator Jam" uploaded by LSPLASH counts as LSPLASH)
- a distinctive album word ("Doors") as half an artist, only when the length also matches
- cyrillic and japanese kana spelled in latin letters ("タカオカミズキ" = "takaokamizuki"), with kanji, kana and latin split into separate words
- length, and a penalty for versions the original title doesn't mention (cover, remix, sped up, nightcore, piano…)
- small typos in upload titles (two letters swapped, one missing), for words of 6+ letters
- uploads that don't name the artist at all (the original on a fan channel, "巡音ルカ「ルカルカ★ナイトフィーバー」") count when the whole title is there, the length is within 3 seconds and nothing marks it as a cover (歌ってみた, off vocal, MMD…); they only just pass, so an upload naming the artist always wins. If "artist + title" finds nothing anywhere, YouTube and SoundCloud are searched by the bare title too
- the playlist a song came from: soundtracks and Roblox music are often uploaded under the game's name ("untitled tag game OST: B0MB!!") rather than the artist's, so the playlist name's distinctive words are searched with (as a last try) and count like an album word
- VocaDB, right after YouTube: it knows every vocaloid / utau / synthv song by its japanese, romaji and english names, so "Rolling Girl" finds "ローリンガール" (wowaka's own upload, not a remake)

## VocaDB

`VocaDbProvider` uses vocadb.net's public API. Songs come out as their original YouTube upload, so they play and download like any YouTube song. `#teto`, `#miku`, `#len`, `#meiko`… (or just typing a voicebank name) lists songs by that voice on every engine it has (on VocaDB, Teto UTAU and Teto SynthV are separate artists), taking turns between them. `#teto-utau`, `#teto-synthv`, `#miku-vocaloid` etc. pick one engine; each includes that voicebank's own updates (SV2, Kyuu, appends); other tags like `#vocarock` use VocaDB's own tags. Search has a "Vocaloid & synth voices" row of tiles.

## Covers

Saved songs get their real album cover instead of a YouTube video thumbnail. For Spotify imports the cover comes from Spotify itself: the CSV's album image url, or public oEmbed with the track URI from the link. It's applied even when the matched song was already saved (older versions skipped those), and songs imported before that are fixed once in the background. Spotify covers are on `i.scdn.co`, which some networks block, so the app falls back to the same picture on Spotify's other CDN hosts. Playlists have "Change cover": a photo from the phone, or a Spotify playlist link (exportify's CSVs don't include the playlist's own cover).

### Playlist covers for Exportify imports

Exportify's files only have the playlist's name, and Spotify only gives a cover by playlist id. The import screen's "Playlist covers" card gets the ids two ways, then asks Spotify's public oEmbed for each one's name and cover and matches them to Stash playlists by name (letters and digits only, the way Exportify names its files). The real name, with its capitals and punctuation, comes back too.

- **Through Exportify**: opens exportify.net inside the app. You log in like you did for the CSVs, then "Grab covers" reads every page of Exportify's playlist list (each name links to the playlist) through a small script.
- **Profile link**: your Spotify profile page lists your public playlists, but only about 10.
- **Pasted links**: any number of playlist links pasted at once.

The in-app Exportify page presents itself as plain Chrome (no WebView marker) with cookies on, since Spotify's login turns WebViews away.

The ids are remembered by name (`PlaylistCovers`), so later Exportify imports get their cover by themselves. Everything else gets Apple Music's cover through the iTunes Search API. `CoverWorker` works through saved songs slowly in the background, because Apple allows about 20 lookups a minute.

## Download size

Settings live on the downloads screen. **High** keeps the best stream (about 130–160 kbps). **Balanced** (the default) picks YouTube's ~70 kbps Opus stream and re-encodes other sources to 96 kbps AAC. **Data saver** goes down to about 50–64 kbps. Re-encoding uses media3 Transformer, so the phone's own encoder, no ffmpeg, and a file is only replaced if the result is actually smaller. "Shrink my downloads" re-encodes what's already on the phone in the background.

## How playback finds audio

The queue only holds `stash://track/<id>` uris. When ExoPlayer opens one, `PlaybackService` resolves it right then (`ResolvingDataSource`):

1. downloaded file, if there is one
2. the track's own provider. For YouTube, three quick ways start together: community YouTube frontends (below), YouTube's own player API the way its Android VR app asks (skipped for 10 minutes after it keeps refusing), and NewPipeExtractor (the library NewPipe, PipePipe and YouTube Classic use: plain Java with Rhino for YouTube's player code, 1-3 seconds). Each link is checked with a 2-byte request and sent with the app identity it was made for. yt-dlp (a whole Python, 30+ seconds on some phones) only starts if neither has a link after 8 seconds.
3. the fallback that worked last time (cached on the track)
4. search every other enabled provider in order and match by title + artist + duration (`Matcher`), then remember the winner

yt-dlp updates itself once a day at most (songs that need it wait while it updates). There's also an "update now" button in settings.

## Adding a provider

Implement `MusicProvider` (search, resolveStream, metadata, downloadSource) and add it to the list in `ProviderRegistry`.

## Setup on the phone

- **Jamendo**: get a free client id at devportal.jamendo.com and paste it in settings. Until you do, Jamendo is skipped.
- **Spotify import**: no Spotify developer app needed (their Web API went premium-only in 2026). Either paste or share a public playlist/album link (read from Spotify's embed page, which only has the first 100 songs), or export from exportify.net and pick the CSVs or the "export all" zip in the importer. Exportify also covers private playlists and liked songs.
- **Apple Music import**: in Apple Music, share a playlist → copy link, then paste it in the importer (or share it straight to stash). A MusicKit developer token in settings is optional.

## Known limits / things to test

- Not tested on a device yet. It builds and the unit tests pass, but there was no emulator in the build environment.
- YouTube keeps changing things. If youtube songs fail, hit "update now" in settings first. Newer yt-dlp versions want a JavaScript runtime for some YouTube formats, which this bundle doesn't have, so some videos may only play through a fallback.
- Bandcamp search uses the site's own autocomplete API, which sometimes sends a bot check. Playback still goes through yt-dlp.
- FMA has no public API anymore, so its search results are scraped from the search page and may break if they redesign the site.
- Only arm64 (all recent phones) and x86_64 (emulator) builds are included. Each one bundles a whole Python for yt-dlp.

## YouTube login (optional, for playback only)

Settings → Playback → Log in to YouTube, the way PipePipe (and YouTube Classic) do it: Google's own sign-in page in a WebView with a desktop Chrome identity and WebView's `X-Requested-With` header turned off (Google's main "this browser may not be secure" signal). Once youtube.com's cookies hold a session (`SAPISID`), they're written as a Netscape `cookies.txt` that yt-dlp gets with `--cookies`, so songs YouTube won't hand out anonymously still play. Search, artist pages and the catalog stay anonymous. If Google won't allow the login in a WebView, "Paste cookies instead" takes a cookie export (Cookie-Editor's header string, a Netscape cookies.txt or its JSON), with step-by-step instructions on the screen. Without a login, yt-dlp skips the watch page and client config requests (`player_skip=webpage,configs`) to get to the audio faster.

## Create, heart, made for you

- The bottom bar has Home, Search, Your library and **Create**. Create opens a menu: create a playlist, move playlists (import), find missing songs (rescan) and get playlist covers (Exportify).
- The **heart** adds to liked songs by default, or to any playlist you pick (Settings → Heart button). It's filled when the song is in that playlist.
- **Made-for-you** mixes (daily mixes, "This is" artist mixes, genre mixes, discover, rewind) live on home only; open one and pick "Save to your library" to keep it in the library.
- The icon: a light blue circle with three rounded bars (long, short, middle). While the app is dark it switches to black with white bars (two launcher activity-aliases, `AppIcon`); Settings → Dark icon in dark mode turns that off.
- The player covers the whole screen; back or a drag down closes it.
- Rescans (Create → Find missing songs) end with a looser pass over the same results that ignores length, and "UTG" in a playlist name counts as "untitled tag game" in an upload (any 3-5 letter abbreviation, both ways).

## Karaoke lyrics (1.24)

The line being sung lights up letter by letter, like Apple Music. Musixmatch's richsync (asked for in the same `macro.subtitles.get`, `optional_calls=track.richsync`) gives every word's start time; for other synced lyrics (LRCLIB) the words are spread over the line by length. Each letter fades from dim to bright at its moment, read off the player every frame. Translations are matched to richsync lines by their original text.

## Musixmatch (1.23)

Works like EeveeSpotify (Reborn): the iPhone app's API (`apic.musixmatch.com`, `app_id=mac-ios-v2.0`). Settings → Lyrics → Musixmatch token takes the Musixmatch app's "Copy debug info" text (`[UserToken]: …`) or a bare token (Android app tokens only work with `app_id=android-player-v1.0`, iPhone ones with `mac-ios-v2.0`: the debug info says which, and a refused bare token gets tried as the other); without one, an anonymous token from `token.get` is used (the desktop app's anonymous tokens now come back all zeros, which is why translations never showed). With "Show translation" on, `macro.subtitles.get` is asked with `selected_language` + `part=subtitle_translated`, so the translation arrives with the lyrics, line for line; Machine translation fills in when Musixmatch has none (most songs have no Russian one): Chrome's own page translator (`translate-pa.googleapis.com/v1/translateHtml`, lines sent as a list so they stay aligned), then the old `translate_a` endpoint, which now often answers with a captcha page. Musixmatch is on by default now.

## TV, tablets and pairing (1.22)

- **Android TV / Google TV:** the same APK. On a TV (leanback feature) Stash opens its remote-friendly app (`ui/tv/TvApp.kt`): a rail on the left (Search, Home, Library, Now playing, Account), big cards, D-pad focus with a white outline, a full-screen player with big controls. It's in the TV's app row (LEANBACK_LAUNCHER, `tv_banner`). Always dark, Stash's own colors.
- **32-bit TVs** (armeabi-v7a, most older Android TVs): `dist/stash-armv7.apk`, built with `gradle assembleRelease -Pabis=armeabi-v7a`. `dist/stash.apk` stays 64-bit (arm64 + x86_64) for phones.
- **Pairing:** a TV (or tablet) shows a 6-character code; on a logged-in phone, Create → Pair a device → type it. The `pair` Supabase edge function (table `device_pairing`, RLS on, service role only) makes a one-time magic-link token for that account; the TV swaps it at `/auth/v1/verify` for its own session (`cloud/Pairing.kt`), then restores playlists like a normal login. Codes last 10 minutes, one use each; the phone's own login never leaves the phone.
- **Tablets:** 600dp+ wide gets a navigation rail instead of the bottom bar; the player puts the cover on the left in landscape.
- **Splash follows the icon:** on Android 12+ the app's night mode is pinned with `UiModeManager.setApplicationNightMode` to match the icon (black only while the dark icon is on), and the app reads the system's dark mode itself for its own colors.

## Motion (1.21)

- Splash screen with the icon's bars on its color (black with white bars in dark mode, `core-splashscreen`); the bars grow and fade, then the app rises in.
- Pages slide a little and fade; going back slides the other way. The player slides up / down with eased timing.
- Lyrics, queue and similar songs rise in over the player and sink back out; the background fades to the lyrics color.
- In the player, tap the artist for their page, or the album name above the cover for the album.

## Matching fixes (1.20)

- Covers, rhythm-game charts (osu, DDR, Pump It Up, Rhythia, Beat Saber), game ports, MMDs, loops and "1 hour" uploads count as other versions and lose clearly to the song itself (−0.35); a length off by a minute or more costs more too.
- YouTube Music "songs" (the label's own audio) get a small edge over equally good video uploads.
- Titles that name nothing ("Untitled #1", "Track 3", "Intro") need the length within 4 seconds; "Untitled #1 - Vaka" keeps its "Vaka" when searching.
- Any song's menu has "Wrong song? Pick the right one": search, tap the right result, and the song plays that from then on.

## WebView player (last resort)

When a song fails every source (and its one slow retry), the player pauses and asks: "Song can't be loaded, we can use a WebView to deliver it." Yes plays it from music.youtube.com in a hidden WebView (desktop Chrome identity, the YouTube login's cookies shared into it, given a 1280x720 size so the page builds its player). A bit of JavaScript twice a second keeps it playing or paused as asked, skips ads, opens the first result when the song had no YouTube match and only a search could be loaded, and reports position and length, so the player screen, seek bar and play button drive it like any song. When YouTube Music moves on to its own next video, the queue moves on. No answer in 30 seconds, or Skip, goes to the next song like before. It plays inside the WebView, so it can't be downloaded or sent through the equalizer.

## YouTube frontends (no VPN needed)

Invidious and Piped servers fetch the audio from YouTube themselves and pass it on, so YouTube never sees the phone's connection (no VPN bot checks, no regional slowdown). The instance list is a community-kept JSON (`http://144.31.189.129/notPipe.json`, thanks gohoski); it's bundled as `assets/frontends.json`, refreshed on launch when older than 6 hours, and Settings → Playback → YouTube frontends → Update now. Invidious is asked with `local=true` (the instance proxies the audio), Piped returns proxied links already. Instances are tried 4 at a time, every link is checked before use, and ones that worked go first next time. Most instances are plain http, so cleartext traffic is allowed (`network_security_config.xml`). yt2009 servers are used too: `/get_video?video_id=ID/mp4` redirects to their own cached `/assets/ID.mp4` (video + audio); a song a server doesn't have yet takes it minutes to build, so that try gives up after 25 seconds, but the server keeps building it, so the next time it's quick. typetype entries aren't used (no documented API). soundcloak (SoundCloud frontend) was checked: the public instances refuse its audio endpoint (417) or sit behind a bot wall.

## Keeping the phone happy

- At most 2 downloads run at once, and downloads have their own 2 yt-dlp slots (each yt-dlp is a whole Python), so they never make a song you tapped wait. Queued downloads wait without a notification.
- SoundCloud streams are HLS playlists, and a song from elsewhere can fall back to SoundCloud. The queue marks those as HLS up front, a plain MP3 is used when SoundCloud still has one, and if the player still gets the wrong kind (`PARSING_CONTAINER_UNSUPPORTED`) it reopens the song as the other kind instead of skipping.
- yt-dlp gets 60 seconds per try (90 overall); on some phones it needs 35-45.
- If YouTube's fast link hasn't answered in 2.5 seconds, yt-dlp starts alongside it and whichever is first wins. If a song's stream breaks while playing, it's fetched again through yt-dlp and continues from the same spot; only a second failure skips it.
- On connections where YouTube refuses the quick way (3 refusals in a row), it's skipped for 10 minutes and yt-dlp starts right away. yt-dlp gets a `--cache-dir`, so YouTube's signature code is solved once instead of on every song. Found links are kept until shortly before YouTube's `expire` stamp (up to 5 hours), and the next two songs in the queue are looked up while the current one plays.
- YouTube's quick way is the Android VR client only (the plain Android client's links were refused as soon as playback started). Every link, quick or NewPipe, is checked the way the player asks for it (open-ended ranges from the start and 1 MB in, reading real bytes) before it's used. A song whose quick link is cut off mid-play is fetched again through NewPipe (then yt-dlp), from the same spot. A failed lookup is remembered for a minute so the player's own retries don't each start over.
- Settings → Playback log shows how each song was found and how long it took, lookups for upcoming songs marked "ready ahead", and "▶ playing" lines with the real time from switching to a song until it was audible. When yt-dlp had to do it, the line says why the quick ways didn't.
- Songs stream: playback starts after half a second of audio and keeps buffering ahead. Finding a song's audio gives up after a minute with a reason, instead of loading forever.
- Background progress (rescan, downloads) updates its notification at most about once a second. Posting one per song made Android's notification service stop responding.

## Building

```
./gradlew :app:assembleDebug
```

The release build runs R8 (no renaming, stash's own classes kept whole), which makes Compose a lot smoother. Room lists on screen go through `calm()` (`data/Flows.kt`) so imports and the cover fixer writing to the database don't make every list redraw several times a second.

compileSdk/targetSdk 35, minSdk 26, AGP 8.7.3, Kotlin 2.0.21 (compose compiler plugin), Hilt + KSP, Room, WorkManager, Media3 1.5.1, youtubedl-android 0.18.1. A prebuilt release apk is at `dist/stash.apk` (release build, much smoother than debug). Every build is signed with `app/stash.keystore` (the standard Android debug key, committed on purpose) so updates always install over the old app.

`icon.svg` is the icon mockup and matches the vector drawables in `res/drawable/ic_launcher_*.xml`.

## Legal-ish

YouTube, SoundCloud and Bandcamp extraction is against their terms, so keep this private. Jamendo, Internet Archive and FMA are the open, CC-licensed ones.
