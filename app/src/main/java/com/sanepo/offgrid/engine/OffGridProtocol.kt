package com.sanepo.offgrid.engine

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object OffGridProtocol {
    const val TCP_MESH_PORT = 47880
    const val UDP_DISCOVERY_PORT = 47881
    const val UDP_AUDIO_PORT = 47882
    const val BLE_MANUFACTURER_ID = 0x5A4E
    const val SERVICE_TYPE = "_sanepolink._tcp"

    const val BLE_PAYLOAD_VERSION = 1
    const val BLE_PAYLOAD_BYTES = 21

    const val AUDIO_MAGIC_0: Byte = 0x53 // 'S'
    const val AUDIO_MAGIC_1: Byte = 0x4E // 'N'
    const val AUDIO_TYPE_PCM: Byte = 0x01
    const val AUDIO_TYPE_ROGER: Byte = 0x02
    const val AUDIO_TYPE_ADPCM_16K: Byte = 0x03
    const val AUDIO_TYPE_ADPCM_8K: Byte = 0x04
    const val AUDIO_HEADER_BYTES = 12

    const val GATT_FRAME_MAGIC: Byte = 0xAB.toByte()
    const val GATT_TYPE_JSON: Byte = 0x01
    const val GATT_TYPE_AUDIO: Byte = 0x02
    const val GATT_FRAME_HEADER_BYTES = 4

    const val MSG_HELLO = "HELLO"
    const val MSG_CHAT = "CHAT"
    const val MSG_CHAT_ACK = "CHAT_ACK"
    const val MSG_SOS = "SOS"
    const val MSG_PTT_FLOOR = "PTT_FLOOR"

    data class BleBeaconPacket(
        val nodeId: String,
        val callsign: String,
        val channelNumber: Int,
        val batteryPercent: Int,
        val isSos: Boolean,
        val latitude: Double?,
        val longitude: Double?
    )

    data class AudioFramePacket(
        val type: Byte,
        val channelNumber: Int,
        val senderNodeId: String,
        val sequence: Int,
        val pcmBytes: ByteArray
    )

    sealed class ControlEnvelope {
        data class Hello(
            val nodeId: String,
            val callsign: String,
            val channelNumber: Int,
            val tcpPort: Int,
            val batteryPercent: Int,
            val isSos: Boolean,
            val latitude: Double?,
            val longitude: Double?,
            val altitudeMeters: Double?,
            val accuracyMeters: Float?
        ) : ControlEnvelope()

        data class Chat(
            val msgId: String,
            val senderId: String,
            val senderCallsign: String,
            val channelNumber: Int,
            val text: String,
            val timestampMs: Long,
            val isSos: Boolean,
            val latitude: Double?,
            val longitude: Double?
        ) : ControlEnvelope()

        data class ChatAck(
            val msgId: String,
            val receiverId: String
        ) : ControlEnvelope()

        data class PttFloor(
            val senderId: String,
            val senderCallsign: String,
            val channelNumber: Int,
            val speaking: Boolean
        ) : ControlEnvelope()

        data class SosSignal(
            val senderId: String,
            val senderCallsign: String,
            val active: Boolean,
            val latitude: Double?,
            val longitude: Double?,
            val batteryPercent: Int
        ) : ControlEnvelope()
    }

    fun sanitizeCallsign(raw: String, maxLen: Int = 14): String {
        val cleaned = raw.trim()
            .replace(Regex("[^A-Za-z0-9_\\- ]"), "")
            .replace(Regex("\\s+"), " ")
            .take(maxLen)
            .uppercase()
        return cleaned.ifBlank { "UNIT-01" }
    }

    fun clampChannel(channel: Int): Int = channel.coerceIn(1, 99)

    fun parseNodeIdInt(nodeIdHex: String): Int {
        val hex = nodeIdHex.trim().take(8).padStart(8, '0')
        return hex.toLongOrNull(16)?.toInt() ?: 0
    }

    fun formatNodeIdHex(value: Int): String = "%08X".format(value)

    fun encodeBlePayload(packet: BleBeaconPacket): ByteArray {
        val buffer = ByteBuffer.allocate(BLE_PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN)
        val hasGps = packet.latitude != null && packet.longitude != null
        var flags = BLE_PAYLOAD_VERSION and 0x0F
        if (hasGps) flags = flags or 0x10
        if (packet.isSos) flags = flags or 0x20

        buffer.put(flags.toByte())
        buffer.putInt(parseNodeIdInt(packet.nodeId))
        val batByte = if (packet.batteryPercent in 0..100) packet.batteryPercent else 255
        buffer.put(batByte.toByte())

        val latE5 = if (hasGps) (packet.latitude!! * 100_000.0).roundToInt() else 0
        val lonE5 = if (hasGps) (packet.longitude!! * 100_000.0).roundToInt() else 0
        buffer.putInt(latE5)
        buffer.putInt(lonE5)

        buffer.put(clampChannel(packet.channelNumber).toByte())

        val asciiName = sanitizeCallsign(packet.callsign, maxLen = 6).toByteArray(Charsets.US_ASCII)
        val padded = ByteArray(6)
        val copyLen = minOf(asciiName.size, 6)
        System.arraycopy(asciiName, 0, padded, 0, copyLen)
        buffer.put(padded)

        return buffer.array()
    }

    fun decodeBlePayload(data: ByteArray): BleBeaconPacket? {
        if (data.size < BLE_PAYLOAD_BYTES) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val flags = buffer.get().toInt() and 0xFF
        val version = flags and 0x0F
        if (version != BLE_PAYLOAD_VERSION) return null

        val hasGps = (flags and 0x10) != 0
        val isSos = (flags and 0x20) != 0
        val nodeId = formatNodeIdHex(buffer.int)
        val batRaw = buffer.get().toInt() and 0xFF
        val battery = if (batRaw in 0..100) batRaw else -1

        val latE5 = buffer.int
        val lonE5 = buffer.int
        val lat = if (hasGps) latE5 / 100_000.0 else null
        val lon = if (hasGps) lonE5 / 100_000.0 else null

        val channel = clampChannel(buffer.get().toInt() and 0xFF)
        val nameBytes = ByteArray(6)
        buffer.get(nameBytes)
        val rawName = String(nameBytes, Charsets.US_ASCII).trimEnd('\u0000', ' ')
        val callsign = sanitizeCallsign(rawName.ifBlank { nodeId.take(6) }, maxLen = 6)

        return BleBeaconPacket(
            nodeId = nodeId,
            callsign = callsign,
            channelNumber = channel,
            batteryPercent = battery,
            isSos = isSos,
            latitude = lat,
            longitude = lon
        )
    }

    fun encodeAudioFrame(
        type: Byte,
        channelNumber: Int,
        senderNodeId: String,
        sequence: Int,
        pcmBytes: ByteArray
    ): ByteArray {
        val out = ByteBuffer.allocate(AUDIO_HEADER_BYTES + pcmBytes.size).order(ByteOrder.BIG_ENDIAN)
        out.put(AUDIO_MAGIC_0)
        out.put(AUDIO_MAGIC_1)
        out.put(type)
        out.put(clampChannel(channelNumber).toByte())
        out.putInt(parseNodeIdInt(senderNodeId))
        out.putInt(sequence)
        out.put(pcmBytes)
        return out.array()
    }

    fun decodeAudioFrame(bytes: ByteArray, length: Int = bytes.size): AudioFramePacket? {
        if (length < AUDIO_HEADER_BYTES) return null
        if (bytes[0] != AUDIO_MAGIC_0 || bytes[1] != AUDIO_MAGIC_1) return null
        val buffer = ByteBuffer.wrap(bytes, 2, length - 2).order(ByteOrder.BIG_ENDIAN)
        val type = buffer.get()
        if (type != AUDIO_TYPE_PCM &&
            type != AUDIO_TYPE_ROGER &&
            type != AUDIO_TYPE_ADPCM_16K &&
            type != AUDIO_TYPE_ADPCM_8K
        ) return null
        val channel = clampChannel(buffer.get().toInt() and 0xFF)
        val senderId = formatNodeIdHex(buffer.int)
        val seq = buffer.int
        val pcmLen = length - AUDIO_HEADER_BYTES
        val pcm = ByteArray(pcmLen)
        if (pcmLen > 0) {
            buffer.get(pcm)
        }
        return AudioFramePacket(
            type = type,
            channelNumber = channel,
            senderNodeId = senderId,
            sequence = seq,
            pcmBytes = pcm
        )
    }

    fun encodeControlJson(envelope: ControlEnvelope): String {
        val obj = JSONObject()
        when (envelope) {
            is ControlEnvelope.Hello -> {
                obj.put("t", MSG_HELLO)
                obj.put("id", envelope.nodeId)
                obj.put("cs", envelope.callsign)
                obj.put("ch", envelope.channelNumber)
                obj.put("port", envelope.tcpPort)
                obj.put("bat", envelope.batteryPercent)
                obj.put("sos", envelope.isSos)
                if (envelope.latitude != null && envelope.longitude != null) {
                    obj.put("lat", envelope.latitude)
                    obj.put("lon", envelope.longitude)
                }
                if (envelope.altitudeMeters != null) obj.put("alt", envelope.altitudeMeters)
                if (envelope.accuracyMeters != null) obj.put("acc", envelope.accuracyMeters.toDouble())
            }
            is ControlEnvelope.Chat -> {
                obj.put("t", MSG_CHAT)
                obj.put("mid", envelope.msgId)
                obj.put("id", envelope.senderId)
                obj.put("cs", envelope.senderCallsign)
                obj.put("ch", envelope.channelNumber)
                obj.put("txt", envelope.text)
                obj.put("ts", envelope.timestampMs)
                obj.put("sos", envelope.isSos)
                if (envelope.latitude != null && envelope.longitude != null) {
                    obj.put("lat", envelope.latitude)
                    obj.put("lon", envelope.longitude)
                }
            }
            is ControlEnvelope.ChatAck -> {
                obj.put("t", MSG_CHAT_ACK)
                obj.put("mid", envelope.msgId)
                obj.put("id", envelope.receiverId)
            }
            is ControlEnvelope.PttFloor -> {
                obj.put("t", MSG_PTT_FLOOR)
                obj.put("id", envelope.senderId)
                obj.put("cs", envelope.senderCallsign)
                obj.put("ch", envelope.channelNumber)
                obj.put("spk", envelope.speaking)
            }
            is ControlEnvelope.SosSignal -> {
                obj.put("t", MSG_SOS)
                obj.put("id", envelope.senderId)
                obj.put("cs", envelope.senderCallsign)
                obj.put("act", envelope.active)
                obj.put("bat", envelope.batteryPercent)
                if (envelope.latitude != null && envelope.longitude != null) {
                    obj.put("lat", envelope.latitude)
                    obj.put("lon", envelope.longitude)
                }
            }
        }
        return obj.toString()
    }

    fun decodeControlJson(raw: String): ControlEnvelope? {
        return try {
            val obj = JSONObject(raw)
            when (obj.optString("t")) {
                MSG_HELLO -> {
                    val id = obj.optString("id").trim()
                    if (id.isEmpty()) return null
                    val lat = if (obj.has("lat")) obj.optDouble("lat") else null
                    val lon = if (obj.has("lon")) obj.optDouble("lon") else null
                    val alt = if (obj.has("alt")) obj.optDouble("alt") else null
                    val acc = if (obj.has("acc")) obj.optDouble("acc").toFloat() else null
                    ControlEnvelope.Hello(
                        nodeId = id,
                        callsign = sanitizeCallsign(obj.optString("cs", id)),
                        channelNumber = clampChannel(obj.optInt("ch", 1)),
                        tcpPort = obj.optInt("port", TCP_MESH_PORT),
                        batteryPercent = obj.optInt("bat", -1),
                        isSos = obj.optBoolean("sos", false),
                        latitude = lat,
                        longitude = lon,
                        altitudeMeters = alt,
                        accuracyMeters = acc
                    )
                }
                MSG_CHAT -> {
                    val mid = obj.optString("mid").trim()
                    val id = obj.optString("id").trim()
                    val txt = obj.optString("txt")
                    if (mid.isEmpty() || id.isEmpty() || txt.isBlank()) return null
                    val lat = if (obj.has("lat")) obj.optDouble("lat") else null
                    val lon = if (obj.has("lon")) obj.optDouble("lon") else null
                    ControlEnvelope.Chat(
                        msgId = mid,
                        senderId = id,
                        senderCallsign = sanitizeCallsign(obj.optString("cs", id)),
                        channelNumber = clampChannel(obj.optInt("ch", 1)),
                        text = txt,
                        timestampMs = obj.optLong("ts", System.currentTimeMillis()),
                        isSos = obj.optBoolean("sos", false),
                        latitude = lat,
                        longitude = lon
                    )
                }
                MSG_CHAT_ACK -> {
                    val mid = obj.optString("mid").trim()
                    val id = obj.optString("id").trim()
                    if (mid.isEmpty() || id.isEmpty()) return null
                    ControlEnvelope.ChatAck(msgId = mid, receiverId = id)
                }
                MSG_PTT_FLOOR -> {
                    val id = obj.optString("id").trim()
                    if (id.isEmpty()) return null
                    ControlEnvelope.PttFloor(
                        senderId = id,
                        senderCallsign = sanitizeCallsign(obj.optString("cs", id)),
                        channelNumber = clampChannel(obj.optInt("ch", 1)),
                        speaking = obj.optBoolean("spk", false)
                    )
                }
                MSG_SOS -> {
                    val id = obj.optString("id").trim()
                    if (id.isEmpty()) return null
                    val lat = if (obj.has("lat")) obj.optDouble("lat") else null
                    val lon = if (obj.has("lon")) obj.optDouble("lon") else null
                    ControlEnvelope.SosSignal(
                        senderId = id,
                        senderCallsign = sanitizeCallsign(obj.optString("cs", id)),
                        active = obj.optBoolean("act", false),
                        latitude = lat,
                        longitude = lon,
                        batteryPercent = obj.optInt("bat", -1)
                    )
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }
}

object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2.0).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2.0).pow(2)
        val c = 2.0 * atan2(sqrt(a), sqrt(maxOf(0.0, 1.0 - a)))
        return EARTH_RADIUS_M * c
    }

    fun initialBearingDegrees(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val rawDeg = Math.toDegrees(atan2(y, x))
        return (rawDeg + 360.0) % 360.0
    }

    fun normalizeAzimuth(deg: Double): Double {
        val mod = deg % 360.0
        return if (mod < 0.0) mod + 360.0 else mod
    }

    fun cardinalDirectionId(bearingDeg: Double): String {
        val dirs = arrayOf("U", "TL", "T", "TG", "S", "BD", "B", "BL")
        val index = ((normalizeAzimuth(bearingDeg) + 22.5) / 45.0).toInt() % 8
        return dirs[index]
    }

    fun estimateDistanceFromRssi(
        rssiDbm: Int,
        txPowerAt1m: Int = -59,
        pathLossExponent: Double = 2.6
    ): Double {
        if (rssiDbm >= 0) return 0.5
        val exponent = (txPowerAt1m - rssiDbm) / (10.0 * pathLossExponent)
        return 10.0.pow(exponent).coerceIn(0.5, 150.0)
    }

    fun formatDistanceCompact(meters: Double): String {
        return when {
            meters < 1000.0 -> "${meters.roundToInt()} m"
            else -> "%.2f km".format(meters / 1000.0)
        }
    }
}

