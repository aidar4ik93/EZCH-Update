package com.example.homeezch.ai

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/** One scenario implementation, used by the installed service and the device test host. */
class AppControlEngine(private val environment: UiEnvironment) {
    private data class Choice(val label: String, val id: String?, val bounds: String)
    private data class Snapshot(val pkg: String, val at: Long, val items: List<Choice>, val profile: String, val query: String)
    private var snapshot: Snapshot? = null
    fun clear() { snapshot = null }
    fun stateSummary() = "context=${snapshot?.pkg ?: "none"}; results=${snapshot?.items?.size ?: 0}"
    private fun root(): AccessibilityNodeInfo? = environment.root()
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 35 || result.size >= 600) return
            result.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0); return result.filter { it.isVisibleToUser }
    }
    private fun label(n: AccessibilityNodeInfo) = n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString().orEmpty()
    private fun textInput(n: AccessibilityNodeInfo) = n.isEnabled && !n.isPassword &&
        (n.isEditable || n.className?.toString() in setOf("android.widget.EditText", "android.widget.AutoCompleteTextView"))
    private fun searchInput(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()
        return textInput(n) && (id == "q" || id == "query" || id.contains("search") ||
            uiLabel(n.hintText?.toString().orEmpty()) in searchLabels || uiLabel(n.contentDescription?.toString().orEmpty()) in searchLabels)
    }
    private fun identity(n: AccessibilityNodeInfo): Choice {
        val bounds = android.graphics.Rect(); n.getBoundsInScreen(bounds)
        return Choice(label(n), n.viewIdResourceName, bounds.flattenToString())
    }
    private fun clickTarget(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var target: AccessibilityNodeInfo? = n
        var focusable: AccessibilityNodeInfo? = null
        repeat(4) {
            val v = target ?: return focusable
            if (v.isClickable && v.isEnabled) return v
            if (focusable == null && v.isFocusable && v.isEnabled && v.className?.toString() !in
                setOf("android.webkit.WebView", "android.widget.GridView", "androidx.recyclerview.widget.RecyclerView")) focusable = v
            target = v.parent
        }
        return focusable
    }
    private fun click(n: AccessibilityNodeInfo): Boolean {
        val target = clickTarget(n) ?: return false
        if (target.packageName?.toString() != environment.activePackage || !target.isVisibleToUser) return false
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        if (android.os.Build.VERSION.SDK_INT >= 33 && target.isFocusable &&
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
            environment.globalActions.contains(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)) return environment.globalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
        val bounds = android.graphics.Rect(); target.getBoundsInScreen(bounds)
        return bounds.width() > 0 && bounds.height() > 0 && environment.tap(bounds)
    }
    private fun allowed(pkg: String) = environment.allowed(pkg)
    private suspend fun waitRoot(pkg: String? = null, tries: Int = 24, predicate: (AccessibilityNodeInfo) -> Boolean = { true }): AccessibilityNodeInfo? {
        repeat(tries) {
            val r = root()
            if (r != null && (pkg == null || r.packageName?.toString() == pkg) && predicate(r)) return r
            delay(200)
        }
        return null
    }
    suspend fun confirmWindow(pkg: String): Boolean = waitRoot(pkg) != null
    suspend fun execute(action: Action): Outcome {
        if (!environment.enabled) return Outcome(false, "Ева выключена")
        if (action is Action.PlayChannel) return playChannel(action.name)
        if (action is Action.Navigate && action.direction in setOf(Direction.BACK, Direction.HOME)) {
            snapshot = null
            val ok = environment.globalAction(if (action.direction == Direction.BACK) AccessibilityService.GLOBAL_ACTION_BACK else AccessibilityService.GLOBAL_ACTION_HOME)
            return Outcome(ok, if (ok) "Переход запрошен" else "Android запретил переход")
        }
        var r = root() ?: return Outcome(false, "Экран приложения недоступен")
        val pkg = r.packageName?.toString() ?: return Outcome(false, "Приложение не определено")
        if (!allowed(pkg)) return Outcome(false, "Управление этим экраном запрещено профилем или требует ручного действия")
        return when (action) {
            is Action.Search -> search(pkg, action.query)
            is Action.Choose -> choose(pkg, action.index)
            is Action.Quality -> {
                val matches = nodes(r).filter { !it.isEditable && normalize(label(it)).let { text -> !text.startsWith("фильмы в") && (text == action.value || text.contains(action.value) || action.value == "4k" && text.contains("2160")) } }
                val target = matches.singleOrNull() ?: return Outcome(false, if (matches.isEmpty()) "Качество ${action.value} на экране не найдено. Откройте список источников" else "Несколько источников ${action.value}: уточните номер")
                val ok = click(target); delay(350)
                Outcome(ok && root()?.let { nodes(it).any { n -> n.isSelected && normalize(label(n)).contains(action.value) } } == true,
                    "Выбор ${action.value} ${if (ok) "запрошен; проверьте источник на экране" else "недоступен"}")
            }
            Action.Watch -> watch(pkg)
            is Action.Navigate -> {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val key = when (action.direction) {
                        Direction.LEFT -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT; Direction.RIGHT -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
                        Direction.UP -> AccessibilityService.GLOBAL_ACTION_DPAD_UP; Direction.DOWN -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
                        Direction.OK -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER; else -> null
                    }
                    if (key != null && environment.globalActions.contains(key)) {
                        repeat(action.count) {
                            if (environment.activePackage != pkg || !environment.globalAction(key)) return Outcome(false, "Приложение сменилось или команда пульта недоступна")
                            delay(150)
                        }
                        return Outcome(true, "Команда пульта передана приложению")
                    }
                }
                if (action.direction == Direction.OK) {
                    val focused = nodes(r).singleOrNull { it.isFocused }
                    val ok = focused?.let(::click) == true
                    Outcome(ok, if (ok) "Нажатие выбранного элемента запрошено" else "Доступный элемент в фокусе не найден")
                } else {
                    val direction = when (action.direction) { Direction.LEFT -> android.view.View.FOCUS_LEFT; Direction.RIGHT -> android.view.View.FOCUS_RIGHT; Direction.UP -> android.view.View.FOCUS_UP; else -> android.view.View.FOCUS_DOWN }
                    repeat(action.count) {
                        val focused = root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return Outcome(false, "Фокус приложения недоступен")
                        val next = focused.focusSearch(direction) ?: return Outcome(false, "Дальше нет доступного элемента")
                        if (!next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return Outcome(false, "Приложение не разрешило перемещение фокуса")
                        delay(120)
                    }
                    Outcome(true, "Фокус перемещён")
                }
            }
            Action.ClearCache -> Outcome(false, "Подтверждённая функция очистки кэша не обнаружена. Откройте Task Killer вручную; закрытие процессов не считается очисткой кэша")
            else -> Outcome(false, "Действие не поддерживается на этом экране")
        }
    }
    private suspend fun playChannel(query: String): Outcome {
        val pkg = "limehd.ru.lite"
        val profile = environment.profileId
        fun valid() = environment.enabled && environment.profileId == profile && allowed(pkg)
        if (!valid()) return Outcome(false, "Просмотр каналов недоступен в текущем профиле")
        if (environment.activePackage != pkg) {
            if (!environment.openPackage(pkg) || !confirmWindow(pkg))
                return Outcome(false, "Не удалось открыть Лайт HD TV для просмотра канала")
        }
        val searched = search(pkg, query)
        if (!searched.accepted) return searched
        if (!valid() || environment.activePackage != pkg) { clear(); return Outcome(false, "Экран или профиль изменился — повторите команду") }
        val found = snapshot ?: return Outcome(false, "Список каналов недоступен")
        // Never choose a neighbouring channel (for example ТНТ4 instead of ТНТ).
        val exact = found.items.withIndex().filter { exactResultTitle(it.value.label, query) }
        val selected = exact.singleOrNull() ?: return Outcome(false, "Точный канал «$query» не найден или найдено несколько вариантов. Уточните название")
        val chosen = choose(pkg, selected.index + 1)
        if (!chosen.accepted) return chosen
        val player = waitRoot(pkg) { tree ->
            val visible = nodes(tree)
            visible.any { it.viewIdResourceName?.substringAfterLast('/') == "programInformation" && exactResultTitle(label(it), selected.value.label) } &&
                visible.any { it.viewIdResourceName?.substringAfterLast('/') in setOf("player_view", "frame_player", "controls_container") }
        }
        if (!valid()) return Outcome(false, "Профиль изменился — повторите команду")
        return Outcome(player != null, if (player != null) "Открыт канал ${resultTitle(selected.value.label)}"
            else "Канал выбран; появление его плеера не подтверждено")
    }
    private suspend fun search(pkg: String, query: String): Outcome {
        snapshot = null
        val adapter = AppControlAdapters.forPackage(pkg)
        fun searchTargets(root: AccessibilityNodeInfo) = nodes(root).filter {
            uiLabel(label(it)) in searchLabels || it.viewIdResourceName?.substringAfterLast('/') in adapter.searchIds
        }.mapNotNull(::clickTarget).distinctBy { identity(it) }
        // R29: film apps (Lift is a WebView) draw their screen a few seconds after the window opens.
        // Wait up to ~10 s for a search field or a search button before giving up.
        var r = waitRoot(pkg, tries = 100) { tree -> nodes(tree).any(::searchInput) || searchTargets(tree).isNotEmpty() }
            ?: waitRoot(pkg) ?: return Outcome(false, "Приложение не открылось")
        var edit = nodes(r).filter(::searchInput).firstOrNull()
        if (edit == null && searchTargets(r).isEmpty() && adapter.searchFromPlayerViaBack &&
            nodes(r).any { it.viewIdResourceName?.substringAfterLast('/') in adapter.playerIds }) {
            if (!environment.globalAction(AccessibilityService.GLOBAL_ACTION_BACK)) return Outcome(false, "Не удалось вернуться к списку каналов")
            r = waitRoot(pkg) { searchTargets(it).isNotEmpty() } ?: return Outcome(false, "Список каналов не появился")
        }
        if (edit == null) {
            val search = searchTargets(r)
            // R30: a film app may show both a search bar and a «Поиск» menu item — either opens the search.
            val target = search.firstOrNull() ?: return Outcome(false, "Кнопка поиска не найдена за 20 секунд. Откройте поиск вручную")
            if (!click(target)) return Outcome(false, "Не удалось открыть поиск")
            r = waitRoot(pkg) { nodes(it).count(::textInput) == 1 } ?: return Outcome(false, "Поле поиска не появилось")
            edit = nodes(r).singleOrNull(::textInput)
        }
        val field = edit ?: return Outcome(false, "Поле поиска неоднозначно")
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }))
            return Outcome(false, "Приложение не разрешает ввод текста")
        delay(600)
        val fresh = waitRoot(pkg) ?: return Outcome(false, "Экран поиска закрыт")
        if (nodes(fresh).none { textInput(it) && normalize(it.text?.toString().orEmpty()) == normalize(query) }) return Outcome(false, "Ввод запроса не подтверждён")
        // Modern Android exposes the actual IME search action through Accessibility.
        nodes(fresh).singleOrNull(::textInput)?.let { f ->
            if (android.os.Build.VERSION.SDK_INT >= 30)
                f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        if (adapter.submitLabels.isNotEmpty()) {
            val submitRoot = waitRoot(pkg) { tree -> nodes(tree).any { uiLabel(label(it)) in adapter.submitLabels } || resultNodes(tree, query).isNotEmpty() }
            val submits = submitRoot?.let { tree -> nodes(tree).filter { uiLabel(label(it)) in adapter.submitLabels }
                .mapNotNull(::clickTarget).distinctBy { identity(it) } }.orEmpty()
            if (submits.size == 1 && !click(submits.single())) return Outcome(false, "Кнопка поиска недоступна")
            if (submits.size > 1) return Outcome(false, "Несколько кнопок поиска: выберите вручную")
        }
        delay(500)
        var resultRoot = waitRoot(pkg, tries = 15) { resultNodes(it, query).isNotEmpty() }
        // R30: Lift draws its own on-screen keyboard as a dialog over the results (checked on Homatics 29.10):
        // the results are there, but hidden behind it. Back closes only that keyboard.
        fun ownKeyboard(tree: AccessibilityNodeInfo) = nodes(tree).count { uiLabel(label(it)) in setOf("сменить язык", "пробел", "удалить символ", "курсор влево") } >= 2
        var keyboardClosed = "нет"
        if (resultRoot == null) root()?.takeIf { it.packageName?.toString() == pkg && ownKeyboard(it) }?.let {
            keyboardClosed = if (environment.globalAction(AccessibilityService.GLOBAL_ACTION_BACK)) "да" else "отказ"
            delay(900)
            resultRoot = waitRoot(pkg, tries = 25) { resultNodes(it, query).isNotEmpty() }
        }
        // R29: WebView apps (Lift) ignore IME_ENTER; press the keyboard's own «Искать»/Enter key instead.
        if (resultRoot == null) {
            val keys = environment.imeRoot()?.let { ime -> nodes(ime).filter { k ->
                uiLabel(label(k)) in setOf("искать", "поиск", "search", "go", "найти", "ввод", "enter", "готово", "done", "перейти")
            }.mapNotNull { it.takeIf { n -> n.isClickable } ?: it.parent } }.orEmpty()
            keys.firstOrNull()?.let { key ->
                if (!key.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    val b = android.graphics.Rect(); key.getBoundsInScreen(b); if (!b.isEmpty) environment.tap(b)
                }
            }
            resultRoot = waitRoot(pkg, tries = 40) { resultNodes(it, query).isNotEmpty() }
        }
        val candidates = resultRoot?.let { resultNodes(it, query) }.orEmpty()
        if (candidates.isNotEmpty()) {
            snapshot = Snapshot(pkg, SystemClock.elapsedRealtime(), candidates.map(::identity), environment.profileId, query)
            return Outcome(true, "Найдено на экране: ${candidates.size}. Скажите «первый» или «второй»")
        }
        // R30 diagnosis: what the screen exposes when results are not recognised (goes to /diag with the outcome).
        val seen = root()?.let { tree -> nodes(tree).map { label(it) }.filter { it.isNotBlank() }.take(12).joinToString(" | ") }.orEmpty()
        return Outcome(false, "Запрос «$query» введён. Результаты пока не подтверждены [клавиатура закрыта: $keyboardClosed; на экране: ${seen.take(300)}]")
    }
    private fun resultNodes(r: AccessibilityNodeInfo, query: String? = null): List<AccessibilityNodeInfo> {
        val adapter = AppControlAdapters.forPackage(r.packageName?.toString().orEmpty())
        val matching = nodes(r).filter { n ->
            val text = normalize(label(n))
            !textInput(n) && text.length >= 2 && text !in setOf("поиск", "search", "назад", "главная", "фильмы", "сериалы") &&
                (query == null || relevantResultTitle(text, query)) && clickTarget(n) != null
        }
        val titles = matching.filter { it.viewIdResourceName?.substringAfterLast('/') in adapter.titleIds }
        return titles.ifEmpty { matching }.groupBy { clickTarget(it)?.let(::identity)?.bounds }
            .values.map { group -> group.minBy { label(it).length } }.take(20)
    }
    private suspend fun watch(pkg: String): Outcome {
        val found = snapshot
        if (found != null && found.pkg == pkg) {
            val exact = found.items.withIndex().filter { exactResultTitle(it.value.label, found.query) }
            val index = exact.singleOrNull()?.index ?: if (found.items.size == 1) 0 else
                return Outcome(false, "Несколько результатов. Скажите «первый» или «второй», затем «смотреть»")
            val chosen = choose(pkg, index + 1)
            if (!chosen.accepted) return chosen
        }
        if (environment.isPlaying(pkg) == true) return Outcome(true, "Воспроизведение запущено")
        // R29: the film page loads after the card is chosen — wait up to ~10 s for its «Смотреть» button.
        fun watchTargets(tree: AccessibilityNodeInfo) = nodes(tree).filter { isWatchLabel(label(it)) }.mapNotNull(::clickTarget).distinctBy { identity(it) }
        val r = waitRoot(pkg, tries = 50) { watchTargets(it).isNotEmpty() || environment.isPlaying(pkg) == true }
            ?: waitRoot(pkg) ?: return Outcome(false, "Окно приложения недоступно")
        if (environment.isPlaying(pkg) == true) return Outcome(true, "Воспроизведение запущено")
        val targets = watchTargets(r).let { all -> if (all.size > 1) all.take(1) else all }
        val target = targets.singleOrNull() ?: return Outcome(false, "Карточка выбрана. Однозначная кнопка «Смотреть» не найдена; проверьте экран или выберите источник")
        if (!click(target)) return Outcome(false, "Кнопка воспроизведения недоступна")
        repeat(12) {
            delay(250)
            if (environment.isPlaying(pkg) == true) return Outcome(true, "Воспроизведение запущено")
        }
        return Outcome(false, "Кнопка «Смотреть» нажата; воспроизведение ещё не подтверждено")
    }
    private suspend fun choose(pkg: String, index: Int): Outcome {
        val s = snapshot ?: return Outcome(false, "Нет свежего списка результатов. Повторите поиск")
        if (s.pkg != pkg || SystemClock.elapsedRealtime() - s.at > 120_000 || s.profile != environment.profileId) { snapshot = null; return Outcome(false, "Контекст изменился. Повторите поиск") }
        val selected = s.items.getOrNull(index - 1) ?: return Outcome(false, "В списке нет варианта $index")
        val r = root() ?: return Outcome(false, "Экран недоступен")
        val visible = nodes(r)
        // Verify the entire captured list before using its ordinal, including ordering.
        val identities = visible.map(::identity)
        if (identities.filter { it in s.items } != s.items) { snapshot = null; return Outcome(false, "Список изменился. Повторите поиск") }
        val target = visible.singleOrNull { identity(it) == selected } ?: return Outcome(false, "Выбор неоднозначен")
        if (!click(target)) return Outcome(false, "Приложение не разрешило выбор")
        snapshot = null; delay(500)
        val changed = root()?.let { nodes(it).map(::identity) != identities } == true
        return Outcome(changed, if (changed) "Открыт вариант $index: ${selected.label}" else "Нажатие отправлено; переход не подтверждён")
    }
}

