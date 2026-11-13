# TV Music

An experimental Android TV music app for a five-way remote. Version `0.8.0-native-preview` includes native artist/album browsing, voice search, whole-album queue actions, account-liked tracks, Media3 audio/video playback, queue editing, and native synchronized lyrics. Its normal music interface loads no YouTube Music webpage. The previously tested website player remains available during the migration. Initial hardware target: your TCL Android TV 11, firmware `V8-R51AT01-LF1V126.020732`.

## Native preview

The native preview uses Java Android Views, recycled lists, one Media3 player/session, and a native lyrics panel. D-pad moves focus; OK activates. Search results have an Actions control for Play next and Add to queue. Queue actions support Move with Up/Down and OK, removal/Undo, shuffle, repeat, and saved-queue restoration. The current item stays separate from upcoming entries. Back/Forward navigate screen history; channel and media Next/Previous invoke track changes when the TV delivers those keys. Playback continues while browsing inside the app; leaving the app pauses by default, with an optional background-audio setting.

To queue a whole album, open **Search → Artists**, find the artist, open an album, and choose **Add all to queue**. **Play next** puts the complete album immediately after the current track; **Play all** replaces the queue and starts the album. Album track order and duplicate occurrences are preserved. The selected catalogue edition determines the tracklist: the current EASY release includes additional sped-up and slowed/reverb versions. Albums can also be found directly through Search → Albums. Back restores the artist/album, query, filter and scroll position.

For voice input, open Search and select **Voice** with D-pad/OK. Allow microphone access when Android asks. The in-app Listening dialog uses the TV's recognition service and searches in the current Songs/Artists/Albums filter. Cancel keeps the previous query. Recording stops when the dialog closes or the app loses visibility, with no audio stored by this app. This native path has been confirmed working with the TCL remote; the earlier external Google speech window opened without hearing it. The dedicated Assistant key remains system-controlled. See [Android speech recognition](https://developer.android.com/reference/android/speech/SpeechRecognizer).

Large cover art uses available 640-pixel renditions rather than enlarged 120-pixel search thumbnails. The loader has one worker, a 4 MiB bitmap cache and a 2 MiB encoded-response cap. It crops before sampling, preserves 512-pixel sources instead of reducing them to 256, and falls back to the supplied thumbnail if a larger rendition is unavailable. Temporary decode buffers add to the cache budget; it is not a total image-memory limit. Album songs without row artwork now inherit the selected album cover. Empty artwork clears the previous image, and a bounded metadata-only repair can fill missing covers in an existing queue while preserving its IDs, order, source and playback position. The repair was verified on the TCL: all six existing IVE EMPATHY entries gained their covers while queue IDs/order and the current entry stayed intact; playback advanced without resetting.

The Audio/Video control requests representations of the **same video ID**, preserving the queue. **Quality** selects Auto (up to 720p), 360p, 480p, 720p, 1080p or optional **4K (2160p)** as a maximum resolution. The player uses MP4/H.264 through 1080p and compatible hardware-decoded VP9 for higher resolutions, capped at 30 fps, with a lower-resolution fallback; the status shows the actual decoded dimensions. Quality changes retain the current queue entry, position and play/pause state, and the preference is saved. Separate audio and video representations are merged by Media3. An album recording and a differently edited music video are not silently substituted for one another. Unavailable video can fall back to audio.

Optional 4K requires a compatible hardware VP9 decoder and verified profile-0, 8-bit SDR video. For older YouTube `vp9` metadata, at most two 256 KiB HTTP range probes validate the WebM track and first keyframe; unsupported, encrypted, malformed or explicitly HDR inputs fall back to an eligible H.264 stream. Verification runs on the existing playback worker and does not decode a frame or change the UI resolution. On this TCL, a short 3840×2160 VP9 hardware sample rendered 4,394 frames with zero decoder-reported drops; sustained 4K playback remains unqualified.

