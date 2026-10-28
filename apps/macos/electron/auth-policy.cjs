// SPDX-License-Identifier: AGPL-3.0-only
// Yandex SSO finishes through ya.ru before returning to yandex.ru.
function isYandexUrl(url) {
  try {
    const parsed = new URL(url);
    return (
      parsed.protocol === "https:" &&
      [
        "yandex.ru",
        "yandex.com",
        "yandex.net",
        "yandex.kz",
        "yandex.by",
        "ya.ru",
      ].some(
        (domain) =>
          parsed.hostname === domain || parsed.hostname.endsWith("." + domain),
      )
    );
  } catch {
    return false;
  }
}
module.exports = { isYandexUrl };
