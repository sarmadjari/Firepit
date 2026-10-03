package com.getfirepit.core.crypto

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hands one phone a secret that the radio carrying it cannot read.
 *
 * A grant or a key rotation travels as a PKI direct message, which the firmware
 * encrypts to the recipient's *radio*. Whoever holds that radio can read its
 * private key over Bluetooth, so the firmware's layer alone would give the
 * room's own key to anybody who picks the hardware up. This seals it a second
 * time, to a key pair that only ever exists on the phone.
 *
 * ECDH on P-256, HKDF-SHA256 and AES-256-GCM, all platform-provided on every
 * Android release Firepit supports. Nothing here is invented beyond putting the
 * three together in the standard way.
 */
object KeyEnvelope {

    /** A compressed P-256 point: a parity byte, then the x coordinate. */
    const val PUBLIC_KEY_SIZE = 33

    /** The sender's one-off key, then nonce, the sealed 32-byte secret and its tag. */
    const val SEALED_SIZE = PUBLIC_KEY_SIZE + RoomCipher.NONCE_SIZE + RoomCipher.KEY_SIZE + RoomCipher.TAG_SIZE

    private const val CURVE = "secp256r1"
    private const val INFO = "firepit-key-envelope-v1"
    private const val HMAC = "HmacSHA256"
    private const val COORDINATE_SIZE = 32
    private const val EVEN: Byte = 0x02
    private const val ODD: Byte = 0x03

    private val params: ECParameterSpec by lazy { (newPair().public as ECPublicKey).params }
    private val prime: BigInteger by lazy { (params.curve.field as ECFieldFp).p }

    fun generateKeyPair(): KeyPair = newPair()

    /** What goes on the wire and into other people's databases. */
    fun publicBytes(key: PublicKey): ByteArray = compress((key as ECPublicKey).w)

    /** For storage only; the caller wraps it before it touches the disk. */
    fun privateBytes(pair: KeyPair): ByteArray = pair.private.encoded

    fun restorePrivate(encoded: ByteArray): PrivateKey? = try {
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))
    } catch (_: GeneralSecurityException) {
        null
    }

    /** True when [bytes] is a point on the curve, and so something a secret can be sealed to. */
    fun isValidPublicKey(bytes: ByteArray): Boolean = decode(bytes) != null

    /**
     * Binds a sealed secret to one room, one generation, one recipient and
     * the hour it is the key for, so it cannot be replayed as a different
     * room's key, handed to somebody else, or relabelled as another hour's.
     */
    fun contextOf(roomId: Int, generation: Int, recipientNodeNum: Int, hour: Int): ByteArray =
        intBytes(roomId) + intBytes(generation) + intBytes(recipientNodeNum) + intBytes(hour)

    /** Seals [secret] so that only the holder of the private half of [recipient] can open it. */
    fun seal(recipient: ByteArray, secret: ByteArray, context: ByteArray): ByteArray {
        val recipientKey = requireNotNull(decode(recipient)) { "not a usable public key" }
        val ephemeral = newPair()
        val ephemeralBytes = publicBytes(ephemeral.public)
        val key = derive(agree(ephemeral.private, recipientKey), ephemeralBytes, recipient, context)
        return try {
            ephemeralBytes + RoomCipher.seal(key, secret, context)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Null when this was not sealed to us, the context differs, or a byte was
     * changed. [ownPublic] is the recipient's own public key, which the sender
     * mixed into the derivation.
     */
    fun open(privateKey: PrivateKey, ownPublic: ByteArray, sealed: ByteArray, context: ByteArray): ByteArray? {
        if (sealed.size < PUBLIC_KEY_SIZE + RoomCipher.OVERHEAD) return null
        val ephemeralBytes = sealed.copyOfRange(0, PUBLIC_KEY_SIZE)
        // Validated before use: agreeing on a point that is not on the curve is
        // how a static private key is leaked a few bits at a time.
        val ephemeral = decode(ephemeralBytes) ?: return null
        val shared = try {
            agree(privateKey, ephemeral)
        } catch (_: GeneralSecurityException) {
            return null
        }
        val key = derive(shared, ephemeralBytes, ownPublic, context)
        return try {
            RoomCipher.open(key, sealed.copyOfRange(PUBLIC_KEY_SIZE, sealed.size), context)
        } finally {
            key.fill(0)
        }
    }

    private fun newPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE)) }.generateKeyPair()

    internal fun agree(privateKey: PrivateKey, publicKey: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }

    /** HKDF-SHA256 (RFC 5869), one block: exactly one AES-256 key is needed. */
    private fun derive(shared: ByteArray, ephemeral: ByteArray, recipient: ByteArray, context: ByteArray): ByteArray {
        val prk = hmac(ephemeral + recipient, shared)
        shared.fill(0)
        return try {
            hmac(prk, INFO.toByteArray() + context + byteArrayOf(1))
        } finally {
            prk.fill(0)
        }
    }

    internal fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).run {
            init(SecretKeySpec(key, HMAC))
            doFinal(data)
        }

    private fun compress(point: ECPoint): ByteArray {
        val prefix = if (point.affineY.testBit(0)) ODD else EVEN
        return byteArrayOf(prefix) + fixed(point.affineX)
    }

    /**
     * Back to a key, or null for anything that is not a point on P-256.
     *
     * P-256's prime is 3 mod 4, so the square root is a single exponentiation;
     * checking that it squares back is what rejects an x with no point above it.
     */
    internal fun decode(bytes: ByteArray): ECPublicKey? {
        if (bytes.size != PUBLIC_KEY_SIZE || (bytes[0] != EVEN && bytes[0] != ODD)) return null
        val x = BigInteger(1, bytes.copyOfRange(1, PUBLIC_KEY_SIZE))
        if (x >= prime) return null

        val curve = params.curve
        val rhs = x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(prime)
        var y = rhs.modPow(prime.add(BigInteger.ONE).shiftRight(2), prime)
        if (y.multiply(y).mod(prime) != rhs) return null
        if (y.testBit(0) != (bytes[0] == ODD)) {
            if (y.signum() == 0) return null
            y = prime.subtract(y)
        }

        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == COORDINATE_SIZE -> raw
            raw.size > COORDINATE_SIZE -> raw.copyOfRange(raw.size - COORDINATE_SIZE, raw.size)
            else -> ByteArray(COORDINATE_SIZE - raw.size) + raw
        }
    }

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
