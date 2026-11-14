package com.askew137.gcwatch.core.communication

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Installs an update of THIS app (same package, same signing key) through the system
 * installer. Android always asks the user to confirm; nothing here is silent. Shared by the
 * phone app and the watch app (the phone sends the watch APK over the Data Layer).
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /** Null if [apk] is a newer build of this app, otherwise a short reason why not. */
    fun verifyUpdate(context: Context, apk: File): String? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return "not a valid APK"
        if (info.packageName != context.packageName) return "wrong package ${info.packageName}"
        val installed = pm.getPackageInfo(context.packageName, 0).longVersionCode
        if (info.longVersionCode <= installed) return "not newer (${info.longVersionCode} <= $installed)"
        return null
    }

    /** "Install unknown apps" must be allowed for this app (once). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user allows installs for this app (where the device has one). */
    fun permissionSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Streams [apk] into an installer session and commits it. The system then asks the user
     * to confirm ([ApkInstallReceiver] opens that dialog). The app is restarted after install.
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // Explicit + mutable: the system adds the status extras.
            val intent = Intent(context, ApkInstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        Log.i(TAG, "Install session $sessionId committed for ${apk.name}")
    }
}

/** Receives the installer's status and opens the system confirmation dialog when asked to. */
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("ApkInstaller", "Update installed")
            else -> Log.w(
                "ApkInstaller",
                "Install did not finish: status=$status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}",
            )
        }
    }
}
