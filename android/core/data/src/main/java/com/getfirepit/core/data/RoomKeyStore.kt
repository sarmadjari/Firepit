package com.getfirepit.core.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import androidx.core.content.edit
import com.getfirepit.core.crypto.DirectSeal
import com.getfirepit.core.crypto.HourKey
import com.getfirepit.core.crypto.RoomCipher
import com.getfirepit.core.crypto.RoomRatchet
import com.getfirepit.core.crypto.SealedText
import com.getfirepit.core.database.KeystoreWrapping
import com.getfirepit.protocol.meshchat.SealedMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import okio.ByteString.Companion.toByteString
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The keys that open rooms, held where the radio cannot reach, and destroyed
 * as the clock moves on.
 *
 * A room key has to leave the phone — sealed to another phone's key in a grant
 * or a rotation — so it cannot live inside the Keystore itself. Instead each one
 * is wrapped by a master key that never leaves secure hardware, so what sits in
 * preferences is useless to anyone who copies the file off the phone.
 *
 * For each generation of a room's key, one hour's key is kept: the hour just
 * gone, so a packet the mesh delivers late still opens. Later hours are derived
 * from it when needed and earlier ones are destroyed, busy room or quiet
 * ([RoomRatchet]), so whoever takes the phone cannot read what was said before.
 */
