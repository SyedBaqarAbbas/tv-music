(function () {
  "use strict";

  // LRC line timing only: do not invent word timings when the provider has none.
  function parseLrc(text, durationSeconds) {
    var result = [], offset = 0;
    var raw = String(text || "").slice(0, 200000);
    var offsetMatch = raw.match(/\[offset:([+-]?\d+)\]/i);
    if (offsetMatch) offset = Number(offsetMatch[1]);
    raw.split(/\r?\n/).slice(0, 2000).forEach(function (line) {
      var stamp = /\[(\d{1,3}):([0-5]\d)(?:[.:](\d{1,3}))?\]/g;
      var matches = [], match;
      while (result.length + matches.length < 1000 && (match = stamp.exec(line)) !== null) {
        matches.push((Number(match[1]) * 60 + Number(match[2])) * 1000
          + Number((match[3] || "0").padEnd(3, "0")) + offset);
      }
      var words = line.replace(/\[[^\]]*\]/g, "").trim().slice(0, 1000);
      matches.forEach(function (time) {
        result.push({ startTimeMs: Math.max(0, time), durationMs: 0,
          words: words, isInstrumental: !words });
      });
    });
    result.sort(function (a, b) { return a.startTimeMs - b.startTimeMs; });
    var end = Number(durationSeconds) * 1000;
    result.forEach(function (line, index) {
      var next = index + 1;
      while (next < result.length && result[next].startTimeMs === line.startTimeMs) next++;
      var stop = next < result.length ? result[next].startTimeMs
        : isFinite(end) && end > line.startTimeMs ? end : line.startTimeMs + 5000;
      line.durationMs = Math.max(1, stop - line.startTimeMs);
    });
    return result;
  }

  if (typeof module !== "undefined" && module.exports) module.exports = { parseLrc: parseLrc };
  if (typeof document === "undefined" || window.TvMusic) return;

  var focused = null, overlay = null, lyrics = null, status = null, heading = null;
  var timer = null, activeTrack = "", suspended = false, latestRequest = null;
  var savedLyrics = null, savedPlain = "", savedStatus = "", plain = null, scroller = null;
  var transport = null, transportTitle = null, transportTime = null, transportPlay = null, transportSeek = null;
  var lastTransportSecond = -1;
  var SELECTOR = 'a[href],button,input,[role="button"],[role="tab"],[tabindex="0"]';

  function player() {
    var main = document.querySelector('#song-video video, #movie_player video, video.html5-main-video');
    if (main) return main;
    var media = document.querySelectorAll('video,audio');
    for (var i = 0; i < media.length; i++) {
      if (isFinite(media[i].duration) && media[i].duration > 0) return media[i];
    }
    return media[0] || null;
  }
  function clean(value) { return typeof value === 'string' ? value.trim().slice(0, 512) : ''; }
  function metadata() {
    var video = player();
    var md = navigator.mediaSession && navigator.mediaSession.metadata;
    var bar = document.querySelector("ytmusic-player-bar");
    var titleEl = bar && bar.querySelector(".title");
    var artistEl = bar && (bar.querySelector(".byline a") || bar.querySelector(".byline"));
    var title = clean(md && md.title) || clean(titleEl && titleEl.textContent);
    var artist = clean(md && md.artist) || clean(artistEl && artistEl.textContent).split(/\s*[•·]\s*/)[0];
    if (!title || !artist || !video || !isFinite(video.duration)) return null;
    // Player links are safer than the page URL while browsing during playback.
    var link = bar && bar.querySelector('a[href*="watch?v="]');
    var id = link ? link.getAttribute("href") : title + "\n" + artist;
    return { trackId: clean(id), title: title, artist: artist,
      album: clean(md && md.album), duration: Math.round(video.duration) };
  }

  function visible() { return !!overlay && !overlay.hidden; }
  function clearFocus() {
    if (focused) focused.classList.remove("tv-focused");
    focused = null;
  }
  function focus(element) {
    clearFocus();
    focused = element;
    element.classList.add("tv-focused");
    if (element.tabIndex < 0) element.tabIndex = 0;
    element.focus({ preventScroll: true });
    element.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "auto" });
  }
  function candidates() {
    var dialog = document.querySelector('tp-yt-paper-dialog[opened],[role="dialog"][aria-modal="true"]');
    var nodes = (dialog || document).querySelectorAll(SELECTOR);
    var list = [];
    for (var i = 0; i < nodes.length && i < 800; i++) {
      var el = nodes[i];
      if (el.disabled || el.closest('[aria-hidden="true"],#tv-lyrics-overlay')) continue;
      var rect = el.getBoundingClientRect();
      if (rect.width < 8 || rect.height < 8 || rect.bottom < -innerHeight
          || rect.top > innerHeight * 2 || rect.right < 0 || rect.left > innerWidth) continue;
      list.push({ el: el, rect: rect });
    }
    return list;
  }
  function move(direction) {
    if (visible()) {
      if (direction === "up" && scroller.scrollTop <= 0) return false;
      if (direction === "up" || direction === "down") {
        scroller.scrollTop += direction === "down" ? innerHeight * 0.4 : -innerHeight * 0.4;
      } else seek(direction === "left" ? -10 : 10);
      return true;
    }
    var list = candidates();
    if (!list.length) return false;
    if (!focused || !document.contains(focused)) { focus(list[0].el); return true; }
    var origin = focused.getBoundingClientRect();
    var ox = origin.left + origin.width / 2, oy = origin.top + origin.height / 2;
    var best = null, bestScore = Infinity;
    list.forEach(function (item) {
      if (item.el === focused) return;
      var dx = item.rect.left + item.rect.width / 2 - ox;
      var dy = item.rect.top + item.rect.height / 2 - oy;
      var primary = direction === "right" ? dx : direction === "left" ? -dx
        : direction === "down" ? dy : -dy;
      var cross = direction === "left" || direction === "right" ? Math.abs(dy) : Math.abs(dx);
      if (primary <= 3) return;
      var score = primary + cross * 2.5;
      if (score < bestScore) { best = item.el; bestScore = score; }
    });
    if (best) { focus(best); return true; }
    if (direction === "up") { clearFocus(); return false; }
    window.scrollBy(0, direction === "down" ? innerHeight * 0.65 : 0);
    return true;
  }
  function activate() {
    if (visible()) return media("playpause");
    if (!focused || !document.contains(focused)) {
      var list = candidates();
      if (!list.length) return false;
      focus(list[0].el);
    }
    focused.click();
    return true;
  }
  function seek(delta) {
    var video = player();
    if (video && isFinite(video.duration)) {
      video.currentTime = Math.max(0, Math.min(video.duration, video.currentTime + delta));
    }
  }
  function media(action) {
    var video = player();
    if (action === "playpause" || action === "play" || action === "pause") {
      if (!video) return false;
      if (action === "pause") { video.pause(); return true; }
      if (action === "play" || video.paused) {
        var promise = video.play();
        if (promise && promise.catch) promise.catch(function () {
          if (status && visible()) status.textContent = "Select a song in Browse to start playback.";
        });
      } else video.pause();
      return true;
    }
    var button = document.querySelector(action === "next"
      ? 'ytmusic-player-bar .next-button' : 'ytmusic-player-bar .previous-button');
    if (button) button.click();
    return !!button;
  }
  // Cursor edge scrolling: inspect only the ancestor chain under the pointer.
  // Fractions keep native pixels independent of CSS pixels and page scaling.
  function scrollAt(x, y, dx, dy) {
    var node = document.elementFromPoint(Math.max(0, Math.min(innerWidth - 1, x * innerWidth)),
      Math.max(0, Math.min(innerHeight - 1, y * innerHeight)));
    // At the screen edge the pointer can sit over the fixed controls or header.
    // The lyrics scroller still owns vertical scrolling while its overlay is open.
    if (visible() && scroller && !scroller.contains(node)) node = scroller;
    var sx = dx * innerWidth, sy = dy * innerHeight;
    function scrollChain(element) {
      while (element && element !== document.documentElement) {
        var style = getComputedStyle(element);
        var scrollX = sx && /(auto|scroll)/.test(style.overflowX) && element.scrollWidth > element.clientWidth;
        var scrollY = sy && /(auto|scroll)/.test(style.overflowY) && element.scrollHeight > element.clientHeight;
        if (scrollX || scrollY) {
          var beforeX = element.scrollLeft, beforeY = element.scrollTop;
          if (scrollX) element.scrollLeft += sx;
          if (scrollY) element.scrollTop += sy;
          if (element.scrollLeft !== beforeX || element.scrollTop !== beforeY) return true;
        }
        element = element.parentElement;
      }
      return false;
    }
    if (scrollChain(node)) return true;
    if (visible()) return false;
    // Fixed headers/transport can cover the page's scrolling content at an edge.
    // Sample inside that content before deciding an upward move should leave it.
    if (sy && scrollChain(document.elementFromPoint(Math.max(1, Math.min(innerWidth - 1, x * innerWidth)),
        innerHeight * 0.5))) return true;
    var root = document.scrollingElement || document.documentElement;
    var left = root.scrollLeft, top = root.scrollTop;
    root.scrollLeft += sx;
    root.scrollTop += sy;
    return root.scrollLeft !== left || root.scrollTop !== top;
  }
  function formatTime(seconds) {
    if (!isFinite(seconds) || seconds < 0) return '--:--';
    var whole = Math.floor(seconds);
    return Math.floor(whole / 60) + ':' + String(whole % 60).padStart(2, '0');
  }
  function buildTransport() {
    transport = document.createElement('section');
    transport.id = 'tv-transport';
    transport.hidden = true;
    transport.setAttribute('aria-label', 'Playback controls');
    transportTitle = document.createElement('span');
    transportTitle.className = 'tv-transport-title';
    function button(label, action) {
      var el = document.createElement('button');
      el.type = 'button'; el.textContent = label;
      el.addEventListener('click', function () { media(action); updateTransport(true); });
      transport.appendChild(el);
      return el;
    }
    button('Previous', 'previous');
    transportPlay = button('Play', 'playpause');
    button('Next', 'next');
    transport.appendChild(transportTitle);
    transportSeek = document.createElement('input');
    transportSeek.type = 'range'; transportSeek.min = '0'; transportSeek.max = '1000'; transportSeek.value = '0';
    transportSeek.setAttribute('aria-label', 'Seek through song');
    transportSeek.addEventListener('input', function () {
      var video = player();
      if (video && isFinite(video.duration)) video.currentTime = Number(transportSeek.value) * video.duration / 1000;
      updateTransport(true);
    });
    transportTime = document.createElement('span');
    transportTime.className = 'tv-transport-time';
    transport.append(transportSeek, transportTime);
    document.body.appendChild(transport);
  }
  function updateTransport(force) {
    if (suspended || document.hidden) return;
    var video = player();
    var ready = !!video && isFinite(video.duration) && video.duration > 0;
    if (!transport && !ready) return;
    if (!transport) buildTransport();
    transport.hidden = !ready;
    document.documentElement.classList.toggle('tv-transport-ready', ready);
    if (!ready) return;
    var second = Math.floor(video.currentTime);
    transportPlay.textContent = video.paused ? 'Play' : 'Pause';
    if (!force && second === lastTransportSecond) return;
    lastTransportSecond = second;
    var request = metadata();
    transportTitle.textContent = request ? request.title : 'Now playing';
    transportTime.textContent = formatTime(video.currentTime) + ' / ' + formatTime(video.duration);
    transportSeek.value = String(Math.round(video.currentTime * 1000 / video.duration));
  }
  ['timeupdate', 'loadedmetadata', 'durationchange', 'play', 'pause', 'emptied'].forEach(function (name) {
    document.addEventListener(name, function (event) {
      if (event.target === player()) updateTransport(name !== 'timeupdate');
    }, true);
  });
  function tick() {
    if (!lyrics || !visible() || suspended || document.hidden) return;
    var video = player();
    var playing = !!video && !video.paused && !video.ended;
    if (lyrics.playing !== playing) lyrics.playing = playing;
    lyrics.currentTime = video && isFinite(video.currentTime) ? video.currentTime : 0;
  }
  function stopClock() {
    if (timer !== null) clearInterval(timer);
    timer = null;
    if (lyrics) lyrics.playing = false;
  }
  function startClock() {
    stopClock();
    if (visible() && !suspended && !document.hidden) { tick(); timer = setInterval(tick, 250); }
  }
  function buildOverlay() {
    overlay = document.createElement("section");
    overlay.id = "tv-lyrics-overlay";
    overlay.hidden = true;
    overlay.setAttribute("aria-label", "Now playing lyrics");
    var label = document.createElement("div");
    label.className = "tv-eyebrow";
    label.textContent = "NOW PLAYING";
    heading = document.createElement("h1");
    status = document.createElement("p");
    status.className = "tv-lyrics-status";
    scroller = document.createElement("div");
    scroller.className = "tv-lyrics-scroll";
    plain = document.createElement("div");
    plain.className = "tv-plain-lyrics";
    var hint = document.createElement("footer");
    hint.textContent = "Arrows Move cursor   ·   OK Click   ·   At an edge Scroll   ·   Menu Toolbar";
    overlay.append(label, heading, status, scroller, hint);
    document.body.appendChild(overlay);
  }
  function mountRenderer() {
    if (lyrics || !window.customElements || !customElements.get("braccato-lyrics")) return;
    // YouTube installs an ES5 custom-elements adapter that calls constructors as
    // functions. Construct the registered ES class directly so that adapter does
    // not turn it into an unupgraded HTMLUnknownElement.
    var LyricsElement = customElements.get("braccato-lyrics");
    lyrics = new LyricsElement();
    lyrics.theme = window.BraccatoTV ? window.BraccatoTV.theme : "/* blyrics-letter-wave = false; */";
    lyrics.tickOptions = { smoothScroll: false, followPausedSeeks: true };
    lyrics.host = { getScrollElement: function () { return scroller; } };
    lyrics.addEventListener("braccato:line-click", function (event) {
      var video = player();
      if (video && isFinite(event.detail.timeS)) video.currentTime = event.detail.timeS;
    });
    lyrics.addEventListener("braccato:error", function () {
      status.textContent = "Lyrics renderer could not start. Try updating Android System WebView.";
    });
    scroller.appendChild(lyrics);
    if (savedLyrics) lyrics.lyrics = savedLyrics;
  }
  function closeLyrics() {
    if (!visible()) return false;
    stopClock();
    overlay.hidden = true;
    if (lyrics) { lyrics.remove(); lyrics = null; }
    return true;
  }
  function toggleLyrics() {
    if (closeLyrics()) return false;
    if (!overlay) buildOverlay();
    overlay.hidden = false;
    clearFocus();
    mountRenderer();
    status.textContent = savedStatus || (latestRequest ? "Finding lyrics…" : "Play a song to load lyrics.");
    if (savedPlain) { plain.textContent = savedPlain; scroller.appendChild(plain); }
    getLyricsRequest();
    startClock();
    return true;
  }
  function getLyricsRequest() {
    if (!visible()) return null;
    var request = metadata();
    if (!request) return null;
    var key = request.trackId + "\n" + request.title + "\n" + request.artist
      + "\n" + request.album + "\n" + request.duration;
    if (key !== activeTrack) {
      activeTrack = key;
      savedLyrics = null;
      savedPlain = "";
      savedStatus = "Finding lyrics…";
      plain.remove();
      if (lyrics) lyrics.lyrics = [];
      scroller.scrollTop = 0;
      heading.textContent = request.title + " · " + request.artist;
      status.textContent = "Finding lyrics…";
    }
    latestRequest = request;
    return request;
  }
  function receiveLyrics(payload) {
    var request = getLyricsRequest();
    if (!request || payload.trackId !== request.trackId || payload.title !== request.title
        || payload.artist !== request.artist) return false;
    savedLyrics = null;
    savedPlain = "";
    plain.remove();
    if (lyrics) lyrics.lyrics = [];
    status.textContent = "Lyrics by LRCLIB";
    if (payload.status === "synced") {
      savedLyrics = parseLrc(payload.syncedLyrics, request.duration);
      if (lyrics && savedLyrics.length) { lyrics.lyrics = savedLyrics; tick(); }
      else status.textContent = "Timed lyrics could not be displayed on this WebView.";
    } else if (payload.status === "plain") {
      savedPlain = String(payload.plainLyrics || "").slice(0, 200000);
      plain.textContent = savedPlain;
      scroller.appendChild(plain);
      status.textContent = payload.timingMatched === false
        ? "Lyrics by LRCLIB · Untimed: different recording" : "Lyrics by LRCLIB · Unsynced";
    } else status.textContent = payload.message || "Lyrics are unavailable for this recording.";
    savedStatus = status.textContent;
    return true;
  }
  function search() {
    closeLyrics();
    var input = document.querySelector('ytmusic-search-box input,input[type="search"]');
    if (input) { input.focus(); input.click(); return true; }
    var button = document.querySelector('ytmusic-search-box [role="button"],ytmusic-search-box tp-yt-paper-icon-button');
    if (button) { button.click(); return true; }
    return false;
  }
  document.addEventListener("visibilitychange", function () {
    if (document.hidden) stopClock(); else { startClock(); updateTransport(true); }
  });
  window.TvMusic = {
    move: move, activate: activate, search: search, media: media, scrollAt: scrollAt,
    toggleLyrics: toggleLyrics, isLyricsVisible: visible,
    getLyricsRequest: getLyricsRequest, receiveLyrics: receiveLyrics,
    back: closeLyrics,
    home: function () { location.href = "https://music.youtube.com/"; },
    suspend: function () { suspended = true; stopClock(); var video = player(); if (video) video.pause(); },
    resume: function () { suspended = false; startClock(); updateTransport(true); }
  };
  updateTransport(true);
})();
