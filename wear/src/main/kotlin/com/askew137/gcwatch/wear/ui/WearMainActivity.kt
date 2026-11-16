package com.askew137.gcwatch.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.askew137.gcwatch.wear.WearApplication
import com.askew137.gcwatch.wear.gnss.GnssRepository
import com.askew137.gcwatch.wear.navigation.NavigationService
import com.askew137.gcwatch.wear.map.AutoMapSelector
import com.askew137.gcwatch.wear.map.MapEngineService
import com.askew137.gcwatch.wear.ui.compass.CompassViewModel
import com.askew137.gcwatch.wear.ui.map.MapViewModel
import timber.log.Timber

class WearMainActivity : ComponentActivity() {

    // Single-instance engine/repo – in a production app these would be injected via Hilt
    private lateinit var mapEngine: MapEngineService
    private lateinit var gnssRepository: GnssRepository
    private lateinit var autoMapSelector: AutoMapSelector

    /** True while the watch is in ambient (always-on, dimmed) mode. */
    private var isAmbient by mutableStateOf(false)

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient = true
            gnssRepository.setAmbient(true)
        }

        override fun onExitAmbient() {
            isAmbient = false
            gnssRepository.setAmbient(false)
        }
    }

    /** Location (needed for the GPS and for the foreground service) and, on Android 13+, notifications. */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            gnssRepository.onPermissionChanged()
            startNavigationServiceIfAllowed()
        }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val missing = buildList {
            if (!hasLocationPermission()) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@WearMainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    /** The service needs the location permission and a visible app at the moment it starts (Android 14). */
    private fun startNavigationServiceIfAllowed() {
        if (!hasLocationPermission()) return
        try {
            NavigationService.start(this)
        } catch (e: Exception) {
            // Not fatal: without the service the app still works while the screen is on.
            Timber.w(e, "WearMainActivity: could not start the navigation service")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keeps the app running (dimmed) when the screen would otherwise turn off, instead of
        // the system sending it back to the watch face.
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))

        mapEngine = MapEngineService(applicationContext)
        gnssRepository = (application as WearApplication).gnssRepository
        autoMapSelector = AutoMapSelector(applicationContext)

        Timber.d("WearMainActivity: onCreate")
        requestMissingPermissions()

        setContent {
            val configViewModel: ConfigViewModel = viewModel()
            val mapViewModel: MapViewModel = viewModel()
            val navigationViewModel: NavigationViewModel = viewModel(
                factory = NavigationViewModel.Factory(applicationContext)
            )
            val compassViewModel: CompassViewModel = viewModel()

            WearApp(
                configViewModel = configViewModel,
                mapViewModel = mapViewModel,
                navigationViewModel = navigationViewModel,
                compassViewModel = compassViewModel,
                gnssRepository = gnssRepository,
                mapEngine = mapEngine,
                autoMapSelector = autoMapSelector,
                isAmbient = isAmbient,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        NavigationService.cancelTimeout(this)
        gnssRepository.setUiVisible(true)
        gnssRepository.setActive("ui", true)
        startNavigationServiceIfAllowed()
    }

    override fun onStop() {
        super.onStop()
        // Screen off or another app on top: the GPS keeps running only if the service holds it.
        gnssRepository.setUiVisible(false)
        gnssRepository.setActive("ui", false)
        if (!isFinishing) NavigationService.startTimeout(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Closed on purpose (swipe away, "Ukončit"): stop the service too. A destroy by the system
        // while in the background (isFinishing == false) leaves it running.
        if (isFinishing) NavigationService.stop(this)
        mapEngine.destroy()
        Timber.d("WearMainActivity: onDestroy")
    }
}
