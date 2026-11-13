# Native preview validation

14 November 2026. Tested on the paired TCL Android TV 11 (API 30),
`V8-R51AT01-LF1V126.020732`, 1920×1080 at 320 dpi. `/proc/meminfo`
reports 2 GiB RAM. This is an initial native preview, not sustained hardware qualification.

## Checks completed

| Check | Evidence/result |
| --- | --- |
| Build | `./scripts/build.sh assembleDebug lintDebug` passed with Java 17, SDK 35, one Gradle worker and a 1 GiB JVM heap. Recent cached integration builds: 43–44 seconds. |
| Native catalogue | Actual HTTPS/JSON search returned 11 LEMONADE/aespa results on the TV; no music website loaded. |
| Search input/focus | Hardware Enter with the IME closed submitted the query, focused the first result, and Right/OK opened its Actions menu. |
| Native voice search | The user confirmed that the in-app recognition path hears the TCL remote and submits a search. The earlier external speech window did not hear this remote. |
| Launcher | The Leanback launcher category resolves to NativeTvActivity and launches the native screen. |
| Native audio | Media3 session reached playing state, speed 1, with advancing position and buffered media for LEMONADE. |
| Lyrics | Native synchronized LRCLIB lines loaded with several surrounding lines, manual focus/line seeking, and explicit Follow. |
| Track actions | D-pad navigation opened Actions; Play next and Add to queue retained the current entry and its paused 141677 ms position. |
| Move | Up/OK moved the music-video entry before the song entry. Checkpoint order changed from A→B→C to A→C→B, preserving A. |
| Remove/Undo | Removed an upcoming entry, then restored the same queue-entry ID and placement. |
| Clear/Undo | Cleared both upcoming entries in one command, then restored both original IDs and order through group Undo. |
| Channel commands | Injected Android Channel Down events traversed C→B→A once per event. Physical TCL key delivery still needs a remote check. |
| Restore | Cold process restoration retained the shuffled queue IDs/order, started the media player idle, and resumed on explicit Media Play. |
| Home/resume | TV launcher became the resumed activity; playback paused. Returning retained the paused position. |
| Source transport | Actual Java resolver fetched MP4 audio and separate MP4 audio/video using HTTP 206 at initial and later byte ranges. |
| Native video | Realtek hardware decoder reached first frame; short sample rendered 709 frames with zero decoder-reported drops. A direct PixelCopy capture produced a real 320×180 video frame. Ordinary TV screencaps omit the hardware video plane. |
| Mode preservation | Video→Audio preserved exactly 46403 ms and paused state for the same queue entry. |
| Hidden video | Leaving Now Playing logged video-decoder disable while the media clock/audio continued. |
| Account session | The app-owned bootstrap reports `LOGGED_IN=true` after upgrade/restart. Liked playlist returned HTTP 200 with zero track rows; populated account-content access remains unverified. |
| Account window | Native Account opens SignInActivity; Back returns to the native screen and refreshes liked songs. No account-page screenshot was taken. |
| Offline tests | 509 native checks pass: 136 queue/checkpoint, 122 catalogue/bootstrap/message/album, 55 artwork URL, 84 stream-selection/budget and 112 WebM/VP9-header cases. Existing 4 LRC parser, 36 matching, 12 lyric-provider and 31 account-navigation cases also pass. |

Screenshots and raw local samples are in the ignored `.tools/design-qa/` directory.
They include search, track actions, native listening room, queue and reorder states.
Tests use public music metadata and never capture or print session cookies or signed media URLs.

## Resource sample and remaining checks

A short native audio/lyrics sample reported **112284 KiB total app PSS**
(about 110 MiB), including 12656 KiB Java heap, 14736 KiB native heap and
16737 KiB graphics. This is one `dumpsys meminfo` sample, not a peak or a
comparison against the previous player. A later sample after mode switches was
123120 KiB (about 120 MiB) app PSS. Vendor decoder services are outside that app
PSS figure. The debug APK was approximately 6.6 MiB.