@Singleton
class RoomKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences =
        context.getSharedPreferences("firepit_room_keys", Context.MODE_PRIVATE)

    private val seen = SeenSeals(File(context.noBackupFilesDir, SEEN_FILE))

    /**
     * Unwrapped keys, so the Keystore is asked once per room rather than once
     * per message. A busy room decrypts on every arrival, and the platform asks
     * that its Keystore not be used on hot paths or the main thread.
     */
    private val unwrapped = ConcurrentHashMap<String, HourKey>()

    /** Moving a key on is read, derive, write: two at once would each keep a different hour. */
    private val lock = Any()

    /**
     * The phone's clock for sealing and opening, and real time for erasing
     * (see [KeyClock]). Replaced only by tests.
     */
    internal var time: KeyTime = KeyClock(
        wall = System::currentTimeMillis,
        monotonic = SystemClock::elapsedRealtime,
        boot = {
            runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString() }
                .getOrNull()
        },
        anchors = PreferenceAnchors(context.getSharedPreferences("firepit_key_clock", Context.MODE_PRIVATE)),
    )

    /**
     * True when this phone holds the key [roomId] seals with now, which is what makes a slot one of ours.
     * Under the lock, so a room being forgotten at that moment cannot have its key put back in memory.
     */
    fun holds(roomId: Int): Boolean = synchronized(lock) {
        held(roomId, generationOf(roomId))?.key?.fill(0) != null
    }

    /**
     * True when something new may be sealed for [roomId]: we hold its key, and
     * the room has not moved to one that never reached us. The only other
     * people still sealing under an old key are whoever was removed.
     */
    fun canSeal(roomId: Int): Boolean = !isSuperseded(roomId) && holds(roomId)

    /** Which generation this room is sealing with. */
    fun generationOf(roomId: Int): Int = preferences.getInt(current(roomId), FIRST)

    /** True when a member told us the room moved on to a later key than the one we hold. */
    fun isSuperseded(roomId: Int): Boolean = preferences.getInt(superseded(roomId), 0) > generationOf(roomId)

    /** Records that [roomId] has moved on to [generation]; cleared once we hold that key. */
    fun markSuperseded(roomId: Int, generation: Int) {
        if (generation <= generationOf(roomId)) return
        preferences.edit { putInt(superseded(roomId), maxOf(generation, preferences.getInt(superseded(roomId), 0))) }
    }

    /**
     * [plaintext] sealed by [sender] for [roomId] under this hour's key, ready
     * to travel. Null when there is no key to seal with.
     *
     * [generation] is for a rotation, which is sealed under the key a member
     * still holds. Anything else uses the room's current key, and nothing is
     * sealed at all once the room has moved on without us.
     */
    fun seal(roomId: Int, sender: Int, plaintext: ByteArray, generation: Int? = null): SealedMessage? {
        if (generation == null && isSuperseded(roomId)) return null
        val sealingGeneration = generation ?: generationOf(roomId)
        val now = RoomRatchet.hourOf(time.wallMillis())
        val held = advanced(roomId, sealingGeneration, erasableHour()) ?: return null
        val hour = RoomRatchet.currentHour(held.hour, now)
        val key = senderKey(roomId, sealingGeneration, held, hour, sender) ?: return null
        return try {
            SealedMessage(
                room_id = roomId,
                ciphertext = SealedText.seal(key, hour, plaintext, SealedText.contextOf(roomId, sender)).toByteString(),
                generation = sealingGeneration,
            )
        } finally {
            key.fill(0)
        }
    }

    /**
     * What [sender] sealed for [roomId] under [generation], opened, or why not.
     *
     * Only messages sealed in the hours this phone still holds keys for open,
     * and each one only once.
     */
    fun open(roomId: Int, generation: Int, sender: Int, payload: ByteArray): Opening {
        val tag = SealedText.hourTagOf(payload)
            ?: return if (SealedText.isFirstFormat(payload)) Opening.Outdated else Opening.Unreadable
        val now = RoomRatchet.hourOf(time.wallMillis())
        val erasable = erasableHour()
        val held = advanced(roomId, generation, erasable) ?: return Opening.NoKey
        val hour = RoomRatchet.hourNear(tag, RoomRatchet.currentHour(held.hour, now))
        if (!RoomRatchet.opens(held.hour, now, hour)) {
            held.key.fill(0)
            return Opening.OutOfHours(hour, now)
        }
        val key = senderKey(roomId, generation, held, hour, sender) ?: return Opening.NoKey
        val plain = try {
            SealedText.open(key, payload, SealedText.contextOf(roomId, sender))
        } finally {
            key.fill(0)
        } ?: return Opening.Unreadable
        val nonce = SealedText.nonceOf(payload) ?: return Opening.Unreadable
        if (!seen.firstSight(roomId, generation, sender, hour, nonce, erasable)) return Opening.Replayed
        // Another phone sealed this in our hour: the room agrees with our clock.
        if (abs(hour - now) <= 1) time.agreed()
        return Opening.Read(plain)
    }

    /**
     * This hour's key for [generation] of [roomId]: what somebody joining, or
     * a member who missed a rotation, is handed. Never an earlier hour's, so
     * they cannot read what was said before they had it.
     */
    fun currentKey(roomId: Int, generation: Int = generationOf(roomId)): HourKey? {
        val now = RoomRatchet.hourOf(time.wallMillis())
        val held = advanced(roomId, generation, erasableHour()) ?: return null
        val hour = RoomRatchet.currentHour(held.hour, now)
        val key = try {
            RoomRatchet.forward(held.key, roomId, generation, held.hour, hour)
        } finally {
            held.key.fill(0)
        } ?: return null
        return HourKey(hour, key)
    }

    fun directSecretForSealing(roomId: Int): DirectSeal.RoomSecret? = synchronized(lock) {
        if (isSuperseded(roomId)) return null
        val generation = generationOf(roomId)
        val now = RoomRatchet.hourOf(time.wallMillis())
        val held = advanced(roomId, generation, erasableHour()) ?: return null
        val hour = RoomRatchet.currentHour(held.hour, now)
        val key = try {
            RoomRatchet.forward(held.key, roomId, generation, held.hour, hour)
        } finally {
            held.key.fill(0)
        } ?: return null
        DirectSeal.RoomSecret(roomId, generation, hour, key)
    }

    fun directSecretsForOpening(tag: Int): List<DirectSeal.RoomSecret> = synchronized(lock) {
        val now = RoomRatchet.hourOf(time.wallMillis())
        val erasable = erasableHour()
        preferences.all.keys
            .mapNotNull(::parseSlot)
            .distinct()
            .sortedWith(compareBy<RoomGeneration> { it.roomId }.thenBy { it.generation })
            .mapNotNull { generation ->
                val held = advanced(generation.roomId, generation.generation, erasable) ?: return@mapNotNull null
                val hour = RoomRatchet.hourNear(tag, RoomRatchet.currentHour(held.hour, now))
                if (!RoomRatchet.opens(held.hour, now, hour)) {
                    held.key.fill(0)
                    return@mapNotNull null
                }
                val key = try {
                    RoomRatchet.forward(held.key, generation.roomId, generation.generation, held.hour, hour)
                } finally {
                    held.key.fill(0)
                } ?: return@mapNotNull null
                DirectSeal.RoomSecret(generation.roomId, generation.generation, hour, key)
            }
    }

    fun directTagInWindow(tag: Int): Boolean {
        val now = RoomRatchet.hourOf(time.wallMillis())
        val hour = RoomRatchet.hourNear(tag, now)
        return hour in (now - 1)..(now + 1)
    }

    fun firstDirectSight(sender: Int, opening: DirectSeal.Opening): Boolean {
        val room = opening.room ?: return true
        val hour = opening.hour ?: return true
        val nonce = opening.nonce ?: return true
        return seen.firstSight(room.roomId, room.generation, sender, hour, nonce, erasableHour())
    }

    fun remember(roomId: Int, key: HourKey, generation: Int = FIRST) {
        synchronized(lock) {
            val previous = generationOf(roomId)
            val now = erasableHour()
            preferences.edit {
                putString(slot(roomId, generation), encode(key))
                remove(retired(roomId, generation))
                when {
                    // Never walk backwards: a late rotation message must not
                    // undo a newer one that has already been applied.
                    generation < previous -> putInt(retired(roomId, generation), now)
                    generation > previous -> {
                        putInt(current(roomId), generation)
                        // Kept a little longer, for packets sealed just before the move.
                        if (preferences.contains(slot(roomId, previous))) putInt(retired(roomId, previous), now)
                    }
                    else -> putInt(current(roomId), generation)
                }
                // Holding the key the room moved to is the end of being left behind.
                if (generation >= preferences.getInt(superseded(roomId), 0)) remove(superseded(roomId))
            }
            unwrapped.put(slot(roomId, generation), key.copy())?.key?.fill(0)
        }
    }

    /** A new key for [roomId], starting this hour. */
    fun generate(roomId: Int, generation: Int = FIRST): HourKey =
        HourKey(RoomRatchet.hourOf(time.wallMillis()), RoomCipher.generateKey()).also { remember(roomId, it, generation) }

    /**
     * Destroys every key that is no longer needed, in every room.
     *
     * Run on the clock, not on traffic: a quiet room has to forget as surely
     * as a busy one. A generation the room has moved on from goes entirely
     * once late packets sealed under it have had time to arrive, unless it is
     * in [owed]: a member who missed the move still holds it, and their new key
     * has to be sealed under it.
     */
    fun erase(owed: Set<RoomGeneration> = emptySet()) {
        val now = erasableHour()
        val slots = preferences.all.keys.mapNotNull(::parseSlot)
        slots.forEach { held ->
            synchronized(lock) {
                if (held.generation != generationOf(held.roomId)) {
                    val retiredAt = preferences.getInt(retired(held.roomId, held.generation), NEVER)
                        .takeIf { it != NEVER }
                        ?: now.also { preferences.edit { putInt(retired(held.roomId, held.generation), it) } }
                    if (now - retiredAt >= RETIRED_GRACE_HOURS && held !in owed) {
                        drop(held.roomId, held.generation)
                        return@synchronized
                    }
                }
                advanced(held.roomId, held.generation, now)?.key?.fill(0)
            }
        }
    }

    /** Leaving a room takes every key it ever had, or leaving would not mean much. */
    fun forget(roomId: Int) {
        synchronized(lock) {
            preferences.edit {
                preferences.all.keys
                    .filter { it == current(roomId) || it == superseded(roomId) || it.startsWith("$roomId/") }
                    .forEach { remove(it) }
            }
            // Leaving has to take the copies in memory too, or the room stays
            // readable for the life of the process.
            unwrapped.keys.filter { it.startsWith("$roomId/") }.forEach { slot ->
                unwrapped.remove(slot)?.key?.fill(0)
            }
        }
    }

    /** The hour old keys may be erased up to: real time, never a clock that jumped ahead. */
    private fun erasableHour(): Int = RoomRatchet.hourOf(time.eraseMillis())

    /**
     * The held key for one generation, first moved on so that nothing older
     * than the hour before [now] survives, on disk or in memory. [now] is
     * [erasableHour], not the wall clock.
     */
    private fun advanced(roomId: Int, generation: Int, now: Int): HourKey? = synchronized(lock) {
        val held = held(roomId, generation) ?: return null
        val keep = RoomRatchet.keepFrom(held.hour, now)
        if (keep == held.hour) return held
        // A clock decades out: keep what we have rather than spin.
        val moved = RoomRatchet.forward(held.key, roomId, generation, held.hour, keep) ?: return held
        held.key.fill(0)
        val next = HourKey(keep, moved)
        preferences.edit { putString(slot(roomId, generation), encode(next)) }
        unwrapped.put(slot(roomId, generation), next.copy())?.key?.fill(0)
        next
    }

    /** Wipes [held] whatever happens: it is a copy made for this one use. */
    private fun senderKey(roomId: Int, generation: Int, held: HourKey, hour: Int, sender: Int): ByteArray? {
        val hourKey = try {
            RoomRatchet.forward(held.key, roomId, generation, held.hour, hour)
        } finally {
            held.key.fill(0)
        } ?: return null
        return try {
            RoomRatchet.senderKey(hourKey, roomId, generation, hour, sender)
        } finally {
            hourKey.fill(0)
        }
    }

    /** A copy every time: the cached one is wiped when it moves on, and a caller part-way through must not see that. */
    private fun held(roomId: Int, generation: Int): HourKey? {
        val slot = slot(roomId, generation)
        unwrapped[slot]?.let { return it.copy() }
        val stored = preferences.getString(slot, null) ?: return null
        val plain = unwrap(Base64.decode(stored, Base64.NO_WRAP)) ?: return null
        return try {
            decode(plain)?.also { unwrapped[slot] = it.copy() }
        } finally {
            plain.fill(0)
        }
    }

    private fun drop(roomId: Int, generation: Int) {
        preferences.edit {
            remove(slot(roomId, generation))
            remove(retired(roomId, generation))
        }
        unwrapped.remove(slot(roomId, generation))?.key?.fill(0)
    }

    /** The hour, then its key, wrapped. */
    private fun encode(key: HourKey): String {
        val plain = intBytes(key.hour) + key.key
        return try {
            Base64.encodeToString(wrap(plain), Base64.NO_WRAP)
        } finally {
            plain.fill(0)
        }
    }

    /** A bare 32-byte key was stored before keys moved on, and counts from [RoomRatchet.LEGACY_HOUR]. */
    private fun decode(plain: ByteArray): HourKey? = when (plain.size) {
        RoomCipher.KEY_SIZE -> HourKey(RoomRatchet.LEGACY_HOUR, plain.copyOf())
        HOUR_SIZE + RoomCipher.KEY_SIZE -> HourKey(
            ((plain[0].toInt() and 0xFF) shl 24) or ((plain[1].toInt() and 0xFF) shl 16) or
                ((plain[2].toInt() and 0xFF) shl 8) or (plain[3].toInt() and 0xFF),
            plain.copyOfRange(HOUR_SIZE, plain.size),
        )
        else -> null
    }

    private fun parseSlot(name: String): RoomGeneration? {
        val match = SLOT.matchEntire(name) ?: return null
        val roomId = match.groupValues[1].toIntOrNull() ?: return null
        val generation = match.groupValues[2].toIntOrNull() ?: return null
        return RoomGeneration(roomId, generation)
    }

    private fun slot(roomId: Int, generation: Int) = "$roomId/$generation"

    private fun retired(roomId: Int, generation: Int) = "$roomId/$generation.retired"

    private fun current(roomId: Int) = "$roomId.generation"

    private fun superseded(roomId: Int) = "$roomId.superseded"

    private fun wrap(key: ByteArray): ByteArray = KeystoreWrapping.wrap(ALIAS, key)

    private fun unwrap(stored: ByteArray): ByteArray? = KeystoreWrapping.unwrap(ALIAS, stored)

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    companion object {
        /** Rooms start here; a rotation is always one more. */
        const val FIRST = 1

        const val TAG = "FirepitRoomKeys"

        /** Unchanged since the first release: renaming it would orphan every stored key. */
        const val ALIAS = "firepit_room_key_wrapping"

        /**
         * How long a generation the room has moved on from stays, in hours:
         * long enough for anything sealed under it just before the move, from
         * a sender whose clock runs a little behind, to arrive and open.
         */
        const val RETIRED_GRACE_HOURS = 2

        private const val HOUR_SIZE = 4
        private const val NEVER = Int.MIN_VALUE
        private const val SEEN_FILE = "firepit-seen-seals"
        private val SLOT = Regex("""(-?\d+)/(\d+)""")
    }
}

