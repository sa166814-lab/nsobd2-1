package com.example.nsobd2.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class GpsTracker(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var lastLocation: Location? = null

    private val _totalKmGps = MutableStateFlow(0.0)
    val totalKmGps = _totalKmGps.asStateFlow()

    private val _currentSpeedGps = MutableStateFlow(0.0)
    val currentSpeedGps = _currentSpeedGps.asStateFlow()

    private val _hasFix = MutableStateFlow(false)
    val hasFix = _hasFix.asStateFlow()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(res: LocationResult) {
            val loc = res.lastLocation ?: return
            _hasFix.value = true

            // GPS hızı m/s → km/h
            if (loc.hasSpeed()) {
                _currentSpeedGps.value = loc.speed * 3.6
            }

            lastLocation?.let { prev ->
                val deltaMeters = prev.distanceTo(loc)
                // Sadece anlamlı hareket say: duran araçta GPS sapması km biriktirmesin
                val iyiKonum = !loc.hasAccuracy() || loc.accuracy <= 25f
                val hareketli = !loc.hasSpeed() || loc.speed >= 1.0f   // ~3.6 km/s üstü
                if (deltaMeters in 2f..200f && iyiKonum && hareketli) {
                    _totalKmGps.value = _totalKmGps.value + (deltaMeters / 1000.0)
                }
            }
            lastLocation = loc
        }
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) return
        _totalKmGps.value = 0.0
        lastLocation = null
        _hasFix.value = false

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        try {
            client.requestLocationUpdates(req, callback, null)
        } catch (_: SecurityException) {}
    }

    fun stop() {
        try { client.removeLocationUpdates(callback) } catch (_: Exception) {}
    }
}