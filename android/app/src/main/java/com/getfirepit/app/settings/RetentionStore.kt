package com.getfirepit.app.settings

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.getfirepit.core.database.MessageDao
import com.getfirepit.core.protocol.MessageRetention
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long this phone keeps messages.
 *
 * Only this phone. Everyone else holds their own copy and their own setting,
 * and nothing on a mesh can reach across to delete theirs.
 */
@Singleton
class RetentionStore @Inject constructor(
    @param:ApplicationContext context: Context,
    private val messageDao: MessageDao,
) {
    private val preferences = context.getSharedPreferences("firepit_retention", Context.MODE_PRIVATE)

    private val _choice = MutableStateFlow(MessageRetention.named(preferences.getString(KEY, null)))
    val choice: StateFlow<MessageRetention> = _choice.asStateFlow()

    suspend fun choose(retention: MessageRetention) {
        preferences.edit { putString(KEY, retention.name) }
        _choice.value = retention
        sweep()
    }

    /** Runs at launch and on change, so a phone left closed still catches up. */
    suspend fun sweep(nowMillis: Long = System.currentTimeMillis()) {
        val cutoff = _choice.value.cutoff(nowMillis) ?: return
        val deleted = messageDao.deleteOlderThan(cutoff)
        if (deleted > 0) Log.i(TAG, "deleted $deleted messages past the retention window")
    }

    private companion object {
        const val TAG = "FirepitRetention"
        const val KEY = "retention"
    }
}
