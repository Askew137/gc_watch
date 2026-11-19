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
        return hotspotAddress()
    }

    /**
     * The phone is the hotspot (the watch joined the phone's own Wi-Fi): then the phone is not a Wi-Fi *client*, so
     * there is no Wi-Fi network above, but the access-point interface (ap0, swlan0, wlan1, ...) holds a private
     * IPv4 address that the watch can reach.
     */
    private fun hotspotAddress(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && !BAD_INTERFACE.containsMatchIn(it.name) }
            .flatMap { nif -> nif.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { nif.name to it } }
            .sortedBy { (name, _) -> if (Regex("""^(ap|swlan|softap|wlan)""").containsMatchIn(name)) 0 else 1 }
            .firstOrNull()
            ?.also { (name, addr) -> timber.log.Timber.i("LanAddress: hotspot interface %s %s", name, addr.hostAddress) }
            ?.second?.hostAddress
    } catch (e: Exception) {
        null
    }

    /** Mobile data, VPNs and the like: never the way to the watch. */
    private val BAD_INTERFACE = Regex("""^(rmnet|ccmni|v4-|clat|tun|tap|ppp|dummy|ifb|ip6|sit|lo)""")

    /** A random token for the one-time URL. */
    fun token(): String =
        (1..16).map { "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
}
