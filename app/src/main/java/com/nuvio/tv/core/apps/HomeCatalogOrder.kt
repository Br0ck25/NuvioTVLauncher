package com.nuvio.tv.core.apps

/**
 * Shared by Home and Settings so a saved Installed Apps position means the same thing in both.
 * In follow-addons mode only independent rows may move; addon catalogs retain manifest order.
 *
 * The Installed Apps row starts at the very top of Home and only moves once the user reorders it, so
 * `null`/stale saved orders never bury it under every catalog.
 */
fun mergeHomeCatalogOrder(
    addonKeys: List<String>,
    independentKeys: List<String>,
    savedOrderKeys: List<String>,
    followAddonsOrder: Boolean,
    addonOwner: (String) -> String? = { it }
): List<String> {
    val addons = addonKeys.distinct()
    val independent = independentKeys.distinct().filterNot { it in addons }
    val available = (addons + independent).toSet()
    val saved = savedOrderKeys.asSequence().filter { it in available }.distinct().toList()
    // Never saved, or the key appeared after the order was stored: lead with it.
    val unsavedDefaultFirst = independent.filter {
        it == INSTALLED_APPS_CATALOG_KEY && it !in saved
    }
    if (!followAddonsOrder) {
        return unsavedDefaultFirst +
            saved +
            (addons + independent).filterNot { it in saved || it in unsavedDefaultFirst }
    }
    if (saved.isEmpty()) {
        return unsavedDefaultFirst + addons + independent.filterNot { it in unsavedDefaultFirst }
    }

    val independentSet = independent.toSet()
    val result = mutableListOf<String>()
    var pointer = 0
    for (key in saved) {
        if (key in independentSet) {
            result.add(key)
        } else {
            val target = addons.indexOf(key)
            while (pointer <= target) {
                if (addons[pointer] !in result) result.add(addons[pointer])
                pointer++
            }
        }
    }
    while (pointer < addons.size) {
        if (addons[pointer] !in result) result.add(addons[pointer])
        pointer++
    }
    independent.forEach { if (it !in result) result.add(it) }
    // Still above everything when it was never given a saved position.
    if (unsavedDefaultFirst.isNotEmpty()) {
        result.removeAll(unsavedDefaultFirst.toSet())
        result.addAll(0, unsavedDefaultFirst)
    }
    return normalizeIndependentRows(result, independentSet, addonOwner)
}

/** An independent row cannot split two catalogs belonging to the same addon. */
private fun normalizeIndependentRows(
    order: List<String>,
    independentKeys: Set<String>,
    owner: (String) -> String?
): List<String> {
    val result = order.toMutableList()
    var changed: Boolean
    do {
        changed = false
        for (index in result.indices) {
            val key = result[index]
            if (key !in independentKeys) continue
            val previousOwner = result.take(index).lastOrNull { it !in independentKeys }?.let(owner)
            val nextOwner = result.drop(index + 1).firstOrNull { it !in independentKeys }?.let(owner)
            if (previousOwner == null || previousOwner != nextOwner) continue
            result.removeAt(index)
            var insertAt = index
            while (insertAt < result.size && result[insertAt] !in independentKeys && owner(result[insertAt]) == previousOwner) {
                insertAt++
            }
            result.add(insertAt, key)
            changed = changed || insertAt != index
            break
        }
    } while (changed)
    return result
}

/** Move between addon blocks, or swap with an adjacent independent row. */
fun moveIndependentHomeCatalog(
    order: List<String>,
    key: String,
    direction: Int,
    addonOwner: (String) -> String?
): List<String> {
    val index = order.indexOf(key)
    if (index < 0 || !isIndependentHomeCatalogKey(key) || direction == 0) return order
    val adjacent = index + direction
    if (adjacent !in order.indices) return order
    if (isIndependentHomeCatalogKey(order[adjacent])) return moveInstalledApp(order, key, direction)
    var target = adjacent
    while (target + direction in order.indices &&
        !isIndependentHomeCatalogKey(order[target + direction]) &&
        addonOwner(order[target + direction]) == addonOwner(order[adjacent])
    ) {
        target += direction
    }
    return order.toMutableList().apply {
        removeAt(index)
        add(target, key)
    }
}
