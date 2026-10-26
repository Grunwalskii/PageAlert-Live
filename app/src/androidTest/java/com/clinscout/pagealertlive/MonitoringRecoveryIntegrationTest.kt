package com.clinscout.pagealertlive

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MonitoringRecoveryIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val monitor = Monitor(id = "recovery", name = "Recovery watchdog", url = "https://example.com", keyword = "in stock", alternateNetworks = false, alternateIpFamilies = false)
    private val lastActive = 1792000000000L

    @Before fun prepare() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        instrumentation.runOnMainSync { MonitorService.send(context, MonitorService.ACTION_STOP) }
        await { !context.repository.state.value.serviceAlive }
        manager.cancelAll()
    }

    @After fun cleanUp() {
        instrumentation.runOnMainSync { MonitorService.send(context, MonitorService.ACTION_STOP) }
        await { !context.repository.state.value.serviceAlive }
        context.repository.update(true) { AppState() }
        manager.cancelAll()
    }

    @Test fun interruptedSessionRestoresLastHeartbeatButNormalStopsDoNotWarn() {
        context.repository.update(true) { AppState(monitors = listOf(monitor), running = true, serviceAlive = true) }
        context.repository.heartbeat(lastActive)
        val restarted = MonitorRepository(context)
        assertFalse(restarted.state.value.serviceAlive)
        restarted.onProcessStarted()
        assertEquals(lastActive, restarted.state.value.interruption!!.lastRunningAt)
        assertEquals(restarted.state.value.interruption, MonitorRepository(context).state.value.interruption)
        context.repository.update(true) { restarted.state.value }
        instrumentation.runOnMainSync { MonitorService.send(context, MonitorService.ACTION_STOP) }
        val stopped = MonitorRepository(context)
        stopped.onProcessStarted()
        assertNull(stopped.state.value.interruption)

        context.repository.update(true) { AppState(monitors = listOf(monitor.copy(enabled = false)), running = true) }
        val disabled = MonitorRepository(context)
        disabled.onProcessStarted()
        assertNull(disabled.state.value.interruption)

        // Upgrade from an older version without a heartbeat uses its last check.
        context.getSharedPreferences("pagealert_live", Context.MODE_PRIVATE).edit().remove("last_running_at").commit()
        context.repository.update(true) { AppState(monitors = listOf(monitor), running = true,
            statuses = mapOf(monitor.id to MonitorStatus(checkedAt = lastActive))) }
        val legacy = MonitorRepository(context)
        legacy.onProcessStarted()
        assertEquals(lastActive, legacy.state.value.interruption!!.lastRunningAt)
    }

    @Test fun interruptionNoticeShowsTimestampAndBatteryButtonOnlyWhenNeeded() {
        val wasExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        shell("dumpsys deviceidle whitelist -${context.packageName}")
        context.repository.update(true) { AppState(monitors = listOf(monitor), running = true, interruption = MonitoringInterruption(lastActive)) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                assertNotNull(findText(root, "Monitoring was interrupted"))
                assertNotNull(findText(root, "Last active: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(lastActive))}"))
                assertNotNull(findText(root, "Allow unrestricted battery use"))
            }
            shell("dumpsys deviceidle whitelist +${context.packageName}")
            scenario.recreate()
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                assertNotNull(findText(root, "Monitoring was interrupted"))
                assertNull(findText(root, "Allow unrestricted battery use"))
                assertTrue((findText(root, "Dismiss") as Button).performClick())
            }
            scenario.recreate()
            scenario.onActivity { assertNull(findText(it.findViewById(android.R.id.content), "Monitoring was interrupted")) }
            assertNull(MonitorRepository(context).state.value.interruption)
        } finally {
            scenario.close()
            shell("dumpsys deviceidle whitelist ${if (wasExempt) "+" else "-"}${context.packageName}")
        }
    }

    @Test fun httpErrorsNotifyAndPersistInActivityWithoutLoopingAlarmOrDuplicateSpam() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val responseCode = AtomicInteger(429)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(responseCode.get()).setBody("<p>Unavailable</p>")
        }
        server.start()
        val watched = monitor.copy(url = server.url("/").toString(), minSeconds = 1, maxSeconds = 1)
        try {
            scenario.onActivity {
                context.repository.update(true) { AppState(monitors = listOf(watched)) }
                MonitorService.send(it, MonitorService.ACTION_START)
            }
            await { manager.activeNotifications.any { it.id == MonitorService.HTTP_NOTIFICATION_ID } }
            val first = manager.activeNotifications.first { it.id == MonitorService.HTTP_NOTIFICATION_ID }.notification
            assertEquals(MonitorService.HTTP_CHANNEL, first.channelId)
            assertEquals("HTTP 429: Too many requests (increase check interval).", first.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertTrue(first.flags and Notification.FLAG_AUTO_CANCEL != 0)
            scenario.close()
            await { (context.repository.state.value.statuses[watched.id]?.checks ?: 0) >= 2 }
            assertEquals(1, context.repository.state.value.history.size)
            assertTrue(context.repository.state.value.alarms.isEmpty())
            assertNull("Closing the Activity must not report interrupted monitoring", context.repository.state.value.interruption)
            val saved = MonitorRepository(context).state.value
            assertEquals(EventKind.HTTP_ERROR, saved.history.single().kind)
            assertTrue(saved.history.single().connection.endsWith("System routing"))
            assertEquals(saved.statuses.getValue(watched.id).connection, saved.history.single().connection)
            assertEquals(429, saved.statuses.getValue(watched.id).httpStatus)

            responseCode.set(200)
            await { context.repository.state.value.statuses[watched.id]?.httpStatus == null }
            responseCode.set(429)
            await { context.repository.state.value.history.size == 2 }
            assertTrue(context.repository.state.value.alarms.isEmpty())
            assertEquals(1, manager.activeNotifications.count { it.id == MonitorService.HTTP_NOTIFICATION_ID })
            val launch = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                first.contentIntent.send()
                val activity = launch.waitForActivityWithTimeout(5000)
                assertNotNull("HTTP notification must open Activity", activity)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val root = activity.findViewById<View>(android.R.id.content)
                    assertTrue((findText(root, "Events ${context.repository.state.value.history.size}") as Button).isSelected)
                    assertNotNull(findText(root, "HTTP error • Recovery watchdog"))
                    assertNotNull(findText(root, "Connection: ${saved.history.single().connection}"))
                    activity.finish()
                }
            } finally { instrumentation.removeMonitor(launch) }
        } finally {
            instrumentation.runOnMainSync { MonitorService.send(context, MonitorService.ACTION_STOP) }
            scenario.close()
            server.shutdown()
        }
    }

    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findText(view.getChildAt(index), text)?.let { return it }
        return null
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue("Timed out: ${context.repository.state.value}", predicate())
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }
}
