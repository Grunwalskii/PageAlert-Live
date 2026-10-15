package com.clinscout.pagealertlive

import org.junit.Assert.*
import org.junit.Test

class AlertReducerTest {
    private val monitor = Monitor(name = "Stock", url = "https://example.com", keyword = "available")
    private val initial = AppState(monitors = listOf(monitor), running = true)
    private fun check(state: AppState, matched: Boolean) = AlertReducer.record(state, monitor, CheckResult(matched, "Result"), null, 1000, 2000)

    @Test fun `alarm stays latched after condition disappears`() {
        val fired = check(initial, true)
        assertEquals(1, fired.alarms.size)
        val clear = check(fired, false)
        assertEquals(fired.alarms, clear.alarms)
        assertFalse(clear.statuses.getValue(monitor.id).matched)
    }

    @Test fun `acknowledgement rearms the next match without condition clearing`() {
        val acknowledged = check(initial, true).copy(alarms = emptyMap())
        val stillMatched = check(acknowledged, true)
        assertEquals(1, stillMatched.alarms.size)
        assertEquals(2, stillMatched.history.size)
        val third = check(stillMatched.copy(alarms = emptyMap()), true)
        assertEquals(1, third.alarms.size)
        assertEquals(3, third.history.size)
    }

    @Test fun `network errors preserve match state without triggering`() {
        val fired = check(initial, true).copy(alarms = emptyMap())
        val error = AlertReducer.record(fired, monitor, null, "HTTP 403", 2000, 9000)
        assertTrue(error.alarms.isEmpty())
        assertTrue(error.statuses.getValue(monitor.id).matched)
        assertEquals(1, error.statuses.getValue(monitor.id).failures)
        assertEquals(1, check(error, true).alarms.size)
        assertEquals(2, check(error, true).history.size)
    }

    @Test fun `active alarm suppresses duplicate events even across condition changes`() {
        val fired = check(initial, true)
        val stillActive = check(check(check(fired, true), false), true)
        assertEquals(fired.alarms, stillActive.alarms)
        assertEquals(1, stillActive.history.size)
    }

    @Test fun `disarming all monitors allows each unchanged matching rule to alert again`() {
        val other = monitor.copy(id = "other", name = "Other")
        val first = check(initial.copy(monitors = listOf(monitor, other)), true)
        val both = AlertReducer.record(first, other, CheckResult(true, "Matched"), null, 1000, 2000)
        val acknowledged = both.copy(alarms = emptyMap())
        val rearmed = AlertReducer.record(check(acknowledged, true), other, CheckResult(true, "Matched"), null, 3000, 4000)
        assertEquals(2, rearmed.alarms.size)
        assertEquals(4, rearmed.history.size)
    }

    @Test fun `disarm followed by nonmatching or failed check does not ring`() {
        val acknowledged = check(initial, true).copy(alarms = emptyMap())
        assertTrue(check(acknowledged, false).alarms.isEmpty())
        assertTrue(AlertReducer.record(acknowledged, monitor, null, "Timeout", 3000, 4000).alarms.isEmpty())
    }

    @Test fun `stale requests after stop delete disable or edit cannot alarm`() {
        listOf(initial.copy(running = false), initial.copy(monitors = emptyList()),
            initial.copy(monitors = listOf(monitor.copy(enabled = false))),
            initial.copy(monitors = listOf(monitor.copy(keyword = "different")))).forEach {
            assertEquals(it, check(it, true))
        }
    }

    @Test fun `independent monitors keep all active alarms`() {
        val other = monitor.copy(id = "other", name = "Price")
        val state = check(initial.copy(monitors = listOf(monitor, other)), true)
        val both = AlertReducer.record(state, other, CheckResult(true, "Price met"), null, 1500, 2500)
        assertEquals(2, both.alarms.size)
        assertEquals(2, both.history.size)
    }

    @Test fun `history remains bounded`() {
        var state = initial
        repeat(110) { state = check(state.copy(alarms = emptyMap()), true) }
        assertEquals(100, state.history.size)
    }

    private fun http(state: AppState, code: Int, target: Monitor = monitor) =
        AlertReducer.record(state, target, null, HttpErrors.describe(code), 3000, 9000, code)

    @Test fun `HTTP errors enter activity without starting or clearing alarms`() {
        val failed = http(initial, 429)
        assertTrue(failed.alarms.isEmpty())
        assertEquals(EventKind.HTTP_ERROR, failed.history.single().kind)
        assertEquals(429, failed.statuses.getValue(monitor.id).httpStatus)
        val active = check(initial, true)
        assertEquals(active.alarms, http(active, 503).alarms)
    }

    @Test fun `identical consecutive HTTP failures are deduplicated per monitor`() {
        val failed = http(http(initial, 429), 429)
        assertEquals(1, failed.history.size)
        assertEquals(2, failed.statuses.getValue(monitor.id).failures)
        val other = monitor.copy(id = "other", name = "Other")
        assertEquals(2, http(failed.copy(monitors = listOf(monitor, other)), 429, other).history.size)
    }

    @Test fun `changed or recurring HTTP errors create fresh events after recovery`() {
        val first = http(initial, 429)
        val changed = http(first, 503)
        assertEquals(2, changed.history.size)
        val recovered = check(changed, false)
        assertNull(recovered.statuses.getValue(monitor.id).httpStatus)
        assertEquals(3, http(recovered, 503).history.size)
    }

    @Test fun `HTTP activity retains the failed route after rotation and recovery`() {
        val wifi = AlertReducer.record(initial, monitor, null, HttpErrors.describe(429), 1000, 2000, 429, "Wi-Fi • IPv6")
        val mobile = AlertReducer.record(wifi, monitor, null, HttpErrors.describe(429), 2000, 3000, 429, "Mobile data")
        assertEquals("Mobile data", mobile.statuses.getValue(monitor.id).connection)
        assertEquals("Wi-Fi • IPv6", mobile.history.single().connection)
        val recovered = check(mobile, false)
        val failedAgain = AlertReducer.record(recovered, monitor, null, HttpErrors.describe(429), 4000, 5000, 429, "Mobile data")
        assertEquals(listOf("Mobile data", "Wi-Fi • IPv6"), failedAgain.history.map { it.connection })
        assertEquals(listOf("Mobile data", "Wi-Fi • IPv6"), check(failedAgain, false).history.map { it.connection })
    }

    @Test fun `late HTTP errors cannot enter activity after monitoring stops or changes`() {
        listOf(initial.copy(running = false), initial.copy(monitors = emptyList()),
            initial.copy(monitors = listOf(monitor.copy(enabled = false))),
            initial.copy(monitors = listOf(monitor.copy(url = "https://other.example")))).forEach {
            assertSame(it, http(it, 429))
        }
    }
}
