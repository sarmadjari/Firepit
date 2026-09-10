package com.getfirepit.app.settings

import android.content.Context
import androidx.core.content.edit
import com.getfirepit.core.protocol.Person
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * You, on this phone.
 *
 * Deliberately not the radio's owner name: this is the person, and a person
 * keeps their name when they pick up a different radio. Nothing here is written
 * to a device, so it can be set with no radio connected at all.
 */
@Singleton
class PersonStore @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_identity", Context.MODE_PRIVATE)

    private val _person = MutableStateFlow(read())

    /** Null until this phone has been given a name. */
    val person: StateFlow<Person?> = _person.asStateFlow()

    fun save(name: String, tag: String) {
        val person = Person.of(
            id = preferences.getInt(KEY_ID, UNSET).takeIf { it != UNSET } ?: newId(),
            name = name,
            tag = tag,
            colourSlot = _person.value?.colourSlot,
        )
        require(person.name.isNotEmpty()) { "A name cannot be empty" }
        write(person)
    }

    fun chooseColour(slot: Int?) {
        val current = _person.value ?: return
        write(current.copy(colourSlot = slot))
    }

    private fun write(person: Person) {
        preferences.edit {
            putInt(KEY_ID, person.id)
            putString(KEY_NAME, person.name)
            putString(KEY_TAG, person.tag)
            val slot = person.colourSlot
            if (slot == null) remove(KEY_SLOT) else putInt(KEY_SLOT, slot)
        }
        _person.value = person
    }

    private fun read(): Person? {
        val name = preferences.getString(KEY_NAME, null) ?: return null
        return Person(
            id = preferences.getInt(KEY_ID, UNSET).takeIf { it != UNSET } ?: newId(),
            name = name,
            tag = preferences.getString(KEY_TAG, null).orEmpty(),
            colourSlot = preferences.getInt(KEY_SLOT, UNSET).takeIf { it != UNSET },
        )
    }

    /** Never a node number: a person outlives the radio they happen to hold. */
    private fun newId(): Int = Random.nextInt()

    private companion object {
        const val KEY_ID = "person_id"
        const val KEY_NAME = "person_name"
        const val KEY_TAG = "person_tag"

        // Kept from when this file held only a colour, so nobody loses theirs.
        const val KEY_SLOT = "identity_slot"
        const val UNSET = -1
    }
}
