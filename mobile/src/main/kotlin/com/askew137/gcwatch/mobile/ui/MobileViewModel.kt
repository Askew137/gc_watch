package com.askew137.gcwatch.mobile.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.askew137.gcwatch.core.model.AppConfig
import com.askew137.gcwatch.mobile.maps.CopyProgress
import com.askew137.gcwatch.mobile.maps.MapFileRepository
import com.askew137.gcwatch.mobile.maps.MapTransferService
import com.askew137.gcwatch.mobile.routing.SegmentDownloadService
import com.askew137.gcwatch.mobile.routing.SegmentRepository
import com.askew137.gcwatch.core.communication.ApkInstaller
import com.askew137.gcwatch.mobile.update.AppUpdater
import com.askew137.gcwatch.mobile.update.UpdateChecker
import com.askew137.gcwatch.mobile.update.UpdateInfo
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.io.File

/**
 * Shared [ViewModel] for the mobile companion app.
 *
 * All mutable state is exposed as [StateFlow] and never modified from the UI.
 */
class MobileViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<MobileApplication>()
    private val configRepo get() = app.configRepository

    // ── Config ────────────────────────────────────────────────────────────────

    val config: StateFlow<AppConfig> = configRepo.config

    fun updateConfig(newConfig: AppConfig) {
        configRepo.updateConfig(newConfig)
    }

    // ── Watch connectivity ────────────────────────────────────────────────────

    private val _connectedNodeName = MutableStateFlow<String?>(null)
    val connectedNodeName: StateFlow<String?> = _connectedNodeName.asStateFlow()

    // ── Map files ─────────────────────────────────────────────────────────────

    private val _mapFiles = MutableStateFlow<List<File>>(emptyList())
    val mapFiles: StateFlow<List<File>> = _mapFiles.asStateFlow()

    private val _copyProgress = MutableStateFlow<CopyProgress?>(null)
    val copyProgress: StateFlow<CopyProgress?> = _copyProgress.asStateFlow()

    // ── Routing data (BRouter .rd5 tiles) ─────────────────────────────────────

    private val _installedSegments = MutableStateFlow<List<File>>(emptyList())
    val installedSegments: StateFlow<List<File>> = _installedSegments.asStateFlow()

    val segmentDownload = SegmentRepository.download
    val segmentDownloadError = SegmentRepository.downloadError
    val missingSegment = SegmentRepository.missingTile
    val segmentRemoteSizes = SegmentRepository.remoteSizes

    // ── Update check ──────────────────────────────────────────────────────────

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _updateCheckInProgress = MutableStateFlow(false)
    val updateCheckInProgress: StateFlow<Boolean> = _updateCheckInProgress.asStateFlow()

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        refreshMapFiles()
        refreshConnectedNode()
        refreshSegments()
        checkForUpdate() // silent: only a newer release shows anything
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            SegmentRepository.fetchRemoteSizes(SegmentRepository.knownTiles.map { it.name })
        }
        // A finished (or failed) download changes what is installed.
        viewModelScope.launch {
            SegmentRepository.download.collect { refreshSegments() }
        }
    }

    // ── Public actions ────────────────────────────────────────────────────────

    fun refreshMapFiles() {
        _mapFiles.value = MapFileRepository.listLocalMapFiles(app)
    }

    fun copyMapFile(uri: Uri) {
        viewModelScope.launch {
            MapFileRepository.copyMapFileFromUri(app, uri).collectLatest { progress ->
                _copyProgress.value = progress
                if (progress.done) {
                    refreshMapFiles()
                    _copyProgress.value = null
                }
            }
        }
    }

    fun sendMapToWatch(mapFile: File) {
        MapTransferService.startTransfer(app, Uri.fromFile(mapFile))
    }

    fun refreshSegments() {
        _installedSegments.value = SegmentRepository.installedFiles(app)
    }

    fun downloadSegment(tile: String) = SegmentDownloadService.start(app, tile)

    fun deleteSegment(tile: String) {
        SegmentRepository.delete(app, tile)
        refreshSegments()
    }

    /** Also fetches the size of a tile the engine asked for that is not in the known list. */
    fun fetchSegmentSize(tile: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { SegmentRepository.fetchRemoteSizes(listOf(tile)) }
    }

    val updateState = AppUpdater.state
    val watchVersion = com.askew137.gcwatch.mobile.update.WatchVersion.state

    fun refreshWatchVersion() {
        viewModelScope.launch { com.askew137.gcwatch.mobile.update.WatchVersion.refresh(app) }
    }

    /**
     * Phone and watch run different versions: fetch the newer one's release and update the
     * side that is behind. [watch] is what the watch reported (NoAnswer = assume it is behind).
     */
    fun alignVersions(watch: com.askew137.gcwatch.mobile.update.WatchVersion.State) {
        val phoneCode = com.askew137.gcwatch.mobile.BuildConfig.VERSION_CODE.toLong()
        val phoneName = com.askew137.gcwatch.mobile.BuildConfig.VERSION_NAME
        val known = watch as? com.askew137.gcwatch.mobile.update.WatchVersion.State.Known
        val phoneBehind = known != null && known.code > phoneCode
        val targetName = if (phoneBehind) known!!.name else phoneName
        viewModelScope.launch {
            val release = UpdateChecker.releaseFor(targetName)
            if (release == null) {
                AppUpdater.fail("Release v$targetName not found on GitHub")
            } else {
                AppUpdater.catchUp(app, release, updateWatch = !phoneBehind, updatePhone = phoneBehind)
            }
        }
    }

    fun canInstallApks(): Boolean = ApkInstaller.canInstall(app)

    fun openInstallPermissionSettings() {
        app.startActivity(ApkInstaller.permissionSettingsIntent(app))
    }

    fun startUpdate(info: com.askew137.gcwatch.mobile.update.UpdateInfo) = AppUpdater.start(app, info)

    fun checkForUpdate() {
        if (_updateCheckInProgress.value) return
        viewModelScope.launch {
            _updateCheckInProgress.value = true
            try {
                val versionName = app.packageManager
                    .getPackageInfo(app.packageName, 0)
                    .versionName ?: "1.0.0"
                _updateInfo.value = UpdateChecker.checkForUpdate(versionName)
            } finally {
                _updateCheckInProgress.value = false
            }
        }
    }

    fun clearUpdateInfo() {
        _updateInfo.value = null
    }

    fun openReleaseUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        app.startActivity(intent)
    }

    /** Called when the home screen is shown; the node list is otherwise only read once. */
    fun refreshConnectedNode() {
        viewModelScope.launch {
            try {
                val nodes = Wearable.getNodeClient(app).connectedNodes.await()
                _connectedNodeName.value = nodes.firstOrNull()?.displayName
            } catch (e: Exception) {
                Timber.w(e, "Could not query connected nodes")
                _connectedNodeName.value = null
            }
        }
    }
}

/** Simple factory wiring [MobileViewModel] to the [Application]. */
class MobileViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MobileViewModel(application) as T
}
