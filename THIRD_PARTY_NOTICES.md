# Third-party software

## Native preview runtime

The native preview is an independently implemented TV interface and player. It does not copy Cliamp, Metrolist, SimpMusic, InnerTubeX, or the Better Lyrics Chrome extension. Its native dependencies are pinned in `app/build.gradle`:

| Component | Pinned version | License and source |
| --- | --- | --- |
| NewPipeExtractor | `com.github.TeamNewPipe:NewPipeExtractor:v0.27.1` | GPL version 3 or later. [Upstream release](https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.27.1), [tagged source](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.27.1), [full license](licenses/NewPipeExtractor-GPL-3.0.txt). Obtained from JitPack; not from an unrelated repackaged extractor. |
| AndroidX Media3 | `1.5.1` | Apache 2.0. [Tagged source](https://github.com/androidx/media/tree/1.5.1), [license](https://github.com/androidx/media/blob/1.5.1/LICENSE). Uses ExoPlayer, HLS/DASH transport, and MediaSession; the UI uses native Android views. |
| AndroidX RecyclerView | `1.3.2` | Apache 2.0. [AndroidX source](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:recyclerview/), [release notes](https://developer.android.com/jetpack/androidx/releases/recyclerview#1.3.2). |
| Java API desugaring | `com.android.tools:desugar_jdk_libs_nio:2.1.5` | Apache 2.0. [Upstream](https://github.com/google/desugar_jdk_libs). Provides compatible Java library APIs on Android 11. |

NewPipeExtractor's published runtime dependencies are also packaged under their own licenses:

| Component | Version | License/source |
| --- | --- | --- |
| TeamNewPipe nanojson | `e9d656ddb49a412a5a0a5d5ef20ca7ef09549996` | Apache 2.0; copyright 2011 The nanojson Authors. The license is present in [source file headers](https://github.com/TeamNewPipe/nanojson/blob/e9d656ddb49a412a5a0a5d5ef20ca7ef09549996/src/main/java/com/grack/nanojson/JsonParser.java). |
| jsoup | `1.24.1` | MIT; [license](licenses/jsoup-MIT.txt), [upstream](https://github.com/jhy/jsoup). |
| JSR-305 annotations | `3.0.2` | Apache 2.0; [published metadata](https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom). |
| Protocol Buffers Java Lite | `4.36.2` | BSD 3-Clause; [license](licenses/protobuf-BSD-3-Clause.txt), [upstream](https://github.com/protocolbuffers/protobuf/tree/v36.2). |
| Mozilla Rhino and Rhino engine | `1.8.1` | MPL 2.0; [upstream release/source](https://github.com/mozilla/rhino/tree/Rhino1_8_1_Release), [license](licenses/Rhino-MPL-2.0.txt). Executes extracted player-script transforms; no Python or ffmpeg process is used. |

AndroidX support, Guava, and annotation dependencies retain their upstream licenses; the resolved Gradle dependency graph is authoritative. Preserve the pinned dependencies and their notices when producing a release.

Linking GPL-covered NewPipeExtractor changes the distribution obligations of the combined native APK. A distributed release needs a GPL-compatible license and corresponding source/build materials, with existing copyright and license notices retained. The Apache, MIT, BSD, and MPL components keep their own notices. Development and local testing here do not publish a release or export account credentials. No third-party OAuth client credentials are copied into this app.

## Legacy renderer and development tools

The bundled Better Lyrics renderer is Braccato 1.21.5, licensed under MIT.

- [Bundled license](app/src/main/assets/lyrics/LICENSE)
- [Pinned package and build provenance](app/src/main/assets/lyrics/PROVENANCE.json)
- Upstream: https://github.com/better-lyrics/braccato

The Chrome extension itself is not included. Renderer build dependencies are recorded in `app/src/main/assets/lyrics/build-src/package-lock.json`; they are development tools, not runtime app dependencies.

The optional Java compile check downloads `org.robolectric:android-all:11-robolectric-6757853`, licensed under Apache 2.0. That framework jar is used only locally for validation and is not packaged in the APK. Its metadata is at https://repo.maven.apache.org/maven2/org/robolectric/android-all/11-robolectric-6757853/android-all-11-robolectric-6757853.pom.

The Gradle wrapper and downloaded JDK/Android build tools retain their upstream licenses. They are build tools and are not part of the app runtime.
