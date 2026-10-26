package com.clinscout.pagealertlive

import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class NetworkDiagnosticsIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun settingsShowAndCopyTheOriginalFailureAfterMobileChecksAndReload() {
        val monitor = Monitor(name = "Amazon debug", url = "https://example.com/product", keyword = "missing")
        val fallback = ConnectionDebug(1000, "Wi-Fi • IPv6", "Wi-Fi • IPv4", "Success",
            "UnknownHostException: No IPv6 DNS address for example.com. DNS returned IPv4=3, IPv6=0.",
            "Network: 123\nIPv6 default route: missing\nDNS servers: 192.0.2.53")
        val mobile = ConnectionDebug(2000, "Mobile data", "Mobile data", "Success")
        context.repository.update(true) { AppState(monitors = listOf(monitor), running = true) }
        context.repository.record(monitor, CheckResult(false, "Missing"), null, 2000, connection = fallback.used,
            ipFallback = true, connectionDebug = fallback)
        context.repository.record(monitor, CheckResult(false, "Missing"), null, 3000, connection = mobile.used,
            connectionDebug = mobile)
        context.repository.update(true) { it.copy(running = false) }
        val saved = MonitorRepository(context).state.value
        assertEquals(listOf(monitor), saved.monitors)
        assertEquals(fallback, saved.statuses.getValue(monitor.id).lastIpFallback)
        assertEquals(mobile, saved.statuses.getValue(monitor.id).connectionDebug)
        context.repository.update(true) { saved }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity -> views(activity.findViewById(android.R.id.content)).first { it.text == "Settings" }.performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val textViews = views(activity.findViewById(android.R.id.content))
                assertTrue(textViews.indexOfFirst { it.text == "Alarm volume settings" } < textViews.indexOfFirst { it.text == "Network diagnostics" })
                assertFalse(textViews.any { it.text.contains("Current Wi-Fi status:") || it.text == "Refresh network info" })
                textViews.first { it.text == "Network diagnostics" }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val textViews = WindowInspector.getGlobalWindowViews().flatMap(::views)
                val report = textViews.first { it.text.contains("Current Wi-Fi status:") }.text.toString()
                assertTrue(report.contains("Watchdog: Amazon debug"))
                assertTrue(report.contains("Last IP fallback (retained after later checks):"))
                assertTrue(report.contains("Requested: Wi-Fi • IPv6"))
                assertTrue(report.contains("Used: Wi-Fi • IPv4"))
                assertTrue(report.contains("DNS returned IPv4=3, IPv6=0"))
                assertTrue(report.contains("IPv6 default route: missing"))
                assertTrue(report.contains("DNS servers: 192.0.2.53"))
                textViews.first { it.text == "Copy network debug info" }.performClick()
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                assertEquals(report, clipboard.primaryClip!!.getItemAt(0).text.toString())
                textViews.first { it.text == "Refresh network info" }.performClick()
            }
            val latest = mobile.copy(checkedAt = 4000, outcome = "Latest successful check")
            context.repository.update(true) { it.copy(statuses = it.statuses + (monitor.id to it.statuses.getValue(monitor.id).copy(connectionDebug = latest))) }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val textViews = WindowInspector.getGlobalWindowViews().flatMap(::views)
                textViews.first { it.text == "Refresh network info" }.performClick()
                val refreshed = textViews.first { it.text.contains("Current Wi-Fi status:") }.text.toString()
                assertTrue(refreshed.contains("Latest successful check"))
                assertTrue(refreshed.contains("DNS returned IPv4=3, IPv6=0"))
                textViews.first { it.text == "Copy network debug info" }.performClick()
                assertEquals(refreshed, context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
                textViews.first { it.text == "Close" }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertFalse(views(activity.findViewById(android.R.id.content)).any { it.text.contains("Current Wi-Fi status:") })
            }
            assertTrue(context.repository.state.value.history.isEmpty())
            assertTrue(context.repository.state.value.alarms.isEmpty())
        } finally { scenario.close(); context.repository.update(true) { AppState() } }
    }

    @Test fun olderSavedWatchdogsWithoutDiagnosticsRemainReadableAndUntouched() {
        val monitor = Monitor(name = "Existing watchdog", url = "https://example.com/", keyword = "stock")
        val status = MonitorStatus(checkedAt = 1000, connection = "Wi-Fi • IPv4", ipFallback = true)
        context.repository.update(true) { AppState(monitors = listOf(monitor), statuses = mapOf(monitor.id to status)) }
        val preferences = context.getSharedPreferences("pagealert_live", Context.MODE_PRIVATE)
        val json = JSONObject(preferences.getString("state", "{}")!!)
        json.getJSONArray("statuses").getJSONObject(0).apply {
            remove("connectionDebug")
            remove("lastIpFallback")
        }
        assertTrue(preferences.edit().putString("state", json.toString()).commit())
        try {
            val saved = MonitorRepository(context).state.value
            assertEquals(listOf(monitor), saved.monitors)
            assertEquals(status, saved.statuses.getValue(monitor.id))
            assertEquals(json.toString(), JSONObject(preferences.getString("state", "{}")!!).toString())
        } finally { context.repository.update(true) { AppState() } }
    }

    private fun views(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(views(view.getChildAt(index)))
    }
}
