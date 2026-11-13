# TV Music

An experimental standalone Android TV launcher for the YouTube Music website, with remote navigation and the actual Better Lyrics renderer, [Braccato](https://github.com/better-lyrics/braccato). Initial hardware target: your TCL Android TV 11, firmware `V8-R51AT01-LF1V126.020732`.

Version 0.5 adds an in-app Google account window using the player's normal WebView profile. Sign-in was completed on this TCL with the unchanged system user agent, then verified in the actual player alongside synchronized lyrics. The charcoal interface includes a collapsible command strip, artwork-and-lyrics listening room, native lyric glides, and compact playback controls. Cursor movement and paused work are kept small, and incomplete timed lyric fragments fall back to full text. This remains an experimental website-based player; extended performance and compatibility testing is still needed. There is no standalone YouTube Music playback API integrated here.

## Lightweight development

The app uses Java, platform Android views, one playback WebView, and a temporary account WebView that is destroyed on return. No Compose, React, Electron, or bundled browser. Gradle runs one worker with a 1 GB heap and stops after the build. That is a JVM heap limit, not a limit on total system RAM. Android 8.0+ is the initial minimum; your TV runs Android 11. An updated Android System WebView is needed; the bundled JavaScript targets Chrome 80 syntax, which is not a guarantee that the current YouTube Music site supports that engine.

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

Account → Sign in here opens Google's HTTPS pages in an account window with the current origin and path visible. Use the remote cursor and TV keyboard; Cancel returns to the player. When the account window returns to Music, it closes and refreshes the player's Home page using the same normal WebView cookie profile. The music website determines the signed-in state; a completed navigation alone is not treated as proof of login.

The account window uses the unmodified system user agent, no injected script or JavaScript bridge, and no credential reading or cookie export/import. Its navigation permits HTTPS Google/YouTube pages and the exact regional host `accounts.google.com.pk`, which this TV's sign-in flow uses. Query strings and fragments are omitted from address labels and diagnostic logs. The external browser option keeps its own separate session. More → Open YouTube launches the official TV app separately.

## Lyrics and resource limits

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
python3 scripts/check-browser.py
```

Parser checks cover repeated timestamps, offsets, instrumental gaps, malformed tags, missing duration, and a maximum of 1,000 rendered lines. The Java check compiles the app against an Apache-licensed Android 11 framework jar, with a 512 MB compiler heap; this check does not package an APK or run Android lint. The browser check uses an installed Firefox and Python's standard library, with no browser download; it tests the real renderer, metadata, stale results, controls, timer cleanup, and the offline preview. Firefox passing does not certify Android WebView behavior. The renderer has a pinned provenance manifest and a standalone rebuild under `app/src/main/assets/lyrics/rebuild.mjs`. See [third-party notices](THIRD_PARTY_NOTICES.md).

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
