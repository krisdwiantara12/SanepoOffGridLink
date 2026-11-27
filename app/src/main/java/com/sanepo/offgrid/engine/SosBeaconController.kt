package com.sanepo.offgrid.engine

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class SosBeaconController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private var flashCameraId: String? = null

    @Volatile
    private var sosLoopActive = false

    @Volatile
    private var manualTorchOn = false

    @Volatile
    private var sosThread: Thread? = null

    @Volatile
    private var sirenTrack: AudioTrack? = null

    init {
        flashCameraId = resolveBackFlashCameraId()
    }

    fun hasFlashlight(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) &&
            flashCameraId != null
    }

    fun isManualTorchOn(): Boolean = manualTorchOn

    fun setManualTorch(on: Boolean): Boolean {
        manualTorchOn = on
        return applyTorchState(on)
    }

    fun startSosPatterns(enableSiren: Boolean, enableMorseFlash: Boolean) {
        stopSosPatterns()
        sosLoopActive = true
        val thread = Thread({
            var sirenBuf: ByteArray? = null
            if (enableSiren) {
                sirenBuf = synthesizeSirenSweepPcm()
                startSirenTrack()
            }
            var stepIndex = 0
            // Morse SOS durations: . . . (180ms) _ _ _ (450ms) . . . (180ms)
            val patternMs = longArrayOf(
                180, 140, 180, 140, 180, 280,
                460, 160, 460, 160, 460, 280,
                180, 140, 180, 140, 180, 750
            )
            while (sosLoopActive && !Thread.currentThread().isInterrupted) {
                val isOn = (stepIndex % 2) == 0
                if (enableMorseFlash) {
                    applyTorchState(isOn)
                }
                if (enableSiren && sirenBuf != null) {
                    try {
                        sirenTrack?.let { track ->
                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                            track.write(sirenBuf, 0, sirenBuf.size)
                        }
                    } catch (_: Throwable) {
                    }
                }
                try {
                    Thread.sleep(patternMs[stepIndex % patternMs.size])
                } catch (_: InterruptedException) {
                    break
                }
                stepIndex++
            }
            if (enableMorseFlash) {
                applyTorchState(manualTorchOn)
            }
            stopSirenTrack()
        }, "SanepoSosBeacon")
        sosThread = thread
        thread.start()
    }

    fun stopSosPatterns() {
        sosLoopActive = false
        sosThread?.interrupt()
        sosThread = null
        stopSirenTrack()
        if (!manualTorchOn) {
            applyTorchState(false)
        }
    }

    private fun resolveBackFlashCameraId(): String? {
        val mgr = cameraManager ?: return null
        return try {
            for (id in mgr.cameraIdList) {
                val chars = mgr.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyTorchState(enabled: Boolean): Boolean {
        val mgr = cameraManager ?: return false
        val id = flashCameraId ?: resolveBackFlashCameraId()?.also { flashCameraId = it } ?: return false
        return try {
            mgr.setTorchMode(id, enabled)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun startSirenTrack() {
        try {
            val sr = 16_000
            val minOut = AudioTrack.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sr)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minOut, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.play()
                sirenTrack = track
            } else {
                track.release()
            }
        } catch (_: Throwable) {
        }
    }

    private fun stopSirenTrack() {
        try {
            sirenTrack?.pause()
            sirenTrack?.flush()
            sirenTrack?.release()
        } catch (_: Throwable) {
        }
        sirenTrack = null
    }

    private fun synthesizeSirenSweepPcm(): ByteArray {
        val sr = 16_000
        val samples = (sr * 0.25).toInt()
        val buf = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        var phase = 0.0
        for (i in 0 until samples) {
            val frac = i.toDouble() / samples
            val freq = if (frac < 0.5) 2200.0 + (frac * 2.0) * 1100.0 else 3300.0 - ((frac - 0.5) * 2.0) * 1100.0
            phase += 2.0 * PI * freq / sr
            val sample = (sin(phase) * 11000.0).toInt().coerceIn(-32767, 32767).toShort()
            buf.putShort(sample)
        }
        return buf.array()
    }
}
