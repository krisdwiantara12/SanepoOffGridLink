package com.sanepo.offgrid

import com.sanepo.offgrid.engine.AppUpdateManager
import com.sanepo.offgrid.engine.GeoMath
import com.sanepo.offgrid.engine.OffGridProtocol
import com.sanepo.offgrid.engine.PttAudioEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OffGridProtocolTest {

    @Test
    fun bleBeaconPayloadFitsLegacyLimitAndRoundTripsCoordinatesAndSos() {
        val original = OffGridProtocol.BleBeaconPacket(
            nodeId = "7F3A19C2",
            callsign = "KRIS01",
            channelNumber = 7,
            batteryPercent = 84,
            isSos = true,
            latitude = -7.86643,
            longitude = 111.46662
        )
        val encoded = OffGridProtocol.encodeBlePayload(original)
        assertEquals(OffGridProtocol.BLE_PAYLOAD_BYTES, encoded.size)
        assertTrue(encoded.size <= 24)

        val decoded = requireNotNull(OffGridProtocol.decodeBlePayload(encoded))
        assertEquals("7F3A19C2", decoded.nodeId)
        assertEquals("KRIS01", decoded.callsign)
        assertEquals(7, decoded.channelNumber)
        assertEquals(84, decoded.batteryPercent)
        assertTrue(decoded.isSos)
        assertTrue(abs(decoded.latitude!! - (-7.86643)) < 0.00002)
        assertTrue(abs(decoded.longitude!! - 111.46662) < 0.00002)
    }

    @Test
    fun blePayloadHandlesMissingGpsCleanly() {
        val original = OffGridProtocol.BleBeaconPacket(
            nodeId = "00AABB11",
            callsign = "TIM B",
            channelNumber = 12,
            batteryPercent = -1,
            isSos = false,
            latitude = null,
            longitude = null
        )
        val encoded = OffGridProtocol.encodeBlePayload(original)
        val decoded = requireNotNull(OffGridProtocol.decodeBlePayload(encoded))
        assertNull(decoded.latitude)
        assertNull(decoded.longitude)
        assertEquals(-1, decoded.batteryPercent)
        assertFalse(decoded.isSos)
    }

    @Test
    fun audioFrameHeaderEncodesAndDecodesPcmPayload() {
        val pcm = byteArrayOf(0x10, 0x20, -0x80, 0x7F, 0x00, 0x01)
        val frameBytes = OffGridProtocol.encodeAudioFrame(
            type = OffGridProtocol.AUDIO_TYPE_PCM,
            channelNumber = 3,
            senderNodeId = "DEAD1234",
            sequence = 42,
            pcmBytes = pcm
        )
        val parsed = requireNotNull(OffGridProtocol.decodeAudioFrame(frameBytes))
        assertEquals(OffGridProtocol.AUDIO_TYPE_PCM, parsed.type)
        assertEquals(3, parsed.channelNumber)
        assertEquals("DEAD1234", parsed.senderNodeId)
        assertEquals(42, parsed.sequence)
        assertArrayEquals(pcm, parsed.pcmBytes)
    }

    @Test
    fun controlEnvelopeJsonRoundTripsChatAndSos() {
        val chat = OffGridProtocol.ControlEnvelope.Chat(
            msgId = "msg-101",
            senderId = "11223344",
            senderCallsign = "UNIT-04",
            channelNumber = 5,
            text = "Konvoi aman",
            timestampMs = 1795758933000L,
            isSos = false,
            latitude = -7.871,
            longitude = 111.462
        )
        val encoded = OffGridProtocol.encodeControlJson(chat)
        val decoded = OffGridProtocol.decodeControlJson(encoded) as OffGridProtocol.ControlEnvelope.Chat
        assertEquals("msg-101", decoded.msgId)
        assertEquals("UNIT-04", decoded.senderCallsign)
        assertEquals("Konvoi aman", decoded.text)
        assertEquals(5, decoded.channelNumber)
        assertEquals(-7.871, decoded.latitude!!, 0.0001)
    }

    @Test
    fun geoMathComputesPonorogoAzimuthDistanceAndRssiEstimate() {
        // 1 km south to north (~0.009 degrees latitude is ~1000m)
        val dist = GeoMath.haversineDistanceMeters(-7.8700, 111.4600, -7.8610, 111.4600)
        assertTrue(dist in 990.0..1015.0)
        val bearingNorth = GeoMath.initialBearingDegrees(-7.8700, 111.4600, -7.8610, 111.4600)
        assertTrue(bearingNorth < 2.0 || bearingNorth > 358.0)
        assertEquals("U", GeoMath.cardinalDirectionId(bearingNorth))

        val bearingSouthEast = GeoMath.initialBearingDegrees(0.0, 0.0, -0.01, 0.01)
        assertEquals("TG", GeoMath.cardinalDirectionId(bearingSouthEast))

        val closeRssi = GeoMath.estimateDistanceFromRssi(-59)
        assertTrue(closeRssi in 0.9..1.1)
        val farRssi = GeoMath.estimateDistanceFromRssi(-85)
        assertTrue(farRssi > closeRssi)
    }

    @Test
    fun audioRogerBeepSynthAndRmsMeterWorkCleanly() {
        val beep = PttAudioEngine.synthesizeRogerBeepPcm()
        assertTrue(beep.size > 1000)
        assertTrue(PttAudioEngine.computeNormalizedRms(beep) > 0.1f)
        assertEquals(0f, PttAudioEngine.computeNormalizedRms(ByteArray(100)), 0.0001f)
    }

    @Test
    fun otaSemverAndRawManifestParserVerifyVersions() {
        assertTrue(AppUpdateManager.isRemoteNewer("1.0.0", "v1.0.1"))
        assertFalse(AppUpdateManager.isRemoteNewer("1.0.0", "v1.0.0"))
        val sampleJson = """
            {
              "tagName": "v1.1.0",
              "releaseName": "Sanepo OffGrid Link v1.1.0",
              "body": "Fitur baru",
              "publishedAt": "2026-11-28T00:00:00Z",
              "htmlUrl": "https://github.com/krisdwiantara12/SanepoOffGridLink/releases",
              "isPreRelease": false,
              "apkAssetName": "SanepoOffGridLink-v1.1.0.apk",
              "apkDownloadUrl": "https://raw.githubusercontent.com/krisdwiantara12/SanepoOffGridLink/main/releases/SanepoOffGridLink-v1.1.0.apk",
              "apkSizeBytes": 1250000,
              "expectedSha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
            }
        """.trimIndent()
        val rel = requireNotNull(AppUpdateManager.parseRawManifestJson(sampleJson, "1.0.0"))
        assertTrue(rel.updateAvailable)
        assertEquals("v1.1.0", rel.tagName)
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", rel.expectedSha256)
    }
}
