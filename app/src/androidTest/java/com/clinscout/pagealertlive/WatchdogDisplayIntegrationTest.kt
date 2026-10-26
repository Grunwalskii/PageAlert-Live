package com.clinscout.pagealertlive

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchdogDisplayIntegrationTest {
    @Test fun keywordResultsUseOneRuleLineAndIntervalSharesConnectionRow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        val present = Monitor(id = "present", name = "Found", url = "https://example.com", keyword = "stock", minSeconds = 60, maxSeconds = 120)
        val missing = present.copy(id = "missing", name = "Missing", keyword = "sold out", trigger = Trigger.KEYWORD_MISSING)
        val unmatched = present.copy(id = "unmatched", name = "Not matched", keyword = "available")
        val price = present.copy(id = "price", name = "Price", trigger = Trigger.PRICE, selector = ".price", threshold = "50")
        val expensive = price.copy(id = "expensive", name = "Above threshold", selector = ".expensive", threshold = "900")
        val monitors = listOf(present, missing, unmatched, price, expensive)
        val statuses = monitors.associate { monitor ->
            val result = TriggerEvaluator.evaluate(monitor, "<p>In stock</p><p class='price'>$30.00</p><p class='expensive'>€1740.55</p>")
            monitor.id to MonitorStatus(checkedAt = System.currentTimeMillis(), detail = result.detail,
                matched = result.matched, checks = 1, connection = "Wi-Fi • IPv4")
        }
        context.repository.update(true) { AppState(monitors = monitors, statuses = statuses) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val lines = textLines(activity.findViewById(android.R.id.content))
                assertTrue(lines.contains("5 enabled · 5 checks"))
                assertTrue(lines.contains("Events 0"))
                assertFalse(lines.any { it.contains("\nENABLED") || it.contains("\nCHECKS") || it.contains("\nEVENTS") })
                assertTrue(lines.contains("Keyword present • “stock” ✓"))
                assertTrue(lines.contains("Keyword missing • “sold out” ✓"))
                assertTrue(lines.contains("Keyword present • “available” ✗"))
                fun keywordViews(view: View): List<TextView> = buildList {
                    if (view is TextView && view.text.startsWith("Keyword ")) add(view)
                    if (view is ViewGroup) for (index in 0 until view.childCount) addAll(keywordViews(view.getChildAt(index)))
                }
                val rules = keywordViews(activity.findViewById(android.R.id.content))
                assertEquals(3, rules.size)
                assertTrue(rules.all { it.currentTextColor == android.graphics.Color.rgb(113, 226, 188) })
                assertFalse(lines.any { it.startsWith("Keyword “") })
                assertEquals(5, lines.count { it == "Interval  60–120 s • Wi-Fi • IPv4" })
                assertFalse(lines.any { it.startsWith("Random interval") || it.startsWith("Connection:") })
                assertTrue(lines.contains("Price at or below 50 - 30 ≤ 50"))
                assertFalse(lines.contains("Price 30 ≤ 50"))
                assertTrue(lines.contains("Price at or below 900 - 1740.55 > 900"))
                assertFalse(lines.contains("Price 1740.55 > 900"))
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/pagealert-overview-110.png").use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
            }
            scenario.onActivity {
                context.repository.update(true) { state -> state.copy(statuses = state.statuses +
                    (present.id to statuses.getValue(present.id).copy(detail = "HTTP 429: Too many requests (increase check interval).", failures = 1, httpStatus = 429)) +
                    (expensive.id to statuses.getValue(expensive.id).copy(detail = "HTTP 503: Website unavailable (will retry).", failures = 1, httpStatus = 503))) }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val lines = textLines(activity.findViewById(android.R.id.content))
                assertTrue(lines.contains("HTTP 429: Too many requests (increase check interval)."))
                assertTrue(lines.contains("Keyword present • “stock”"))
                assertFalse("A failed check must not show a stale success mark", lines.contains("Keyword present • “stock” ✓"))
                assertTrue(lines.contains("HTTP 503: Website unavailable (will retry)."))
                assertTrue(lines.contains("Price at or below 900"))
                assertFalse(lines.contains("Price at or below 900 - 1740.55 > 900"))
            }
        } finally { scenario.close(); context.repository.update(true) { AppState() } }
    }

    private fun textLines(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(textLines(view.getChildAt(index)))
    }
}
