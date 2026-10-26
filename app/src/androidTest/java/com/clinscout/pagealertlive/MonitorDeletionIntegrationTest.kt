package com.clinscout.pagealertlive

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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MonitorDeletionIntegrationTest {
    @Test fun cancelPreservesStateAndConfirmationDeletesOnlyTheSelectedWatchdogAndItsAlarm() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        val selected = Monitor(name = "Console restock", url = "https://example.com/console", keyword = "stock")
        val other = selected.copy(id = "other", name = "Other watchdog")
        val selectedEvent = AlertEvent(1000, selected.id, selected.name, "In stock")
        val otherEvent = selectedEvent.copy(monitorId = other.id, name = other.name)
        val initial = AppState(monitors = listOf(selected, other),
            statuses = mapOf(selected.id to MonitorStatus(checks = 2), other.id to MonitorStatus(checks = 3)),
            alarms = mapOf(selected.id to selectedEvent, other.id to otherEvent), history = listOf(selectedEvent, otherEvent))
        context.repository.update(true) { initial }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun ui(action: (MainActivity) -> Unit) { scenario.onActivity(action); instrumentation.waitForIdleSync() }
        fun dialogViews(): List<TextView> {
            val root = WindowInspector.getGlobalWindowViews().first { root -> views(root).any { it.text == "Delete watchdog?" } }
            return views(root)
        }
        try {
            ui { activity -> views(activity.findViewById(android.R.id.content)).first { it.text == "Delete" }.performClick() }
            ui {
                assertTrue(dialogViews().any { it.text == selected.name })
                assertTrue(dialogViews().any { it.text == selected.url })
            }
            instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/pagealert-delete-126.png").use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
            }
            ui { dialogViews().first { it.text == "Cancel" }.performClick() }
            assertEquals(initial, MonitorRepository(context).state.value)
            ui { activity -> views(activity.findViewById(android.R.id.content)).first { it.text == "Delete" }.performClick() }
            ui { dialogViews().first { it.text == "Delete" }.performClick() }
            val saved = MonitorRepository(context).state.value
            assertEquals(listOf(other), saved.monitors)
            assertEquals(initial.statuses - selected.id, saved.statuses)
            assertEquals(mapOf(other.id to otherEvent), saved.alarms)
            assertEquals(initial.history, saved.history)
        } finally { scenario.close(); context.repository.update(true) { AppState() } }
    }

    private fun views(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(views(view.getChildAt(index)))
    }
}
