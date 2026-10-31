package com.nuvio.tv.core.apps

/**
 * Shared by Home and Settings so a saved Installed Apps position means the same thing in both.
 * In follow-addons mode only independent rows may move; addon catalogs retain manifest order.
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
    if (!followAddonsOrder) return saved + (addons + independent).filterNot { it in saved }
    if (saved.isEmpty()) return addons + independent

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
