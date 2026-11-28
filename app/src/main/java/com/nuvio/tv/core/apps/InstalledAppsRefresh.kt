package com.nuvio.tv.core.apps

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Package broadcasts closer together than this are answered with one query. */
internal const val INSTALLED_APPS_REFRESH_WINDOW_MS = 2_000L

/**
 * Runs [query] for each signal in this flow, the way the installed-apps list needs it:
 *  - The first signal is answered at once; after that, a burst of signals (some TVs send PACKAGE_CHANGED about once a
 *    minute for their own system packages) is answered with one query, [windowMs] after the last one.
 *  - A failing query (for example DeadSystemException from the package manager under memory pressure) must not take
 *    the app down: the last good result is emitted again, or [fallback] if there has been none yet.
 */
@OptIn(FlowPreview::class)
internal fun <T> Flow<Unit>.refreshSafely(
    windowMs: Long = INSTALLED_APPS_REFRESH_WINDOW_MS,
    fallback: T,
    query: () -> T
): Flow<T> = flow {
    var first = true
    var last = fallback
    emitAll(
        this@refreshSafely
            .debounce { if (first) 0L else windowMs }
            .conflate()
            .map {
                first = false
                try {
                    query().also { last = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    last
                }
            }
    )
}
