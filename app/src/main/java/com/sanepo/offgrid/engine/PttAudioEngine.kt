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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class PttAudioEngine(
    private val context: Context,
    private val onSendAudioFrame: (ByteArray) -> Unit,
    private val onSendPttFloor: (speaking: Boolean) -> Unit
) {
    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_SAMPLES = 320 // 20 ms
        const val FRAME_BYTES = FRAME_SAMPLES * 2 // 640 bytes 16-bit PCM

        fun computeNormalizedRms(pcmBytes: ByteArray, length: Int = pcmBytes.size): Float {
            val samples = length / 2
            if (samples <= 0) return 0f
            var sumSq = 0.0
            var i = 0
            while (i + 1 < length) {
                val lo = pcmBytes[i].toInt() and 0xFF
                val hi = pcmBytes[i + 1].toInt()
                val sample = (hi shl 8) or lo
                sumSq += sample.toDouble() * sample.toDouble()
                i += 2
            }
            val rms = sqrt(sumSq / samples)
            return (rms / 9000.0).toFloat().coerceIn(0f, 1f)
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioTrack: AudioTrack? = null
    private var rxWatchdogPosted = false
    private var lastRxPacketMs = 0L

    @Volatile
    private var transmitting = false

    @Volatile
    private var recordingThread: Thread? = null

    @Volatile
    private var txSequence = 0

    private val rogerBeepBytes: ByteArray by lazy { synthesizeRogerBeepPcm() }

    private val rxTimeoutRunnable = Runnable {
        rxWatchdogPosted = false
        val elapsed = System.currentTimeMillis() - lastRxPacketMs
        if (elapsed >= 2200L) {
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
        ensureAudioTrack()
    }

    fun stopAll() {
        stopTransmitting(sendRoger = false)
        mainHandler.removeCallbacks(rxTimeoutRunnable)
        rxWatchdogPosted = false
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

        val recBufSize = maxOf(minBuf * 2, FRAME_BYTES * 8)
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
        playLocalCue(rogerBeepBytes)
        onSendPttFloor(true)
        MeshRepository.updateSnapshot(forceImmediate = true) {
            copy(isLocalTransmitting = true, lastError = null)
        }

        val thread = Thread({
            val frameBuf = ByteArray(FRAME_BYTES)
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
                    val pkt = OffGridProtocol.encodeAudioFrame(
                        type = OffGridProtocol.AUDIO_TYPE_PCM,
                        channelNumber = snap.channelNumber,
                        senderNodeId = snap.localNodeId,
                        sequence = ++txSequence,
                        pcmBytes = if (offset == FRAME_BYTES) frameBuf else frameBuf.copyOf(offset)
                    )
                    onSendAudioFrame(pkt)

                    val now = System.currentTimeMillis()
                    if (now - lastMeterUiMs >= 60L) {
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
            playLocalCue(rogerBeepBytes)
            val rogerPkt = OffGridProtocol.encodeAudioFrame(
                type = OffGridProtocol.AUDIO_TYPE_ROGER,
                channelNumber = snap.channelNumber,
                senderNodeId = snap.localNodeId,
                sequence = ++txSequence,
                pcmBytes = ByteArray(0)
            )
            onSendAudioFrame(rogerPkt)
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
        if (transmitting) return // Simplex priority: ignore echo while holding TX

        lastRxPacketMs = System.currentTimeMillis()
        scheduleRxTimeout()

        val peerName = MeshRepository.getPeersSorted()
            .firstOrNull { it.nodeId == frame.senderNodeId }?.callsign ?: frame.senderNodeId.take(6)

        if (frame.type == OffGridProtocol.AUDIO_TYPE_ROGER) {
            playLocalCue(rogerBeepBytes)
            MeshRepository.updateSnapshot(forceImmediate = true) {
                copy(activeTalkerId = null, activeTalkerCallsign = null, rxLevelNormalized = 0f)
            }
            return
        }

        if (frame.pcmBytes.isEmpty()) return
        val track = ensureAudioTrack() ?: return
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            track.write(frame.pcmBytes, 0, frame.pcmBytes.size)
            val rxLevel = computeNormalizedRms(frame.pcmBytes)
            MeshRepository.updateSnapshot(forceImmediate = false) {
                copy(
                    activeTalkerId = frame.senderNodeId,
                    activeTalkerCallsign = peerName,
                    rxLevelNormalized = rxLevel
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun scheduleRxTimeout() {
        mainHandler.removeCallbacks(rxTimeoutRunnable)
        rxWatchdogPosted = true
        mainHandler.postDelayed(rxTimeoutRunnable, 2300L)
    }

    private fun playLocalCue(pcm: ByteArray) {
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
                .setBufferSizeInBytes(maxOf(minOut, FRAME_BYTES * 8))
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
