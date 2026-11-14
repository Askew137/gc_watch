package com.askew137.gcwatch.wear.ui

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.askew137.gcwatch.wear.gnss.GnssRepository
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keeps the app running (dimmed) when the screen would otherwise turn off, instead of
        // the system sending it back to the watch face.
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))

        mapEngine = MapEngineService(applicationContext)
        gnssRepository = GnssRepository(applicationContext)
        autoMapSelector = AutoMapSelector(applicationContext)

        Timber.d("WearMainActivity: onCreate")

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

    override fun onDestroy() {
        super.onDestroy()
        mapEngine.destroy()
        Timber.d("WearMainActivity: onDestroy")
    }
}
