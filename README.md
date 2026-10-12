# EZCH Launcher 0.26 / Update 1.6.4

Выпуск 0.26 основан на восстановленных исходниках Launcher 0.25. [Изменения, проверки и ограничения 0.26](RELEASE_0.26_RU.md).

Launcher: `com.example.homeezch.usb`, versionCode 27, versionName 0.26-USB. Update: `com.example.ezchupdate.tvtest`, versionCode 25, versionName 1.6.4. Android 8.0+ (API 26), штатные HOME/TV/file/installation API.

Источники раскрываются последовательным горизонтальным рядом. Автоматически показываются объявленные системой входы и доступные накопители; ручные ярлыки добавляются в настройках. Верхняя шестерёнка раскрывает компактные настройки. Auto выбирает Lite при RAM ≤ 1,5 ГиБ или признаке low-RAM. Погода MET Norway сохраняет последний прогноз и соблюдает ограничения запросов.

## Сборка и проверка

JDK 17+, Android SDK Platform 37.0, Build Tools 36.0.0. Локальный сценарий определяет JDK Android Studio и SDK:

```powershell
./scripts/build.ps1
```

Собираются debug APK обоих приложений, Android-тесты, JVM-тесты обоих модулей и lint. На подготовленном эмуляторе:

```powershell
./gradlew.bat :launcher:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Подпись выпуска использует существующий локальный каталог владельца с `ezch-update-release.jks` и необходимыми свойствами. Приватный ключ и пароли не входят в исходники:

```powershell
./scripts/build-release.ps1 -Module launcher -SigningProject C:\Path\To\PrivateSigning -Output ./dist/EZCH_Launcher_0.26_USB.apk
./scripts/build-release.ps1 -Module app -SigningProject C:\Path\To\PrivateSigning -Output ./dist/EZCH_Update_1.6.4.apk
```

Скрипт проверяет сертификат, packageName, фактические versionCode/versionName и выравнивание APK. Сертификат выпуска: `ea0e9a1aad77b1fb42f32644a7386131ceed43e07464dcde22754691e70cc602`. Debug APK имеет отдельный пакет с суффиксом `.debug` и не заменяет release.

## Установка

Скопируйте подписанный APK Launcher на USB и откройте его штатным установщиком телевизора. При совпадении пакета и подписи он обновит прежнюю установку с сохранением данных. После открытия подтвердите доступный системный выбор HOME. На ограниченной прошивке помощник показывает фактическое состояние; дополнительная служба Home включается только через Android владельцем.

Магазин и проводник входят в Launcher; отдельный Update необязателен. Загрузка APK не считается успешной установкой: очередь ждёт фактического результата PackageInstaller и системного подтверждения.

## Каталог и публикация

[Скачать Launcher 0.26 USB](https://github.com/aidar4ik93/EZCH-Update/releases/tag/v0.26-usb). [Скачать Update 1.6.4](https://github.com/aidar4ik93/EZCH-Update/releases/tag/v1.6.4).

Оба APK опубликованы и скачаны по публичным ссылкам; размеры и SHA-256 совпадают с подписанными локальными файлами. `apps.json` и две встроенные копии обновлены до этих версий. Остальные приложения каталога сохранены. Исходный код объединён в main через [PR #3](https://github.com/aidar4ik93/EZCH-Update/pull/3); [итоговые проверки сборки, Android TV и Google TV](https://github.com/aidar4ik93/EZCH-Update/actions/runs/38239745435) прошли.

Встроенный каталог в опубликованном APK является снимком на момент сборки; при подключении к сети приложение получает актуальный каталог из main. Изменение каталога после публикации не меняет подписанный APK.

## Поддержка и пределы проверки

[Telegram проекта](https://t.me/+huiJDfK5jdA4NzQy). [Полный отчёт 0.26](RELEASE_0.26_RU.md).

Физическая приставка во время этой работы недоступна. Доступность HOME, HDMI, тюнера и профиль Bluetooth определяется прошивкой. Подтверждённая подпись не гарантирует отсутствие внешнего предупреждения Play Protect. Результаты эмулятора не объявляются физическим тестом или доказательством 60 FPS.
