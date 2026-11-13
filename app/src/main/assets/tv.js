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
  document.documentElement.classList.add('tv-app');

  var focused = null, overlay = null, lyrics = null, status = null, heading = null;
  var timer = null, activeTrack = "", suspended = false, latestRequest = null;
  var savedLyrics = null, savedPlain = "", savedStatus = "", plain = null, scroller = null;
  var transport = null, transportTitle = null, transportTime = null, transportPlay = null, transportSeek = null;
  var transportArtist = null, transportArtwork = null, lyricsArtwork = null, artistLabel = null;
  var lyricsBody = null, lyricsRetry = null, lyricsFollow = null, emptyState = null, retryNonce = 0;
  var lastArtwork = '', lastTrackStamp = '', renderFailure = false;
  var lastTransportSecond = -1;
  var cachedMedia = null, cachedBar = null, cachedTitle = null, cachedArtist = null, cachedSitePlayer = null;
  var lastLyricTime = -1, lastLyricPlaying = null;
  var motionQuery = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
  var smoothLyrics = !!(window.CSS && CSS.supports && CSS.supports('scroll-behavior', 'smooth')
    && !(motionQuery && motionQuery.matches));
  document.documentElement.classList.toggle('tv-smooth-lyrics', smoothLyrics);
  function updateMotionPreference() {
    smoothLyrics = !!(window.CSS && CSS.supports && CSS.supports('scroll-behavior', 'smooth')
      && !(motionQuery && motionQuery.matches));
    document.documentElement.classList.toggle('tv-smooth-lyrics', smoothLyrics);
    if (!smoothLyrics) instantScroll(scroller);
  }
  if (motionQuery) {
    if (motionQuery.addEventListener) motionQuery.addEventListener('change', updateMotionPreference);
    else if (motionQuery.addListener) motionQuery.addListener(updateMotionPreference);
  }
  var SELECTOR = 'a[href],button,input,[role="button"],[role="tab"],[tabindex="0"]';

  function player() {
    if (cachedMedia && cachedMedia.isConnected
        && cachedMedia.matches('#song-video video, #movie_player video, video.html5-main-video')) return cachedMedia;
    var main = document.querySelector('#song-video video, #movie_player video, video.html5-main-video');
    if (main) return (cachedMedia = main);
    var media = document.querySelectorAll('video,audio');
    for (var i = 0; i < media.length; i++) {
      if (isFinite(media[i].duration) && media[i].duration > 0) return (cachedMedia = media[i]);
    }
    return (cachedMedia = media[0] || null);
  }
  function sitePlayer() {
    if (!cachedSitePlayer || !cachedSitePlayer.isConnected) cachedSitePlayer = document.querySelector('#movie_player');
    return cachedSitePlayer;
  }
  function clean(value) { return typeof value === 'string' ? value.trim().slice(0, 512) : ''; }
  function metadata() {
    var video = player();
    var md = navigator.mediaSession && navigator.mediaSession.metadata;
    if (!cachedBar || !cachedBar.isConnected) cachedBar = document.querySelector('ytmusic-player-bar');
    var bar = cachedBar;
    if (!cachedTitle || !cachedTitle.isConnected) cachedTitle = bar && bar.querySelector('.title');
    if (!cachedArtist || !cachedArtist.isConnected || cachedArtist.classList.contains('byline')) {
      cachedArtist = bar && (bar.querySelector('.byline a') || bar.querySelector('.byline'));
    }
    var titleEl = cachedTitle;
    var artistEl = cachedArtist;
    var title = clean(md && md.title) || clean(titleEl && titleEl.textContent);
    var artist = clean(md && md.artist) || clean(artistEl && artistEl.textContent).split(/\s*[•·]\s*/)[0];
    if (!title || !artist || !video || !isFinite(video.duration)) return null;
    // Player links are safer than the page URL while browsing during playback.
    var link = bar && bar.querySelector('a[href*="watch?v="]');
    var videoId = '';
    try {
      var api = sitePlayer();
      var details = api && api.getVideoData && api.getVideoData();
      videoId = clean(details && details.video_id);
    } catch (error) { }
    var id = link ? link.getAttribute("href") : videoId || title + "\n" + artist;
    return { trackId: clean(id), title: title, artist: artist,
      album: clean(md && md.album), duration: Math.round(video.duration), retryNonce: retryNonce };
  }

  function node(tag, className, text) {
    var element = document.createElement(tag);
    if (className) element.className = className;
    if (text) element.textContent = text;
    return element;
  }
  var iconPaths = {
    play: 'M9 5l11 7-11 7z', pause: 'M8 5v14M16 5v14',
    previous: 'M6 5v14M19 5L8 12l11 7z', next: 'M18 5v14M5 5l11 7-11 7z',
    lyrics: 'M4 6h16M4 12h11M4 18h14', back: 'M14 5l-7 7 7 7',
    retry: 'M20 7v5h-5M19 12a7 7 0 1 0-2 5', follow: 'M12 3v18M3 12h18M8 8l4 4 4-4',
    note: 'M9 17V5l10-2v12M9 8l10-2M9 17c0 2-2 3-4 3s-3-1-3-2 2-3 4-3 3 1 3 2M19 15c0 2-2 3-4 3s-3-1-3-2 2-3 4-3 3 1 3 2'
  };
  function setIcon(button, kind) {
    while (button.firstChild) button.removeChild(button.firstChild);
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true');
    var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', iconPaths[kind]); path.setAttribute('fill', kind === 'play' ? 'currentColor' : 'none');
    path.setAttribute('stroke', 'currentColor'); path.setAttribute('stroke-width', '1.7');
    path.setAttribute('stroke-linejoin', 'round'); path.setAttribute('stroke-linecap', 'round');
    svg.appendChild(path); button.appendChild(svg);
  }
  function iconButton(label, kind, action) {
    var button = node('button', 'tv-icon-button'); button.type = 'button';
    button.setAttribute('aria-label', label); button.title = label;
    setIcon(button, kind); button.addEventListener('click', action); return button;
  }
  function artwork(className) {
    var frame = node('div', 'tv-artwork ' + className);
    var placeholder = node('span', 'tv-artwork-placeholder');
    setIcon(placeholder, 'note');
    var img = node('img'); img.alt = ''; img.decoding = 'async';
    img.addEventListener('error', function () { img.hidden = true; frame.classList.add('tv-artwork-empty'); });
    img.addEventListener('load', function () { img.hidden = false; frame.classList.remove('tv-artwork-empty'); });
    img.hidden = true; frame.classList.add('tv-artwork-empty');
    frame.append(placeholder, img); return frame;
  }
  function artworkUrl() {
    var md = navigator.mediaSession && navigator.mediaSession.metadata;
    var image = md && md.artwork && md.artwork[0];
    var barImage = document.querySelector('ytmusic-player-bar img');
    var source = clean(image && image.src) || clean(barImage && barImage.getAttribute('src'));
    if (!source || source.indexOf('data:') === 0) {
      try {
        var api = sitePlayer();
        var response = api && api.getPlayerResponse && api.getPlayerResponse();
        var thumbnail = response && response.videoDetails && response.videoDetails.thumbnail;
        var thumbnails = thumbnail && thumbnail.thumbnails;
        if (thumbnails && thumbnails.length) {
          var selected = thumbnails[0];
          for (var i = 0; i < thumbnails.length; i++) {
            if (thumbnails[i].width <= 512) selected = thumbnails[i];
          }
          source = clean(selected.url);
        }
      } catch (error) { }
    }
    try {
      var url = new URL(source, location.href);
      if (!source || url.protocol !== 'https:') return '';
      // Size an existing YouTube thumbnail down rather than decoding a 4K image.
      return url.href.replace(/=w\d+-h\d+[^?#]*/, '=w480-h480-l90-rj');
    } catch (error) { return ''; }
  }
  function updateArtwork(force) {
    var source = artworkUrl();
    if (!force && source === lastArtwork) return;
    lastArtwork = source;
    [transportArtwork, lyricsArtwork].forEach(function (frame) {
      if (!frame) return;
      var image = frame.querySelector('img');
      if (source) { if (image.getAttribute('src') !== source) image.src = source; }
      else { image.removeAttribute('src'); image.hidden = true; frame.classList.add('tv-artwork-empty'); }
    });
  }
  function notifyLyricsState() {
    if (window.__musicForTvNative) window.prompt('tv-music:lyrics-state', visible() ? 'on' : 'off');
  }
  function setChromeExpanded(expanded) {
    document.documentElement.classList.toggle('tv-chrome-expanded', !!expanded);
    return !!expanded;
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
        var oldScrollTop = scroller.scrollTop;
        instantScroll(scroller, function () {
          scroller.scrollTop += direction === "down" ? innerHeight * 0.4 : -innerHeight * 0.4;
        });
        if (scroller.scrollTop !== oldScrollTop) noteManualScroll();
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
      tick(true, true);
    }
  }
  function media(action) {
    var video = player();
    if (action === "playpause" || action === "play" || action === "pause") {
      if (!video) return false;
      if (action === "pause") { video.pause(); tick(true, true); stopClock(); return true; }
      if (action === "play" || video.paused) {
        var promise = video.play();
        if (promise && promise.catch) promise.catch(function () {
          if (status && visible()) status.textContent = "Select a song in Browse to start playback.";
        });
      } else video.pause();
      if (video.paused) { tick(true, true); stopClock(); } else startClock();
      return true;
    }
    var button = document.querySelector(action === "next"
      ? 'ytmusic-player-bar .next-button' : 'ytmusic-player-bar .previous-button');
    if (button) button.click();
    return !!button;
  }
  // Cursor edge scrolling: inspect only the ancestor chain under the pointer.
  // Fractions keep native pixels independent of CSS pixels and page scaling.
  function instantScroll(element, action) {
    if (!element) return action ? action() : undefined;
    var previous = element.style.scrollBehavior;
    element.style.scrollBehavior = 'auto';
    try {
      if (action) return action();
      // An explicit instant write also cancels any in-flight browser scroll.
      element.scrollTop = element.scrollTop;
    } finally { element.style.scrollBehavior = previous; }
  }
  function noteManualScroll() {
    if (visible() && lyrics && lyrics.renderer) lyrics.renderer.noteUserScroll(true);
  }
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
          instantScroll(element, function () {
            if (scrollX) element.scrollLeft += sx;
            if (scrollY) element.scrollTop += sy;
          });
          if (element.scrollLeft !== beforeX || element.scrollTop !== beforeY) { noteManualScroll(); return true; }
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
    transportArtwork = artwork('tv-transport-artwork');
    transportTitle = document.createElement('span');
    transportTitle.className = 'tv-transport-title';
    function button(label, action) {
      var el = iconButton(label, action === 'playpause' ? 'play' : action, function () {
        media(action); updateTransport(true);
      });
      controls.appendChild(el);
      return el;
    }
    var info = node('div', 'tv-transport-info');
    transportArtist = node('span', 'tv-transport-artist');
    info.append(transportTitle, transportArtist);
    var controls = node('div', 'tv-transport-controls');
    button('Previous', 'previous');
    transportPlay = button('Play', 'playpause');
    transportPlay.classList.add('tv-play-button');
    button('Next', 'next');
    transportSeek = document.createElement('input');
    transportSeek.type = 'range'; transportSeek.min = '0'; transportSeek.max = '1000'; transportSeek.value = '0';
    transportSeek.setAttribute('aria-label', 'Seek through song');
    transportSeek.addEventListener('input', function () {
      var video = player();
      if (video && isFinite(video.duration)) video.currentTime = Math.max(0, Math.min(1000, Number(transportSeek.value) || 0)) * video.duration / 1000;
      updateTransport(true);
      tick(true, true);
    });
    transportSeek.addEventListener('keydown', function (event) {
      if (event.key !== 'Home' && event.key !== 'End') return;
      event.preventDefault(); transportSeek.value = event.key === 'Home' ? '0' : '1000';
      transportSeek.dispatchEvent(new Event('input'));
    });
    transportTime = document.createElement('span');
    transportTime.className = 'tv-transport-time';
    var extra = node('div', 'tv-transport-extra');
    var lyricsToggle = iconButton('Show lyrics', 'lyrics', function () { toggleLyrics(); });
    lyricsToggle.classList.add('tv-lyrics-toggle');
    extra.append(transportTime, lyricsToggle);
    transport.append(transportSeek, transportArtwork, info, controls, extra);
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
    var label = video.paused ? 'Play' : 'Pause';
    if (transportPlay.getAttribute('aria-label') !== label) {
      transportPlay.setAttribute('aria-label', label); transportPlay.title = label;
      setIcon(transportPlay, video.paused ? 'play' : 'pause');
    }
    if (!force && second === lastTransportSecond) return;
    var request = metadata();
    var trackStamp = request ? request.trackId + request.title + request.artist : '';
    if (!force && second === lastTransportSecond && trackStamp === lastTrackStamp) return;
    lastTransportSecond = second;
    var trackChanged = trackStamp !== lastTrackStamp;
    lastTrackStamp = trackStamp;
    var title = request ? request.title : 'Now playing', artist = request ? request.artist : '';
    if (transportTitle.textContent !== title) transportTitle.textContent = title;
    if (transportArtist.textContent !== artist) transportArtist.textContent = artist;
    transportTime.textContent = formatTime(video.currentTime) + ' / ' + formatTime(video.duration);
    transportSeek.value = String(Math.round(video.currentTime * 1000 / video.duration));
    transportSeek.setAttribute('aria-valuetext', formatTime(video.currentTime) + ' of ' + formatTime(video.duration));
    transportSeek.style.setProperty('--tv-progress', transportSeek.value / 10 + '%');
    if (force || trackChanged || !lastArtwork) updateArtwork(false);
    syncLyricsToggle();
  }
  ['timeupdate', 'loadedmetadata', 'durationchange', 'play', 'pause', 'emptied'].forEach(function (name) {
    document.addEventListener(name, function (event) {
      if (event.target !== player()) return;
      updateTransport(name !== 'timeupdate');
      if (name === 'play' || name === 'loadedmetadata') startClock();
      else if (name === 'pause' || name === 'emptied') { tick(true, true); stopClock(); }
      else if (name !== 'timeupdate') tick(true);
    }, true);
  });
  ['seeking', 'seeked', 'ended'].forEach(function (name) {
    document.addEventListener(name, function (event) {
      if (event.target === player()) {
        updateTransport(true); tick(true, true);
        if (name === 'ended') stopClock();
      }
    }, true);
  });
  function tick(force, instant) {
    if (!lyrics || !visible() || suspended || document.hidden) return;
    var video = player();
    var playing = !!video && !video.paused && !video.ended;
    var time = video && isFinite(video.currentTime) ? video.currentTime : 0;
    if (!force && playing === lastLyricPlaying && time === lastLyricTime) return;
    function update() {
      if (lyrics.playing !== playing) lyrics.playing = playing;
      lyrics.currentTime = time;
    }
    if (instant) instantScroll(scroller, update); else update();
    lastLyricTime = time;
    lastLyricPlaying = playing;
  }
  function stopClock() {
    if (timer !== null) clearInterval(timer);
    timer = null;
    instantScroll(scroller);
    if (lyrics) lyrics.playing = false;
    lastLyricPlaying = null;
  }
  function startClock() {
    stopClock();
    if (visible() && !suspended && !document.hidden) {
      tick(true, true);
      var video = player();
      if (video && !video.paused && !video.ended) timer = setInterval(tick, 250);
    }
  }
  function buildOverlay() {
    overlay = document.createElement("section");
    overlay.id = "tv-lyrics-overlay";
    overlay.hidden = true;
    overlay.setAttribute("aria-label", "Now playing lyrics");
    var label = document.createElement("div");
    label.className = "tv-eyebrow";
    label.textContent = "THE LISTENING ROOM";
    var top = node('div', 'tv-lyrics-top');
    var back = iconButton('Back to music', 'back', closeLyrics);
    var section = node('span', 'tv-section-label', 'LYRICS');
    top.append(back, section, label);
    var identity = node('aside', 'tv-song-identity');
    lyricsArtwork = artwork('tv-cover-art');
    heading = document.createElement("h1");
    artistLabel = node('p', 'tv-song-artist');
    status = document.createElement("p");
    status.className = "tv-lyrics-status";
    scroller = document.createElement("div");
    scroller.className = "tv-lyrics-scroll";
    scroller.addEventListener('wheel', noteManualScroll, { passive: true });
    scroller.addEventListener('touchmove', noteManualScroll, { passive: true });
    plain = document.createElement("div");
    plain.className = "tv-plain-lyrics";
    emptyState = node('div', 'tv-lyrics-empty');
    emptyState.append(node('span', 'tv-empty-mark', '♪'), node('p', '', 'The words will find you.'));
    scroller.appendChild(emptyState);
    lyricsRetry = node('button', 'tv-text-button tv-lyrics-retry', 'Try again'); lyricsRetry.type = 'button'; lyricsRetry.hidden = true;
    lyricsRetry.addEventListener('click', retryLyrics);
    identity.append(lyricsArtwork, heading, artistLabel, status, lyricsRetry);
    lyricsBody = node('div', 'tv-lyrics-body');
    lyricsBody.append(identity, scroller);
    var hint = document.createElement("footer");
    hint.append(node('span', 'tv-remote-hint', 'MENU  Controls     ·     BACK  Music'));
    lyricsFollow = node('button', 'tv-text-button', 'Follow song'); lyricsFollow.type = 'button';
    lyricsFollow.setAttribute('aria-label', 'Follow current lyric');
    lyricsFollow.addEventListener('click', function () {
      if (!lyrics) return;
      if (lyrics.renderer && lyrics.renderer.resumeAutoscroll) lyrics.renderer.resumeAutoscroll();
      var current = lyrics.querySelector('.blyrics--active');
      if (current) scroller.scrollTop += current.getBoundingClientRect().top
        - scroller.getBoundingClientRect().top - scroller.clientHeight * 0.3;
      tick(true);
    });
    hint.append(lyricsFollow);
    overlay.append(top, lyricsBody, hint);
    document.body.appendChild(overlay);
  }
  function mountRenderer() {
    if (lyrics || !window.customElements || !customElements.get("braccato-lyrics")) return;
    // YouTube installs an ES5 custom-elements adapter that calls constructors as
    // functions. Construct the registered ES class directly so that adapter does
    // not turn it into an unupgraded HTMLUnknownElement.
    var LyricsElement = customElements.get("braccato-lyrics");
    lyrics = new LyricsElement();
    lyrics.theme = (window.BraccatoTV ? window.BraccatoTV.theme : '/* blyrics-letter-wave = false; */')
      + '\n/* blyrics-line-synced-animation-delay = 0; */';
    // The WebView scrolls the viewport; per-line WAAPI motion is unnecessary.
    lyrics.tickOptions = { smoothScroll: false, followPausedSeeks: true, countEveryUserScroll: true };
    lastLyricTime = -1;
    lastLyricPlaying = null;
    lyrics.host = { getScrollElement: function () { return scroller; } };
    lyrics.addEventListener("braccato:line-click", function (event) {
      var video = player();
      if (video && isFinite(event.detail.timeS)) { video.currentTime = event.detail.timeS; tick(true, true); }
    });
    lyrics.addEventListener("braccato:error", function () {
      renderFailure = true;
      status.textContent = "Lyrics renderer could not start. Try updating Android System WebView.";
      lyricsRetry.hidden = false;
    });
    scroller.appendChild(lyrics);
    if (savedLyrics) lyrics.lyrics = savedLyrics;
  }
  function closeLyrics() {
    if (!visible()) return false;
    stopClock();
    overlay.hidden = true;
    if (lyrics) { lyrics.remove(); lyrics = null; }
    document.documentElement.classList.remove('tv-lyrics-open');
    syncLyricsToggle();
    notifyLyricsState();
    return true;
  }
  function toggleLyrics() {
    if (closeLyrics()) return false;
    if (!overlay) buildOverlay();
    overlay.hidden = false;
    document.documentElement.classList.add('tv-lyrics-open');
    clearFocus();
    renderFailure = false;
    try { mountRenderer(); } catch (error) {
      renderFailure = true;
    }
    status.textContent = savedStatus || (latestRequest ? "Finding lyrics…" : "Play a song to load lyrics.");
    if (savedPlain) { plain.textContent = savedPlain; scroller.appendChild(plain); }
    getLyricsRequest();
    if (renderFailure) { status.textContent = 'The lyrics view could not start. Try again.'; lyricsRetry.hidden = false; }
    updateArtwork(true);
    syncLyricsToggle();
    startClock();
    notifyLyricsState();
    return true;
  }
  function syncLyricsToggle() {
    var button = transport && transport.querySelector('.tv-lyrics-toggle');
    if (!button) return;
    button.setAttribute('aria-label', visible() ? 'Hide lyrics' : 'Show lyrics');
    button.title = visible() ? 'Hide lyrics' : 'Show lyrics';
    button.setAttribute('aria-pressed', String(visible()));
  }
  function retryLyrics() {
    if (!visible()) return false;
    retryNonce++;
    savedStatus = 'Finding lyrics…'; status.textContent = savedStatus;
    emptyState.hidden = false;
    lyricsRetry.hidden = true;
    if (renderFailure) {
      if (lyrics) { lyrics.remove(); lyrics = null; }
      renderFailure = false;
      try { mountRenderer(); } catch (error) { renderFailure = true; }
    }
    notifyLyricsState();
    return true;
  }
  function getLyricsRequest() {
    if (!visible()) return null;
    var request = metadata();
    if (!request) {
      if (activeTrack) {
        activeTrack = ''; latestRequest = null; savedLyrics = null; savedPlain = ''; savedStatus = '';
        if (lyrics) lyrics.lyrics = [];
        plain.remove();
        lastArtwork = '';
        [transportArtwork, lyricsArtwork].forEach(function (frame) {
          if (!frame) return;
          var image = frame.querySelector('img'); image.removeAttribute('src'); image.hidden = true;
          frame.classList.add('tv-artwork-empty');
        });
      }
      lyricsRetry.hidden = true;
      heading.textContent = 'Nothing playing'; artistLabel.textContent = 'Choose a song to begin';
      status.textContent = 'Open Search from the Controls menu.';
      emptyState.hidden = false;
      return null;
    }
    var key = request.trackId + "\n" + request.title + "\n" + request.artist
      + "\n" + request.album + "\n" + request.duration;
    if (key !== activeTrack) {
      activeTrack = key;
      retryNonce = 0;
      request.retryNonce = 0;
      savedLyrics = null;
      savedPlain = "";
      savedStatus = "Finding lyrics…";
      lyricsRetry.hidden = true;
      plain.remove();
      if (lyrics) lyrics.lyrics = [];
      instantScroll(scroller, function () { scroller.scrollTop = 0; });
      heading.textContent = request.title;
      artistLabel.textContent = request.artist;
      status.textContent = "Finding lyrics…";
      emptyState.hidden = false;
      updateArtwork(true);
    }
    latestRequest = request;
    return request;
  }
  function receiveLyrics(payload) {
    var request = getLyricsRequest();
    if (!request || payload.trackId !== request.trackId || payload.title !== request.title
        || payload.artist !== request.artist
        || (Number(payload.retryNonce || 0) !== Number(request.retryNonce || 0))) return false;
    savedLyrics = null;
    savedPlain = "";
    plain.remove();
    if (lyrics) lyrics.lyrics = [];
    status.textContent = "Lyrics by LRCLIB";
    emptyState.hidden = true;
    if (payload.status === "synced") {
      savedLyrics = parseLrc(payload.syncedLyrics, request.duration);
      if (lyrics && savedLyrics.length && !renderFailure) {
        instantScroll(scroller, function () { lyrics.lyrics = savedLyrics; tick(true); });
      }
      else { status.textContent = "Timed lyrics could not be displayed on this WebView."; emptyState.hidden = false; }
    } else if (payload.status === "plain") {
      savedPlain = String(payload.plainLyrics || "").slice(0, 200000);
      plain.textContent = savedPlain;
      scroller.appendChild(plain);
      status.textContent = payload.syncedIncomplete
        ? "Lyrics by LRCLIB · Full text: timing incomplete"
        : payload.timingMatched === false
          ? "Lyrics by LRCLIB · Untimed: different recording" : "Lyrics by LRCLIB · Unsynced";
    } else { status.textContent = payload.message || "Lyrics are unavailable for this recording."; emptyState.hidden = false; }
    lyricsRetry.hidden = payload.status === 'synced' || payload.status === 'plain' || payload.status === 'instrumental';
    if (renderFailure) lyricsRetry.hidden = false;
    if (payload.status === 'synced' && !savedLyrics.length) lyricsRetry.hidden = false;
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
  function searchFor(query) {
    var box = document.querySelector('ytmusic-search-box');
    var input = box && box.querySelector('input');
    if (!box || !input || typeof box.navigateToQueryResults !== 'function') return false;
    closeLyrics();
    input.value = clean(query);
    if (!input.value) return false;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    box.navigateToQueryResults();
    return true;
  }
  function home() {
    closeLyrics();
    var app = document.querySelector('ytmusic-app');
    if (app && typeof app.navigate === 'function') { app.navigate('FEmusic_home'); return true; }
    location.href = 'https://music.youtube.com/';
    return true;
  }
  document.addEventListener("visibilitychange", function () {
    if (document.hidden) stopClock(); else { startClock(); updateTransport(true); }
  });
  window.TvMusic = {
    move: move, activate: activate, search: search, searchFor: searchFor, media: media, scrollAt: scrollAt,
    setChromeExpanded: setChromeExpanded, retryLyrics: retryLyrics,
    toggleLyrics: toggleLyrics, isLyricsVisible: visible,
    getLyricsRequest: getLyricsRequest, receiveLyrics: receiveLyrics,
    back: closeLyrics,
    home: home,
    suspend: function () { suspended = true; stopClock(); var video = player(); if (video) video.pause(); },
    resume: function () { suspended = false; startClock(); updateTransport(true); }
  };
  updateTransport(true);
})();