For fullscreen video, select **Video → Full screen** in Now playing. The video fills the available screen while preserving its proportions. Playback controls hide after five seconds while playing; **OK**, **Up**, or **Down** brings them back. Visible **Rewind 10s**, **Forward 10s** and a seek rail allow seeking; Left/Right on the rail or with controls hidden skips ten seconds. **Back** closes visible controls; another Back exits fullscreen. **Exit full screen** returns directly to the normal player. Menu shows or hides fullscreen controls. Pausing or a playback error reveals controls. Search and Info shortcuts return to the normal layout. Fullscreen uses the same player and surface, with hidden lyrics and transport updates stopped. Encoded buffering targets 32 MiB with a 10–20-second window; decoder, surface and other native allocations are additional, so this is not a total memory cap.

Catalogue requests use native HTTPS/JSON and YouTube Music's private interfaces. Search currently returns at most 60 playable song/video results from one response; it is not a complete paginated catalogue browser. **Library currently means the first page, up to 60 liked tracks**, not the complete account library, uploaded music, or every saved playlist. Account → Sign in opens the existing private account window; Refresh library then checks native account access separately. The catalogue adapter uses only the app's own Music-session cookies for requests to `music.youtube.com`; it does not import another app's browser profile or log session material.

Actual playable sources come from **NewPipeExtractor `v0.27.1`**, not the official YouTube Data API. The resolver currently makes guest requests and does not attach the account session to media extraction. A signed-in catalogue therefore does not promise playback of private, age-restricted, uploaded, region-blocked, or subscription-only tracks. There is no new Google Cloud client setup, laptop relay, Python runtime, yt-dlp, or ffmpeg process on the TV. Private service interfaces and expiring sources can change or fail; Retry/Skip and an audio fallback remain available. See [implementation proposal](docs/native-tv-proposal.md) and [licenses/provenance](THIRD_PARTY_NOTICES.md).

Native lyrics reuse the existing LRCLIB provider and recording/timing safeguards, presented with recycled native text views driven by the Media3 clock. The native panel preserves the listening-room design; it does not run Braccato's JavaScript/DOM renderer. The original Braccato renderer remains bundled for the legacy player and offline preview.

Native audio/video playback, search, synchronized lyrics, Play next, Add to queue, Move, Remove/Undo, Clear/Undo, channel commands, shuffled restoration and Home/pause have been checked on the TCL. PixelCopy captured a real video frame from the native surface; the TV's ordinary screencap omits its video plane. A short Realtek hardware-decoder sample reported 709 rendered frames with zero decoder-dropped frames. These are initial checks, not sustained performance qualification. The saved account reports a signed-in bootstrap; its liked-track response currently contains zero tracks, so a populated account library remains unverified. See [hardware validation](docs/native-preview-validation.md).

The TV launcher now opens the native preview. It can also be opened explicitly:

```sh
.tools/android-sdk/platform-tools/adb shell am start -n dev.ytmusictv/.nativeapp.ui.NativeTvActivity
```

The original activity remains available explicitly as `dev.ytmusictv/.MainActivity` for rollback.

The physical TCL shortcuts are confirmed: the virtual-keyboard button opens Search and its keyboard; Info/subtitles opens Lyrics. Web opens the TV's browser and is left as-is at the user's request. These shortcuts do not change remote behavior in other apps.

## Previously tested website player

Version 0.5 adds an in-app Google account window using the player's normal WebView profile. Sign-in was completed on this TCL with the unchanged system user agent, then verified in the actual player alongside synchronized lyrics. The charcoal interface includes a collapsible command strip, artwork-and-lyrics listening room, native lyric glides, and compact playback controls. Cursor movement and paused work are kept small, and incomplete timed lyric fragments fall back to full text. This remains an experimental website-based player; extended performance and compatibility testing is still needed. There is no standalone YouTube Music playback API integrated here.

## Lightweight development

