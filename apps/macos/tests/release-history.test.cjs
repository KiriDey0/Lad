// SPDX-License-Identifier: AGPL-3.0-only
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { loadReleaseHistory, HISTORY_URL } = require('../electron/release-history.cjs');
const release = (version, date, extra = {}) => ({ tag_name: version, published_at: date, body: 'Changes', ...extra });

test('history uses the fixed repository and returns only public stable releases newest first', async () => {
  const entries = await loadReleaseHistory(async (url, options) => {
    assert.equal(url, HISTORY_URL);
    assert.equal(options.redirect, 'error');
    return Response.json([
      release('v0.5.9', '2026-11-15T12:00:00Z'),
      release('v0.6.2', '2026-11-16T15:00:00Z', { draft: true }),
      release('v0.6.3', '2026-11-17T15:00:00Z', { prerelease: true }),
      release('v0.6.1', '2026-11-16T12:00:00Z'),
      release('bad', '2026-11-16T12:00:00Z'),
      release('v0.5.8', 'bad'), null,
    ]);
  });
  assert.deepEqual(entries.map(r => r.version), ['0.6.1', '0.5.9']);
  assert.equal(entries[0].notes, 'Changes');
});

test('history reports rate limiting and rejects malformed or oversized responses', async () => {
  await assert.rejects(loadReleaseHistory(async () => new Response('', { status: 403 })), /ограничил/);
  await assert.rejects(loadReleaseHistory(async () => Response.json({ message: 'bad' })), /некорректную/);
  await assert.rejects(loadReleaseHistory(async () => new Response('x'.repeat(1048577))), /слишком большой/);
  assert.deepEqual(await loadReleaseHistory(async () => Response.json([])), []);
});
