package com.getfirepit.core.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Signatures that say an invite came from a room admin.
 *
 * Asymmetric rather than a shared MAC on purpose: every member has to be able
 * to *check* an invite, and with a shared secret anyone who can check can also
 * forge. Only the admin holds the half that signs.
 *
 * P-256 rather than Ed25519 because the platform has provided it since API 23,
 * and a private key can be generated inside the Android Keystore where it never
 * becomes bytes the app can leak. Invites are scanned from a screen, so the
 * signature costs nothing against the mesh payload budget.
 */
object RoomAdmin {

    private const val CURVE = "secp256r1"
    private const val ALGORITHM = "EC"
    private const val SIGNATURE = "SHA256withECDSA"

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(ALGORITHM).apply {
            initialize(ECGenParameterSpec(CURVE))
        }.generateKeyPair()

    /**
     * What an admin signs to promote somebody.
     *
     * Bound to the generation, so a grant does not survive a key rotation: the
     * only way to take admin back from someone is to rotate and not re-grant.
     */
    fun grantBytes(roomId: Int, generation: Int, granteePublicKey: ByteArray): ByteArray =
        buildMessage("grant", roomId, generation) + granteePublicKey

    /**
     * What an admin signs to issue an invite.
     *
     * Covers the key as well as the room, so a signature cannot be lifted onto
     * an invite carrying a different one.
     */
    fun inviteBytes(roomId: Int, generation: Int, inviteId: Int, psk: ByteArray): ByteArray =
        buildMessage("invite", roomId, generation) + inviteId.toBytes() + psk

    fun sign(privateKey: PrivateKey, message: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE).run {
            initSign(privateKey)
            update(message)
            sign()
        }

    /** False rather than throwing: a bad signature is an answer, not a failure. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            val key = KeyFactory.getInstance(ALGORITHM)
                .generatePublic(X509EncodedKeySpec(publicKey))
            Signature.getInstance(SIGNATURE).run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        }.getOrDefault(false)

    /**
     * The label keeps the two kinds of signature apart, so a grant can never be
     * replayed as an invite for the same room and generation.
     */
    private fun buildMessage(label: String, roomId: Int, generation: Int): ByteArray =
        label.toByteArray(Charsets.UTF_8) + roomId.toBytes() + generation.toBytes()

    private fun Int.toBytes() = byteArrayOf(
        (this ushr 24).toByte(),
        (this ushr 16).toByte(),
        (this ushr 8).toByte(),
        this.toByte(),
    )
}
