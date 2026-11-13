# Third-party software

The bundled Better Lyrics renderer is Braccato 1.21.5, licensed under MIT.

- [Bundled license](app/src/main/assets/lyrics/LICENSE)
- [Pinned package and build provenance](app/src/main/assets/lyrics/PROVENANCE.json)
- Upstream: https://github.com/better-lyrics/braccato

The Chrome extension itself is not included. Renderer build dependencies are recorded in `app/src/main/assets/lyrics/build-src/package-lock.json`; they are development tools, not runtime app dependencies.

The optional Java compile check downloads `org.robolectric:android-all:11-robolectric-6757853`, licensed under Apache 2.0. That framework jar is used only locally for validation and is not packaged in the APK. Its metadata is at https://repo.maven.apache.org/maven2/org/robolectric/android-all/11-robolectric-6757853/android-all-11-robolectric-6757853.pom.

The Gradle wrapper and downloaded JDK/Android build tools retain their upstream licenses. They are build tools and are not part of the app runtime.
