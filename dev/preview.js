(function () {
  'use strict';
  const video = document.querySelector('video');
  let position = 0, paused = false;
  const duration = 32;
  Object.defineProperties(video, {
    duration: { get: () => duration },
    currentTime: { get: () => position, set: value => { position = value; } },
    paused: { get: () => paused },
    ended: { get: () => false }
  });
  video.play = () => { paused = false; return Promise.resolve(); };
  video.pause = () => { paused = true; };
  video.dispatchEvent(new Event('loadedmetadata'));
  setInterval(() => { if (!paused && !document.hidden) position = (position + 0.25) % duration; }, 250);
  const demo = '[00:00.00]Open skies above the room\n[00:05.00]A little space to hear the tune\n[00:10.00]The evening finds a slower pace\n[00:15.00]And every line falls into place\n[00:21.00]Let the quiet carry through\n[00:26.00]One more song, a different view';
  function loadDemo() {
    const request = TvMusic.getLyricsRequest();
    if (request) TvMusic.receiveLyrics({ ...request, status: 'synced', syncedLyrics: demo });
    const status = document.querySelector('.tv-lyrics-status');
    if (status) status.textContent = 'Original demo text · Simulated clock';
  }
  document.querySelector('#lyrics').onclick = () => { if (TvMusic.toggleLyrics()) loadDemo(); };
  document.querySelector('#browse').onclick = () => TvMusic.back();
  document.querySelector('#play').onclick = () => TvMusic.media('playpause');
  document.addEventListener('keydown', event => {
    if (!TvMusic.isLyricsVisible()) return;
    const directions = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
    if (directions[event.key]) { TvMusic.move(directions[event.key]); event.preventDefault(); }
    else if (event.key === 'Enter') { TvMusic.activate(); event.preventDefault(); }
    else if (event.key === 'Escape') { TvMusic.back(); event.preventDefault(); }
  });
})();
