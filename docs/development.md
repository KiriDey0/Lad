# Разработка и сборка

## Требования

- Node.js 22+ и npm для desktop-интерфейсов.
- Python 3.12+ для desktop-сервиса и его тестов.
- JDK 17, Android SDK 35 для нативных приложений.
- macOS для macOS-пакета, Windows x64 для Windows-пакета.

Работай из каталога нужного приложения. Учётные записи и устройства для unit-тестов не требуются.

## macOS / Windows

```sh
cd apps/macos   # либо apps/windows
python3 -m venv .venv
.venv/bin/python -m pip install -r backend/requirements.txt pytest pytest-asyncio
npm ci
npm run build
.venv/bin/python -m pytest tests -q
node --test tests/auth-policy.test.cjs
npm run desktop
```

В Windows используй `py -3.12`, `.venv\Scripts\python.exe` и обычный `npm` из PowerShell. Electron использует виртуальное окружение приложения; не требуется путь к компьютеру разработчика. `npm run dev` запускает интерфейс Vite только на loopback. Учётная запись Яндекса нужна лишь для ручной проверки реального каталога.

Самостоятельный desktop-пакет со встроенным Python-сервисом:

```sh
python3 scripts/build.py
```

На Windows: `py -3.12 scripts/build.py`. Скрипт устанавливает закреплённые Python-зависимости и PyInstaller, выполняет `npm ci`, frontend build и упаковку Electron. Результат появляется в `release/`. У macOS цель — приложение `.app`; у Windows — NSIS installer и portable ZIP.

UI-тесты используют отдельный профиль:

```sh
npm run test:ui
```

Для Windows packaged lifecycle-тестов укажи `LAD_TEST_EXE`, `LAD_TEST_PROFILE`, `LAD_WINDOWS_PROFILE`, `LAD_QA_DIR` и запусти `npm exec playwright test -- --config playwright.windows.config.ts` из интерактивной desktop-сессии. Никогда не направляй тестовый профиль в обычный каталог Лада. Тест аварийного завершения останавливает только sidecar тестового экземпляра.

## Android / TV

Создай собственный `local.properties` в `apps/android` или `apps/tv` с `sdk.dir` либо настрой SDK через переменные окружения. Этот файл не добавляется в git.

```sh
cd apps/android   # либо apps/tv
java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Для подписанного release передай `LAD_ANDROID_KEYSTORE`, `LAD_ANDROID_STORE_PASSWORD`, `LAD_ANDROID_KEY_ALIAS`, `LAD_ANDROID_KEY_PASSWORD` через защищённое окружение. Без этих параметров release APK собирается без подписи; debug QA подписывается отдельным стандартным debug-ключом. Для обновления существующего приложения используй его прежний сертификат.

Для выпуска используй из корня репозитория `python3 scripts/build-native.py android` или `python3 scripts/build-native.py tv`. Скрипт выполняет чистую сборку, тесты и lint, затем сверяет номера в APK с `release-config.json`. Для QA добавь `--debug`. При прямом вызове Gradle release-задач сначала выполняй `:app:clean`. Используется JDK 17. Wrapper проверяет SHA-256 Gradle distribution. Телефон поддерживает API 26+, TV — API 28+ и ARMv7.

Тестовый пакет имеет suffix `.qa`. Для телефонных device-тестов:

```sh
# После assembleDebug и assembleDebugAndroidTest
python3 scripts/test-device.py --serial <adb-serial>
```

Нужен явно выбранный разрешённый телефон, включённая отладка и подтверждение установки. Скрипт работает только с отдельным QA-пакетом. Для TV после сборки debug/test APK можно вызвать `android.test.InstrumentationTestRunner` с классами `com.lad.music.EngineRaceTest,com.lad.music.TvRemoteTest` в пакете `com.lad.music.tv.qa.test`. Эмулятор для JVM-тестов не нужен.

`assembleBenchmark` телефонного приложения создаёт оптимизированный R8-вариант с `.qa` для замеров без данных основного аккаунта.

## Проверка изменений

CI проверяет обе desktop-сборки интерфейса, их backend/auth-тесты, оба нативных набора JVM-тестов и debug/lint, а также гигиену публичного дерева. Ручные проверки нужны для реального звука, пульта, жестов и сетевого подключения к Станции. Они не заменяются успешным unit-прогоном.

Версии зависимостей записаны в lockfiles и Gradle. При изменении совместимости обнови README и changelog; не добавляй отчёты с личными данными устройств.
