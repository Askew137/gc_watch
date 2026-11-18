package com.askew137.gcwatch.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.askew137.gcwatch.mobile.R
import com.askew137.gcwatch.mobile.update.AppUpdater
import com.askew137.gcwatch.mobile.update.WatchVersion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MobileViewModel,
    onNavigateMaps: () -> Unit,
    onNavigateSettings: () -> Unit,
) {
    val connectedNode by viewModel.connectedNodeName.collectAsState()
    val watchSearching by viewModel.watchSearching.collectAsState()
    val reportedName by viewModel.watchDeviceName.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val updateInProgress by viewModel.updateCheckInProgress.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val watchVersion by viewModel.watchVersion.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshConnectedNode()
        viewModel.refreshWatchVersion()
    }
    val toastContext = LocalContext.current
    val checkMessage by viewModel.checkMessage.collectAsState()
    // A failed update must be noticeable, not just a small line inside a card.
    androidx.compose.runtime.LaunchedEffect(updateState) {
        (updateState as? AppUpdater.State.Failed)?.let {
            android.widget.Toast.makeText(toastContext, toastContext.getString(R.string.home_update_failed, it.message), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    // After a watch-only update the watch reinstalls itself without telling us: re-ask until the
    // versions match (the mismatch card then disappears on its own) or we give up.
    androidx.compose.runtime.LaunchedEffect(updateState) {
        if (updateState is AppUpdater.State.AwaitingWatchConfirmation) {
            repeat(30) {
                kotlinx.coroutines.delay(10_000)
                viewModel.refreshWatchVersion()
            }
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Show snackbar when update check finds no new version.
    // UpdateInfo dialog is shown below when non-null.

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── Watch connection card ─────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Watch,
                        contentDescription = null,
                        tint = if (connectedNode != null) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column {
                        Text(
                            text = when {
                                connectedNode != null -> stringResource(R.string.home_watch_connected)
                                watchSearching -> stringResource(R.string.home_watch_searching)
                                else -> stringResource(R.string.home_watch_none)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (connectedNode != null) {
                            Text(
                                text = reportedName ?: connectedNode!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── Navigation shortcuts ─────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateMaps() },
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            stringResource(R.string.maps_routing_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.home_maps_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateSettings() },
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.home_settings_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Phone / watch version mismatch ───────────────────────────────
            val phoneCode = com.askew137.gcwatch.mobile.BuildConfig.VERSION_CODE.toLong()
            val mismatch = when (val w = watchVersion) {
                is WatchVersion.State.Known -> w.code != phoneCode
                WatchVersion.State.NoAnswer -> true
                WatchVersion.State.Unknown -> false
            }
            if (mismatch) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            when (val w = watchVersion) {
                                is WatchVersion.State.Known ->
                                    stringResource(R.string.home_mismatch_versions, w.name, com.askew137.gcwatch.mobile.BuildConfig.VERSION_NAME)
                                else -> stringResource(R.string.home_mismatch_no_report)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.home_mismatch_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        val st = updateState
                        if (st is AppUpdater.State.Working || st is AppUpdater.State.Failed || st is AppUpdater.State.AwaitingWatchConfirmation) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                when (st) {
                                    is AppUpdater.State.Working -> if (st.percent >= 0) stringResource(R.string.step_with_percent, st.step, st.percent) else stringResource(R.string.step_unknown, st.step)
                                    is AppUpdater.State.Failed -> stringResource(R.string.home_failed, st.message)
                                    else -> stringResource(R.string.home_confirm_on_watch)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            enabled = updateState !is AppUpdater.State.Working,
                            onClick = {
                                if (viewModel.canInstallApks()) viewModel.alignVersions(watchVersion)
                                else viewModel.openInstallPermissionSettings()
                            },
                        ) {
                            Text(if (viewModel.canInstallApks()) stringResource(R.string.home_align_versions) else stringResource(R.string.home_allow_install))
                        }
                    }
                }
            }

            // ── Update check ─────────────────────────────────────────────────
            Button(
                onClick = {
                    viewModel.checkForUpdate(manual = true)
                },
                enabled = !updateInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (updateInProgress) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .height(18.dp)
                            .padding(end = 8.dp),
                    )
                }
                Text(if (updateInProgress) stringResource(R.string.home_checking) else stringResource(R.string.home_check_update))
            }

            // Result of a manual check that found nothing new (stays until the next check).
            checkMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Show update result when available.
            updateInfo?.let { info ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.home_update_available, info.version),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        val working = updateState is AppUpdater.State.Working
                        Text(
                            when (val st = updateState) {
                                is AppUpdater.State.Working ->
                                    if (st.percent >= 0) stringResource(R.string.step_with_percent, st.step, st.percent) else stringResource(R.string.step_unknown, st.step)
                                AppUpdater.State.AwaitingConfirmation ->
                                    stringResource(R.string.home_update_confirm_both)
                                AppUpdater.State.AwaitingWatchConfirmation ->
                                    stringResource(R.string.home_confirm_on_watch)
                                is AppUpdater.State.Failed -> stringResource(R.string.home_update_failed, st.message)
                                AppUpdater.State.Idle ->
                                    if (info.canUpdateInApp) stringResource(R.string.home_update_idle_inapp)
                                    else stringResource(R.string.home_update_idle_page)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (updateState is AppUpdater.State.Working) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val pct = (updateState as AppUpdater.State.Working).percent
                            if (pct >= 0) {
                                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(onClick = { viewModel.cancelUpdate() }) { Text(stringResource(R.string.action_cancel)) }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (info.canUpdateInApp) {
                            Button(
                                enabled = !working,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (viewModel.canInstallApks()) viewModel.startUpdate(info)
                                    else viewModel.openInstallPermissionSettings()
                                },
                            ) {
                                Text(if (viewModel.canInstallApks()) stringResource(R.string.home_update_both) else stringResource(R.string.home_allow_install))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.openReleaseUrl(info.releaseUrl) }) { Text(stringResource(R.string.home_release_page)) }
                            OutlinedButton(onClick = { viewModel.clearUpdateInfo() }) { Text(stringResource(R.string.action_dismiss)) }
                        }
                    }
                }
            }

            // Show "up to date" message once check finished with no result.
            if (!updateInProgress && updateInfo == null) {
                // Nothing extra to show – silence is success.
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                stringResource(
                    R.string.home_version_footer,
                    com.askew137.gcwatch.mobile.BuildConfig.VERSION_NAME,
                    com.askew137.gcwatch.mobile.BuildConfig.VERSION_CODE,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
