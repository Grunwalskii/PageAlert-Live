package com.clinscout.pagealertlive

import org.junit.Assert.*
import org.junit.Test

class AlarmWebpagesTest {
    private val first = Monitor(id = "first", name = "First", url = "https://example.com/first", keyword = "stock")
    private val second = first.copy(id = "second", name = "Second", url = "https://example.com/second")
    private fun event(id: String, time: Long) = AlertEvent(time, id, id, "Matched")

    @Test fun `alarm pages keep watchdog order and ignore test alarms and removed monitors`() {
        val state = AppState(monitors = listOf(first, second), alarms = mapOf("second" to event("second", 20),
            "__test__" to event("__test__", 30), "first" to event("first", 10), "deleted" to event("deleted", 40)))
        assertEquals(listOf(first, second), AlarmWebpages.targets(state, listOf("second", "first", "__test__", "deleted"), listOf(20, 10, 30, 40)))
    }

    @Test fun `stale dismissed rearmed or malformed actions cannot open a page`() {
        val state = AppState(monitors = listOf(first), alarms = mapOf("first" to event("first", 11)))
        assertTrue(AlarmWebpages.targets(state, listOf("first"), listOf(10)).isEmpty())
        assertTrue(AlarmWebpages.targets(state.copy(alarms = emptyMap()), listOf("first"), listOf(11)).isEmpty())
        assertTrue(AlarmWebpages.targets(state, listOf("first"), emptyList()).isEmpty())
        assertEquals(listOf(first), AlarmWebpages.targets(state, listOf("first"), listOf(11)))
    }
}
