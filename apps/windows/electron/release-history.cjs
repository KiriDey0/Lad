// SPDX-License-Identifier: AGPL-3.0-only
const HISTORY_URL = 'https://api.github.com/repos/KiriDey0/Lad/releases?per_page=50';

async function loadReleaseHistory(fetcher = fetch) {
  const response = await fetcher(HISTORY_URL, {
    signal: AbortSignal.timeout(15000),
    redirect: 'error',
    headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'Lad' },
  });
  if (!response.ok) {
    await response.body?.cancel();
    throw Error(response.status === 403 || response.status === 429
      ? 'GitHub временно ограничил запросы. Попробуй позже.'
      : 'Не удалось загрузить историю версий. Проверь подключение и попробуй ещё раз.');
  }
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > 1048576) throw Error('Ответ с историей версий слишком большой.');
      chunks.push(Buffer.from(value));
    }
  } finally {
    await reader.cancel();
  }
  const releases = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  if (!Array.isArray(releases)) throw Error('GitHub вернул некорректную историю версий.');
  return releases
    .filter(r => r && !r.draft && !r.prerelease && typeof r.tag_name === 'string'
      && /^v?\d+\.\d+\.\d+$/.test(r.tag_name) && Number.isFinite(Date.parse(r.published_at)))
    .sort((a, b) => Date.parse(b.published_at) - Date.parse(a.published_at))
    .map(r => ({
      version: r.tag_name.replace(/^v/, ''),
      date: r.published_at,
      notes: typeof r.body === 'string' ? r.body.slice(0, 30000) : '',
    }));
}
module.exports = { loadReleaseHistory, HISTORY_URL };