object ImaAdpcmCodec {
    private val INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    class EncoderState {
        var stepIndex: Int = 0
    }

    fun encodePcm16Le(pcmBytes: ByteArray, length: Int = pcmBytes.size, state: EncoderState? = null): ByteArray {
        val sampleCount = length / 2
        if (sampleCount <= 0) return ByteArray(0)
        val firstLo = pcmBytes[0].toInt() and 0xFF
        val firstHi = pcmBytes[1].toInt()
        var predictor = ((firstHi shl 8) or firstLo).toShort().toInt()
        var index = (state?.stepIndex ?: 0).coerceIn(0, 88)

        val codedCount = sampleCount - 1
        val nibbleBytes = (codedCount + 1) / 2
        val out = ByteArray(4 + nibbleBytes)
        out[0] = (predictor ushr 8).toByte()
        out[1] = (predictor and 0xFF).toByte()
        out[2] = index.toByte()
        out[3] = if ((codedCount % 2) == 1) 1 else 0

        var outPos = 4
        var lowNibble = true
        var currentByte = 0

        for (i in 1 until sampleCount) {
            val bOff = i * 2
            val sLo = pcmBytes[bOff].toInt() and 0xFF
            val sHi = pcmBytes[bOff + 1].toInt()
            val sample = ((sHi shl 8) or sLo).toShort().toInt()

            val step = STEP_TABLE[index]
            var diff = sample - predictor
            var code = 0
            if (diff < 0) {
                code = 8
                diff = -diff
            }
            var vpdiff = step ushr 3
            if (diff >= step) {
                code = code or 4
                diff -= step
                vpdiff += step
            }
            val halfStep = step ushr 1
            if (diff >= halfStep) {
                code = code or 2
                diff -= halfStep
                vpdiff += halfStep
            }
            val quarterStep = step ushr 2
            if (diff >= quarterStep) {
                code = code or 1
                vpdiff += quarterStep
            }
            predictor = if ((code and 8) != 0) predictor - vpdiff else predictor + vpdiff
            predictor = predictor.coerceIn(-32768, 32767)
            index = (index + INDEX_TABLE[code]).coerceIn(0, 88)

            if (lowNibble) {
                currentByte = code and 0x0F
                lowNibble = false
            } else {
                out[outPos++] = (currentByte or ((code and 0x0F) shl 4)).toByte()
                lowNibble = true
            }
        }
        if (!lowNibble) {
            out[outPos] = currentByte.toByte()
        }
        state?.stepIndex = index
        return out
    }

