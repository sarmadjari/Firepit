package com.getfirepit.core.data

import java.io.File

/**
 * Sealed messages already opened, so a recording played back is refused.
 *
 * A copy of a sealed packet opens as well as the original: the seal proves who
 * wrote it, not when it was sent. Somebody who recorded a room could otherwise
 * play back a receipt, a position or a roster change and have it believed
 * again. Each sealed message carries a nonce that is never repeated, so a
 * second arrival of one is a copy, whatever packet it came wrapped in.
 *
 * Only the hours a message can still be opened in need remembering
 * ([com.getfirepit.core.crypto.RoomRatchet.opens]); anything older is refused
 * before it gets here. So the list is a few hours of traffic at most, and is
 * kept on disk so that restarting the app is not a way in either. Nothing in it
 * is secret: a nonce travels in the clear.
 */
class SeenSeals(private val file: File?) {

    /** Each message's identity, and the hour it was sealed in, for forgetting it later. */
    private val seen = HashMap<String, Int>()
    private var loaded = false

    /**
     * True the first time a message is offered, and false for every copy after.
     * Only called for a message that opened, so nobody without the key can
     * fill the list.
     */
    @Synchronized
    fun firstSight(roomId: Int, generation: Int, sender: Int, hour: Int, nonce: ByteArray, nowHour: Int): Boolean {
        load()
        val pruned = prune(nowHour)
        val id = idOf(roomId, generation, sender, nonce)
        if (id in seen) {
            if (pruned) save()
            return false
        }
        seen[id] = hour
        save()
        return true
    }

    /** How many messages are remembered. For tests. */
    @Synchronized
    fun size(): Int {
        load()
        return seen.size
    }

    private fun prune(nowHour: Int): Boolean {
        val before = seen.size
        seen.values.removeAll { it < nowHour - KEEP_HOURS }
        if (seen.size > MAX_ENTRIES) {
            seen.entries.sortedBy { it.value }.take(seen.size - MAX_ENTRIES).forEach { seen.remove(it.key) }
        }
        return seen.size != before
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val lines = runCatching { file?.takeIf(File::exists)?.readLines() }.getOrNull() ?: return
        lines.forEach { line ->
            val parts = line.split(' ')
            val hour = parts.getOrNull(4)?.toIntOrNull() ?: return@forEach
            if (parts.size == 5) seen[parts.take(4).joinToString(" ")] = hour
        }
    }

    private fun save() {
        val target = file ?: return
        runCatching {
            val staged = File(target.parentFile, "${target.name}.tmp")
            staged.writeText(seen.entries.joinToString("") { (id, hour) -> "$id $hour\n" })
            if (!staged.renameTo(target)) {
                target.delete()
                staged.renameTo(target)
            }
        }
    }

    private fun idOf(roomId: Int, generation: Int, sender: Int, nonce: ByteArray): String =
        "$roomId $generation $sender ${nonce.joinToString("") { "%02x".format(it) }}"

    private companion object {
        /** The hour just gone, this one and the next can open; one more for a clock that stepped back. */
        const val KEEP_HOURS = 2

        /** More than a LoRa channel can carry in [KEEP_HOURS] hours, so only a fault reaches it. */
        const val MAX_ENTRIES = 20_000
    }
}
