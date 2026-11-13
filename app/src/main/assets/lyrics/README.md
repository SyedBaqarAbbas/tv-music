# Vendored Better Lyrics renderer

`core.js` and `core.css` bundle `@braccato/core` 1.21.5, the standalone renderer
maintained by the Better Lyrics authors. The renderer is MIT licensed; its notice
is preserved in `LICENSE`. No Chrome extension code is included.

Upstream: <https://github.com/better-lyrics/braccato>

The npm publication's provenance identifies source commit
`48b6d470ac7fd413932cd7b08287505070a89f68`. `PROVENANCE.json` records the registry
tarball, integrity hash, generated asset hashes and pinned build tool.

## Use

Load `core.css`, then `core.js` as a normal script. The IIFE registers
`<braccato-lyrics>` and exposes `BraccatoTV.theme` and `BraccatoTV.version`.

```js
const view = document.createElement("braccato-lyrics");
view.theme = BraccatoTV.theme; // Also disables per-letter DOM generation.
view.tickOptions = { smoothScroll: false, followPausedSeeks: true };
view.lyrics = [
  { startTimeMs: 0, durationMs: 4000, words: "A lyric line" },
  { startTimeMs: 4000, durationMs: 4000, words: "The next line" },
];
document.querySelector("#lyrics-scroller").appendChild(view);
view.playing = true;
view.currentTime = 2.5; // Seconds. Write from the actual player clock.
view.addEventListener("braccato:line-click", event => {
  player.seekTo(event.detail.timeS);
});
```

Give the scroller a constrained height and `overflow-y: auto`. The element itself
must have `display: block` (already supplied by this TV theme). Keep the element
mounted; assigning `lyrics` replaces the current song. Assigning an empty array
clears it. Set `playing` only when play state changes, then update `currentTime`
from the player. The component does not start a polling loop without `source`.

Each lyric takes `startTimeMs`, `durationMs` and `words`; optional `parts` use the
same fields for word or syllable timing. The clock property and seek event use
**seconds**, while lyric timestamps use **milliseconds**. This theme removes
upstream's default timing advance, so line seeks use the supplied timestamp.

An optional `source` property can bind an HTML audio/video element or its CSS
selector. In that mode the component reads its clock itself and performs seeks.
Do not also drive `currentTime` manually when a source is bound.

Useful events: `braccato:lyrics-loaded` (`lineCount`, `syncType`),
`braccato:line-click` (`timeS`), `braccato:scroll-state` (`userScrolling`) and
`braccato:error` (`phase`, `error`). They bubble. `view.status` reports `rendering`
after a successful mount. Add the error listener before mounting, because mount
errors are reported in a microtask.

## Older WebView support

JavaScript syntax is transpiled for Chrome 80. Small conditional shims cover
`Array.at`, `Array.findLast`, `Object.hasOwn` and `String.replaceAll`. Required
browser features include custom elements, `ResizeObserver`, `DOMMatrix`,
`Element.animate`, Unicode property regular expressions and `Intl.Locale`, which
are present in Chrome 80. `IntersectionObserver` enables upstream culling.

Modern upstream motion also uses CSS `translate`, `linear()` easing and newer
color functions. Transpiling JavaScript cannot add those browser capabilities.
This TV theme supplies sRGB colors and disables swipe/glow/letter/line effects;
keep `smoothScroll: false` to avoid that motion path on older WebViews. Timed
highlight colors and automatic line following still work. The component is not
certified against an actual Chrome 80 device; test the target TV's WebView.

The checked-in bundle was exercised in headless Firefox with all four shimmed
built-ins removed: mounting, timed word highlights, no per-letter DOM, and an
8-second line-seek event passed. No renderer network calls or web fonts are used.

## Rebuild

Requires Node.js 18+ and npm with network access:

```sh
node app/src/main/assets/lyrics/rebuild.mjs
```

The script runs `npm ci` from the pinned lockfile in a temporary directory,
bundles both assets, copies the MIT license and rewrites `PROVENANCE.json`.
It removes temporary dependencies afterwards. The Android app needs none of
these build tools at runtime.