Video content was inspected with a debug-only, one-shot PixelCopy capture of the
native surface. It writes only a bounded frame into app-private storage when
explicitly triggered, and has no recurring capture work. No account pages are captured.

Network-loss and expired-source recovery are implemented with bounded failure/retry
paths but have not been forced on the physical TV in this pass. A 30–60 minute
continuous soak, repeatable frame-time measurements, and physical remote-key
delivery remain qualification work. Source extraction uses guest access; private,
age/subscription/region-restricted tracks are not established by these tests.

Search and liked songs currently show one bounded response (up to 60 tracks).
Artist/album browsing and batch album queue actions are now implemented. Album
loading preserves the selected release's parameters and only reads its primary
track shelf; recommendations do not belong to that album. Collection loading is
bounded at 200 tracks and three continuation requests, with incomplete collections
labeled explicitly. Full playlist/library pagination and richer recommendations
remain later stages. Native voice search has been confirmed by the user.

## Artwork and artist/album follow-up

The old artwork path selected 120-pixel search thumbnails and capped decodes at
320 pixels despite 450–600-pixel cover presentation. Public CDN probes verified
the same Google cover at 120×120 and 640×640; a YouTube video thumbnail had a
1280×720 rendition. The TV logged a real 640×640 source and 640×640 decoded bitmap,
and its detailed EASY cover was visually inspected. The single-cover loader uses
a 4 MiB cache, a 2 MiB response cap, bounded region decoding, stale-request
cancellation, original-image fallback, and no hidden/video-mode requests.

Native artist search opened LE SSERAFIM, its releases list, and EASY on the TV.
Returning from Now Playing restored the album page. The live queue contains its
selected seven-track edition in order, including the two additional EASY versions.
The application preserves this existing queue during updates.

## Artwork recovery and remote shortcut follow-up

The provider now fills missing song-row artwork from the selected primary album
header, or from the selected album card when that header has no cover. Explicit
row artwork is preserved. A public Drama fixture contains six rows without
thumbnails; all six inherit its album cover in the offline regression. The loader
now clears an old image for an empty artwork key. A bounded metadata-only repair
also fills missing artwork in an existing queue without changing queue-entry IDs,
order, media source or playback position.

Version `0.7.3-native-preview` (version code 11) passed `assembleDebug` and
`lintDebug` and was installed on the TCL. Before repair, IVE's FLU showed stale
EASY artwork. The corrected foreground capture shows IVE EMPATHY artwork for
You Wanna Cry. All six existing IVE queue rows gained their missing thumbnails;
queue-entry IDs, order and the current entry were preserved during the repair.
The playhead advanced normally without resetting. Captures are
`.tools/design-qa/artwork-before-repair.png` and
`.tools/design-qa/artwork-repair-foreground.png`.

A Drama track loaded from its native album page was temporarily queued next.
Switching from IVE to Drama displayed the correct red Drama cover, and returning
to IVE restored the IVE EMPATHY cover. Both transitions were visually inspected
in `artwork-drama-switched.png` and `artwork-ive-returned.png`. The temporary
track was removed afterward; the original six queue IDs and their order were
verified intact, with artwork present for all six entries.

Physical capture from `BT_RC813_MA` recorded raw Linux scan codes **651** for Web,
**235** for the virtual-keyboard button, and **358** for Info. These are scan codes,
not Android key codes. The new app mappings request Search, the Search keyboard,
and Lyrics respectively, and apply only while the app receives those events.
The physical virtual-keyboard button was delivered to the new APK as Android
key code **4056**, scan code **235**; Search and its IME opened on the TV. The user
also confirmed Info opens Lyrics; that press arrived as Android key code **165**,
scan code **195**, with both Down and Up logged. Web opens the TV's browser and
did not appear in the app's shortcut log. The user requested leaving Web as-is
because the keyboard shortcut is sufficient. No global remapping or
Accessibility service is used.

