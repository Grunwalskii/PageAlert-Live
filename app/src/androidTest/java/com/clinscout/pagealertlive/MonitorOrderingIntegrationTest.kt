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
class MonitorOrderingIntegrationTest {
    @Test fun creationOrderSurvivesEditingReloadAndDeletionWithNewEntriesAppended() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        val repository = context.repository
        val oldest = Monitor(id = "oldest", name = "Zulu first", url = "https://example.com", keyword = "stock", alternateNetworks = false, alternateIpFamilies = false)
        val middle = oldest.copy(id = "middle", name = "Alpha second")
        val newest = oldest.copy(id = "newest", name = "Bravo third")
        val added = oldest.copy(id = "added", name = "New fourth")
        val edited = oldest.copy(name = "Edited first", keyword = "available")
        try {
            repository.update(true) { AppState() }
            listOf(oldest, middle, newest).forEach(repository::save)
            repository.save(edited)
            repository.save(middle.copy(enabled = false))
            repository.save(added)
            val ids = listOf(oldest.id, middle.id, newest.id, added.id)
            assertEquals(ids, repository.state.value.monitors.map { it.id })
            assertEquals(ids, MonitorRepository(context).state.value.monitors.map { it.id })
            assertEquals(edited, MonitorRepository(context).state.value.monitors.first())

            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                scenario.onActivity { activity ->
                    val names = mutableListOf<String>()
                    collectNames(activity.findViewById(android.R.id.content), setOf(edited.name, middle.name, newest.name, added.name), names)
                    assertEquals(listOf(edited.name, middle.name, newest.name, added.name), names)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val names = mutableListOf<String>()
                    collectNames(activity.findViewById(android.R.id.content), setOf(edited.name, middle.name, newest.name, added.name), names)
                    assertEquals(listOf(edited.name, middle.name, newest.name, added.name), names)
                }
            } finally { scenario.close() }

            repository.delete(middle.id)
            repository.save(middle)
            assertEquals(listOf(oldest.id, newest.id, added.id, middle.id), MonitorRepository(context).state.value.monitors.map { it.id })
        } finally { repository.update(true) { AppState() } }
    }

    private fun collectNames(view: View, expected: Set<String>, names: MutableList<String>) {
        if (view is TextView && view.text.toString() in expected) names += view.text.toString()
        if (view is ViewGroup) for (index in 0 until view.childCount) collectNames(view.getChildAt(index), expected, names)
    }
}