The native preview uses Java, Android Views/RecyclerView, and Media3. Its account window is temporary; its playback does not run through WebView. The legacy mode uses one playback WebView and the temporary account window. No Compose, React, Electron, bundled browser, or emulator is required. Gradle runs one worker with a 1 GB heap and stops after the build. That is a JVM heap limit, not a limit on total system RAM. Android 8.0+ is the initial minimum; your TV runs Android 11. The legacy page and account flow need an updated Android System WebView; the bundled JavaScript targets Chrome 80 syntax, which is not a guarantee that the current YouTube Music site supports that engine.

Native runtime versions are pinned to Media3 `1.5.1`, RecyclerView `1.3.2`, NewPipeExtractor `v0.27.1`, and `desugar_jdk_libs_nio:2.1.5` for Java library APIs on Android 11. NewPipeExtractor is obtained from a narrowly scoped JitPack repository. There is no Kotlin compiler or whole multiplatform application build in this project. One catalogue worker has one queued request; stale catalogue requests are cancelled. Catalogue JSON is capped at 2 MB, extraction responses at 8 MB to allow current player scripts, and media bytes are streamed by Media3 rather than loaded into memory as a complete track.

Try the offline UI without Android tools:

```sh
python3 -m http.server 8000 --bind 127.0.0.1
```

