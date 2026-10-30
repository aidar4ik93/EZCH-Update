# EZCH Update 1.8.2 / 32 — проверка GPT, 30.10.2026

## Вывод

**Кандидат подготовлен, полная готовность не подтверждена.** Сборка, модульные проверки, большая часть Android-тестов и обновление подписанного release проходят. Один автоматический сценарий повторного короткого ОК остаётся FAIL. Отдельное воспроизведение через adb проходит; причина расхождения не установлена. На физическом пульте нужна повторная проверка.

По последнему указанию Айдара публикация на GitHub и Google Drive отложена. Релизы и публичный apps.json не менялись; лаунчер 0.49.1 не публиковался. Homatics не затрагивался.

## Исходники и сборка

Отдельная рабочая копия: `F:\Aidar-Projects\EZCH_0.41\gpt-update-review-20261030`, ветка `codex/update-1.8.2-verification`. База GitHub main: `66700575c56e3459c542454e77c3c9159be1b1e1` (точный SHA дополнительно сохранён в verification/git-base.txt).

Модуль Update перенесён из `claude-build/update/app`, версия 1.8.2 / 32. Каталог и его встроенная копия взяты из свежей main: публичные записи не повышались до неопубликованных APK. Старые рабочие папки и исходники лаунчера не изменялись.

Исправлен конфликт ресурсов при объединении: старый app_mobi_zona.webp и новый app_mobi_zona.png имели одинаковое Android-имя; оставлен PNG из текущей сборки Claude. Логика приложения в этом раунде не менялась.

В тестах обновлён устаревший сценарий короткого ОК. В ReleaseUpgradeDeviceTest добавлено ожидание асинхронного callback, очистка оставшихся тестовых сессий и закрытие прежнего установщика; до исправления тест мог попадать в чужое оставшееся окно. В сценарий подписи добавлен явный параметр -LegacyUpdate и актуальное имя выходного файла.

Проверено на Google TV API 33, эмулятор emulator-5554. JBR Android Studio, SDK 37, Build Tools 36.0.0. Это не проверка производительности физического телевизора.

## Результаты

| Проверка | Статус | Доказательство в verification |
|---|---|---|
| testDebugUnitTest | PASS, 9 тестов | app/build/test-results/testDebugUnitTest; build-resumed.log |
| lintDebug, lint release при сборке | PASS, есть предупреждения | build-resumed.log, final-test-build.log; app/build/reports/lint-results-debug.* |
| Debug, AndroidTest, Release | PASS | build-resumed.log: BUILD SUCCESSFUL |
| Структура apps.json | PASS, 19 записей | catalog-validation.log |
| Тесты синхронизации каталогов | PASS, 25 | catalog-tests.log |
| Тесты утилиты стресс-прогона | PASS, 5; это не тесты самого приложения | stress-harness-tests.log |
| Основной Android-набор | 31 PASS, 1 FAIL, 3 пропущены из 35 | device-tests.log, 113,37 с |
| Повреждённые APK, пакет/версия/размер/сертификат, карантин | PASS основного набора | InstallerDeviceTest в device-tests.log |
| Медленная сеть, офлайн и повреждённый кэш, сохранение выбора | PASS | CatalogStressTest в device-tests.log |
| Очереди 3, 5 и 10 APK, восстановление модели, отмена | PASS | PremiumQueueDeviceTest в device-tests.log. Реальные системные подтверждения, локальные безопасные fixture APK |
| Настройки Lite/Premium, расписание и уведомления | PASS | PremiumUiDeviceTest.settingsAndRussianModesPersist, BackgroundScheduleDeviceTest, UpdateNotificationDeviceTest |
| Повторное короткое ОК в автоматическом UI-сценарии | **FAIL, причина не установлена** | ui-tests-final.log, ui-test-diagnostic.log, ui-tests-shell-input.log, ui-last.log. В одних попытках не наблюдалось снятие отметки, в последней — установка отметки. Сняты dpad-after-second.xml и ui-failure.xml |
| Короткое ОК / снятие отметки / удержание, отдельный adb-прогон | PASS наблюдения | ui-before.xml, ui-short.xml, ui-deselect.xml, ui-long.xml: выбор 0→1→0; удержание открывает сведения Лайт HD TV. rapid-ok.xml: после двух быстрых ОК выбор 0. Это не отменяет FAIL автотеста |
| Обновление штатно подписанной 1.8.1 / 31 → 1.8.2 / 32 | PASS, отдельный тест | upgrade-last.log: OK (1 test), системное подтверждение и итоговый PackageInstaller.STATUS_SUCCESS |
| Доступ постороннего UID к UpdateStatusProvider | PASS запрета | status-untrusted-caller.txt: SecurityException, доступ только EZCH |
| Рендер иконок непосредственно из внешних проверенных APK | NOT TESTED, 2 пропуска | IconRenderDeviceTest требует отдельно загруженных APK. Встроенные значки на release-снимке просмотрены |
| Реальные сетевые загрузки и их параллельность | NOT TESTED | Эмулятор был без сетевого подключения. Очередь тестировалась с локальными APK через инъекцию installOperation, которая отключает prefetch |
| Физический пульт / приставка | NOT TESTED | В этом раунде использовался только эмулятор |

