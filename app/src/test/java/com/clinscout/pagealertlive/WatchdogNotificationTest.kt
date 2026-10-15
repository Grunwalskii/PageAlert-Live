package com.clinscout.pagealertlive

import org.junit.Assert.*
import org.junit.Test

class WatchdogNotificationTest {
    private val first = Monitor(id = "first", name = "Stock", url = "https://example.com/stock", keyword = "ready")
    private val second = first.copy(id = "second", name = "Price")
    private val disabled = first.copy(id = "disabled", name = "Disabled site", enabled = false)

    @Test fun `each watchdog has one row with its independent countdown`() {
        val state = AppState(monitors = listOf(first, second, disabled), running = true,
            statuses = mapOf(first.id to MonitorStatus(nextAt = 65000), second.id to MonitorStatus(nextAt = 3665000)))
        assertEquals(listOf("Stock • 01:00", "Price • 01:01:00", "Disabled site • Disabled"), WatchdogNotification.lines(state, 5000))
    }

    @Test fun `elapsed time updates the remaining countdown`() {
        val state = AppState(monitors = listOf(first), running = true, statuses = mapOf(first.id to MonitorStatus(nextAt = 10000)))
        assertEquals(listOf("Stock • 00:05"), WatchdogNotification.lines(state, 5000))
        assertEquals(listOf("Stock • 00:04"), WatchdogNotification.lines(state, 6000))
        assertEquals(listOf("Stock • Checking…"), WatchdogNotification.lines(state, 10000))
    }

    @Test fun `first checks paused and disabled states are explicit`() {
        val initial = AppState(monitors = listOf(first, disabled), running = true)
        assertEquals(listOf("Stock • Checking…", "Disabled site • Disabled"), WatchdogNotification.lines(initial, 1000))
        assertEquals(listOf("Stock • Paused", "Disabled site • Disabled"), WatchdogNotification.lines(initial.copy(running = false), 1000))
    }

    @Test fun `active alarms retain their countdown and are marked`() {
        val state = AppState(monitors = listOf(first), running = true,
            statuses = mapOf(first.id to MonitorStatus(nextAt = 20000)),
            alarms = mapOf(first.id to AlertEvent(1000, first.id, first.name, "Matched")))
        assertEquals(listOf("Stock • 00:15 • ALARM"), WatchdogNotification.lines(state, 5000))
    }

    @Test fun `names cannot introduce additional notification rows`() {
        val state = AppState(monitors = listOf(first.copy(name = "Stock\nwatch\r\nlist\tA")), running = false)
        assertEquals(listOf("Stock watch list A • Paused"), WatchdogNotification.lines(state, 0))
    }

    @Test fun `countdowns round up and support long intervals`() {
        assertEquals("00:01", WatchdogNotification.countdown(1))
        assertEquals("01:00", WatchdogNotification.countdown(60000))
        assertEquals("24:00:00", WatchdogNotification.countdown(86400000))
        assertEquals("00:00", WatchdogNotification.countdown(-10))
    }
}