Open [the preview](http://127.0.0.1:8000/dev/preview.html) in a browser. It uses original demo text and a simulated clock, with no audio or YouTube connection. Use Lyrics, arrows, Enter, and Escape.

## Build an APK

Portable JDK 17 and Android command-line tools are downloaded under ignored `.tools/`; no system packages, Android Studio, or emulator are needed. Downloads are checksum verified. The first setup requires reviewing Google's SDK licenses in your terminal:

```sh
./scripts/bootstrap-android.sh
./scripts/build.sh assembleDebug lintDebug
```

The bootstrap supports Linux x86-64. On other systems, install JDK 17 and Android SDK, set `JAVA_HOME` and `ANDROID_HOME`, then use `scripts/build.sh`. Tool versions are pinned: Gradle 8.9, Android Gradle Plugin 8.7.3, SDK platform 35, build tools 34.0.0. Target API 35 is for this local prototype, not a claim about future Play Store submission requirements.

The debug APK will be `app/build/outputs/apk/debug/app-debug.apk`. If licenses have not been reviewed, packaging remains unverified; the source checks do not substitute for an Android build.

## Install on your TCL

After enabling developer options and network debugging on the TV, use the address and port displayed by its settings. If the TV requires wireless pairing, pair first; if it exposes legacy network ADB, connect directly. Keep the laptop and TV on the same network.

```sh
.tools/android-sdk/platform-tools/adb connect TV_IP:PORT
.tools/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
.tools/android-sdk/platform-tools/adb shell am start -n dev.ytmusictv/.nativeapp.ui.NativeTvActivity
```

The command above and the TV launcher open the native player. Arrows move focus, OK activates, and Back returns through screens. Menu shows or hides Controls. Search uses the TV keyboard. A result's Actions menu offers Play next and Add to queue. Queue → Actions → Move uses Up/Down and OK; Back cancels the draft. Channel +/− select next/previous entries when those key events reach the app. Left/Right on the seek rail changes position by ten seconds.

The following account-window and cursor instructions describe the retained legacy player, opened explicitly with `.MainActivity`. Its directional pad moves a cursor; hold an arrow to move faster, and use OK to click. Its Music webpage is not loaded by the native player.

Lyrics, or the lyrics icon in the playback strip, opens the listening room; Back returns to browsing. Previous, Play/Pause, Next, the seek rail, and elapsed/total times remain visible. Clicking a timed lyric seeks to that line; Follow song restores automatic scrolling after browsing lyrics manually. Missing or failed lyrics have a Try again action. Artwork has a local fallback and uses modest thumbnail sizes.

Account → Sign in here opens Google's HTTPS pages in an account window with the current origin and path visible. Use the remote cursor and TV keyboard; Cancel returns to the player. When the account window returns to Music, it closes and refreshes the player's Home page using the same normal WebView cookie profile. The music website determines the signed-in state; a completed navigation alone is not treated as proof of login.

The account window uses the unmodified system user agent, no injected script or JavaScript bridge, and no credential reading or cookie export/import. Its navigation permits HTTPS Google/YouTube pages and the exact regional host `accounts.google.com.pk`, which this TV's sign-in flow uses. Query strings and fragments are omitted from address labels and diagnostic logs. The external browser option keeps its own separate session. More → Open YouTube launches the official TV app separately.

## Legacy lyrics and resource limits

- Braccato 1.21.5 is bundled locally, approximately 104 KB JavaScript and 19 KB CSS before APK compression. Its MIT license and reproducible build are included; the GPL Chrome extension itself is not copied.
- The TV theme disables letter wave, glow, wobble, full-line opacity fades, and per-line scroll animations. Supported WebViews use native smooth scrolling and a small gold marker; reduced-motion preferences and unsupported browsers use instant following. Seeks and explicit cursor scrolling stay immediate. Manual scrolling pauses following until Follow song or the renderer's 25-second timeout.
- The display samples the actual media clock four times per second only while lyrics are visible and the player is running. Paused seeks update from events. Closing lyrics disconnects the renderer; pausing or backgrounding stops its clock and pending scroll. Already-delivered lyrics survive activity pause/resume. The playback strip uses video events and no idle timer; a cached 25dp cursor moves through native view transforms.
- The opaque lyrics screen skips rendering the covered YouTube Music page while retaining its media/session state. Browsing restores the site. Connected player/metadata nodes are cached, and unchanged lyric times avoid renderer writes.
- A native network worker fetches lyrics from [LRCLIB](https://lrclib.net/docs). It tries an exact match, a cleaned title/artist lookup, and a filtered search when needed: at most three sequential requests. It sends the song title, artist, album if known, and duration. There is one worker, one queued request, eight cached tracks, and a 256 KB response limit. Errors and missing lyrics have explicit states; rate-limit cooldowns are honored. A recording whose duration differs by more than three seconds is shown as untimed lyrics, rather than guessing its timing.
- This first provider uses line-synced LRC, or plain lyrics when timing is absent. Contradictory partial timing is checked against the same record's fuller vocal text; remaining bounded lookups can find complete compatible timing, otherwise the full text is clearly marked as untimed. Sparse songs are not rejected from cue count or duration alone. Word timing, translations, and a native library API are not implemented; signed-in library browsing is provided by the music website. A matching recording is not always available.
- YouTube Music controls the media it serves. Using its site can still load video and consume substantial memory; a small APK alone does not establish low runtime memory use. Website markup changes may require adapting the remote/metadata selectors.

## Validation

```sh
node --test tests/*.test.cjs
node --check app/src/main/assets/tv.js
./scripts/check-java.sh
./scripts/check-matcher.sh
./scripts/check-provider.sh
./scripts/check-sign-in.sh
./scripts/check-native-provider.sh
./scripts/check-native-queue.sh
./scripts/check-artwork.sh
./scripts/build.sh assembleDebug lintDebug
python3 scripts/check-browser.py
```

Parser checks cover repeated timestamps, offsets, instrumental gaps, malformed tags, missing duration, and a maximum of 1,000 rendered lines. The lightweight Java check compiles the retained legacy sources against an Apache-licensed Android 11 framework jar with a 512 MB compiler heap. Gradle compiles and lints the complete native integration. The browser check tests the legacy renderer and offline preview using an installed Firefox; it does not validate native views or Android WebView behavior. The renderer has a pinned provenance manifest and a standalone rebuild under `app/src/main/assets/lyrics/rebuild.mjs`. See [third-party notices](THIRD_PARTY_NOTICES.md).

The native-provider check uses sanitized public search, artist, album and playlist-style JSON fixtures, runs offline after the shared framework jar is prepared, and limits the compiler to 256 MB and test JVM to 128 MB. Its 122 catalogue checks cover row metadata, duration and video identity, malformed/private rows, duplicates, nested containers, selected album editions, recommendation exclusion, continuations and album-cover fallback. The 55 artwork URL checks, 136 queue/checkpoint checks, 84 stream-selection/budget checks and 112 WebM/VP9-header checks also pass, for 509 native checks total. `./scripts/check-native-streams.sh` verifies resolution caps, muxed/adaptive selection, lower-quality fallback and codec/frame-rate limits. Search is bounded at 60 results and albums at 200 tracks with three continuation requests. The library-shaped fixture uses public album metadata; it does not certify account authentication or capture private library contents.

Hardware checks on this TCL use Android TV 11 and Android System WebView 157. The visible cursor and complete playback strip have been observed on-device; aespa's LEMONADE lyrics were fetched and rendered. YouTube's ES5 custom-element adapter requires constructing Braccato's registered class directly, rather than using `document.createElement` for that tag.

The 0.3 review includes 134 Firefox checks, four parser tests, 25 matching checks, Android compilation, and Android lint. Physical TV checks cover collapsed/expanded controls, Search and cancellation, playback-preserving Search/Home, Play/Pause, Next/Previous, seeking and lyric-line seeks, artwork, lyrics open/close, Home/resume, the Account/More/Remote guide dialogs, and network error/retry recovery. Untimed and error lyric presentations were also injected as controlled fixtures, visually reviewed, and restored through the real native lyric lookup. That earlier review did not cover Google account sign-in or every upstream website variation.

The TV review caught a host-page CSS sizing mismatch, an unwanted before-unload prompt during Search, and the initial dialog focus indicator; these were corrected. Local screenshots and JSON check reports are under ignored `.tools/design-qa/`.

The 0.4 browser review passes 173 Firefox checks, including native motion, reduced-motion and capability fallbacks, immediate manual scrolling after engine scrolling, Follow recovery, paused clock cleanup, and preserved renderer DOM. A fixture reproducing YouTube Music's missing animation timing method also verifies Follow, paused seeks, and zero caught renderer exceptions. Matching checks cover incomplete timing corroboration, and provider policy checks cover full-text fallback, recording duration, and conflicting instrumental flags. A live LRCLIB record for Hearts2Hearts' Pretty Please contained one timed opening cue and 86 lines of plain text; this is now treated as incomplete timing rather than a complete synchronized song.

The 0.5 account flow has 31 focused navigation/redaction checks. Hardware testing completed direct Google sign-in with the system identity, verified `LOGGED_IN` in the actual player after a page reload and a cold restart following the 0.5 upgrade, opened the signed-in Library, and rendered all 93 LEMONADE lyric lines during playback. The normal Account flow returned automatically to Music; Cancel preserved the existing player page. The original diagnostic hang was our navigation filter blocking Google's regional `/accounts/SetSID` redirect, not a captured crash or ANR. External TCL-browser sign-in was also verified, but did not transfer to the player. The production activity accepts the exact regional host and logs no redirect query values.

Short TCL playback samples of LEMONADE around 32–43 seconds measured 0.53 seconds of JavaScript execution before these changes and 0.39 seconds in the final compatible build, over ten seconds each. Overall main-thread task time was 4.04 versus 4.45 seconds and frame pacing remained variable, so these samples do not establish an overall frame-rate improvement. They include profiling overhead and are not sustained results. Per-line motion, full-line fades, and even the decorative marker transition cost more on this TV, so the final design uses native scrolling and a static marker. Raw local samples are in `.tools/performance/`.

For sustained performance validation, run 15–30 minutes of playback and inspect memory and frame statistics:

```sh
.tools/android-sdk/platform-tools/adb shell dumpsys meminfo dev.ytmusictv
.tools/android-sdk/platform-tools/adb shell dumpsys gfxinfo dev.ytmusictv framestats
```

WebView rendering also runs in a separate process, so include its memory when profiling. Sustained TV RAM and frame-rate results have not been recorded yet.

Google documents its [sign-in browser requirements](https://developers.google.com/identity/siwg/best-practices). Android documents [TV remote and launcher requirements](https://developer.android.com/training/tv/get-started/create) and the [pinned build-tool compatibility](https://developer.android.com/build/releases/agp-8-7-0-release-notes).