/** Where [KeyClock] keeps its anchor. Nothing in it is secret. */
private class PreferenceAnchors(private val preferences: SharedPreferences) : ClockAnchors {
    override fun load(): ClockAnchor? {
        if (!preferences.contains(WALL) || !preferences.contains(MONOTONIC)) return null
        return ClockAnchor(
            wallMillis = preferences.getLong(WALL, 0),
            monotonicMillis = preferences.getLong(MONOTONIC, 0),
            boot = preferences.getString(BOOT, null),
        )
    }

    override fun save(anchor: ClockAnchor) {
        preferences.edit {
            putLong(WALL, anchor.wallMillis)
            putLong(MONOTONIC, anchor.monotonicMillis)
            putString(BOOT, anchor.boot)
        }
    }

    private companion object {
        const val WALL = "wall"
        const val MONOTONIC = "monotonic"
        const val BOOT = "boot"
    }
}

/** One generation of one room's key. */
data class RoomGeneration(val roomId: Int, val generation: Int)

/** What came of trying to open a sealed message. */
sealed interface Opening {
    /** It opened, for the first time. */
    class Read(val plain: ByteArray) : Opening

    /** This phone holds no key for that room and generation, or no longer does. */
    data object NoKey : Opening

    /**
     * Sealed in an hour whose key this phone has destroyed, or not yet
     * reached: an old recording, or two clocks more than an hour apart.
     */
    data class OutOfHours(val hour: Int, val now: Int) : Opening

    /** Opened before: a copy of a message already read. */
    data object Replayed : Opening

    /** Sealed by a build from before hourly keys, which nobody on this one can open. */
    data object Outdated : Opening

    /** Not a seal this build reads, or one that was changed or made with another key. */
    data object Unreadable : Opening
}
