package com.clinscout.pagealertlive

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BrowserActionsIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun browserLinksUseBrowsableDefaultBrowserTaskAndRejectOtherSchemes() {
        val launched = mutableListOf<Intent>()
        val capture = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launched += Intent(intent) }
        }
        assertTrue(BrowserLinks.open(capture, "https://example.invalid/product"))
        assertTrue(BrowserLinks.open(capture, "http://example.invalid/other"))
        assertFalse(BrowserLinks.open(capture, "javascript:alert(1)"))
        assertFalse(BrowserLinks.open(capture, "file:///data/local/tmp/page.html"))
        assertEquals(2, launched.size)
        launched.forEach {
            assertEquals(Intent.ACTION_VIEW, it.action)
            assertTrue(it.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertTrue(it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertEquals(0, it.flags and (Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            assertNull("Do not hard-code a browser activity", it.component)
        }
    }

    @Test fun disarmOnlyAndBothOpenControlsPreservePollingAndStaleOpenDoesNotRestart() {
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("<p>Sold out</p>")); start() }
        val pageUrl = server.url("/alarm-page").toString()
        val watch = Monitor(name = "Browser alarm", url = pageUrl, keyword = "in stock", minSeconds = 3600, maxSeconds = 3600,
            alternateNetworks = false, alternateIpFamilies = false)
        val opened = CopyOnWriteArrayList<Intent>()
        val interceptor = interceptBrowser(opened)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val baseTime = System.currentTimeMillis()
        fun notification() = context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == 41 }?.notification
        fun openAction() = notification()?.actions?.firstOrNull { it.title.toString() == "Disarm & open webpage" }
        try {
            scenario.onActivity { activity ->
                context.repository.update(true) { AppState(monitors = listOf(watch), running = true,
                    alarms = mapOf(watch.id to AlertEvent(baseTime, watch.id, watch.name, "Matched"))) }
                MonitorService.send(activity, MonitorService.ACTION_START)
            }
            await { openAction() != null && (context.repository.state.value.statuses[watch.id]?.checks ?: 0) > 0 }
            scenario.onActivity { activity -> button(activity, "Disarm alarm").performClick() }
            await { context.repository.state.value.alarms.isEmpty() && openAction() == null }
            assertTrue(opened.isEmpty())
            assertTrue(context.repository.state.value.running && context.repository.state.value.serviceAlive)
            for (turn in 1..2) {
                context.repository.update(true) { it.copy(alarms = mapOf(watch.id to AlertEvent(baseTime + turn, watch.id, watch.name, "Matched"))) }
                await { openAction() != null }
                if (turn == 1) scenario.onActivity { activity -> button(activity, "Disarm & open webpage").performClick() }
                else openAction()!!.actionIntent.send()
                await { opened.size == turn && context.repository.state.value.alarms.isEmpty() && openAction() == null }
                assertEquals(pageUrl, opened.last().dataString)
                assertTrue(opened.last().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                assertTrue(context.repository.state.value.running && context.repository.state.value.serviceAlive)
            }
            context.repository.update(true) { it.copy(alarms = mapOf(watch.id to AlertEvent(baseTime + 3, watch.id, watch.name, "Matched"))) }
            await { openAction() != null }
            val stale = openAction()!!.actionIntent
            instrumentation.runOnMainSync { MonitorService.send(context, MonitorService.ACTION_STOP) }
            await { !context.repository.state.value.serviceAlive }
            stale.send()
            instrumentation.waitForIdleSync()
            assertFalse(context.repository.state.value.running || context.repository.state.value.serviceAlive)
            assertEquals(2, opened.size)
            assertTrue(MonitorRepository(context).state.value.alarms.isEmpty())
        } finally {
            instrumentation.runOnMainSync { MonitorService.send(context, MonitorService.ACTION_STOP) }
            scenario.close(); instrumentation.removeMonitor(interceptor)
            context.repository.update(true) { AppState() }; server.shutdown()
        }
    }

    @Test fun multipleAlarmsOfferCorrectPagesAndRecreationDoesNotDisarmNewAlarms() {
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        val first = Monitor(id = "first", name = "First page", url = "https://example.invalid/first", keyword = "stock")
        val second = first.copy(id = "second", name = "Second page", url = "https://example.invalid/second")
        val state = AppState(monitors = listOf(first, second), alarms = listOf(first, second).associate {
            it.id to AlertEvent(100, it.id, it.name, "Matched")
        })
        context.repository.update(true) { state }
        val opened = CopyOnWriteArrayList<Intent>()
        val interceptor = interceptBrowser(opened)
        val scenario = ActivityScenario.launch<AlarmWebpageActivity>(AlarmWebpages.intent(context, state))
        try {
            assertTrue(context.repository.state.value.alarms.isEmpty())
            assertTrue(opened.isEmpty())
            context.repository.update(true) { it.copy(alarms = mapOf(first.id to AlertEvent(200, first.id, first.name, "New match"))) }
            scenario.recreate()
            assertEquals(200L, context.repository.state.value.alarms.getValue(first.id).time)
            scenario.onActivity {
                val list = WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<ListView>().last()
                assertEquals(listOf("First page", "Second page"), (0 until list.adapter.count).map { list.adapter.getItem(it).toString() })
                list.performItemClick(list.getChildAt(1), 1, list.adapter.getItemId(1))
            }
            await { opened.size == 1 }
            assertEquals(second.url, opened.single().dataString)
            assertEquals(200L, context.repository.state.value.alarms.getValue(first.id).time)
        } finally { scenario.close(); instrumentation.removeMonitor(interceptor); context.repository.update(true) { AppState() } }
    }

    private fun interceptBrowser(opened: MutableList<Intent>): Instrumentation.ActivityMonitor =
        object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                opened += Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }.also { instrumentation.addMonitor(it) }

    private fun button(activity: Activity, label: String) = descendants(activity.findViewById(android.R.id.content))
        .filterIsInstance<TextView>().first { it.text == label }
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue("Browser/alarm action did not complete", predicate())
    }
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
    }
}
