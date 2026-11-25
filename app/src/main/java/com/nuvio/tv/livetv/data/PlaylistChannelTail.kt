package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveChannel
import java.util.BitSet

/** Tracks candidate stray positions while an M3U is converted directly into the shared channel list. */
internal class PlaylistChannelTail(
    private val channels: MutableList<LiveChannel>,
    private val usedKeys: MutableSet<String>,
    private val firstNumber: Int,
) {
    private val from = channels.size
    private val unmarked = BitSet()
    var removed: Int = 0
        private set

    /** Call immediately before appending an unmarked channel. */
    fun unmarkedEntry() { unmarked.set(channels.size - from) }

    /** Parsing may fail after emitting some entries; roll those back as one playlist transaction. */
    fun rollback() {
        for (i in from until channels.size) usedKeys.remove(channels[i].key)
        channels.subList(from, channels.size).clear()
    }

    /** Removes episode-named, unmarked entries if the completed playlist meets the stray rule. */
    fun removeStrays(nextNumber: Int, isEpisodeName: (String) -> Boolean): Int {
        var candidate = unmarked.nextSetBit(0)
        while (candidate >= 0) {
            if (!isEpisodeName(channels[from + candidate].name)) unmarked.clear(candidate)
            candidate = unmarked.nextSetBit(candidate + 1)
        }
        removed = unmarked.cardinality()
        if (removed == 0) return nextNumber

        for (i in from until channels.size) usedKeys.remove(channels[i].key)
        var counter = firstNumber
        var write = from
        for (read in from until channels.size) {
            if (unmarked[read - from]) continue
            val channel = channels[read]
            val baseKey = "${channel.sourceId}|${channel.tvgId ?: ""}|${channel.name}"
            var key = baseKey
            var suffix = 2
            while (!usedKeys.add(key)) key = "$baseKey#${suffix++}"
            val number = if (channel.autoNumber) counter else channel.number
            counter = maxOf(counter, number) + 1
            channels[write++] = if (key == channel.key && number == channel.number) channel
                else channel.copy(key = key, number = number)
        }
        channels.subList(write, channels.size).clear()
        return counter
    }
}
