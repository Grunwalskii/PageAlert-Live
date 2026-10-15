package com.clinscout.pagealertlive

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class MonitoringIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun prepareUnlockedTestDevice() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        instrumentation.waitForIdleSync()
    }

    @Test fun expandedNotificationShowsEachWatchdogAndUpdatesCountdownWhileScreenOff() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("input keyevent 224")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<p>Not available</p>")
        }
        server.start()
        val first = Monitor(id = "countdown-first", name = "First watchdog", url = server.url("/first").toString(), keyword = "in stock", minSeconds = 9, maxSeconds = 9, alternateNetworks = false, alternateIpFamilies = false)
        val second = first.copy(id = "countdown-second", name = "Second watchdog", url = server.url("/second").toString(), minSeconds = 14, maxSeconds = 14)
        val disabled = first.copy(id = "countdown-disabled", name = "Disabled watchdog", enabled = false)
        try {
            instrumentation.runOnMainSync {
                context.repository.update(true) { AppState(monitors = listOf(first, second, disabled)) }
                MonitorService.send(context, MonitorService.ACTION_START)
            }
            await { context.repository.state.value.statuses.size == 2 }
            await {
                val text = managerNotification()?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
                Regex("First watchdog • \\d{2}:\\d{2}").containsMatchIn(text) && Regex("Second watchdog • \\d{2}:\\d{2}").containsMatchIn(text)
            }
            scenario.close()
            shell("input keyevent 223")
            val before = managerNotification()!!.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            assertEquals(3, before.lines().size)
            assertTrue(before.contains("Disabled watchdog • Disabled"))
            await { managerNotification()?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() != before }
            val notification = managerNotification()!!
            val after = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            assertEquals(3, after.lines().size)
            assertEquals(after, notification.publicVersion.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
            assertEquals("Countdown updates must not initiate extra requests", 2, server.requestCount)
            assertEquals(1L, context.repository.state.value.statuses.getValue(first.id).checks)
            assertEquals(1L, context.repository.state.value.statuses.getValue(second.id).checks)
        } finally {
            instrumentation.runOnMainSync { MonitorService.send(context, MonitorService.ACTION_STOP); context.repository.update(true) { AppState() } }
            scenario.close()
            server.shutdown()
            shell("input keyevent 224")
        }
    }

    @Test fun stopAllFromAppAndNotificationCanBeRepeatedWithoutStartingAnIdleService() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("input keyevent 224")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<p>In stock</p>")
        }
        server.start()
        val monitor = Monitor(name = "Stop regression", url = server.url("/").toString(), keyword = "in stock", minSeconds = 4, maxSeconds = 4, alternateNetworks = false, alternateIpFamilies = false)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            instrumentation.runOnMainSync { context.repository.update(true) { AppState(monitors = listOf(monitor)) } }
            repeat(4) { cycle ->
                scenario.onActivity { MonitorService.send(it, MonitorService.ACTION_START) }
                await { context.repository.state.value.alarms.isNotEmpty() }
                assertNull(context.repository.state.value.alarmError)
                if (cycle % 2 == 0) {
                    awaitButton(scenario, "Disarm alarm")
                    scenario.onActivity { activity ->
                        val disarm = findButton(activity.findViewById(android.R.id.content), "Disarm alarm")
                        assertNotNull(disarm)
                        assertTrue(disarm!!.performClick())
                    }
                    await { context.repository.state.value.alarms.isEmpty() }
                    assertTrue("Monitoring must remain active after the first alarm is disarmed", context.repository.state.value.serviceAlive)
                    assertTrue(context.repository.state.value.running)
                    awaitButton(scenario, "Stop monitoring & disarm")
                    scenario.onActivity { activity ->
                        val button = findButton(activity.findViewById(android.R.id.content), "Stop monitoring & disarm")
                        assertNotNull("In-app stop control must be available", button)
                        assertTrue(button!!.performClick())
                    }
                } else {
                    await { manager.activeNotifications.any { it.id == 41 } }
                    manager.activeNotifications.first { it.id == 41 }.notification.actions
                        .first { it.title.toString() == "Stop all" }.actionIntent.send()
                }
                await { !context.repository.state.value.serviceAlive }
                assertFalse(context.repository.state.value.running)
                assertTrue(context.repository.state.value.alarms.isEmpty())
                await { manager.activeNotifications.none { it.id == 41 } }
                scenario.onActivity { MonitorService.send(it, MonitorService.ACTION_STOP) }
                assertFalse("Stopping an idle app must not start a service", context.repository.state.value.serviceAlive)
            }
        } finally {
            instrumentation.runOnMainSync {
                context.repository.update(true) { AppState() }
                context.stopService(android.content.Intent(context, MonitorService::class.java))
            }
            scenario.close()
            server.shutdown()
        }
    }

    private fun findButton(view: android.view.View, label: String): android.widget.Button? {
        if (view is android.widget.Button && view.text.toString() == label) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) findButton(view.getChildAt(index), label)?.let { return it }
        }
        return null
    }

    @Test fun rapidDisarmThenAppStopDoesNotCrashOrRestartAfterward() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<p>In stock</p>")
        }
        server.start()
        val monitor = Monitor(name = "Rapid stop", url = server.url("/").toString(), keyword = "in stock", minSeconds = 60, maxSeconds = 60, alternateNetworks = false, alternateIpFamilies = false)
        try {
            repeat(6) { cycle ->
                scenario.onActivity {
                    context.repository.update(true) { AppState(monitors = listOf(monitor)) }
                    MonitorService.send(it, MonitorService.ACTION_START)
                }
                await { context.repository.state.value.alarms.isNotEmpty() }
                awaitButton(scenario, "Disarm alarm")
                await { managerNotification()?.actions?.any { it.title.toString() == "Disarm alarm" } == true }
                val staleNotification = managerNotification()!!
                scenario.onActivity { activity ->
                    if (cycle % 2 == 0) {
                        assertTrue(findButton(activity.findViewById(android.R.id.content), "Disarm alarm")!!.performClick())
                    } else {
                        staleNotification.actions.first { it.title.toString() == "Disarm alarm" }.actionIntent.send()
                    }
                    // Deliberately stop in the same main-loop turn, before a
                    // queued service/broadcast command has been delivered.
                    assertTrue(findButton(activity.findViewById(android.R.id.content), "Stop monitoring & disarm")!!.performClick())
                }
                await { !context.repository.state.value.serviceAlive }
                await { managerNotification() == null }
                assertFalse(context.repository.state.value.running)
                assertTrue(context.repository.state.value.alarms.isEmpty())
            }
            // Android can report foreground-service start violations later,
            // after all immediate UI/state assertions have already passed.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (System.nanoTime() < deadline) {
                assertFalse(context.repository.state.value.serviceAlive)
                assertNull(managerNotification())
                Thread.sleep(100)
            }
        } finally {
            instrumentation.runOnMainSync { MonitorService.send(context, MonitorService.ACTION_STOP) }
            scenario.close()
            server.shutdown()
        }
    }

    @Test fun disarmingTestAlarmThenUsingStaleControlsNeverRestartsService() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val observation = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val restarted = AtomicBoolean(false)
        try {
            scenario.onActivity {
                context.repository.update(true) { AppState() }
                MonitorService.send(it, MonitorService.ACTION_TEST)
            }
            await { context.repository.state.value.alarms.isNotEmpty() && managerNotification()?.actions?.size == 2 }
            val stale = managerNotification()!!
            awaitButton(scenario, "Disarm alarm")
            scenario.onActivity { activity ->
                assertTrue(findButton(activity.findViewById(android.R.id.content), "Disarm alarm")!!.performClick())
            }
            await { !context.repository.state.value.serviceAlive && managerNotification() == null }
            instrumentation.runOnMainSync {
                observation.launch { context.repository.state.collect { if (it.serviceAlive) restarted.set(true) } }
            }
            repeat(4) {
                scenario.onActivity {
                    MonitorService.send(it, MonitorService.ACTION_DISARM)
                    MonitorService.send(it, MonitorService.ACTION_STOP)
                }
                for (action in stale.actions) {
                    val delivered = CountDownLatch(1)
                    action.actionIntent.send(context, 0, null, { _, _, _, _, _ -> delivered.countDown() }, null)
                    assertTrue("Stale notification action must finish", delivered.await(5, TimeUnit.SECONDS))
                }
                instrumentation.waitForIdleSync()
            }
            assertFalse("Disarm and Stop controls must not recreate an idle service", restarted.get())
            assertFalse(context.repository.state.value.running)
            assertTrue(context.repository.state.value.alarms.isEmpty())
            assertNull(managerNotification())
        } finally {
            instrumentation.runOnMainSync { observation.cancel(); MonitorService.send(context, MonitorService.ACTION_STOP) }
            scenario.close()
        }
    }

    private fun awaitButton(scenario: ActivityScenario<MainActivity>, label: String) {
        await {
            var present = false
            scenario.onActivity { activity -> present = findButton(activity.findViewById(android.R.id.content), label) != null }
            present
        }
    }

    @Test fun legacyLowPriorityMonitoringChannelUpgradesForLockscreen() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(MonitorService.STATUS_CHANNEL)
        manager.createNotificationChannel(NotificationChannel("monitoring", "Monitoring status", NotificationManager.IMPORTANCE_LOW))
        MonitorService.channels(context)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(MonitorService.STATUS_CHANNEL).importance)
        assertNull("Monitoring must remain silent", manager.getNotificationChannel(MonitorService.STATUS_CHANNEL).sound)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(MonitorService.ALARM_CHANNEL).importance)
    }

    @Test fun unchangedKeywordAndPriceCanRingAfterRepeatedDisarmWithScreenOff() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("input keyevent 224")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html")
                .setBody("<p>In stock</p><p class='price'>$50.00</p>")
        }
        server.start()
        val keyword = Monitor(name = "Unchanged keyword", url = server.url("/").toString(), keyword = "in stock", minSeconds = 2, maxSeconds = 2, alternateNetworks = false, alternateIpFamilies = false)
        val price = keyword.copy(id = "price", name = "Unchanged price", trigger = Trigger.PRICE, selector = ".price", threshold = "50.00")
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            instrumentation.runOnMainSync {
                context.repository.update(true) { AppState(monitors = listOf(keyword, price)) }
                MonitorService.send(context, MonitorService.ACTION_START)
            }
            await { context.repository.state.value.alarms.size == 2 }
            await { context.repository.state.value.statuses.values.all { it.checks >= 2 } }
            assertEquals("An active alarm must not generate duplicate events", 2, context.repository.state.value.history.size)
            scenario.close()
            shell("input keyevent 223")
            repeat(3) { cycle ->
                await { manager.activeNotifications.any { posted -> posted.id == 41 && posted.notification.actions?.any { it.title.toString() == "Disarm alarm" } == true } }
                val notification = manager.activeNotifications.first { it.id == 41 }.notification
                assertEquals(MonitorService.ALARM_CHANNEL, notification.channelId)
                assertLockscreenControls(notification)
                if (cycle % 2 == 0) {
                    notification.actions.first { it.title.toString() == "Disarm alarm" }.actionIntent.send()
                } else {
                    // Same route as the in-app Disarm button.
                    instrumentation.runOnMainSync { MonitorService.send(context, MonitorService.ACTION_DISARM) }
                }
                await { context.repository.state.value.alarms.isEmpty() }
                val restored = MonitorRepository(context).state.value
                assertTrue(restored.alarms.isEmpty())
                assertTrue("Disarm must preserve the last observed match", restored.statuses.values.all { it.matched })
                await { context.repository.state.value.alarms.size == 2 && context.repository.state.value.history.size == (cycle + 2) * 2 }
                assertNull("A later alarm must restart sound playback", context.repository.state.value.alarmError)
                assertTrue(context.repository.state.value.serviceAlive)
            }
        } finally {
            instrumentation.runOnMainSync {
                context.repository.update(true) { AppState() }
                context.stopService(android.content.Intent(context, MonitorService::class.java))
            }
            scenario.close()
            server.shutdown()
            shell("input keyevent 224")
        }
    }

    private fun assertLockscreenControls(notification: Notification) {
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertNotNull("Redacted lock-screen content must retain controls", notification.publicVersion)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.publicVersion.visibility)
        assertEquals(listOf("Disarm alarm", "Disarm & open webpage", "Stop all"), notification.publicVersion.actions.map { it.title.toString() })
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(notification.actions.filter { it.title.toString() != "Disarm & open webpage" }.all { !it.isAuthenticationRequired })
            assertTrue(notification.publicVersion.actions.filter { it.title.toString() != "Disarm & open webpage" }.all { !it.isAuthenticationRequired })
            assertTrue(notification.actions.first { it.title.toString() == "Disarm & open webpage" }.isAuthenticationRequired)
        }
    }

    @Test fun rateLimitedHostDoesNotStarveAnotherHost() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("input keyevent 224")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/healthy") MockResponse().setBody("<p>Healthy site</p>")
                else MockResponse().setResponseCode(429).setHeader("Retry-After", "15")
        }
        server.start()
        val blocked = Monitor(name = "Rate-limited", url = server.url("/blocked").newBuilder().host("127.0.0.1").build().toString(), keyword = "trigger", minSeconds = 1, maxSeconds = 1, alternateNetworks = false, alternateIpFamilies = false)
        val healthy = blocked.copy(id = "healthy", name = "Other host", url = server.url("/healthy").newBuilder().host("localhost").build().toString())
        try {
            instrumentation.runOnMainSync {
                context.repository.update(true) { AppState(monitors = listOf(blocked)) }
                MonitorService.send(context, MonitorService.ACTION_START)
            }
            await { context.repository.state.value.statuses[blocked.id]?.failures == 1 }
            instrumentation.runOnMainSync {
                context.repository.update(true) { it.copy(monitors = listOf(blocked) +
                    (1..6).map { index -> blocked.copy(id = "waiting-$index", name = "Waiting $index") } + healthy) }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (context.repository.state.value.statuses[healthy.id] == null && System.nanoTime() < deadline) Thread.sleep(50)
            assertEquals("A cooling-down host must not consume all six request permits", 0, context.repository.state.value.statuses[healthy.id]?.failures)
        } finally {
            instrumentation.runOnMainSync {
                context.repository.update(true) { AppState() }
                context.stopService(android.content.Intent(context, MonitorService::class.java))
            }
            scenario.close()
            server.shutdown()
        }
    }

    @Test fun parallelPollingSurvivesClosedActivityAndScreenOffAndNotificationDisarms() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("input keyevent 224") // Wake the test device.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val available = AtomicBoolean(true)
        val parallelArrivals = CountDownLatch(2)
        val simultaneous = AtomicBoolean(true)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                parallelArrivals.countDown()
                if (!parallelArrivals.await(5, TimeUnit.SECONDS)) simultaneous.set(false)
                return MockResponse().setHeader("Content-Type", "text/html")
                    .setBody(if (available.get()) "<p>In stock</p>" else "<p>Sold out</p>")
            }
        }
        server.start()
        val first = Monitor(name = "First site", url = server.url("/first").toString(), keyword = "in stock", minSeconds = 1, maxSeconds = 1, alternateNetworks = false, alternateIpFamilies = false)
        val second = first.copy(id = "second", name = "Second site", url = server.url("/second").toString())
        try {
            instrumentation.runOnMainSync {
                context.repository.update(true) { AppState(monitors = listOf(first, second)) }
                MonitorService.send(context, MonitorService.ACTION_START)
            }
            await { context.repository.state.value.alarms.size == 2 }
            assertTrue("Both first requests must overlap", simultaneous.get())
            assertTrue(context.repository.state.value.serviceAlive)
            val restored = MonitorRepository(context).state.value
            assertEquals("Alarm latches must survive process recreation", 2, restored.alarms.size)
            assertTrue("Running intent must be persisted", restored.running)
            assertEquals(listOf(first, second), restored.monitors)
            @Suppress("DEPRECATION")
            val service = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
                .first { it.service.className == MonitorService::class.java.name }
            assertTrue("Monitoring service must be in the foreground", service.foreground)

            // Destroy the Activity and turn off the display while polling continues.
            scenario.close()
            shell("input keyevent 223")
            available.set(false)
            await { context.repository.state.value.statuses.size == 2 && context.repository.state.value.statuses.values.all { !it.matched && it.checks >= 2 } }
            assertEquals("Alarm remains latched after the condition clears", 2, context.repository.state.value.alarms.size)
            assertNull("Alarm playback should start successfully", context.repository.state.value.alarmError)

            val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
            val notification = notifications.first { it.id == 41 }.notification
            assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            notification.actions.first { it.title.toString() == "Disarm alarm" }.actionIntent.send()
            await { context.repository.state.value.alarms.isEmpty() }
            assertTrue("Disarming leaves polling running", context.repository.state.value.running)
            await { managerNotification()?.channelId == MonitorService.STATUS_CHANNEL }
            val statusNotification = managerNotification()!!
            assertEquals(Notification.VISIBILITY_PUBLIC, statusNotification.visibility)
            assertEquals(Notification.VISIBILITY_PUBLIC, statusNotification.publicVersion.visibility)
            assertEquals("Stop all", statusNotification.publicVersion.actions.single().title.toString())

            // A new match after the clearing check must alarm again, even with the screen off.
            available.set(true)
            await { context.repository.state.value.alarms.size == 2 }
            val active = context.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 41 }
            active.notification.actions.first { it.title.toString() == "Stop all" }.actionIntent.send()
            await { !context.repository.state.value.serviceAlive }
            assertFalse(context.repository.state.value.running)
            assertTrue(context.repository.state.value.alarms.isEmpty())
            await { context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 41 } }
        } finally {
            instrumentation.runOnMainSync {
                context.repository.update(true) { AppState() }
                context.stopService(android.content.Intent(context, MonitorService::class.java))
            }
            scenario.close()
            server.shutdown()
            shell("input keyevent 224")
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for expected app state: ${context.repository.state.value}", predicate())
    }

    private fun managerNotification(): Notification? = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id == 41 }?.notification

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }
}
