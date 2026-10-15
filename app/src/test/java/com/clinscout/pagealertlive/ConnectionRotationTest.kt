package com.clinscout.pagealertlive

import org.junit.Assert.*
import org.junit.Test

class ConnectionRotationTest {
    private val both = DeviceConnections(true, true)
    private val dual = WifiIpSupport(true, true)

    @Test fun `three paths each receive exactly one turn per cycle`() {
        val selected = (0L..8L).map { ConnectionRotation.select(both, dual, true, it)!!.label }
        assertEquals(List(3) { listOf("Wi-Fi • IPv4", "Wi-Fi • IPv6", "Mobile data") }.flatten(), selected)
        assertEquals(3, selected.count { it == "Mobile data" })
    }

    @Test fun `single stack sites do not create duplicate WiFi slots`() {
        assertEquals(listOf("Wi-Fi • IPv4", "Mobile data"),
            ConnectionRotation.slots(both, WifiIpSupport(true, false), true).map { it.label })
        assertEquals(listOf("Wi-Fi • IPv6", "Mobile data"),
            ConnectionRotation.slots(both, WifiIpSupport(false, true), true).map { it.label })
        assertEquals(listOf("Wi-Fi", "Mobile data"), ConnectionRotation.slots(both, dual, false).map { it.label })
    }

    @Test fun `disabled transports are removed without weighting remaining paths`() {
        assertEquals(listOf("Wi-Fi • IPv4", "Wi-Fi • IPv6"),
            ConnectionRotation.slots(DeviceConnections(true, false), dual, true).map { it.label })
        assertEquals(listOf("Mobile data"), ConnectionRotation.slots(DeviceConnections(false, true), dual, true).map { it.label })
        assertNull(ConnectionRotation.select(DeviceConnections(false, false), dual, true, 0))
    }

    @Test fun `saved turns resume the same path sequence for each watchdog`() {
        assertEquals("Mobile data", ConnectionRotation.select(both, dual, true, 5)!!.label)
        assertEquals("Wi-Fi • IPv4", ConnectionRotation.select(both, dual, true, 6)!!.label)
        assertEquals("Wi-Fi • IPv6", ConnectionRotation.select(both, dual, true, 7)!!.label)
    }
}
