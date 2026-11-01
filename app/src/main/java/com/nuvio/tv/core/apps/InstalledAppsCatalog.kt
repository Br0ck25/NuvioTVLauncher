package com.nuvio.tv.core.apps

const val INSTALLED_APPS_CATALOG_KEY = "installed_apps"

/** Collections and the device-app row can sit between manifest-ordered addon blocks. */
fun isIndependentHomeCatalogKey(key: String): Boolean =
    key == INSTALLED_APPS_CATALOG_KEY || key.startsWith("collection_")

/** Saved packages first; newly installed packages append alphabetically. Removals never uninstall. */
fun orderInstalledApps(
    availablePackages: List<String>,
    savedOrder: List<String>,
    removedPackages: Set<String>
): List<String> {
    val available = availablePackages.toSet()
    val saved = savedOrder.asSequence().filter { it in available && it !in removedPackages }.distinct().toList()
    val savedSet = saved.toSet()
    return saved + availablePackages.distinct().filter { it !in savedSet && it !in removedPackages }
}

fun moveInstalledApp(order: List<String>, packageName: String, direction: Int): List<String> {
    val index = order.indexOf(packageName)
    val target = index + direction
    if (index < 0 || target !in order.indices || direction == 0) return order
    return order.toMutableList().apply { this[index] = this[target]; this[target] = packageName }
}
