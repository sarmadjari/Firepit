package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.protocol.OwnerName
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.meshtastic.proto.User

/** What this radio calls itself to the rest of the mesh. */
data class Owner(val longName: String, val shortName: String)

/**
 * Our own name, as everyone else sees it.
 *
 * The radio owns this, not the phone: it is broadcast in NodeInfo, so changing
 * it here changes what appears in other people's chats and maps.
 */
@Singleton
class OwnerRepository @Inject constructor(
    private val mesh: MeshRepository,
    private val admin: NodeAdminClient,
) {
    /** Null until the radio has told us who it is. */
    val owner: Flow<Owner?> = combine(mesh.observeNodes(), mesh.myNodeNum) { nodes, myNodeNum ->
        nodes.firstOrNull { it.nodeNum == myNodeNum }
    }.map { node ->
        node?.let { Owner(longName = it.longName.orEmpty(), shortName = it.shortName.orEmpty()) }
    }

    /**
     * Renames this radio.
     *
     * The id is the node's own hex name and must be sent back unchanged; the
     * firmware treats a differing id as a request to change identity.
     */
    suspend fun rename(longName: String, shortName: String) {
        val myNodeNum = mesh.myNodeNum.value ?: error("Connect your node first")
        val trimmedLong = OwnerName.longName(longName)
        require(trimmedLong.isNotEmpty()) { "A name cannot be empty" }
        val trimmedShort = OwnerName.shortName(shortName.ifBlank { OwnerName.suggestShort(trimmedLong) })

        admin.setOwner(
            User(
                id = MeshConstants.formatNodeId(myNodeNum),
                long_name = trimmedLong,
                short_name = trimmedShort,
            ),
        )
        mesh.setOwnName(myNodeNum, trimmedLong, trimmedShort)
        Log.i(TAG, "renamed this node to $trimmedLong ($trimmedShort)")
    }

    private companion object {
        const val TAG = "FirepitOwner"
    }
}
