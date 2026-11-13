# TV Music

An experimental standalone Android TV launcher for the YouTube Music website, with remote navigation and the actual Better Lyrics renderer, [Braccato](https://github.com/better-lyrics/braccato). Initial hardware target: your TCL Android TV 11, firmware `V8-R51AT01-LF1V126.020732`.

Version 0.3 adds a warm charcoal interface, a collapsible command strip, an artwork-and-lyrics listening room, and compact playback controls. It has been installed and visually reviewed on your TCL. It remains an experimental player; extended performance and compatibility testing is still needed. Google sign-in in embedded WebViews is unsupported; opening a browser does not transfer its session into this app. There is no official standalone YouTube Music playback API integrated here.

## Lightweight development

The app uses Java, platform Android views, and one system WebView. No Compose, React, Electron, or bundled browser. Gradle runs one worker with a 1 GB heap and stops after the build. That is a JVM heap limit, not a limit on total system RAM. Android 8.0+ is the initial minimum; your TV runs Android 11. An updated Android System WebView is needed; the bundled JavaScript targets Chrome 80 syntax, which is not a guarantee that the current YouTube Music site supports that engine.

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

## Try it on your TCL

After enabling developer options and network debugging on the TV, use the address and port displayed by its settings. If the TV requires wireless pairing, pair first; if it exposes legacy network ADB, connect directly. Keep the laptop and TV on the same network.

```sh
.tools/android-sdk/platform-tools/adb connect TV_IP:PORT
.tools/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
.tools/android-sdk/platform-tools/adb shell am start -n dev.ytmusictv/.MainActivity
```

The app also appears in the TV launcher. The directional pad moves a visible cursor; hold an arrow to move faster, and press OK to click. Keep moving at an edge to scroll. Menu toggles the command strip; Hide or Down collapses it to the corner Controls button. Its preference is remembered. Search opens the TV keyboard and navigates within YouTube Music to preserve playback. Home in the command strip also preserves playback; the remote's system Home button pauses the app.

Lyrics, or the lyrics icon in the playback strip, opens the listening room; Back returns to browsing. Previous, Play/Pause, Next, the seek rail, and elapsed/total times remain visible. Clicking a timed lyric seeks to that line; Follow song restores automatic scrolling after browsing lyrics manually. Missing or failed lyrics have a Try again action. Artwork has a local fallback and uses modest thumbnail sizes.

Account explains the sign-in limitation and can launch the installed official YouTube TV app, where choosing Music provides Google's account experience. That app runs separately from this lyrics view. Browser-sign-in experimentation is deferred; no user-agent disguise, credential collection, or cookie importing is implemented.

## Lyrics and resource limits

- Braccato 1.21.5 is bundled locally, approximately 104 KB JavaScript and 19 KB CSS before APK compression. Its MIT license and reproducible build are included; the GPL Chrome extension itself is not copied.
- The TV theme disables letter wave, glow, wobble, and complex scroll effects. The display samples the actual media clock four times per second while visible. It disconnects the renderer and stops the clock when closed. The cursor redraws only on input; the playback strip uses video events and does not introduce an idle timer.
- A native network worker fetches lyrics from [LRCLIB](https://lrclib.net/docs). It tries an exact match, a cleaned title/artist lookup, and a filtered search when needed: at most three sequential requests. It sends the song title, artist, album if known, and duration. There is one worker, one queued request, eight cached tracks, and a 256 KB response limit. Errors and missing lyrics have explicit states; rate-limit cooldowns are honored. A recording whose duration differs by more than three seconds is shown as untimed lyrics, rather than guessing its timing.
- This first provider uses line-synced LRC, or plain lyrics when timing is absent. Word timing, translations, and account/library access are not implemented. A matching recording is not always available.
- YouTube Music controls the media it serves. Using its site can still load video and consume substantial memory; a small APK alone does not establish low runtime memory use. Website markup changes may require adapting the remote/metadata selectors.

## Validation

```sh
node --test tests/*.test.cjs
node --check app/src/main/assets/tv.js
./scripts/check-java.sh
./scripts/check-matcher.sh
python3 scripts/check-browser.py
```

Parser checks cover repeated timestamps, offsets, instrumental gaps, malformed tags, missing duration, and a maximum of 1,000 rendered lines. The Java check compiles the app against an Apache-licensed Android 11 framework jar, with a 512 MB compiler heap; this check does not package an APK or run Android lint. The browser check uses an installed Firefox and Python's standard library, with no browser download; it tests the real renderer, metadata, stale results, controls, timer cleanup, and the offline preview. Firefox passing does not certify Android WebView behavior. The renderer has a pinned provenance manifest and a standalone rebuild under `app/src/main/assets/lyrics/rebuild.mjs`. See [third-party notices](THIRD_PARTY_NOTICES.md).

Hardware checks on this TCL use Android TV 11 and Android System WebView 157. The visible cursor and complete playback strip have been observed on-device; aespa's LEMONADE lyrics were fetched and rendered. YouTube's ES5 custom-element adapter requires constructing Braccato's registered class directly, rather than using `document.createElement` for that tag.

The 0.3 review includes 134 Firefox checks, four parser tests, 25 matching checks, Android compilation, and Android lint. Physical TV checks cover collapsed/expanded controls, Search and cancellation, playback-preserving Search/Home, Play/Pause, Next/Previous, seeking and lyric-line seeks, artwork, lyrics open/close, Home/resume, the Account/More/Remote guide dialogs, and network error/retry recovery. Untimed and error lyric presentations were also injected as controlled fixtures, visually reviewed, and restored through the real native lyric lookup. This does not certify Google account sign-in or every upstream website variation.

The TV review caught a host-page CSS sizing mismatch, an unwanted before-unload prompt during Search, and the initial dialog focus indicator; these were corrected. Local screenshots and JSON check reports are under ignored `.tools/design-qa/`.

For sustained performance validation, run 15–30 minutes of playback and inspect memory and frame statistics:

```sh
.tools/android-sdk/platform-tools/adb shell dumpsys meminfo dev.ytmusictv
.tools/android-sdk/platform-tools/adb shell dumpsys gfxinfo dev.ytmusictv framestats
```

WebView rendering also runs in a separate process, so include its memory when profiling. Sustained TV RAM and frame-rate results have not been recorded yet.

Google documents its [restriction on embedded sign-in](https://developers.google.com/identity/siwg/best-practices). Android documents [TV remote and launcher requirements](https://developer.android.com/training/tv/get-started/create) and the [pinned build-tool compatibility](https://developer.android.com/build/releases/agp-8-7-0-release-notes).
