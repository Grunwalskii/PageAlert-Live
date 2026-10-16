package com.clinscout.pagealertlive

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException

class ConnectionDebugTest {
    @Test fun `failure details retain exception type root cause and bounded messages`() {
        val error = ConnectException("Could not connect\nto IPv6").apply {
            initCause(IllegalStateException("ENETUNREACH: Network is unreachable"))
        }
        val details = ConnectionDebug.failure(error)
        assertTrue(details.contains("ConnectException: Could not connect to IPv6"))
        assertTrue(details.contains("ENETUNREACH: Network is unreachable"))
        assertFalse(details.contains('\n'))
        assertTrue(ConnectionDebug.failure(UnknownHostException("x".repeat(10000))).length <= 2048)
    }

    @Test fun `last fallback survives normal and mobile checks without retaining the red mark`() {
        val monitor = Monitor(name = "Amazon", url = "https://example.com/", keyword = "missing")
        val initial = AppState(monitors = listOf(monitor), running = true)
        val failed = ConnectionDebug(1000, "Wi-Fi • IPv6", "Wi-Fi • IPv4", "Success",
            "UnknownHostException: No IPv6 DNS address. DNS returned IPv4=3, IPv6=0.", "IPv6 default route: missing")
        val fallback = AlertReducer.record(initial, monitor, CheckResult(false, "Missing"), null, 1000, 2000,
            connection = failed.used, ipFallback = true, connectionDebug = failed)
        val mobile = ConnectionDebug(2000, "Mobile data", "Mobile data", "Success")
        val normal = AlertReducer.record(fallback, monitor, CheckResult(false, "Missing"), null, 2000, 3000,
            connection = mobile.used, connectionDebug = mobile)
        val status = normal.statuses.getValue(monitor.id)
        assertFalse(status.ipFallback)
        assertEquals(mobile, status.connectionDebug)
        assertEquals(failed, status.lastIpFallback)
        assertTrue(status.lastIpFallback!!.report().contains("Requested: Wi-Fi • IPv6"))
        assertTrue(status.lastIpFallback.report().contains("Used: Wi-Fi • IPv4"))
        assertTrue(status.lastIpFallback.report().contains("IPv4=3, IPv6=0"))
        assertTrue(normal.history.isEmpty())
        assertTrue(normal.alarms.isEmpty())
    }
}
