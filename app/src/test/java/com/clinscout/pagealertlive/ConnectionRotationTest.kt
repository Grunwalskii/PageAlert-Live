package com.clinscout.pagealertlive

import org.junit.Assert.*
import org.junit.Test

class ConnectionRotationTest {
    private val both = DeviceConnections(true, true)

    @Test fun `three paths each receive exactly one turn per cycle`() {
        val selected = (0L..8L).map { ConnectionRotation.select(both, true, it)!!.label }
        assertEquals(List(3) { listOf("Wi-Fi • IPv4", "Wi-Fi • IPv6", "Mobile data") }.flatten(), selected)
        assertEquals(3, selected.count { it == "Mobile data" })
    }

    @Test fun `family rotation is controlled by the setting rather than detected availability`() {
        assertEquals(listOf("Wi-Fi • IPv4", "Wi-Fi • IPv6", "Mobile data"),
            ConnectionRotation.slots(both, true).map { it.label })
        assertEquals(listOf("Wi-Fi", "Mobile data"), ConnectionRotation.slots(both, false).map { it.label })
    }

    @Test fun `disabled transports are removed without weighting remaining paths`() {
        assertEquals(listOf("Wi-Fi • IPv4", "Wi-Fi • IPv6"),
            ConnectionRotation.slots(DeviceConnections(true, false), true).map { it.label })
        assertEquals(listOf("Mobile data"), ConnectionRotation.slots(DeviceConnections(false, true), true).map { it.label })
        assertNull(ConnectionRotation.select(DeviceConnections(false, false), true, 0))
    }

    @Test fun `saved turns resume the same path sequence for each watchdog`() {
        assertEquals("Mobile data", ConnectionRotation.select(both, true, 5)!!.label)
        assertEquals("Wi-Fi • IPv4", ConnectionRotation.select(both, true, 6)!!.label)
        assertEquals("Wi-Fi • IPv6", ConnectionRotation.select(both, true, 7)!!.label)
    }
}