    fun decodeToPcm16Le(adpcmBytes: ByteArray): ByteArray {
        if (adpcmBytes.size < 4) return ByteArray(0)
        var predictor = (((adpcmBytes[0].toInt() and 0xFF) shl 8) or (adpcmBytes[1].toInt() and 0xFF)).toShort().toInt()
        var index = (adpcmBytes[2].toInt() and 0xFF).coerceIn(0, 88)
        val hasOddTail = (adpcmBytes[3].toInt() and 0xFF) == 1
        val nibbleByteCount = adpcmBytes.size - 4
        val codedCount = maxOf(0, nibbleByteCount * 2 - if (hasOddTail) 1 else 0)
        val totalSamples = 1 + codedCount

        val out = ByteArray(totalSamples * 2)
        out[0] = (predictor and 0xFF).toByte()
        out[1] = (predictor ushr 8).toByte()

        var outPos = 2
        for (k in 0 until codedCount) {
            val byteVal = adpcmBytes[4 + (k ushr 1)].toInt() and 0xFF
            val code = if ((k and 1) == 0) (byteVal and 0x0F) else ((byteVal ushr 4) and 0x0F)
            val step = STEP_TABLE[index]
            var vpdiff = step ushr 3
            if ((code and 4) != 0) vpdiff += step
            if ((code and 2) != 0) vpdiff += step ushr 1
            if ((code and 1) != 0) vpdiff += step ushr 2
            predictor = if ((code and 8) != 0) predictor - vpdiff else predictor + vpdiff
            predictor = predictor.coerceIn(-32768, 32767)
            index = (index + INDEX_TABLE[code]).coerceIn(0, 88)

            out[outPos++] = (predictor and 0xFF).toByte()
            out[outPos++] = (predictor ushr 8).toByte()
        }
        return out
    }

