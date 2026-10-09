package com.example.homeezch.install

import android.content.pm.PackageInstaller

/** Keep Android's original reason available while explaining the failure to the user. */
object InstallFailureMessage {
    fun describe(status: Int, detail: String?): String {
        val explanation = when {
            detail?.contains("INSTALL_FAILED_VERIFICATION_FAILURE") == true ->
                "Проверка безопасности Android заблокировала установку. Если показано предупреждение Play Защиты, требуется проверка приложения разработчиком."
            status == PackageInstaller.STATUS_FAILURE_ABORTED ->
                "Установка отменена. Можно выбрать приложение и повторить попытку."
            status == PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "Android заблокировал установку. Проверьте причину в системном окне: ограничения устройства или проверка безопасности."
            status == PackageInstaller.STATUS_FAILURE_STORAGE ->
                "Недостаточно места. Освободите память приставки и повторите установку."
            status == PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "APK несовместим с установленной версией или её подписью. Нужна совместимая сборка."
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "Эта сборка не подходит для версии Android или процессора приставки."
            status == PackageInstaller.STATUS_FAILURE_INVALID ->
                "Android отклонил APK как недопустимый. Нужна проверенная сборка приложения."
            else -> "Android отклонил установку."
        }
        return if (detail.isNullOrBlank()) explanation else "$explanation\nПричина Android: $detail"
    }
}
