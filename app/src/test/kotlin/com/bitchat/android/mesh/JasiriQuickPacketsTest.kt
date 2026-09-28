package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.protocol.BinaryProtocol
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.util.AppConstants
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.SecureRandom

/**
 * Send-side JASIRI quick message packets: the broadcast builder, wire round-trip, and a real
 * Ed25519 sign/verify through SecurityManager. The quick payload is treated as opaque bytes here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class JasiriQuickPacketsTest {
    private val securityManagers = mutableListOf<SecurityManager>()

    @After
    fun tearDown() {
        securityManagers.forEach(SecurityManager::shutdown)
    }

    @Test
    fun `buildBroadcast sets every header field as given`() {
        val packet = JasiriQuickPackets.buildBroadcast(SENDER_ID, QUICK_GOLDEN, TIMESTAMP_MS)

        assertEquals(0x41u.toUByte(), packet.type)
        assertEquals(MessageType.JASIRI_QUICK.value, packet.type)
        assertEquals(1u.toUByte(), packet.version)
        assertArrayEquals(SpecialRecipients.BROADCAST, packet.recipientID)
        assertEquals(AppConstants.MESSAGE_TTL_HOPS, packet.ttl)
        assertEquals(TIMESTAMP_MS.toULong(), packet.timestamp)
        assertArrayEquals(SENDER_ID, packet.senderID)
        assertArrayEquals(QUICK_GOLDEN, packet.payload)
        assertNull(packet.signature)
        assertNull(packet.route)
    }

    @Test
    fun `built packet round-trips through BinaryProtocol`() {
        val packet = JasiriQuickPackets.buildBroadcast(SENDER_ID, QUICK_GOLDEN, TIMESTAMP_MS)

        val encoded = BinaryProtocol.encode(packet)
        assertNotNull(encoded)
        val decoded = BinaryProtocol.decode(encoded!!)
        assertNotNull(decoded)

        assertEquals(MessageType.JASIRI_QUICK.value, decoded!!.type)
        assertArrayEquals(QUICK_GOLDEN, decoded.payload)
        assertEquals(AppConstants.MESSAGE_TTL_HOPS, decoded.ttl)
        assertArrayEquals(SpecialRecipients.BROADCAST, decoded.recipientID)
        assertArrayEquals(SENDER_ID, decoded.senderID)
        assertEquals(TIMESTAMP_MS.toULong(), decoded.timestamp)
    }

    @Test
    fun `signed packet is accepted by a SecurityManager that knows the sender key`() {
        val keys = SenderKeys.generate()
        val packet = JasiriQuickPackets.buildBroadcast(SENDER_ID, QUICK_GOLDEN, System.currentTimeMillis())
        val signed = packet.copy(signature = keys.sign(packet.toBinaryDataForSigning()!!))

        assertEquals(64, signed.signature?.size)
        assertTrue(securityManagerKnowing(keys.publicKey).validatePacket(signed, SENDER_PEER_ID))
    }

    @Test
    fun `unsigned copy is rejected by a SecurityManager that knows the sender key`() {
        val keys = SenderKeys.generate()
        val packet = JasiriQuickPackets.buildBroadcast(SENDER_ID, QUICK_GOLDEN, System.currentTimeMillis())

        assertFalse(securityManagerKnowing(keys.publicKey).validatePacket(packet, SENDER_PEER_ID))
    }

    @Test
    fun `packet signed by a different key is rejected`() {
        val senderKeys = SenderKeys.generate()
        val otherKeys = SenderKeys.generate()
        val packet = JasiriQuickPackets.buildBroadcast(SENDER_ID, QUICK_GOLDEN, System.currentTimeMillis())
        val signed = packet.copy(signature = otherKeys.sign(packet.toBinaryDataForSigning()!!))

        assertFalse(securityManagerKnowing(senderKeys.publicKey).validatePacket(signed, SENDER_PEER_ID))
    }

    @Test
    fun `buildBroadcast rejects a 7-byte senderID`() {
        assertThrows(IllegalArgumentException::class.java) {
            JasiriQuickPackets.buildBroadcast(ByteArray(7), QUICK_GOLDEN, TIMESTAMP_MS)
        }
    }

    @Test
    fun `buildBroadcast rejects an empty payload`() {
        assertThrows(IllegalArgumentException::class.java) {
            JasiriQuickPackets.buildBroadcast(SENDER_ID, ByteArray(0), TIMESTAMP_MS)
        }
    }

    /** A fresh manager per check, so duplicate detection never masks a signature decision. */
    private fun securityManagerKnowing(signingPublicKey: ByteArray): SecurityManager {
        val delegate = mock<SecurityManagerDelegate>()
        val info = PeerInfo(
            id = SENDER_PEER_ID,
            nickname = "Test User",
            isConnected = true,
            isDirectConnection = true,
            noisePublicKey = ByteArray(32),
            signingPublicKey = signingPublicKey,
            isVerifiedNickname = false,
            lastSeen = System.currentTimeMillis()
        )
        whenever(delegate.getPeerInfo(SENDER_PEER_ID)).thenReturn(info)
        return SecurityManager(RealVerifyEncryptionService(), MY_PEER_ID).also {
            it.delegate = delegate
            securityManagers += it
        }
    }

    /** Skips KeyStore-backed initialization but keeps the real Ed25519 verification. */
    private class RealVerifyEncryptionService : EncryptionService(RuntimeEnvironment.getApplication()) {
        override fun initialize() = Unit
    }

    private class SenderKeys(
        private val privateKey: Ed25519PrivateKeyParameters,
        val publicKey: ByteArray
    ) {
        fun sign(data: ByteArray): ByteArray {
            val signer = Ed25519Signer()
            signer.init(true, privateKey)
            signer.update(data, 0, data.size)
            return signer.generateSignature()
        }

        companion object {
            fun generate(): SenderKeys {
                val generator = Ed25519KeyPairGenerator()
                generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
                val pair = generator.generateKeyPair()
                return SenderKeys(
                    pair.private as Ed25519PrivateKeyParameters,
                    (pair.public as Ed25519PublicKeyParameters).encoded
                )
            }
        }
    }

    private companion object {
        const val MY_PEER_ID = "1111222233334444"
        const val SENDER_PEER_ID = "aaaabbbbccccdddd"
        const val TIMESTAMP_MS = 1_790_000_000_123L
        val SENDER_ID = SENDER_PEER_ID.hexToBytes()

        /** Valid quick v1 payload bytes (golden vector from docs/JASIRI_QUICK_V1.md). */
        val QUICK_GOLDEN = "0101010203040506070800026ab13b8000".hexToBytes()

        fun String.hexToBytes(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
