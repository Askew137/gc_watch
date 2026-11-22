package com.askew137.gcwatch.mobile.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanAddressTest {
    @Test fun hotspotInterfaceIsChosenOverMobileData() {
        val picked = LanAddress.pickLanAddress(listOf("rmnet_data1" to "10.20.30.40", "wlan1" to "192.168.43.1"))
        assertEquals("192.168.43.1", picked)
    }

    @Test fun mobileDataAloneIsNotUsed() {
        assertNull(LanAddress.pickLanAddress(listOf("rmnet0" to "10.0.0.5", "v4-rmnet0" to "192.0.0.4")))
    }

    @Test fun accessPointBeatsBluetoothTether() {
        assertEquals("172.20.10.1", LanAddress.pickLanAddress(listOf("bt-pan" to "192.168.44.1", "ap0" to "172.20.10.1")))
    }

    @Test fun publicAddressesAreIgnored() {
        assertNull(LanAddress.pickLanAddress(listOf("wlan0" to "8.8.8.8", "wlan1" to "172.32.0.1")))
    }
}
