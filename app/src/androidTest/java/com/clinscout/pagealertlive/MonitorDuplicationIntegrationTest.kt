package com.clinscout.pagealertlive

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MonitorDuplicationIntegrationTest {
    @Test fun duplicateOpensPrefilledEditorAndSavesAnIndependentWatchdogWithoutRuntimeState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        val original = Monitor(name = "Original", url = "https://example.com/product", trigger = Trigger.KEYWORD_MISSING,
            keyword = "sold out", caseSensitive = true, selector = ".availability", threshold = "12.34",
            numberFormat = NumberFormat.COMMA_DECIMAL, minSeconds = 33, maxSeconds = 47, enabled = false,
            alternateNetworks = true, alternateIpFamilies = false, checkPrice = true, priceSelector = ".price", requireAllConditions = true)
        val status = MonitorStatus(checkedAt = 1000, checks = 7, detail = "Original result")
        val event = AlertEvent(1000, original.id, original.name, "Original alert")
        val initial = AppState(monitors = listOf(original), statuses = mapOf(original.id to status),
            alarms = mapOf(original.id to event), history = listOf(event))
        context.repository.update(true) { initial }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun ui(action: () -> Unit) { scenario.onActivity { action() }; instrumentation.waitForIdleSync() }
        fun click(label: String) { texts().first { it.text.toString() == label }.performClick() }
        try {
            ui { click("Duplicate") }
            ui {
                assertTrue(texts().any { it.text == "New watchdog" })
                val fields = texts().filterIsInstance<EditText>()
                assertTrue(fields.any { it.text.toString() == "Original (copy)" })
                assertTrue(fields.any { it.text.toString() == original.url })
                assertTrue(fields.any { it.text.toString() == original.keyword })
                assertTrue(fields.any { it.text.toString() == original.priceSelector })
                assertEquals(initial, MonitorRepository(context).state.value)
                click("Cancel")
            }
            assertEquals(initial, MonitorRepository(context).state.value)
            ui { click("Duplicate") }
            ui {
                texts().filterIsInstance<EditText>().first { it.text.toString() == "Original (copy)" }.setText("Independent copy")
                click("Save")
            }
            ui { click("Confirm & save") }
            val saved = MonitorRepository(context).state.value
            assertEquals(2, saved.monitors.size)
            assertEquals(original, saved.monitors.first())
            val duplicate = saved.monitors.last()
            assertNotEquals(original.id, duplicate.id)
            assertEquals(original.copy(id = duplicate.id, name = "Independent copy"), duplicate)
            assertEquals(initial.statuses, saved.statuses)
            assertEquals(initial.alarms, saved.alarms)
            assertEquals(initial.history, saved.history)
            context.repository.save(duplicate.copy(keyword = "different"))
            assertEquals(original, MonitorRepository(context).state.value.monitors.first())
            assertEquals("different", MonitorRepository(context).state.value.monitors.last().keyword)
        } finally { scenario.close(); context.repository.update(true) { AppState() } }
    }

    private fun texts(): List<TextView> = WindowInspector.getGlobalWindowViews().flatMap(::views)
    private fun views(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(views(view.getChildAt(index)))
    }
}
