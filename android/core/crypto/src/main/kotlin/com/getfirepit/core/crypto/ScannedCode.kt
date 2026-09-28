package com.getfirepit.core.crypto

import com.getfirepit.core.protocol.ChannelUrl
import com.getfirepit.protocol.meshchat.Invite

/**
 * What a scanned code turned out to be.
 *
 * One camera reads two unrelated formats, and which one a code is decides how
 * private the resulting room can be. Keeping the answer as a type rather than a
 * nullable means the caller has to deal with the cases where it is ours but
 * unusable, instead of letting those slide into "unrecognised".
 */
sealed interface ScannedCode {

    /** A Firepit invite: a sealed room, with everything Firepit adds. */
    data class Firepit(val invite: Invite) : ScannedCode

    /** A Meshtastic channel link: plain Meshtastic, readable by other clients. */
    data class Meshtastic(val shared: ChannelUrl.Shared) : ScannedCode

    /** Firepit's marker, but this build cannot use what follows it. */
    data class FirepitUnreadable(val reason: Reason) : ScannedCode

    data object Unrecognised : ScannedCode

    enum class Reason {
        /** Issued by a later version of the app than this one. */
        NEWER_VERSION,

        /** Damaged, truncated, or already expired. */
        MALFORMED,
    }
}

/**
 * Decides which decoder a scan belongs to before doing any decoding.
 *
 * Firepit's scheme is checked first because it is a nine-character prefix and
 * settles the question outright. Only a code without it is offered to the
 * Meshtastic decoder, which has to base64 and parse a protobuf to find out.
 */
object CodeScanner {

    fun classify(scanned: String): ScannedCode {
        if (!InviteCodec.isFirepitCode(scanned)) {
            return ChannelUrl.decode(scanned)
                ?.let(ScannedCode::Meshtastic)
                ?: ScannedCode.Unrecognised
        }

        val declared = InviteCodec.declaredVersion(scanned)
        if (declared != null && declared > InviteCodec.VERSION) {
            return ScannedCode.FirepitUnreadable(ScannedCode.Reason.NEWER_VERSION)
        }

        // Ours either way from here: a broken Firepit code is never reported as
        // an unrecognised one, because the person scanning it knows what it was.
        return InviteCodec.decode(scanned)
            ?.let(ScannedCode::Firepit)
            ?: ScannedCode.FirepitUnreadable(ScannedCode.Reason.MALFORMED)
    }
}
