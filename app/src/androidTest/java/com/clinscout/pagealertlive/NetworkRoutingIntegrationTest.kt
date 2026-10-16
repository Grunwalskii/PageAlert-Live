package com.clinscout.pagealertlive

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NetworkRoutingIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun disabledMobileDataUsesWifiEveryTurnAndExplainsItInOverview() {
        val original = DeviceNetworkState.read(context)
        shell("svc wifi enable")
        shell("svc data disable")
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val server = server()
        val monitor = Monitor(name = "Wi-Fi routing", url = server.url("/").newBuilder().host("127.0.0.1").build().toString(),
            keyword = "not present", minSeconds = 1, maxSeconds = 1, alternateNetworks = true)
        try {
            await { !DeviceNetworkState.read(context).mobileEnabled && DeviceNetworkState.read(context).wifiEnabled }
            scenario.onActivity {
                context.repository.update(true) { AppState(monitors = listOf(monitor)) }
                MonitorService.send(it, MonitorService.ACTION_START)
            }
            await { (context.repository.state.value.statuses[monitor.id]?.checks ?: 0) >= 2 }
            val state = context.repository.state.value
            assertTrue(state.statuses.getValue(monitor.id).connection.startsWith("Wi-Fi"))
            assertEquals(0, state.statuses.getValue(monitor.id).failures)
            assertTrue("Both turns must send real HTTP requests over Wi-Fi", server.requestCount >= 2)
            assertTrue(state.alarms.isEmpty())
            assertNull("Per-request routing must not bind the whole process", context.getSystemService(ConnectivityManager::class.java).boundNetworkForProcess)
            scenario.onActivity { activity ->
                assertNotNull(findText(activity.findViewById(android.R.id.content), "Mobile data off • Wi-Fi only"))
            }
            assertTrue(MonitorRepository(context).state.value.monitors.single().alternateNetworks)
            scenario.onActivity {
                context.repository.save(monitor.copy(alternateNetworks = false, alternateIpFamilies = false))
            }
            await { context.repository.state.value.statuses[monitor.id]?.connection == "Wi-Fi • System routing" }
            assertFalse(MonitorRepository(context).state.value.monitors.single().alternateNetworks)
        } finally {
            instrumentation.runOnMainSync { MonitorService.send(context, MonitorService.ACTION_STOP) }
            await { !context.repository.state.value.serviceAlive }
            context.repository.update(true) { AppState() }
            scenario.close()
            server.shutdown()
            shell("svc data ${if (original.mobileEnabled) "enable" else "disable"}")
            if (!original.wifiEnabled) shell("svc wifi disable")
        }
    }

    @Test fun unavailableSelectedConnectionSkipsOneSlotWithoutSendingOrAlarming() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val turns = Collections.synchronizedList(mutableListOf<Long>())
        val server = server()
        val monitor = Monitor(name = "Missing mobile", url = server.url("/").toString(), keyword = "not present", minSeconds = 1, maxSeconds = 1, alternateNetworks = true)
        val chooser = RouteChooser { _, turn, _, waitMillis, onWaiting ->
            turns += turn
            if (turn % 2 == 1L) {
                onWaiting("Mobile data", System.currentTimeMillis() + waitMillis)
                delay(waitMillis)
                throw NetworkTurnSkipped("Mobile data")
            }
            RequestRoute(label = "Wi-Fi")
        }
        val engine = MonitorEngine(scope, context.repository, routeChooser = chooser)
        try {
            instrumentation.runOnMainSync {
                context.repository.update(true) { AppState(monitors = listOf(monitor), running = true) }
                engine.reconcile(context.repository.state.value)
            }
            await { (context.repository.state.value.statuses[monitor.id]?.checks ?: 0) >= 2 }
            val state = context.repository.state.value
            assertEquals(listOf(0L, 1L, 2L), synchronized(turns) { turns.take(3) })
            assertEquals(2, server.requestCount)
            assertEquals(3L, state.statuses.getValue(monitor.id).networkTurn)
            assertEquals(3L, MonitorRepository(context).state.value.statuses.getValue(monitor.id).networkTurn)
            assertTrue("Network skips must not create HTTP errors or alarms", state.history.isEmpty() && state.alarms.isEmpty())
        } finally {
            instrumentation.runOnMainSync {
                context.repository.update(true) { AppState() }
                scope.cancel()
                engine.close()
            }
            server.shutdown()
        }
    }

    private fun server() = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<p>Available</p>")
        }
        start(InetAddress.getByName("0.0.0.0"), 0)
    }

    @Test fun allThreeConnectionPathsReceiveEqualTurnsAndPersistAcrossRestart() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val selected = Collections.synchronizedList(mutableListOf<String>())
        val server = server()
        val monitor = Monitor(name = "Both rotations", url = server.url("/").toString(), keyword = "not present", minSeconds = 1, maxSeconds = 1, alternateNetworks = true)
        val chooser = RouteChooser { _, turn, _, _, _ ->
            val slot = ConnectionRotation.select(DeviceConnections(true, true), true, turn)!!
            val route = RequestRoute(label = slot.label, path = slot.path, family = slot.family)
            selected += route.label
            route
        }
        val engine = MonitorEngine(scope, context.repository, routeChooser = chooser)
        try {
            instrumentation.runOnMainSync {
                context.repository.update(true) { AppState(monitors = listOf(monitor), running = true) }
                engine.reconcile(context.repository.state.value)
            }
            await { (context.repository.state.value.statuses[monitor.id]?.checks ?: 0) >= 6 }
            assertEquals(listOf("Wi-Fi • IPv4", "Wi-Fi • IPv6", "Mobile data", "Wi-Fi • IPv4", "Wi-Fi • IPv6", "Mobile data"),
                synchronized(selected) { selected.take(6) })
            assertEquals(2, synchronized(selected) { selected.take(6).count { it == "Mobile data" } })
            val saved = MonitorRepository(context).state.value.statuses.getValue(monitor.id)
            assertEquals(6L, saved.networkTurn)
            assertEquals(4L, saved.wifiIpTurn)
            assertTrue(context.repository.state.value.alarms.isEmpty())
        } finally {
            instrumentation.runOnMainSync { context.repository.update(true) { AppState() }; scope.cancel(); engine.close() }
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
