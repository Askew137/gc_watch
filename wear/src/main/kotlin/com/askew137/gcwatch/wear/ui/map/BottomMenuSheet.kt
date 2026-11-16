package com.askew137.gcwatch.wear.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Switch
import androidx.compose.runtime.mutableStateOf
import androidx.wear.compose.material.Checkbox
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.askew137.gcwatch.core.model.AccentColor
import com.askew137.gcwatch.core.model.AppConfig
import com.askew137.gcwatch.core.model.GpsSource
import com.askew137.gcwatch.core.model.ThemeMode
import com.askew137.gcwatch.core.model.Units
import com.askew137.gcwatch.wear.ui.theme.toComposeColor
import com.askew137.gcwatch.wear.ui.theme.Black
import java.io.File

/**
 * Bottom sheet-style overlay with three tabs:
 * - Mapy: map file selection with auto-detect toggle
 * - Vzhled: accent color and theme mode
 * - Ostatní: units (metric / imperial) and GPS source
 */
@Composable
fun BottomMenuSheet(
    config: AppConfig,
    availableMaps: List<File>,
    selectedMaps: List<File>,
    automaticMapSelection: Boolean,
    onConfigChange: (AppConfig) -> Unit,
    gpsSource: GpsSource,
    onGpsSourceChange: (GpsSource) -> Unit,
    reaction: com.askew137.gcwatch.wear.ui.HeadingReaction,
    onReactionChange: (com.askew137.gcwatch.wear.ui.HeadingReaction) -> Unit,
    ambientGpsSeconds: Int,
    onAmbientGpsSecondsChange: (Int) -> Unit,
    serviceTimeoutHours: Int,
    onServiceTimeoutChange: (Int) -> Unit,
    onMapSelectionChange: (List<File>, Boolean) -> Unit,
    onDeleteMaps: (List<File>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    // The system back gesture closes the sheet instead of leaving the screen.
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val tabs = listOf("Mapy", "Vzhled", "Ostatní")

    // Swipe right to close (the standard Wear gesture); without this wrapper the swipe would
    // be handled by the navigation host and leave the screen / the app.
    SwipeToDismissBox(onDismissed = onDismiss) { isBackground ->
      if (!isBackground) Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .clickable(onClick = {}), // consume clicks
      ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 26.dp, end = 26.dp, top = 30.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Tab row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                tabs.forEachIndexed { index, label ->
                    val isSelected = selectedTab == index
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        maxLines = 1,
                        fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else null,
                        color = if (isSelected) MaterialTheme.colors.primary else Color.Gray,
                        modifier = Modifier
                            .clickable { selectedTab = index }
                            .padding(horizontal = 3.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            when (selectedTab) {
                0 -> MapyTab(
                    availableMaps = availableMaps,
                    selectedMaps = selectedMaps,
                    automatic = automaticMapSelection,
                    onSelectionChange = onMapSelectionChange,
                    onDelete = onDeleteMaps,
                )
                1 -> GivenThemeTab(
                    config = config,
                    onConfigChange = onConfigChange,
                )
                2 -> OstatniTab(
                    config = config,
                    onConfigChange = onConfigChange,
                    gpsSource = gpsSource,
                    onGpsSourceChange = onGpsSourceChange,
                    reaction = reaction,
                    onReactionChange = onReactionChange,
                    ambientGpsSeconds = ambientGpsSeconds,
                    onAmbientGpsSecondsChange = onAmbientGpsSecondsChange,
                    serviceTimeoutHours = serviceTimeoutHours,
                    onServiceTimeoutChange = onServiceTimeoutChange,
                )
            }
        }
    }
    }
}

// ── Tab 1: Map files ──────────────────────────────────────────────────────────

@Composable
private fun MapyTab(
    availableMaps: List<File>,
    selectedMaps: List<File>,
    automatic: Boolean,
    onSelectionChange: (List<File>, Boolean) -> Unit,
    onDelete: (List<File>) -> Unit,
) {
    // Delete mode: the switches become checkboxes; "Smazat vybrané" asks once more before deleting.
    var deleting by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf(setOf<File>()) }
    // A map that vanished (deleted elsewhere) must not stay ticked.
    val ticked = toDelete.filter { it in availableMaps }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        ToggleChip(
            checked = automatic,
            onCheckedChange = { onSelectionChange(selectedMaps, it) },
            label = { Text("Auto podle polohy", fontSize = 11.sp, maxLines = 1) },
            toggleControl = { Switch(checked = automatic) },
            modifier = Modifier.fillMaxWidth(),
            colors = ToggleChipDefaults.toggleChipColors(
                checkedStartBackgroundColor = Color.Transparent,
                checkedEndBackgroundColor = Color.Transparent,
            ),
        )
        Spacer(Modifier.height(4.dp))
        availableMaps.forEach { file ->
            if (deleting) {
                val mark = file in toDelete
                ToggleChip(
                    checked = mark,
                    onCheckedChange = {
                        toDelete = if (it) toDelete + file else toDelete - file
                        confirming = false
                    },
                    label = { Text(file.name, fontSize = 11.sp, maxLines = 1) },
                    secondaryLabel = { Text(formatSize(file.length()), fontSize = 9.sp, color = Color.Gray) },
                    toggleControl = { Checkbox(checked = mark) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ToggleChipDefaults.toggleChipColors(
                        checkedStartBackgroundColor = Color(0xFF5A1010),
                        checkedEndBackgroundColor = Color(0xFF5A1010),
                    ),
                )
            } else {
                val isChecked = file in selectedMaps
                ToggleChip(
                    checked = isChecked,
                    onCheckedChange = { checked ->
                        val newSelection = if (checked) selectedMaps + file else selectedMaps - file
                        onSelectionChange(newSelection, automatic)
                    },
                    label = { Text(file.name, fontSize = 11.sp, maxLines = 1) },
                    toggleControl = { Switch(checked = isChecked) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ToggleChipDefaults.toggleChipColors(
                        checkedStartBackgroundColor = Color.Transparent,
                        checkedEndBackgroundColor = Color.Transparent,
                    ),
                )
            }
        }
        if (availableMaps.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            if (!deleting) {
                Chip(
                    onClick = { deleting = true; toDelete = emptySet(); confirming = false },
                    label = { Text("Smazat mapy…", fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                    colors = ChipDefaults.chipColors(backgroundColor = Color.DarkGray),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Chip(
                    enabled = ticked.isNotEmpty(),
                    onClick = {
                        if (!confirming) {
                            confirming = true
                        } else {
                            onDelete(ticked)
                            deleting = false; confirming = false; toDelete = emptySet()
                        }
                    },
                    label = {
                        Text(
                            when {
                                ticked.isEmpty() -> "Zaškrtni mapy ke smazání"
                                confirming -> "Opravdu smazat ${ticked.size}? (klepni znovu)"
                                else -> "Smazat vybrané (${ticked.size})"
                            },
                            fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = if (confirming) Color(0xFFB71C1C) else Color(0xFF5A1010)),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Chip(
                    onClick = { deleting = false; confirming = false; toDelete = emptySet() },
                    label = { Text("Zrušit", fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                    colors = ChipDefaults.chipColors(backgroundColor = Color.DarkGray),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000) "%.1f MB".format(bytes / 1_000_000.0) else "${bytes / 1000} kB"

// ── Tab 2: Theme ──────────────────────────────────────────────────────────────

@Composable
private fun GivenThemeTab(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        Text("Akcent", fontSize = 10.sp, color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        AccentColor.entries.chunked(3).forEach { rowColors ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 3.dp),
            ) {
                rowColors.forEach { color ->
                    val isSelected = config.accentColor == color
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(color.toComposeColor())
                            .then(
                                if (isSelected) Modifier.border(2.dp, Color.White, CircleShape)
                                else Modifier
                            )
                            .clickable { onConfigChange(config.copy(accentColor = color)) },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Téma", fontSize = 10.sp, color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                Chip(
                    onClick = { onConfigChange(config.copy(themeMode = mode)) },
                    label = {
                        Text(
                            text = if (mode == ThemeMode.NORMAL) "Normální" else "Noční",
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = if (config.themeMode == mode)
                            MaterialTheme.colors.primary.copy(alpha = 0.3f)
                        else Color.DarkGray,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ── Tab 3: Units + GPS source ─────────────────────────────────────────────────

@Composable
private fun OstatniTab(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit,
    gpsSource: GpsSource,
    onGpsSourceChange: (GpsSource) -> Unit,
    reaction: com.askew137.gcwatch.wear.ui.HeadingReaction,
    onReactionChange: (com.askew137.gcwatch.wear.ui.HeadingReaction) -> Unit,
    ambientGpsSeconds: Int,
    onAmbientGpsSecondsChange: (Int) -> Unit,
    serviceTimeoutHours: Int,
    onServiceTimeoutChange: (Int) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Units.entries.forEach { units ->
                Chip(
                    onClick = { onConfigChange(config.copy(units = units)) },
                    label = {
                        Text(
                            text = if (units == Units.METRIC) "Metry" else "Míle",
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = if (config.units == units)
                            MaterialTheme.colors.primary.copy(alpha = 0.3f)
                        else Color.DarkGray,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val phone = gpsSource == GpsSource.PHONE_FUSED
        ToggleChip(
            checked = phone,
            onCheckedChange = {
                onGpsSourceChange(if (it) GpsSource.PHONE_FUSED else GpsSource.WATCH_HARDWARE)
            },
            label = { Text("GPS z telefonu", fontSize = 11.sp, maxLines = 1) },
            secondaryLabel = {
                Text(if (phone) "telefon" else "hodinky", fontSize = 9.sp, color = Color.Gray)
            },
            toggleControl = { Switch(checked = phone) },
            modifier = Modifier.fillMaxWidth(),
            colors = ToggleChipDefaults.toggleChipColors(
                checkedStartBackgroundColor = Color.Transparent,
                checkedEndBackgroundColor = Color.Transparent,
            ),
        )
        Spacer(Modifier.height(8.dp))
        // Cone reaction: three positions (fast / normal / frugal), shown from slow to fast.
        val reactions = com.askew137.gcwatch.wear.ui.HeadingReaction.entries.reversed() // ECO, NORMAL, FAST
        Text("Reakce kuželu: ${reaction.label}", fontSize = 11.sp, color = Color.White)
        InlineSlider(
            value = reactions.indexOf(reaction).toFloat(),
            onValueChange = { onReactionChange(reactions[it.toInt().coerceIn(0, reactions.lastIndex)]) },
            increaseIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Add, "Rychleji", Modifier.size(18.dp)) },
            decreaseIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Remove, "Pomaleji", Modifier.size(18.dp)) },
            valueRange = 0f..2f,
            steps = 1,
        )
        Spacer(Modifier.height(6.dp))
        // GPS interval while ambient: 5 s .. 60 s.
        val minS = com.askew137.gcwatch.wear.ui.UiPrefs.AMBIENT_GPS_MIN_S
        val maxS = com.askew137.gcwatch.wear.ui.UiPrefs.AMBIENT_GPS_MAX_S
        val stepS = com.askew137.gcwatch.wear.ui.UiPrefs.AMBIENT_GPS_STEP_S
        Text("GPS při zhasnutí: každých $ambientGpsSeconds s", fontSize = 11.sp, color = Color.White)
        InlineSlider(
            value = ambientGpsSeconds.toFloat(),
            onValueChange = { onAmbientGpsSecondsChange((Math.round(it / stepS) * stepS).coerceIn(minS, maxS)) },
            increaseIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Add, "Méně často", Modifier.size(18.dp)) },
            decreaseIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Remove, "Častěji", Modifier.size(18.dp)) },
            valueRange = minS.toFloat()..maxS.toFloat(),
            steps = (maxS - minS) / stepS - 1,
        )
        Spacer(Modifier.height(6.dp))
        // How long the navigation service (GPS in the background) may run with the app not opened.
        val choices = com.askew137.gcwatch.wear.ui.UiPrefs.SERVICE_TIMEOUT_CHOICES_H
        val idx = choices.indexOf(serviceTimeoutHours).coerceAtLeast(0)
        Text(
            if (serviceTimeoutHours == 0) "Služba na pozadí: neomezeně"
            else "Vypnout GPS na pozadí po $serviceTimeoutHours h",
            fontSize = 11.sp, color = Color.White,
        )
        InlineSlider(
            value = idx.toFloat(),
            onValueChange = { onServiceTimeoutChange(choices[it.toInt().coerceIn(0, choices.lastIndex)]) },
            increaseIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Add, "Déle", Modifier.size(18.dp)) },
            decreaseIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Remove, "Dříve", Modifier.size(18.dp)) },
            valueRange = 0f..(choices.lastIndex).toFloat(),
            steps = choices.size - 2,
        )
        Text("od odchodu z aplikace", fontSize = 9.sp, color = Color.Gray)
        Spacer(Modifier.height(10.dp))
        Text(
            "GC Watch v${com.askew137.gcwatch.wear.BuildConfig.VERSION_NAME} (${com.askew137.gcwatch.wear.BuildConfig.VERSION_CODE})",
            fontSize = 10.sp,
            color = Color.Gray,
        )
        Spacer(Modifier.height(6.dp))
    }
}
