package com.example.nsobd2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.nsobd2.data.AppPrefs
import com.example.nsobd2.data.GpsTracker
import com.example.nsobd2.viewmodel.VehicleViewModel
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    private val viewModel: VehicleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setupWithNavController(navHostFragment.navController)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }

        viewModel.gpsTracker = GpsTracker(applicationContext)
        viewModel.initFromPrefs(this)

        // OTOMATİK BAĞLANMA
        if (AppPrefs.getAutoConnect(this)) {
            val ip = AppPrefs.getSavedIp(this)
            val port = AppPrefs.getSavedPort(this)
            viewModel.connectToVehicle(ip, port)
        }
    }
}