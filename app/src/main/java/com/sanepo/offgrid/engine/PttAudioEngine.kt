package com.sanepo.offgrid.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class PttAudioEngine(
    private val context: Context,
    private val onSendIpAudioFrame: (ByteArray) -> Unit,
    private val onSendBleAudioFrame: (ByteArray) -> Unit,
    private val hasIpTransportPeers: () -> Boolean,
    private val hasBleGattPeers: () -> Boolean,
    private val onSendPttFloor: (speaking: Boolean) -> Unit
) {
    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_SAMPLES = 640 // 40 ms @ 16 kHz (25 packets/sec for rock-solid jitter stability)
        const val FRAME_BYTES = FRAME_SAMPLES * 2 // 1280 bytes raw PCM per 40 ms

        fun computeNormalizedRms(pcmBytes: ByteArray, length: Int = pcmBytes.size): Float {
            val samples = length / 2
            if (samples <= 0) return 0f
            var sumSq = 0.0
            var i = 0
            while (i + 1 < length) {
                val lo = pcmBytes[i].toInt() and 0xFF
                val hi = pcmBytes[i + 1].toInt()
                val sample = ((hi shl 8) or lo).toShort().toInt()
                sumSq += sample.toDouble() * sample.toDouble()
                i += 2
            }
            val rms = sqrt(sumSq / samples)
            return (rms / 8500.0).toFloat().coerceIn(0f, 1f)
        }

        fun synthesizeRogerBeepPcm(): ByteArray {
            val tone1Samples = (SAMPLE_RATE_HZ * 0.045).toInt()
            val gapSamples = (SAMPLE_RATE_HZ * 0.015).toInt()
            val tone2Samples = (SAMPLE_RATE_HZ * 0.055).toInt()
            val total = tone1Samples + gapSamples + tone2Samples
            val out = ByteBuffer.allocate(total * 2).order(ByteOrder.LITTLE_ENDIAN)

            for (n in 0 until tone1Samples) {
                val env = fadeEnvelope(n, tone1Samples)
                val valF = sin(2.0 * PI * 1350.0 * n / SAMPLE_RATE_HZ) * 6500.0 * env
                out.putShort(valF.toInt().coerceIn(-32767, 32767).toShort())
            }
            for (n in 0 until gapSamples) {
                out.putShort(0)
            }
            for (n in 0 until tone2Samples) {
                val env = fadeEnvelope(n, tone2Samples)
                val valF = sin(2.0 * PI * 1760.0 * n / SAMPLE_RATE_HZ) * 7000.0 * env
                out.putShort(valF.toInt().coerceIn(-32767, 32767).toShort())
            }
            return out.array()
        }

        private fun fadeEnvelope(idx: Int, total: Int): Double {
            val edge = minOf(80, total / 4)
            return when {
                idx < edge -> idx.toDouble() / edge
                idx > total - edge -> (total - idx).toDouble() / edge
                else -> 1.0
            }.coerceIn(0.0, 1.0)
        }
    }

    private data class QueuedPcmFrame(
        val sequence: Int,
        val senderId: String,
        val senderName: String,
        val pcmBytes: ByteArray,
        val isRoger: Boolean
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val rxLock = Object()
    private val jitterBuffer = TreeMap<Int, QueuedPcmFrame>()
    private var expectedNextSeq: Int? = null
    private var burstStartWaitMs: Long = 0L
    private var prebuffering = true
    private var lastRxPacketMs = 0L
    private var lastRxUiNotifyMs = 0L

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var playbackRunning = false

    @Volatile
    private var transmitting = false

    @Volatile
    private var recordingThread: Thread? = null

    @Volatile
    private var txSequence = 0

    private val rogerBeepBytes: ByteArray by lazy { synthesizeRogerBeepPcm() }

    private val rxTimeoutRunnable = Runnable {
        val elapsed = System.currentTimeMillis() - lastRxPacketMs
        if (elapsed >= 1800L) {
            synchronized(rxLock) {
                jitterBuffer.clear()
                expectedNextSeq = null
                prebuffering = true
            }
            MeshRepository.updateSnapshot(forceImmediate = true) {
                if (activeTalkerId != null) {
                    copy(activeTalkerId = null, activeTalkerCallsign = null, rxLevelNormalized = 0f)
                } else {
                    this
                }
            }
        }
    }

    fun startPlayback() {
        if (playbackRunning) return
        ensureAudioTrack()
        playbackRunning = true
        val thread = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {
            }
            while (playbackRunning && !Thread.currentThread().isInterrupted) {
                var frameToPlay: QueuedPcmFrame? = null
                synchronized(rxLock) {
                    if (jitterBuffer.isEmpty()) {
                        prebuffering = true
                        burstStartWaitMs = 0L
                        try {
                            rxLock.wait(250L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    } else {
                        if (prebuffering) {
                            val waited = System.currentTimeMillis() - burstStartWaitMs
                            if (jitterBuffer.size >= 2 || waited >= 95L) {
                                prebuffering = false
                            } else {
                                try {
                                    rxLock.wait(15L)
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                        }
                        if (!prebuffering && jitterBuffer.isNotEmpty()) {
                            val firstKey = jitterBuffer.firstKey()
                            val exp = expectedNextSeq
                            if (exp != null && firstKey > exp && jitterBuffer.size < 3) {
                                // Small 15ms wait for one slightly delayed out-of-order UDP packet before skipping gap
                                try {
                                    rxLock.wait(15L)
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                            val nextKey = if (jitterBuffer.isNotEmpty()) jitterBuffer.firstKey() else null
                            if (nextKey != null) {
                                frameToPlay = jitterBuffer.remove(nextKey)
                                expectedNextSeq = nextKey + 1
                            }
                        }
                    }
                }

                val item = frameToPlay ?: continue
                if (transmitting) continue

                val track = ensureAudioTrack() ?: continue
                try {
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }
                    if (item.isRoger) {
                        track.write(rogerBeepBytes, 0, rogerBeepBytes.size)
                        MeshRepository.updateSnapshot(forceImmediate = true) {
                            copy(activeTalkerId = null, activeTalkerCallsign = null, rxLevelNormalized = 0f)
                        }
                    } else if (item.pcmBytes.isNotEmpty()) {
                        track.write(item.pcmBytes, 0, item.pcmBytes.size)
                        val now = System.currentTimeMillis()
                        if (now - lastRxUiNotifyMs >= 90L) {
                            lastRxUiNotifyMs = now
                            val level = computeNormalizedRms(item.pcmBytes)
                            MeshRepository.updateSnapshot(forceImmediate = false) {
                                copy(
                                    activeTalkerId = item.senderId,
                                    activeTalkerCallsign = item.senderName,
                                    rxLevelNormalized = level
                                )
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }, "SanepoAudioRx")
        playbackThread = thread
        thread.start()
    }

    fun stopAll() {
        stopTransmitting(sendRoger = false)
        mainHandler.removeCallbacks(rxTimeoutRunnable)
        playbackRunning = false
        synchronized(rxLock) {
            jitterBuffer.clear()
            rxLock.notifyAll()
        }
        playbackThread?.interrupt()
        playbackThread = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Throwable) {
        }
        audioTrack = null
    }

    fun isTransmitting(): Boolean = transmitting

    @SuppressLint("MissingPermission")
    fun startTransmitting(): Boolean {
        if (transmitting) return true
        if (!PermissionHelper.hasAudioPermission(context)) {
            MeshRepository.updateSnapshot(forceImmediate = true) {
                copy(lastError = "Izin mikrofon dibutuhkan untuk Walkie-Talkie PTT")
            }
            return false
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            MeshRepository.updateSnapshot(forceImmediate = true) {
                copy(lastError = "Hardware mikrofon tidak merespons format 16kHz PCM")
            }
            return false
        }

        val recBufSize = maxOf(minBuf * 3, FRAME_BYTES * 6)
        val recorder = try {
            val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            }
            AudioRecord(
                source,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recBufSize
            )
        } catch (_: Throwable) {
            null
        } ?: return false

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            try {
                recorder.release()
            } catch (_: Throwable) {
            }
            MeshRepository.updateSnapshot(forceImmediate = true) {
                copy(lastError = "Gagal inisialisasi perekam audio")
            }
            return false
        }

        val aec = if (AcousticEchoCanceler.isAvailable()) {
            try {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            } catch (_: Throwable) {
                null
            }
        } else null

        val ns = if (NoiseSuppressor.isAvailable()) {
            try {
                NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
            } catch (_: Throwable) {
                null
            }
        } else null

        transmitting = true
        playImmediateCue(rogerBeepBytes)
        onSendPttFloor(true)
        MeshRepository.updateSnapshot(forceImmediate = true) {
            copy(isLocalTransmitting = true, lastError = null)
        }

        val thread = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {
            }
            val frameBuf = ByteArray(FRAME_BYTES)
            val adpcmState16 = ImaAdpcmCodec.EncoderState()
            val adpcmState8 = ImaAdpcmCodec.EncoderState()
            var lastMeterUiMs = 0L

            try {
                recorder.startRecording()
                while (transmitting && !Thread.currentThread().isInterrupted) {
                    var offset = 0
                    while (offset < FRAME_BYTES && transmitting) {
                        val read = recorder.read(frameBuf, offset, FRAME_BYTES - offset)
                        if (read > 0) {
                            offset += read
                        } else if (read < 0) {
                            break
                        }
                    }
                    if (!transmitting || offset <= 0) break

                    val snap = MeshRepository.getSnapshot()
                    val seq = ++txSequence

                    // Compact 4:1 ADPCM @ 16kHz (~8 KB/s) over Wi-Fi Direct / LAN UDP
                    val adpcm16 = ImaAdpcmCodec.encodePcm16Le(frameBuf, offset, adpcmState16)
                    val wifiPkt = OffGridProtocol.encodeAudioFrame(
                        type = OffGridProtocol.AUDIO_TYPE_ADPCM_16K,
                        channelNumber = snap.channelNumber,
                        senderNodeId = snap.localNodeId,
                        sequence = seq,
                        pcmBytes = adpcm16
                    )
                    onSendIpAudioFrame(wifiPkt)

                    // Fallback 8kHz ADPCM (~4 KB/s) over Bluetooth LE GATT when no Wi-Fi IP peer is active
                    if (!hasIpTransportPeers() && hasBleGattPeers()) {
                        val pcm8k = ImaAdpcmCodec.downsample16kTo8k(frameBuf, offset)
                        val adpcm8 = ImaAdpcmCodec.encodePcm16Le(pcm8k, pcm8k.size, adpcmState8)
                        val blePkt = OffGridProtocol.encodeAudioFrame(
                            type = OffGridProtocol.AUDIO_TYPE_ADPCM_8K,
                            channelNumber = snap.channelNumber,
                            senderNodeId = snap.localNodeId,
                            sequence = seq,
                            pcmBytes = adpcm8
                        )
                        onSendBleAudioFrame(blePkt)
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastMeterUiMs >= 80L) {
                        lastMeterUiMs = now
                        val level = computeNormalizedRms(frameBuf, offset)
                        MeshRepository.updateSnapshot(forceImmediate = true) {
                            copy(micLevelNormalized = level)
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    aec?.release()
                } catch (_: Throwable) {
                }
                try {
                    ns?.release()
                } catch (_: Throwable) {
                }
                try {
                    recorder.stop()
                } catch (_: Throwable) {
                }
                try {
                    recorder.release()
                } catch (_: Throwable) {
                }
            }
        }, "SanepoPttTx")
        recordingThread = thread
        thread.start()
        return true
    }

    fun stopTransmitting(sendRoger: Boolean = true) {
        if (!transmitting) return
        transmitting = false
        recordingThread?.interrupt()
        recordingThread = null

        val snap = MeshRepository.getSnapshot()
        if (sendRoger) {
            playImmediateCue(rogerBeepBytes)
            val rogerPkt = OffGridProtocol.encodeAudioFrame(
                type = OffGridProtocol.AUDIO_TYPE_ROGER,
                channelNumber = snap.channelNumber,
                senderNodeId = snap.localNodeId,
                sequence = ++txSequence,
                pcmBytes = ByteArray(0)
            )
            onSendIpAudioFrame(rogerPkt)
            if (!hasIpTransportPeers() && hasBleGattPeers()) {
                onSendBleAudioFrame(rogerPkt)
            }
        }
        onSendPttFloor(false)
        MeshRepository.updateSnapshot(forceImmediate = true) {
            copy(isLocalTransmitting = false, isPttLatched = false, micLevelNormalized = 0f)
        }
    }

    fun onIncomingAudioFrame(frame: OffGridProtocol.AudioFramePacket) {
        val snap = MeshRepository.getSnapshot()
        if (frame.senderNodeId == snap.localNodeId) return
        if (frame.channelNumber != snap.channelNumber && !snap.localSosActive) return
        if (transmitting) return

        lastRxPacketMs = System.currentTimeMillis()
        mainHandler.removeCallbacks(rxTimeoutRunnable)
        mainHandler.postDelayed(rxTimeoutRunnable, 1900L)

        val peerName = MeshRepository.getPeersSorted()
            .firstOrNull { it.nodeId == frame.senderNodeId }?.callsign ?: frame.senderNodeId.take(6)

        val decodedPcm: ByteArray = when (frame.type) {
            OffGridProtocol.AUDIO_TYPE_ROGER -> ByteArray(0)
            OffGridProtocol.AUDIO_TYPE_ADPCM_16K -> ImaAdpcmCodec.decodeToPcm16Le(frame.pcmBytes)
            OffGridProtocol.AUDIO_TYPE_ADPCM_8K -> {
                val pcm8k = ImaAdpcmCodec.decodeToPcm16Le(frame.pcmBytes)
                ImaAdpcmCodec.upsample8kTo16k(pcm8k)
            }
            OffGridProtocol.AUDIO_TYPE_PCM -> frame.pcmBytes
            else -> return
        }

        val isRoger = (frame.type == OffGridProtocol.AUDIO_TYPE_ROGER)
        if (!isRoger && decodedPcm.isEmpty()) return

        synchronized(rxLock) {
            if (jitterBuffer.isEmpty()) {
                burstStartWaitMs = System.currentTimeMillis()
                prebuffering = true
                expectedNextSeq = frame.sequence
            }
            if (jitterBuffer.size >= 14) {
                jitterBuffer.remove(jitterBuffer.firstKey())
            }
            jitterBuffer[frame.sequence] = QueuedPcmFrame(
                sequence = frame.sequence,
                senderId = frame.senderNodeId,
                senderName = peerName,
                pcmBytes = decodedPcm,
                isRoger = isRoger
            )
            rxLock.notifyAll()
        }
    }

    private fun playImmediateCue(pcm: ByteArray) {
        val track = ensureAudioTrack() ?: return
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            track.write(pcm, 0, pcm.size)
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureAudioTrack(): AudioTrack? {
        audioTrack?.let { if (it.state == AudioTrack.STATE_INITIALIZED) return it }
        return try {
            try {
                audioManager?.mode = AudioManager.MODE_NORMAL
            } catch (_: Throwable) {
            }
            val minOut = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE_HZ)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minOut * 2, FRAME_BYTES * 6))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.play()
                audioTrack = track
                track
            } else {
                track.release()
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun isSpeakerMuted(): Boolean {
        val am = audioManager ?: return false
        return abs(am.getStreamVolume(AudioManager.STREAM_MUSIC)) == 0
    }
}
