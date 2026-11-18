package com.askew137.gcwatch.wear.ui

import androidx.compose.ui.res.stringResource
import com.askew137.gcwatch.wear.R
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.askew137.gcwatch.core.communication.ApkInstaller
import timber.log.Timber
import java.io.File

/**
 * Shown when the phone has sent a newer watch APK. Android needs a tap to confirm the
 * installation: this screen starts it, the system then asks for the final confirmation.
 */
@Composable
fun UpdatePrompt(context: Context, apk: File, onLater: () -> Unit) {
    var hint by remember { mutableStateOf<String?>(null) }
    val allowHint = stringResource(R.string.update_allow_hint, context.packageName)
    val failedHint = { msg: String? -> context.getString(R.string.update_failed, msg) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.update_title),
                fontSize = 14.sp,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
            )
            Chip(
                onClick = {
                    if (!ApkInstaller.canInstall(context)) {
                        hint = allowHint
                    } else {
                        try {
                            ApkInstaller.install(context, apk)
                        } catch (e: Exception) {
                            Timber.e(e, "Could not start the update")
                            hint = failedHint(e.message)
                        }
                    }
                },
                label = { Text(stringResource(R.string.update_install), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Chip(
                onClick = onLater,
                label = { Text(stringResource(R.string.update_later), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            hint?.let {
                Text(it, fontSize = 9.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
