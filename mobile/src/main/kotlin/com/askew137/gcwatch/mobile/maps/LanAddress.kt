package com.askew137.gcwatch.mobile.maps

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** The phone's IPv4 address on Wi-Fi (what the watch connects to for a local download), or null off Wi-Fi. */
object LanAddress {
    fun find(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val addr = cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            if (addr != null) return addr.hostAddress
        }
        return null
    }

    /** A random token for the one-time URL. */
    fun token(): String =
        (1..16).map { "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
}
