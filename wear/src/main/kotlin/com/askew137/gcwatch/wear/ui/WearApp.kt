package com.askew137.gcwatch.wear.ui

import androidx.compose.runtime.Composable
import com.askew137.gcwatch.wear.WearApplication
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.askew137.gcwatch.core.model.AppConfig
import com.askew137.gcwatch.core.model.NavigationTarget
import com.askew137.gcwatch.wear.ui.theme.toComposeColor
import com.askew137.gcwatch.wear.gnss.GnssRepository
import com.askew137.gcwatch.wear.map.AutoMapSelector
import com.askew137.gcwatch.wear.map.MapEngineService
import com.askew137.gcwatch.wear.ui.compass.CompassScreen
import com.askew137.gcwatch.wear.ui.compass.CompassViewModel
import com.askew137.gcwatch.wear.ui.map.MapScreen
import com.askew137.gcwatch.wear.ui.map.MapViewModel
import com.askew137.gcwatch.wear.ui.theme.GcWatchTheme

private const val DEST_MAP = "map"
private const val DEST_COMPASS = "compass"

/**
 * Root composable. Wraps everything in [GcWatchTheme] and sets up [SwipeDismissableNavHost].
 */
@Composable
fun WearApp(
    configViewModel: ConfigViewModel,
    mapViewModel: MapViewModel,
    navigationViewModel: NavigationViewModel,
    compassViewModel: CompassViewModel,
    gnssRepository: GnssRepository,
    mapEngine: MapEngineService,
    autoMapSelector: AutoMapSelector,
    isAmbient: Boolean = false,
) {
    val config by configViewModel.config.collectAsStateWithLifecycle()
    val location by gnssRepository.location.collectAsStateWithLifecycle()
    val activeTarget by navigationViewModel.activeTarget.collectAsStateWithLifecycle()
    val currentRoute by navigationViewModel.currentRoute.collectAsStateWithLifecycle()
    val lineMode by navigationViewModel.lineMode.collectAsStateWithLifecycle()
    val routePending by navigationViewModel.routePending.collectAsStateWithLifecycle()
    val routeRequestNeeded by navigationViewModel.routeRequestNeeded.collectAsStateWithLifecycle()

    // Ask the phone for the route as soon as there is a target AND a GPS fix, so it is ready
    // before the user switches to it. Re-keyed only when the need or the first fix changes.
    val hasFix = location != null
    LaunchedEffect(routeRequestNeeded, hasFix) {
        val target = routeRequestNeeded
        val loc = location
        if (target != null && loc != null) navigationViewModel.requestRoute(loc, target)
    }

    // The sensor heading is magnetic: correct it to true north for the wearer's position.
    LaunchedEffect(location?.lat, location?.lon) {
        location?.let { compassViewModel.updateDeclination(it.lat, it.lon) }
    }

    // Build NavigationTarget from location + active target
    val navigationTarget = remember(location, activeTarget) {
        val loc = location ?: return@remember null
        val target = activeTarget ?: return@remember null
        val bearing = bearingTo(loc.lat, loc.lon, target.lat, target.lon)
        val distance = distanceTo(loc.lat, loc.lon, target.lat, target.lon)
        NavigationTarget(waypoint = target, bearingDeg = bearing, distanceM = distance)
    }

    GcWatchTheme(config = config) {
        val navController = rememberSwipeDismissableNavController()

        // The wearer reached the cache and "switch to the compass" is on: show the compass (also when the
        // arrival happened while the screen was off - the request waits until the app is shown).
        val context0 = LocalContext.current
        val arrivalMonitor = (context0.applicationContext as WearApplication).arrivalMonitor
        val compassRequested by arrivalMonitor.pendingCompass.collectAsStateWithLifecycle()
        LaunchedEffect(compassRequested) {
            if (compassRequested) {
                if (navController.currentDestination?.route != DEST_COMPASS) navController.navigate(DEST_COMPASS)
                arrivalMonitor.consumeCompass()
            }
        }

        if (isAmbient) {
            // Dimmed, minimal screen; the map is not drawn at all while ambient.
            AmbientScreen(target = navigationTarget, units = config.units)
        } else Box {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = DEST_MAP,
        ) {
            composable(DEST_MAP) {
                ExitOnBack()
                // The screen reads the live state itself: values captured by the NavHost builder go stale
                // (the compass distance stood still), only state read inside the destination is current.
                val liveConfig by configViewModel.config.collectAsStateWithLifecycle()
                val liveLocation by gnssRepository.location.collectAsStateWithLifecycle()
                val liveTarget by navigationViewModel.activeTarget.collectAsStateWithLifecycle()
                val liveRoute by navigationViewModel.currentRoute.collectAsStateWithLifecycle()
                val liveLineMode by navigationViewModel.lineMode.collectAsStateWithLifecycle()
                val livePending by navigationViewModel.routePending.collectAsStateWithLifecycle()
                MapScreen(
                    config = liveConfig,
                    location = liveLocation,
                    activeTarget = liveTarget,
                    route = liveRoute,
                    lineMode = liveLineMode,
                    routePending = livePending,
                    mapEngine = mapEngine,
                    autoMapSelector = autoMapSelector,
                    mapViewModel = mapViewModel,
                    compassViewModel = compassViewModel,
                    gnssRepository = gnssRepository,
                    onNavigateToCompass = { navController.navigate(DEST_COMPASS) },
                    onConfigChange = { configViewModel.updateConfig(it) },
                    onCycleLineMode = { navigationViewModel.cycleLineMode() },
                )
            }
            composable(DEST_COMPASS) {
                val liveConfig by configViewModel.config.collectAsStateWithLifecycle()
                val liveLocation by gnssRepository.location.collectAsStateWithLifecycle()
                val liveTarget by navigationViewModel.activeTarget.collectAsStateWithLifecycle()
                val liveNavigationTarget = remember(liveLocation, liveTarget) {
                    val loc = liveLocation ?: return@remember null
                    val target = liveTarget ?: return@remember null
                    NavigationTarget(
                        waypoint = target,
                        bearingDeg = bearingTo(loc.lat, loc.lon, target.lat, target.lon),
                        distanceM = distanceTo(loc.lat, loc.lon, target.lat, target.lon),
                    )
                }
                CompassScreen(
                    config = liveConfig,
                    location = liveLocation,
                    navigationTarget = liveNavigationTarget,
                    compassViewModel = compassViewModel,
                    gnssRepository = gnssRepository,
                    onNavigateToMap = { navController.popBackStack() },
                )
            }
        }

        // A newer watch APK sent by the phone: offer to install it (Android needs the tap).
        val context = LocalContext.current
        val pendingUpdate by (context.applicationContext as WearApplication)
            .pendingUpdate.collectAsStateWithLifecycle()
        var updateLater by remember { mutableStateOf(false) }
        pendingUpdate?.let { apk ->
            if (!updateLater) UpdatePrompt(context, apk, onLater = { updateLater = true })
        }
        }
    }
}

// ── Great-circle helpers ──────────────────────────────────────────────────────

private fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val dLon = Math.toRadians(lon2 - lon1)
    val lat1R = Math.toRadians(lat1)
    val lat2R = Math.toRadians(lat2)
    val y = Math.sin(dLon) * Math.cos(lat2R)
    val x = Math.cos(lat1R) * Math.sin(lat2R) - Math.sin(lat1R) * Math.cos(lat2R) * Math.cos(dLon)
    return ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0).toFloat()
}

private fun distanceTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLon / 2).let { it * it }
    return (r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))).toFloat()
}
