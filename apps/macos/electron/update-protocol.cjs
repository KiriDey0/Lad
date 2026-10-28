// SPDX-License-Identifier: AGPL-3.0-only
const crypto = require('node:crypto');
const PUBLIC_KEY = '8fbccb948b83fa90afc655dd1b5d43f32a2c14a404590385b95964392c311dac';
const FEED = 'https://github.com/KiriDey0/Lad/releases/latest/download/updates.json';
function compareVersion(a, b) {
  const parse = v => {
    if (typeof v !== 'string' || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(v)) throw Error('Некорректная версия обновления');
    const n = v.split('.').map(Number);
    if (n.some(x => !Number.isSafeInteger(x) || x > 100000)) throw Error('Некорректная версия обновления');
    return n;
  };
  const aa = parse(a), bb = parse(b);
  for (let i = 0; i < 3; i++) if (aa[i] !== bb[i]) return aa[i] > bb[i] ? 1 : -1;
  return 0;
}
function verifyManifest(envelope, platform, current, publicKey = PUBLIC_KEY) {
  if (typeof envelope !== 'string' || Buffer.byteLength(envelope) > 262144) throw Error('Описание обновления слишком большое');
  const e = JSON.parse(envelope);
  if (e.schema !== 1 || typeof e.payload !== 'string' || typeof e.signature !== 'string') throw Error('Формат обновления не поддерживается');
  const bytes = Buffer.from(e.payload, 'base64'), signature = Buffer.from(e.signature, 'base64');
  const key = crypto.createPublicKey({ key: Buffer.concat([Buffer.from('302a300506032b6570032100','hex'), Buffer.from(publicKey,'hex')]), format:'der', type:'spki' });
  if (signature.length !== 64 || !crypto.verify(null, bytes, key, signature)) throw Error('Подпись обновления не прошла проверку');
  const m = JSON.parse(bytes.toString('utf8'));
  if (m.schema !== 1 || typeof m.notes !== 'string' || m.notes.length > 12000) throw Error('Некорректное описание обновления');
  const p = m.platforms?.[platform];
  if (!p || typeof p.version !== 'string') throw Error('Для этого устройства пока нет подходящего обновления');
  const newer = compareVersion(p.version, current) > 0;
  const a = p.artifact;
  if (!a || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$/.test(a.name) || !/^[a-f0-9]{64}$/.test(a.sha256) || !Number.isSafeInteger(a.size) || a.size < 1 || a.size > 1610612736) throw Error('Некорректные параметры файла обновления');
  const u = new URL(a.url);
  if (u.protocol !== 'https:' || u.host !== 'github.com' || u.username || u.password || u.search || u.hash || u.pathname !== `/KiriDey0/Lad/releases/download/v${p.version}/${a.name}`) throw Error('Неизвестный источник обновления');
  const extension = platform.endsWith('installer') ? '.exe' : platform.startsWith('android') ? '.apk' : '.zip';
  if (!a.name.endsWith(extension)) throw Error('Неподходящий файл обновления');
  return Object.freeze({ ...p, artifact: Object.freeze({...a}), notes:m.notes, newer });
}
module.exports = { PUBLIC_KEY, FEED, compareVersion, verifyManifest };
