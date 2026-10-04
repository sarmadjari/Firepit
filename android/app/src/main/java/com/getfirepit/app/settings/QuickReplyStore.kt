package com.getfirepit.app.settings

import android.content.Context
import androidx.core.content.edit
import com.getfirepit.core.protocol.QuickReplies
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** The quick replies the composer offers (UX §5.4), on this phone only. */
@Singleton
class QuickReplyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_quick_replies", Context.MODE_PRIVATE)

    private val _replies = MutableStateFlow(read())
    val replies: StateFlow<List<String>> = _replies.asStateFlow()

    fun set(replies: List<String>) {
        val kept = QuickReplies.normalise(replies)
        preferences.edit { putString(KEY, JSONArray(kept).toString()) }
        _replies.value = kept
    }

    fun resetToDefaults() {
        preferences.edit { remove(KEY) }
        _replies.value = QuickReplies.DEFAULTS
    }

    private fun read(): List<String> {
        val saved = preferences.getString(KEY, null) ?: return QuickReplies.DEFAULTS
        val array = runCatching { JSONArray(saved) }.getOrNull() ?: return QuickReplies.DEFAULTS
        return QuickReplies.normalise((0 until array.length()).map { array.optString(it) })
    }

    private companion object {
        const val KEY = "replies"
    }
}
