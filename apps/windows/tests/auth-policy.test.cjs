// SPDX-License-Identifier: AGPL-3.0-only
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {isYandexUrl}=require('../electron/auth-policy.cjs');
test('Yandex SSO can finish on ya.ru and return to Passport',()=>{
 for(const url of ['https://ya.ru/','https://sso.passport.yandex.ru/prepare','https://passport.yandex.ru/auth/finish','https://oauth.yandex.ru/device'])assert.equal(isYandexUrl(url),true);
});
test('SSO does not open unrelated origins or insecure schemes',()=>{
 for(const url of ['http://ya.ru','https://ya.ru.evil.test/','https://evil-yandex.ru/','javascript:alert(1)','file:///tmp/test'])assert.equal(isYandexUrl(url),false);
});