## Fullscreen video and quality follow-up

Version `0.7.6-native-preview` (code 14) was built and installed on the TCL.
The final Gradle log reports `assembleDebug`, `lintDebug` and `BUILD SUCCESSFUL`.
Fullscreen expands the existing video surface/player without changing sources.
SurfaceFlinger reported a 1920×1080 display frame for a 1280×720 source; a bounded
PixelCopy capture confirmed real video content. The ordinary screencap can omit
this TV's hardware video plane. Controls, remote focus and the seek rail were
visually inspected on the device.

The final fullscreen controls include separate Rewind 10s/Forward 10s actions,
track Previous/Next, Quality, a seek rail and Exit full screen. Down from a
button focused the seek rail; Right moved the paused position by exactly
10,000 ms, retaining the current entry. Following the user's preference, the
first Back hides visible controls while retaining the fullscreen 1920×1080
stage; a second Back returns to the normal player and focuses Full screen.
Both steps were checked in the final APK. Search also restored the normal
layout and opened its keyboard. Hidden fullscreen controls suspend the
transport tick and lyric work; the controls hide task is a one-shot timer.

The quality selector offers Auto (maximum 720p), 360p, 480p, 720p and 1080p.
Public stream probes selected 360p and 1080p AVC/MP4 at 30 fps from one extraction,
with successful HTTP 206 byte-range responses. On the TCL, the actual displayed
video dimensions changed from 1920×1080 to 640×360. The controlled paused quality
change retained exactly 26,450 ms and the same queue-entry IDs/order. After
restoring 1080p and updating/restarting the app, the same current entry, queue,
1080p preference and 36,450 ms paused position were retained. These are initial
hardware checks; sustained 1080p performance has not been qualified.


## Optional 4K (2160p)

Version `0.8.0-native-preview` (code 15) passed Android assembly/lint and was
installed on the TCL. The quality menu includes opt-in 4K (2160p); Auto retains
its 720p ceiling. A small, temporary app_process query confirmed hardware
`OMX.realtek.video.decoder` support for VP9 profile 0 and 3840×2160, 3840×1634
and 3350×2160 at 24 fps. Software decoders reported unsupported for those sizes
and are excluded from the VP9 playback path. The temporary probe was removed.

Actual public prefixes for Drama (3840×1634) and LEMONADE (3350×2160) passed the
production WebM/profile-0/8-bit/SDR validation. Requests are limited to two exact
256 KiB ranges, with deadlines and interruption checks, only for explicit 4K.
Synthetic regression fixtures cover malformed/truncated parents, duplicate
colour overrides, HDR, encryption, lacing, dimension mismatch and probe budgets.
Raw media prefixes and signed source URLs are excluded from the repository.

On the TV, Chu~♡ (Live) [Cover] played as 3840×2160 VP9. The resource manager
associated the app process with `OMX.realtek.video.decoder`; released-codec
metrics confirmed VP9, width 3840 and height 2160. SurfaceFlinger showed a
3840×2160 source crop with BT.709 output. A bounded PixelCopy image contained a
real video frame (`.tools/design-qa/4k-rendered-frame.png`). The interface's
1920×1080 coordinates were not changed; these measurements establish decoded
4K buffers and do not independently measure the physical panel's pixel grid.

Leaving Now Playing for Search disabled video decoding and reported 4,394
rendered frames, zero dropped and zero skipped over a roughly 147-second codec
lifetime. Returning resumed video; current entry and queue IDs/order were
preserved, with the 2160p preference saved. This is a short hardware sample,
not sustained 4K qualification. An app-memory sample reported 143,903 KiB PSS
(about 141 MiB); vendor codec/service allocations are additional. Encoded
buffering targets 32 MiB with a 10–20-second window, not a total app memory cap.
