const { test } = require('node:test');
const assert = require('node:assert/strict');
const { parseLrc } = require('../app/src/main/assets/tv.js');

test('sorts repeated tags and gives each line its real duration', () => {
  const lines = parseLrc('[00:03.20]Later\n[00:01.5][00:04.25]Repeated', 8);
  assert.deepEqual(lines.map(l => [l.startTimeMs, l.durationMs, l.words]), [
    [1500, 1700, 'Repeated'], [3200, 1050, 'Later'], [4250, 3750, 'Repeated']
  ]);
});
test('respects LRC offset, blank instrumental markers and untimed metadata', () => {
  const lines = parseLrc('[ar:Original demo]\n[offset:-500]\n[00:00.20]\n[00:01.000]A line', 4);
  assert.equal(lines.length, 2);
  assert.equal(lines[0].startTimeMs, 0);
  assert.equal(lines[0].isInstrumental, true);
  assert.equal(lines[1].startTimeMs, 500);
});
test('handles shared timestamps, malformed input and missing duration', () => {
  const lines = parseLrc('[00:02]A\n[00:02]B\n[00:99]invalid\n[00:05]C');
  assert.deepEqual(lines.map(l => l.durationMs), [3000, 3000, 5000]);
  assert.deepEqual(parseLrc('plain text without timing'), []);
});
test('bounds lyric DOM size even when a file repeats thousands of timestamps', () => {
  const lines = parseLrc('[00:01]'.repeat(4000) + 'x'.repeat(5000), 20);
  assert.equal(lines.length, 1000);
  assert.equal(lines[0].words.length, 1000);
});
