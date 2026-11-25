package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.domain.model.MetaPreview
import java.util.LinkedHashMap

/** A small access-ordered cache for optional Home enrichment; provider data can be reloaded on demand. */
internal class BoundedHomeMap<V>(
    private val maxEntries: Int,
    private val maxWeight: Long,
    private val weightOf: (V) -> Long,
) {
    private val lock = Any()
    private val values = LinkedHashMap<String, V>(16, 0.75f, true)
    private var weight = 0L

    operator fun get(key: String): V? = synchronized(lock) { values[key] }

    operator fun set(key: String, value: V) = synchronized(lock) {
        values.remove(key)?.let { weight -= weightOf(it) }
        values[key] = value
        weight += weightOf(value).coerceAtLeast(0L)
        trim()
    }

    fun containsKey(key: String): Boolean = synchronized(lock) { values.containsKey(key) }

    fun remove(key: String): V? = synchronized(lock) {
        values.remove(key)?.also { weight -= weightOf(it).coerceAtLeast(0L) }
    }

    fun clear() = synchronized(lock) {
        values.clear()
        weight = 0L
    }

    private fun trim() {
        val iterator = values.entries.iterator()
        // Keep one current item even if its metadata alone exceeds the estimate budget.
        while ((values.size > maxEntries || (weight > maxWeight && values.size > 1)) && iterator.hasNext()) {
            val entry = iterator.next()
            weight -= weightOf(entry.value).coerceAtLeast(0L)
            iterator.remove()
        }
    }
}

internal class BoundedHomeSet(private val maxEntries: Int) {
    private val lock = Any()
    private val values = LinkedHashMap<String, Unit>(16, 0.75f, true)

    fun add(key: String): Boolean = synchronized(lock) {
        if (values.containsKey(key)) {
            values[key]
            false
        } else {
            values[key] = Unit
            while (values.size > maxEntries) values.entries.iterator().run { next(); remove() }
            true
        }
    }

    operator fun contains(key: String): Boolean = synchronized(lock) { values.containsKey(key) }
    operator fun plusAssign(key: String) { add(key) }
    fun remove(key: String): Boolean = synchronized(lock) { values.remove(key) != null }
    fun clear() = synchronized(lock) { values.clear() }
}

internal fun metaPreviewWeight(meta: MetaPreview): Long =
    listOfNotNull(
        meta.id, meta.rawType, meta.name, meta.poster, meta.background, meta.logo, meta.description,
        meta.releaseInfo, meta.runtime, meta.status, meta.ageRating, meta.language, meta.released,
        meta.country, meta.imdbId, meta.slug, meta.landscapePoster, meta.rawPosterUrl,
        meta.sourceAddonBaseUrl, meta.behaviorHints?.defaultVideoId
    ).sumOf { it.length.toLong() } +
        meta.genres.sumOf { it.length.toLong() } + meta.director.sumOf { it.length.toLong() } +
        meta.writer.sumOf { it.length.toLong() } + meta.trailerYtIds.sumOf { it.length.toLong() } +
        meta.mdbListRatingOrder.sumOf { it.length.toLong() } +
        meta.links.sumOf { it.name.length.toLong() + it.category.length + it.url.length } +
        meta.trailers.sumOf {
            it.source.orEmpty().length.toLong() + it.type.orEmpty().length + it.name.orEmpty().length +
                it.ytId.orEmpty().length + it.lang.orEmpty().length
        }
