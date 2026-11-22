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
        val candidates = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it.hostAddress.orEmpty() } }
        pickLanAddress(candidates)?.also { timber.log.Timber.i("LanAddress: hotspot address %s (of %s)", it, candidates) }
    } catch (e: Exception) {
        null
    }

    /** Mobile data, VPNs and the like: never the way to the watch. */
    private val BAD_INTERFACE = Regex("""^(rmnet|ccmni|v4-|clat|tun|tap|ppp|dummy|ifb|ip6|sit|lo)""")
    private val HOTSPOT_INTERFACE = Regex("""^(ap|swlan|softap|wlan)""")

    /** From (interface name, IPv4) pairs: a private address on a Wi-Fi-like interface first, any other non-mobile private one next. */
    internal fun pickLanAddress(candidates: List<Pair<String, String>>): String? =
        candidates
            .filter { (name, ip) -> !BAD_INTERFACE.containsMatchIn(name) && isPrivate(ip) }
            .sortedBy { (name, _) -> if (HOTSPOT_INTERFACE.containsMatchIn(name)) 0 else 1 }
            .firstOrNull()?.second

    private fun isPrivate(ip: String): Boolean {
        val o = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }

    /** A random token for the one-time URL. */
    fun token(): String =
        (1..16).map { "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
}
