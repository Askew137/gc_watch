package com.askew137.gcwatch.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.askew137.gcwatch.core.model.NavigationTarget
import com.askew137.gcwatch.core.model.Units
import com.askew137.gcwatch.wear.ui.compass.formatDistance

/**
 * What the app shows while the watch is in ambient (always-on, "screen off") mode: almost black,
 * thin grey text, no filled areas (battery and burn-in). The app stays running, so the GPS keeps
 * going and raising the wrist brings the normal screen back where it was.
 */
@Composable
fun AmbientScreen(target: NavigationTarget?, units: Units) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            if (target != null) {
                Text(
                    text = formatDistance(target.distanceM, units),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                    color = Color(0xFFBBBBBB),
                    textAlign = TextAlign.Center,
                )
                val name = target.waypoint.code.ifEmpty { target.waypoint.label }
                if (name.isNotEmpty()) {
                    Text(
                        text = name,
                        fontSize = 12.sp,
                        color = Color(0xFF777777),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            } else {
                Text("GC Watch", fontSize = 14.sp, color = Color(0xFF777777))
            }
        }
    }
}
