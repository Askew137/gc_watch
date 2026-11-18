package com.askew137.gcwatch.wear.ui

import androidx.compose.ui.res.stringResource
import com.askew137.gcwatch.wear.R
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Icon
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog

/**
 * "Back" on the first screen no longer quits straight away. The first back asks "Ukončit?" (the check mark
 * quits, the cross stays). Back again (or swiping the question away) only goes to the watch face: the app and
 * its background service keep running until the time chosen in the settings.
 */
@Composable
fun ExitOnBack() {
    val activity = LocalContext.current as? Activity
    var asking by remember { mutableStateOf(false) }

    BackHandler(enabled = !asking) { asking = true }

    Dialog(
        showDialog = asking,
        onDismissRequest = {
            // Back (or a swipe) while the question is open: just leave to the watch face.
            asking = false
            activity?.moveTaskToBack(true)
        },
    ) {
        Alert(
            title = { Text(stringResource(R.string.exit_title), textAlign = TextAlign.Center) },
            negativeButton = {
                Button(onClick = { asking = false }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.exit_stay), modifier = Modifier.size(24.dp))
                }
            },
            positiveButton = {
                Button(onClick = { asking = false; activity?.finish() }) {
                    Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.exit_quit), modifier = Modifier.size(24.dp))
                }
            },
        ) {
            Text(stringResource(R.string.exit_back_hint), fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}