После отдельного успешного upgrade-теста уникальное покрытие основного списка: **32 PASS, 1 FAIL, 2 NOT TESTED**. Повторные запуски настроек не считаются дополнительными уникальными тестами. Старые неуспешные журналы сохранены, а не заменены успешным итогом.

## Release: подпись и варианты

Оба APK подготовлены в `F:\Aidar-Projects\EZCH_0.41\dist\update-prepared-20261030`:

- EZCH_Update_1.8.2_TV.apk — com.example.ezchupdate.tvtest, 32 / 1.8.2.
- EZCH_Update_1.8.2_Legacy.apk — com.example.ezchupdate, 32 / 1.8.2.

Штатный сертификат SHA256: ea0e9a1aad77b1fb42f32644a7386131ceed43e07464dcde22754691e70cc602. apksigner verify и zipalign пройдены, метаданные aapt сохранены в cert-TV/Legacy.txt и badging-TV/Legacy.txt. Ключи и пароли в архивы не входят.

TV SHA256: 3d8e853021fcccf0219b30f325f5f46643d679a8e9db645c63ebac8ff2f288f8.
Legacy SHA256: 277cb4d011a698409cd5227aede4b5b482af744431e3ee40b9b0154bb96c2ebd.

Выбирать вариант по пакету существующей установки. Разные пакеты не обновляют друг друга. Runtime-проверка обновления проведена для TV; Legacy собран и проверен по подписи/метаданным, его обновление на устройстве не проверялось.

## Скорость и стабильность

Три холодных запуска TV release: **676, 964, 516 мс TotalTime**, медиана 676 мс. Это показатель ActivityManager, не задержка от кнопки пульта до готовности всех данных.

Первый короткий навигационный стресс: **1500 событий, 33,347 с**, без сброшенных событий. gfxinfo: 1814 кадров, janky 71 / 3,91%; p95 17 мс, p99 19 мс. Это эмуляторный замер, не обещание 60 FPS на телевизоре и не длительный стресс.

PSS до первого прогона 49840 КБ, после 109146 КБ. Рост может включать прогрев и кэши; одного такого замера недостаточно для вывода об утечке. Для проверки продолжения роста запущен второй одинаковый прогон; его результат и второй снимок памяти сохранены в release-monkey-second.txt / release-memory-second.txt. Сводка измерений приложена отдельно в MEASUREMENTS_RU.md рядом с APK.

Снимок release: verification/update-release.png — 1920×1080, интерфейс и значки просмотрены; показана честная подсказка о работе с сохранённым каталогом без сети. Проверка падений основана на сохранённых logcat и monkey, не является гарантией отсутствия всех дефектов.

## Как повторить

1. Указать локальный Android SDK в local.properties, установить JAVA_HOME на JBR/JDK.
2. `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --console=plain`.
3. Установить debug APK и AndroidTest APK только на тестовый эмулятор. Запустить AndroidJUnitRunner; для upgrade-теста предварительно поставить подписанную 1.8.1 и передать releaseApk / releaseSha256. Журналы содержат точные параметры и результаты; без releaseApk этот тест пропускается.
4. Подпись: `scripts/build-release.ps1 -Output <TV.apk>`; для старого пакета дополнительно `-LegacyUpdate`. Нужен существующий локальный ключ владельца; новый не создавался.

## До публикации

Разобрать расхождение UI-теста и проверить короткое/долгое ОК на живом пульте. При доступной сети отдельно проверить фактическое получение каталога и загрузку APK. Не менять apps.json на будущие ссылки до загрузки файлов и проверки этих ссылок. Публикацию возобновлять по отдельному указанию Айдара.
