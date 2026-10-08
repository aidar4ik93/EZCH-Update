package com.example.homeezch

/** Immutable edit transaction: the saved instance remains the cancellation snapshot. */
internal data class Workspace(
    val rows: List<AppShelf>,
    val hiddenApps: List<String> = emptyList(),
    val sourceOrder: List<String> = emptyList(),
    val hiddenSources: List<String> = emptyList()
) {
    fun discover(available: List<String>): Workspace {
        val seen = mutableSetOf<String>()
        val cleaned = rows.map { row -> row.copy(appKeys = row.appKeys.filter { seen.add(it) }) }
            .ifEmpty { listOf(AppShelf("main", "Приложения", emptyList())) }
        val fresh = available.filter { it !in hiddenApps && seen.add(it) }
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
    fun restoreApp(key: String) = copy(hiddenApps = hiddenApps - key)
    fun addRow(title: String, id: String) = copy(rows = rows + AppShelf(id, title, emptyList()))
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
