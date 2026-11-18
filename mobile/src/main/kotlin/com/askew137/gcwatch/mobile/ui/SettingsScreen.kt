package com.askew137.gcwatch.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.askew137.gcwatch.mobile.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.askew137.gcwatch.core.model.AccentColor
import com.askew137.gcwatch.core.model.GpsSource
import com.askew137.gcwatch.core.model.ThemeMode
import com.askew137.gcwatch.core.model.Units
import com.askew137.gcwatch.mobile.ui.theme.toComposeColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MobileViewModel,
    onNavigateUp: () -> Unit,
) {
    val config by viewModel.config.collectAsState()
    val accentColors = remember { AccentColor.entries }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── Accent color ──────────────────────────────────────────────────
            SettingsCard(title = stringResource(R.string.settings_accent)) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(accentColors) { accent ->
                        val selected = config.accentColor == accent
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(accent.toComposeColor())
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            width = 3.dp,
                                            color = Color.White,
                                            shape = CircleShape,
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable {
                                    viewModel.updateConfig(config.copy(accentColor = accent))
                                },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_selected, accentName(config.accentColor)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Theme mode ────────────────────────────────────────────────────
            SettingsCard(title = stringResource(R.string.settings_theme)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = config.themeMode == ThemeMode.NORMAL,
                        onClick  = {
                            viewModel.updateConfig(config.copy(themeMode = ThemeMode.NORMAL))
                        },
                        label = { Text(stringResource(R.string.theme_normal)) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = config.themeMode == ThemeMode.NIGHT_RED,
                        onClick  = {
                            viewModel.updateConfig(config.copy(themeMode = ThemeMode.NIGHT_RED))
                        },
                        label = { Text(stringResource(R.string.theme_night_red)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── Units ─────────────────────────────────────────────────────────
            SettingsCard(title = stringResource(R.string.settings_units)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = config.units == Units.METRIC,
                        onClick  = { viewModel.updateConfig(config.copy(units = Units.METRIC)) },
                        label    = { Text(stringResource(R.string.units_metric)) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = config.units == Units.IMPERIAL,
                        onClick  = { viewModel.updateConfig(config.copy(units = Units.IMPERIAL)) },
                        label    = { Text(stringResource(R.string.units_imperial)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── GPS source ────────────────────────────────────────────────────
            SettingsCard(title = stringResource(R.string.settings_gps)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_use_phone_gps),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.settings_gps_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked  = config.gpsSource == GpsSource.PHONE_FUSED,
                        onCheckedChange = { usePhone ->
                            viewModel.updateConfig(
                                config.copy(
                                    gpsSource = if (usePhone) GpsSource.PHONE_FUSED
                                                else GpsSource.WATCH_HARDWARE
                                )
                            )
                        },
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun accentName(accent: AccentColor): String = stringResource(
    when (accent) {
        AccentColor.SIGNAL_GREEN -> R.string.accent_signal_green
        AccentColor.ELECTRIC_CYAN -> R.string.accent_electric_cyan
        AccentColor.SAFETY_ORANGE -> R.string.accent_safety_orange
        AccentColor.SIGNAL_GOLD -> R.string.accent_signal_gold
        AccentColor.PURE_WHITE -> R.string.accent_pure_white
        AccentColor.NIGHT_RED -> R.string.accent_night_red
    }
)
