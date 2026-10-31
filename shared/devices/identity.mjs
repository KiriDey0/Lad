// SPDX-License-Identifier: AGPL-3.0-only
const clean = value => typeof value === 'string' ? value.trim().replace(/\s+/gu, ' ') : '';
const key = value => clean(value).normalize('NFKC').toLowerCase();
const unique = items => [...new Map(items.filter(Boolean).map(item => [key(item), item])).values()];
export function deviceTitle(d) {
  if (!d) return 'Станция';
  return d.room && key(d.name) === key(d.model) ? d.room : d.name || 'Яндекс Станция';
}
export function deviceLabel(d) {
  if (!d) return 'Станция';
  return unique([deviceTitle(d), d.room, d.household]).join(' · ');
}
export function deviceContext(d) {
  const title = deviceTitle(d);
  return unique([d.room !== title && d.room, d.model !== title && d.model, d.household,
    !d.room && (d.metadataStatus === 'ready' ? 'Комната не указана' : 'Комната недоступна')]).join(' · ');
}
export function deviceAmbiguous(d, devices) {
  if (!d) return false;
  const identity = value => ['name', 'room', 'household'].map(k => key(value[k]));
  const a = identity(d);
  return devices.some(other => other.id !== d.id && identity(other).every((v, i) => v === a[i]));
}