    fun downsample16kTo8k(pcm16k: ByteArray, length: Int = pcm16k.size): ByteArray {
        val sampleCount16k = length / 2
        val pairs = sampleCount16k / 2
        if (pairs <= 0) return ByteArray(0)
        val out = ByteArray(pairs * 2)
        for (i in 0 until pairs) {
            val idx0 = i * 4
            val s0 = (((pcm16k[idx0 + 1].toInt()) shl 8) or (pcm16k[idx0].toInt() and 0xFF)).toShort().toInt()
            val s1 = (((pcm16k[idx0 + 3].toInt()) shl 8) or (pcm16k[idx0 + 2].toInt() and 0xFF)).toShort().toInt()
            val avg = (s0 + s1) / 2
            out[i * 2] = (avg and 0xFF).toByte()
            out[i * 2 + 1] = (avg ushr 8).toByte()
        }
        return out
    }

    fun upsample8kTo16k(pcm8k: ByteArray): ByteArray {
        val samples8k = pcm8k.size / 2
        if (samples8k <= 0) return ByteArray(0)
        val out = ByteArray(samples8k * 4)
        for (i in 0 until samples8k) {
            val sCurr = (((pcm8k[i * 2 + 1].toInt()) shl 8) or (pcm8k[i * 2].toInt() and 0xFF)).toShort().toInt()
            val sNext = if (i + 1 < samples8k) {
                (((pcm8k[(i + 1) * 2 + 1].toInt()) shl 8) or (pcm8k[(i + 1) * 2].toInt() and 0xFF)).toShort().toInt()
            } else sCurr
            val mid = (sCurr + sNext) / 2
            val o = i * 4
            out[o] = (sCurr and 0xFF).toByte()
            out[o + 1] = (sCurr ushr 8).toByte()
            out[o + 2] = (mid and 0xFF).toByte()
            out[o + 3] = (mid ushr 8).toByte()
        }
        return out
    }
}

