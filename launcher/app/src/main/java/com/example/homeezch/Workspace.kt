package com.example.homeezch

/** Immutable edit transaction: the saved instance remains the cancellation snapshot. */
internal data class Workspace(
    val rows: List<AppShelf>,
    val hiddenApps: List<String> = emptyList(),
    val sourceOrder: List<String> = emptyList(),
    val hiddenSources: List<String> = emptyList(),
    val removedApps: List<String> = emptyList()
) {
    fun discover(available: List<String>): Workspace {
        val seen = mutableSetOf<String>()
        val cleaned = rows.map { row -> row.copy(appKeys = row.appKeys.filter { it !in removedApps && seen.add(it) }) }
            .ifEmpty { listOf(AppShelf("main", "Приложения", emptyList())) }
        val fresh = available.filter { it !in hiddenApps && it !in removedApps && seen.add(it) }
        return copy(rows = cleaned.mapIndexed { i, row ->
            if (i == 0) row.copy(appKeys = row.appKeys + fresh) else row
        })
    }
    fun moveApp(key: String, horizontal: Int = 0, vertical: Int = 0,
                available: Set<String>): Workspace {
        val rowIndex = rows.indexOfFirst { key in it.appKeys }
        if (rowIndex < 0) return this
        val row = rows[rowIndex]
        val visible = row.appKeys.filter { it in available && it !in hiddenApps }
        val index = visible.indexOf(key)
        if (index < 0) return this
        if (vertical != 0) {
            val targetIndex = rowIndex + vertical
            if (targetIndex !in rows.indices) return this
            val target = rows[targetIndex]
            val targetVisible = target.appKeys.filter { it in available && it !in hiddenApps }
            val anchor = targetVisible.getOrNull(index)
            val insertAt = anchor?.let { target.appKeys.indexOf(it) } ?: target.appKeys.size
            val keys = target.appKeys.toMutableList().apply { add(insertAt, key) }
            return copy(rows = rows.mapIndexed { i, r -> when (i) {
                rowIndex -> r.copy(appKeys = r.appKeys - key)
                targetIndex -> r.copy(appKeys = keys)
                else -> r
            } })
        }
        val adjacent = visible.getOrNull(index + horizontal) ?: return this
        if (horizontal == 0) return this
        val keys = row.appKeys.toMutableList()
        val a = keys.indexOf(key); val b = keys.indexOf(adjacent)
        keys[a] = adjacent; keys[b] = key
        return copy(rows = rows.mapIndexed { i, r -> if (i == rowIndex) r.copy(appKeys = keys) else r })
    }
    fun moveSource(key: String, delta: Int, available: List<String>): Workspace {
        val all = (sourceOrder + available).distinct()
        val visible = all.filter { it in available && it !in hiddenSources }
        val i = visible.indexOf(key)
        if (i < 0) return this
        val neighbor = visible.getOrNull(i + delta) ?: return this
        val keys = all.toMutableList()
        val a = keys.indexOf(key); val b = keys.indexOf(neighbor)
        keys[a] = neighbor; keys[b] = key
        return copy(sourceOrder = keys)
    }
    fun hideApp(key: String) = copy(hiddenApps = (hiddenApps + key).distinct())
    fun hideSource(key: String) = copy(hiddenSources = (hiddenSources + key).distinct())
    fun removeApp(key: String) = copy(rows = rows.map { it.copy(appKeys = it.appKeys - key) },
        hiddenApps = hiddenApps - key, removedApps = (removedApps + key).distinct())
    fun restoreApp(key: String): Workspace {
        val target = rows.firstOrNull()?.id ?: return this
        return if (rows.any { key in it.appKeys }) copy(hiddenApps = hiddenApps - key, removedApps = removedApps - key)
            else placeApp(key, target)
    }
    fun placeApp(key: String, rowId: String): Workspace {
        if (rows.none { it.id == rowId }) return this
        return copy(rows = rows.map { row ->
            if (row.id == rowId) row.copy(appKeys = if (key in row.appKeys) row.appKeys else row.appKeys + key)
            else row.copy(appKeys = row.appKeys - key)
        }, hiddenApps = hiddenApps - key, removedApps = removedApps - key)
    }
    fun favorite(key: String): Workspace {
        if (rows.none { key in it.appKeys }) return this
        val target = rows.firstOrNull { it.id == "favorites" } ?: AppShelf("favorites", "Избранное", emptyList())
        val other = rows.filterNot { it.id == target.id }.map { it.copy(appKeys = it.appKeys - key) }
        return copy(rows = listOf(target.copy(appKeys = (target.appKeys + key).distinct())) + other,
            hiddenApps = hiddenApps - key, removedApps = removedApps - key)
    }
    fun addRow(title: String, id: String) = copy(rows = rows + AppShelf(id, title, emptyList()))
    fun renameRow(id: String, title: String): Workspace {
        val clean = title.trim().take(40)
        if (clean.isEmpty()) return this
        return copy(rows = rows.map { if (it.id == id) it.copy(title = clean) else it })
    }
    fun moveRow(id: String, delta: Int): Workspace {
        val i = rows.indexOfFirst { it.id == id }; val j = i + delta
        if (i < 0 || j !in rows.indices) return this
        val list = rows.toMutableList(); val row = list.removeAt(i); list.add(j, row)
        return copy(rows = list)
    }
    fun removeRow(id: String): Workspace {
        if (rows.size <= 1) return this
        val removed = rows.firstOrNull { it.id == id } ?: return this
        val remaining = rows.filterNot { it.id == id }
        return copy(rows = remaining.mapIndexed { i, row ->
            if (i == 0) row.copy(appKeys = (row.appKeys + removed.appKeys).distinct()) else row
        })
    }
}
