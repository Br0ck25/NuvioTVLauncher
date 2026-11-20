package com.nuvio.tv.core.apps

/**
 * The Apps row's key in the Home rows' order list. It sits in the same family as collection keys
 * ("collection_...") on purpose: the reorder screen, the "follow addons' order" mode and the row
 * placement all already know how to move such a row between catalog blocks, so Apps moves like any row.
 */
const val INSTALLED_APPS_CATALOG_KEY = "collection_installed_apps"