class GattFrameAccumulator {
    private var buffer = ByteArray(0)

    fun feed(chunk: ByteArray): List<Pair<Byte, ByteArray>> {
        if (chunk.isEmpty()) return emptyList()
        val combined = ByteArray(buffer.size + chunk.size)
        System.arraycopy(buffer, 0, combined, 0, buffer.size)
        System.arraycopy(chunk, 0, combined, buffer.size, chunk.size)
        buffer = combined

        val out = mutableListOf<Pair<Byte, ByteArray>>()
        var offset = 0
        while (offset + OffGridProtocol.GATT_FRAME_HEADER_BYTES <= buffer.size) {
            if (buffer[offset] != OffGridProtocol.GATT_FRAME_MAGIC) {
                offset++
                continue
            }
            val type = buffer[offset + 1]
            val len = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                (buffer[offset + 3].toInt() and 0xFF)
            if (len <= 0 || len > 16_384) {
                offset++
                continue
            }
            val totalNeed = offset + OffGridProtocol.GATT_FRAME_HEADER_BYTES + len
            if (totalNeed > buffer.size) break
            val payload = ByteArray(len)
            System.arraycopy(buffer, offset + OffGridProtocol.GATT_FRAME_HEADER_BYTES, payload, 0, len)
            out.add(type to payload)
            offset = totalNeed
        }
        if (offset > 0) {
            buffer = if (offset >= buffer.size) ByteArray(0)
            else buffer.copyOfRange(offset, buffer.size)
        }
        return out
    }

    companion object {
        fun wrapFrame(type: Byte, payload: ByteArray): ByteArray {
            val out = ByteArray(OffGridProtocol.GATT_FRAME_HEADER_BYTES + payload.size)
            out[0] = OffGridProtocol.GATT_FRAME_MAGIC
            out[1] = type
            out[2] = ((payload.size ushr 8) and 0xFF).toByte()
            out[3] = (payload.size and 0xFF).toByte()
            System.arraycopy(payload, 0, out, OffGridProtocol.GATT_FRAME_HEADER_BYTES, payload.size)
            return out
        }
    }
}
