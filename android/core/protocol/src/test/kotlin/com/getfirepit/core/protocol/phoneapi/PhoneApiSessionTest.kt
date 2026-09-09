package com.getfirepit.core.protocol.phoneapi

import app.cash.turbine.test
import com.getfirepit.core.protocol.FirmwareVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.ToRadio

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneApiSessionTest {

    @Test
    fun `handshake downloads config and reaches ready`() = runTest {
        val transport = FakeRadioTransport { configId -> configDownload(configId) }
        val session = PhoneApiSession(transport)

        val job = launchSession(session)
        val ready = session.awaitReady()

        assertTrue("notifications must be enabled before the handshake", transport.notificationsEnabled)
        assertEquals(0x1234, ready.snapshot.myNodeNum)
        assertEquals(8, ready.snapshot.channels.size)
        assertEquals("2.7.26.54e0d8d", ready.snapshot.metadata?.firmware_version)

        job.cancel()
    }

    @Test
    fun `handshake sends a non-zero config id`() = runTest {
        val transport = FakeRadioTransport { configId -> configDownload(configId) }
        val job = launchSession(PhoneApiSession(transport))

        runCurrent()

        val request = transport.written.firstNotNullOf { it.want_config_id }
        assertTrue("config id 0 means 'no download in progress'", request != 0)

        job.cancel()
    }

    @Test
    fun `capabilities are parsed from the downloaded metadata`() = runTest {
        val transport = FakeRadioTransport { configId -> configDownload(configId) }
        val session = PhoneApiSession(transport)
        val job = launchSession(session)

        val capabilities = session.awaitReady().snapshot.capabilities

        assertEquals(FirmwareVersion(2, 7, 26, "2.7.26.54e0d8d"), capabilities.firmwareVersion)
        assertTrue(capabilities.supportsPki)
        assertFalse("2.7 has no XEdDSA signing", capabilities.supportsSigning)
        assertTrue(capabilities.isSupported)

        job.cancel()
    }

    @Test
    fun `reboot triggers a fresh config download`() = runTest {
        val transport = FakeRadioTransport { configId -> configDownload(configId) }
        val session = PhoneApiSession(transport)
        val job = launchSession(session)
        session.awaitReady()

        transport.deliver(FromRadio(rebooted = true))
        runCurrent()
        session.awaitReady()

        val downloads = transport.written.mapNotNull { it.want_config_id }
        assertEquals("a reboot must re-read config", 2, downloads.size)
        assertTrue("each download needs its own id", downloads[0] != downloads[1])

        job.cancel()
    }

    @Test
    fun `steady state packets reach subscribers`() = runTest {
        val transport = FakeRadioTransport { configId -> configDownload(configId) }
        val session = PhoneApiSession(transport)
        val job = launchSession(session)
        session.awaitReady()

        session.inbound.test {
            transport.deliver(FromRadio(id = 7), FromRadio(id = 8))
            assertEquals(7, awaitItem().id)
            assertEquals(8, awaitItem().id)
        }

        job.cancel()
    }

    @Test
    fun `a malformed frame is skipped without ending the drain`() = runTest {
        val transport = FakeRadioTransport { configId -> configDownload(configId) }
        val session = PhoneApiSession(transport)
        val job = launchSession(session)
        session.awaitReady()

        session.inbound.test {
            transport.deliverGarbageThen(FromRadio(id = 99))
            assertEquals("frame after the bad one must still arrive", 99, awaitItem().id)
        }

        job.cancel()
    }

    @Test
    fun `sending triggers a drain so responses are not stranded`() = runTest {
        val transport = FakeRadioTransport { configId -> configDownload(configId) }
        val session = PhoneApiSession(transport)
        val job = launchSession(session)
        session.awaitReady()

        session.inbound.test {
            // No FromNum notification for this frame: only the drain that the
            // write itself triggers can surface it.
            transport.deliverSilently(FromRadio(id = 55))
            session.send(ToRadio(heartbeat = Heartbeat()))
            assertEquals(55, awaitItem().id)
        }

        job.cancel()
    }

    @Test
    fun `disconnect is announced so the radio resets its state immediately`() = runTest {
        val transport = FakeRadioTransport { configId -> configDownload(configId) }
        val session = PhoneApiSession(transport)
        val job = launchSession(session)
        session.awaitReady()

        session.sendDisconnect()

        assertTrue(transport.written.any { it.disconnect == true })
        job.cancel()
    }

    private fun configDownload(configId: Int): List<FromRadio> = buildList {
        add(FromRadio(my_info = MyNodeInfo(my_node_num = 0x1234, min_app_version = 30200)))
        add(
            FromRadio(
                metadata = DeviceMetadata(
                    firmware_version = "2.7.26.54e0d8d",
                    hasPKC = true,
                    has_xeddsa = false,
                ),
            ),
        )
        repeat(8) { index ->
            add(
                FromRadio(
                    channel = Channel(
                        index = index,
                        role = if (index == 0) Channel.Role.PRIMARY else Channel.Role.DISABLED,
                        settings = ChannelSettings(),
                    ),
                ),
            )
        }
        add(FromRadio(config = Config(lora = Config.LoRaConfig(region = Config.LoRaConfig.RegionCode.EU_868))))
        add(FromRadio(config_complete_id = configId))
    }
}
