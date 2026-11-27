package com.sanepo.offgrid.engine

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class CompassRadarEngine(
    private val context: Context,
    private val onGpsUpdated: (() -> Unit)? = null
) : SensorEventListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val lastAccel = FloatArray(3)
    private val lastMag = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    private var smoothSin = 0.0
    private var smoothCos = 1.0
    private var lastUiUpdateMs = 0L
    private var running = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            publishLocation(location)
        }

        @Deprecated("Deprecated in Android API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {
            updateGpsAvailabilityText()
        }

        override fun onProviderDisabled(provider: String) {
            updateGpsAvailabilityText()
        }
    }

    fun start() {
        if (running) return
        running = true
        startCompassSensors()
        startLocationUpdates()
    }

    fun stop() {
        running = false
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Throwable) {
        }
        if (PermissionHelper.hasLocationPermission(context)) {
            try {
                locationManager?.removeUpdates(locationListener)
            } catch (_: Throwable) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshLocationPermissions() {
        if (!running) return
        startLocationUpdates()
    }

    private fun startCompassSensors() {
        val mgr = sensorManager ?: return
        val supported = rotSensor != null || (accelSensor != null && magSensor != null)
        MeshRepository.updateSnapshot(forceImmediate = false) {
            copy(compassSupported = supported)
        }
        if (rotSensor != null) {
            mgr.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelSensor?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magSensor?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val mgr = locationManager ?: return
        updateGpsAvailabilityText()
        if (!PermissionHelper.hasLocationPermission(context)) {
            MeshRepository.updateSnapshot(forceImmediate = false) {
                copy(gpsStatusText = "Izin Lokasi belum aktif (Radar memakai jarak sinyal BLE)")
            }
            return
        }

        val gpsEnabled = LocationManagerCompat.isLocationEnabled(mgr)
        if (!gpsEnabled) {
            MeshRepository.updateSnapshot(forceImmediate = false) {
                copy(gpsEnabled = false, gpsStatusText = "GPS mati: hidupkan Lokasi untuk arah kompas presisi")
            }
            return
        }

        try {
            val lastGps = mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastGps != null) {
                publishLocation(lastGps)
            }
            if (mgr.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2500L,
                    2.0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
            if (mgr.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    10.0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun updateGpsAvailabilityText() {
        val mgr = locationManager ?: return
        val enabled = LocationManagerCompat.isLocationEnabled(mgr)
        MeshRepository.updateSnapshot(forceImmediate = false) {
            val status = when {
                !PermissionHelper.hasLocationPermission(context) ->
                    "Izin Lokasi belum aktif (mode jarak RSSI)"
                !enabled ->
                    "GPS perangkat mati"
                localLatitude != null ->
                    "GNSS terkunci (+/-${localGpsAccuracyMeters?.toInt() ?: 0}m)"
                else ->
                    "Mencari sinyal satelit GNSS..."
            }
            copy(gpsEnabled = enabled, gpsStatusText = status)
        }
    }

    private fun publishLocation(loc: Location) {
        val accText = if (loc.hasAccuracy()) "+/-${loc.accuracy.toInt()}m" else "akurasi standar"
        MeshRepository.updateSnapshot(forceImmediate = false) {
            copy(
                gpsEnabled = true,
                localLatitude = loc.latitude,
                localLongitude = loc.longitude,
                localAltitudeMeters = if (loc.hasAltitude()) loc.altitude else localAltitudeMeters,
                localGpsAccuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null,
                gpsStatusText = "GNSS siap ($accText)"
            )
        }
        onGpsUpdated?.invoke()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        var rawAzimuthDeg: Double? = null

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                rawAzimuthDeg = GeoMath.normalizeAzimuth(Math.toDegrees(orientationAngles[0].toDouble()))
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAccel, 0, minOf(3, event.values.size))
                hasAccel = true
                if (hasMag && SensorManager.getRotationMatrix(rotationMatrix, null, lastAccel, lastMag)) {
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    rawAzimuthDeg = GeoMath.normalizeAzimuth(Math.toDegrees(orientationAngles[0].toDouble()))
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMag, 0, minOf(3, event.values.size))
                hasMag = true
                if (hasAccel && SensorManager.getRotationMatrix(rotationMatrix, null, lastAccel, lastMag)) {
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    rawAzimuthDeg = GeoMath.normalizeAzimuth(Math.toDegrees(orientationAngles[0].toDouble()))
                }
            }
        }

        val az = rawAzimuthDeg ?: return
        val rad = Math.toRadians(az)
        val alpha = 0.22
        smoothSin = smoothSin * (1.0 - alpha) + sin(rad) * alpha
        smoothCos = smoothCos * (1.0 - alpha) + cos(rad) * alpha
        val smoothedDeg = GeoMath.normalizeAzimuth(Math.toDegrees(atan2(smoothSin, smoothCos))).toFloat()

        val now = System.currentTimeMillis()
        if (now - lastUiUpdateMs >= 75L) {
            lastUiUpdateMs = now
            MeshRepository.updateSnapshot(forceImmediate = true) {
                copy(deviceAzimuthDeg = smoothedDeg)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }
}
