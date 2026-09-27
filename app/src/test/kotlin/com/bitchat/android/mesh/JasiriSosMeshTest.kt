package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Mesh-level behavior of the JASIRI SOS packet type (0x40): relay, processor gating and
 * signature enforcement. The SOS payload is treated as opaque bytes here; payload decoding
 * is covered by the SOS module's own tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class JasiriSosMeshTest {
    private val relayManagers = mutableListOf<PacketRelayManager>()
    private val processors = mutableListOf<PacketProcessor>()
    private val securityManagers = mutableListOf<SecurityManager>()

    @After
    fun tearDown() {
        relayManagers.forEach(PacketRelayManager::shutdown)
        processors.forEach(PacketProcessor::shutdown)
        securityManagers.forEach(SecurityManager::shutdown)
    }

    // Relay

    @Test
    fun `SOS is relayed every time in a large network`() = runTest {
        val delegate = relayDelegate(networkSize = 100)
        val relayManager = relayManager(delegate)

        repeat(30) {
            relayManager.handlePacketRelay(RoutedPacket(sosPacket(ttl = 2u), PEER_ID))
        }

        val captor = argumentCaptor<RoutedPacket>()
        verify(delegate, times(30)).broadcastPacket(captor.capture())
        captor.allValues.forEach { assertEquals(1u.toUByte(), it.packet.ttl) }
    }

    @Test
    fun `relayed SOS TTL is decremented by one without the voice cap`() = runTest {
        val delegate = relayDelegate(networkSize = 100)
        val relayManager = relayManager(delegate)

        relayManager.handlePacketRelay(RoutedPacket(sosPacket(ttl = 7u), PEER_ID))

        val captor = argumentCaptor<RoutedPacket>()
        verify(delegate, times(1)).broadcastPacket(captor.capture())
        assertEquals(6u.toUByte(), captor.firstValue.packet.ttl)
        assertEquals(MessageType.JASIRI_SOS.value, captor.firstValue.packet.type)
        assertTrue(SOS_GOLDEN.contentEquals(captor.firstValue.packet.payload))
    }

    @Test
    fun `SOS with TTL 0 is not relayed`() = runTest {
        val delegate = relayDelegate(networkSize = 100)
        val relayManager = relayManager(delegate)

        relayManager.handlePacketRelay(RoutedPacket(sosPacket(ttl = 0u), PEER_ID))

        verify(delegate, never()).broadcastPacket(any())
    }

    // Processor

    @Test
    fun `accepted SOS is relayed`() = runBlocking {
        val delegate = RecordingDelegate(acceptSos = true)
        val processor = processor(delegate)

        processor.processPacket(RoutedPacket(sosPacket(ttl = 7u), PEER_ID, "direct-link"))

        val relayed = withTimeout(1_000) { delegate.relayed.await() }
        assertEquals(MessageType.JASIRI_SOS.value, relayed.packet.type)
        assertEquals(6u.toUByte(), relayed.packet.ttl)
        assertEquals(PEER_ID, withTimeout(1_000) { delegate.lastSeen.await() })
    }

    @Test
    fun `rejected SOS is not relayed`() = runBlocking {
        val delegate = RecordingDelegate(acceptSos = false)
        val processor = processor(delegate)

        processor.processPacket(RoutedPacket(sosPacket(ttl = 7u), PEER_ID, "direct-link"))
        withTimeout(1_000) { delegate.sosHandled.await() }

        assertNull(withTimeoutOrNull(250) { delegate.relayed.await() })
        assertNull(withTimeoutOrNull(250) { delegate.lastSeen.await() })
        assertEquals(0, delegate.relayCount)
    }

    // Security

    @Test
    fun `unsigned SOS from a known peer is rejected`() {
        val securityDelegate = mock<SecurityManagerDelegate>()
        setupKnownPeer(securityDelegate, PEER_ID)
        val securityManager = securityManager(securityDelegate)

        val packet = sosPacket(ttl = 7u, signature = null)

        assertFalse(securityManager.validatePacket(packet, PEER_ID))
    }

    @Test
    fun `signed SOS from a known peer is accepted`() {
        val securityDelegate = mock<SecurityManagerDelegate>()
        setupKnownPeer(securityDelegate, PEER_ID)
        val securityManager = securityManager(securityDelegate)

        val packet = sosPacket(ttl = 7u, signature = VALID_SIGNATURE)

        assertTrue(securityManager.validatePacket(packet, PEER_ID))
    }

    @Test
    fun `SOS with an invalid signature from a known peer is rejected`() {
        val securityDelegate = mock<SecurityManagerDelegate>()
        setupKnownPeer(securityDelegate, PEER_ID)
        val securityManager = securityManager(securityDelegate)

        val packet = sosPacket(ttl = 7u, signature = INVALID_SIGNATURE)

        assertFalse(securityManager.validatePacket(packet, PEER_ID))
    }

    @Test
    fun `signed SOS from a peer without a signing key is rejected`() {
        val securityDelegate = mock<SecurityManagerDelegate>()
        val securityManager = securityManager(securityDelegate)

        val packet = sosPacket(ttl = 7u, signature = VALID_SIGNATURE, sender = UNKNOWN_PEER_ID)

        assertFalse(securityManager.validatePacket(packet, UNKNOWN_PEER_ID))
    }

    // Helpers

    private fun relayDelegate(networkSize: Int): PacketRelayManagerDelegate {
        val delegate = mock<PacketRelayManagerDelegate>()
        whenever(delegate.getNetworkSize()).thenReturn(networkSize)
        whenever(delegate.getBroadcastRecipient()).thenReturn(SpecialRecipients.BROADCAST)
        return delegate
    }

    private fun relayManager(delegate: PacketRelayManagerDelegate): PacketRelayManager =
        PacketRelayManager(MY_PEER_ID).also {
            it.delegate = delegate
            relayManagers += it
        }

    private fun processor(delegate: RecordingDelegate): PacketProcessor =
        PacketProcessor(MY_PEER_ID).also {
            it.delegate = delegate
            processors += it
        }

    private fun securityManager(delegate: SecurityManagerDelegate): SecurityManager =
        SecurityManager(FakeEncryptionService(), MY_PEER_ID).also {
            it.delegate = delegate
            securityManagers += it
        }

    private fun setupKnownPeer(delegate: SecurityManagerDelegate, peerID: String) {
        val info = PeerInfo(
            id = peerID,
            nickname = "Test User",
            isConnected = true,
            isDirectConnection = true,
            noisePublicKey = ByteArray(32),
            signingPublicKey = SIGNING_KEY,
            isVerifiedNickname = false,
            lastSeen = System.currentTimeMillis()
        )
        whenever(delegate.getPeerInfo(peerID)).thenReturn(info)
    }

    private fun sosPacket(
        ttl: UByte,
        signature: ByteArray? = null,
        sender: String = PEER_ID
    ): BitchatPacket = BitchatPacket(
        version = 1u,
        type = MessageType.JASIRI_SOS.value,
        senderID = sender.hexToBytes(),
        recipientID = SpecialRecipients.BROADCAST,
        timestamp = System.currentTimeMillis().toULong(),
        payload = SOS_GOLDEN,
        signature = signature,
        ttl = ttl
    )

    /** Accepts only [VALID_SIGNATURE]; avoids KeyStore access in tests. */
    private class FakeEncryptionService : EncryptionService(RuntimeEnvironment.getApplication()) {
        override fun initialize() = Unit

        override fun verifyEd25519Signature(
            signature: ByteArray,
            data: ByteArray,
            publicKeyBytes: ByteArray
        ): Boolean = signature.contentEquals(VALID_SIGNATURE) && publicKeyBytes.contentEquals(SIGNING_KEY)
    }

    private class RecordingDelegate(private val acceptSos: Boolean) : PacketProcessorDelegate {
        val sosHandled = CompletableDeferred<Unit>()
        val relayed = CompletableDeferred<RoutedPacket>()
        val lastSeen = CompletableDeferred<String>()
        @Volatile var relayCount = 0

        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) = true
        override fun updatePeerLastSeen(peerID: String) {
            lastSeen.complete(peerID)
        }
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize() = 1
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override fun handleNoiseHandshake(routed: RoutedPacket) = false
        override fun handleNoiseEncrypted(routed: RoutedPacket) = false
        override suspend fun handleAnnounce(routed: RoutedPacket) = false
        override fun handleMessage(routed: RoutedPacket) = Unit
        override fun handleJasiriSos(routed: RoutedPacket): Boolean {
            sosHandled.complete(Unit)
            return acceptSos
        }
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket): BitchatPacket? = null
        override fun handleRequestSync(routed: RoutedPacket) = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) {
            relayCount += 1
            relayed.complete(routed)
        }
        override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
    }

    private companion object {
        const val MY_PEER_ID = "1111222233334444"
        const val PEER_ID = "aaaabbbbccccdddd"
        const val UNKNOWN_PEER_ID = "0123456789abcdef"

        /** Valid SOS v1 payload bytes (golden vector from docs/JASIRI_SOS_V1.md). */
        val SOS_GOLDEN = "0101010203040506070800016ab13b80010201ff3ad75815f29378000f001e4001".hexToBytes()
        val SIGNING_KEY = ByteArray(32) { 0xA }
        val VALID_SIGNATURE = ByteArray(64) { 1 }
        val INVALID_SIGNATURE = ByteArray(64) { 0 }

        fun String.hexToBytes(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
