package com.clinscout.pagealertlive

import android.content.Context
import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.Dns
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
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

@RunWith(AndroidJUnit4::class)
class IpFamilyFallbackIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun fallbackColorsOnlyTheActualFamilyAndPersistsAcrossRecreation() {
        val monitors = listOf("IPv4", "IPv6", "Normal").map { name ->
            Monitor(name = name, url = "https://example.com", keyword = "missing")
        }
        val statuses = monitors.associate { monitor -> monitor.id to MonitorStatus(
            checkedAt = 1, connection = "Wi-Fi • ${if (monitor.name == "IPv6") "IPv6" else "IPv4"}",
            ipFallback = monitor.name != "Normal"
        ) }
        context.repository.update(true) { AppState(monitors = monitors, statuses = statuses) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun verify() = scenario.onActivity { activity ->
            fun rows(view: View): List<TextView> = buildList {
                if (view is TextView && view.text.startsWith("Interval  ")) add(view)
                if (view is ViewGroup) for (index in 0 until view.childCount) addAll(rows(view.getChildAt(index)))
            }
            val rows = rows(activity.findViewById(android.R.id.content))
            assertEquals(3, rows.size)
            rows.forEachIndexed { index, row ->
                assertEquals(Color.rgb(151, 171, 187), row.currentTextColor)
                val styled = row.text as? Spanned
                val spans = styled?.getSpans(0, styled.length, ForegroundColorSpan::class.java).orEmpty()
                if (index < 2) {
                    val span = spans.single()
                    assertEquals(Color.rgb(255, 133, 133), span.foregroundColor)
                    assertEquals(if (index == 0) "IPv4" else "IPv6", row.text.subSequence(styled!!.getSpanStart(span), styled.getSpanEnd(span)).toString())
                    assertEquals(row.text.length, styled.getSpanEnd(span))
                } else assertTrue("A normal request must clear the red fallback mark", spans.isEmpty())
            }
        }
        try {
            verify()
            assertTrue(MonitorRepository(context).state.value.statuses.getValue(monitors[0].id).ipFallback)
            assertFalse(MonitorRepository(context).state.value.statuses.getValue(monitors[2].id).ipFallback)
            scenario.recreate()
            verify()
        } finally { scenario.close(); context.repository.update(true) { AppState() } }
    }

    @Test fun fallbackIsSilentAndBothFamiliesKeepTheirTurns() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val callbacks = AtomicInteger()
        val v6Lookups = AtomicInteger()
        val preferred = Collections.synchronizedList(mutableListOf<String>())
        val completed = Collections.synchronizedList(mutableListOf<MonitorStatus>())
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<p>Available</p>")
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val monitor = Monitor(name = "Silent fallback", url = server.url("/").newBuilder().host("families.test").build().toString(),
            keyword = "missing", minSeconds = 1, maxSeconds = 1, alternateNetworks = true)
        val chooser = RouteChooser { _, turn, _, _, _ ->
            val slot = ConnectionRotation.select(DeviceConnections(true, true), true, turn)!!
            preferred += slot.label
            val base = RequestRoute(key = slot.path.name, label = slot.path.label, socketFactory = SocketFactory.getDefault(),
                dns = object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (slot.family == IpFamily.IPV6) v6Lookups.incrementAndGet()
                        return listOf(InetAddress.getByName("127.0.0.1"))
                    }
                }, path = slot.path)
            slot.family?.let(base::withIpFamilyFallback) ?: base
        }
        val engine = MonitorEngine(scope, context.repository, onHttpError = { callbacks.incrementAndGet() }, routeChooser = chooser)
        try {
            instrumentation.runOnMainSync {
                context.repository.update(true) { AppState(monitors = listOf(monitor), running = true) }
                scope.launch {
                    var lastChecks = 0L
                    context.repository.state.collect { state ->
                        state.statuses[monitor.id]?.takeIf { it.checks > lastChecks }?.let {
                            lastChecks = it.checks
                            completed += it
                        }
                    }
                }
                engine.reconcile(context.repository.state.value)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (completed.size < 6 && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue("Six checks must complete", completed.size >= 6)
            assertEquals(List(2) { listOf("Wi-Fi • IPv4", "Wi-Fi • IPv6", "Mobile data") }.flatten(), synchronized(preferred) { preferred.take(6) })
            val checks = synchronized(completed) { completed.take(6) }
            assertEquals(List(2) { listOf("Wi-Fi • IPv4", "Wi-Fi • IPv4", "Mobile data") }.flatten(), checks.map { it.connection })
            assertEquals(listOf(false, true, false, false, true, false), checks.map { it.ipFallback })
            assertTrue(checks.all { it.failures == 0 && it.httpStatus == null && it.routeNote.isBlank() })
            assertEquals(0, callbacks.get())
            assertTrue(v6Lookups.get() >= 2)
            assertTrue(context.repository.state.value.history.isEmpty())
            assertTrue(context.repository.state.value.alarms.isEmpty())
            val saved = MonitorRepository(context).state.value.statuses.getValue(monitor.id)
            assertEquals(6L, saved.networkTurn)
            assertEquals(4L, saved.wifiIpTurn)
        } finally {
            instrumentation.runOnMainSync {
                scope.cancel()
                engine.close()
                context.repository.update(true) { AppState() }
            }
            server.shutdown()
        }
    }
}
