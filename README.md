# EZCH Update

EZCH Update — Android TV-приложение на Kotlin и Jetpack Compose. Оно загружает каталог из `apps.json`, показывает доступные обновления и устанавливает выбранные APK по очереди через штатный Android `PackageInstaller`.

## Сборка

Для debug APK на Windows:

```powershell
./gradlew.bat :app:assembleDebug
```

Готовый файл появляется в `app/build/outputs/apk/debug/app-debug.apk`.

## Безопасная установка

Перед передачей APK в `PackageInstaller` приложение проверяет package name и `versionCode` из каталога. Android запрашивает разрешение «Устанавливать неизвестные приложения» и подтверждение каждой установки в своей системной панели.

## Каталог приложений

- `versionCode` — положительное целое число, которое должно увеличиваться с каждой новой сборкой.
- `versionName` — версия, видимая пользователю.
- `packageName` должен совпадать с package name внутри APK.
- `apkUrl` должен быть HTTPS-ссылкой, `apkPath` — начинаться с `/`.

Для EZCH Update 1.3 в каталоге установлен `versionCode: 13`. Новая версия должна использовать большее значение и менять APK вместе с записью `apps.json`.

Проверка каталога:

```powershell
./scripts/validate-apps-json.ps1
```

Та же проверка запускается GitHub Actions при изменении каталога.
