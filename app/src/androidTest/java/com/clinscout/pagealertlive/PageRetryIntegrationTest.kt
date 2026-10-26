package com.clinscout.pagealertlive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PageRetryIntegrationTest {
    @Test fun invalidSuccessResponsesRetryInsideOneCheckWithoutFalseAlarmsOrEvents() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    val attempt = counts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                    val html = when (path) {
                        "/keyword" -> if (attempt == 1) "<form action='/errors/validateCaptcha'><p>Continue shopping</p></form>" else "<p>In stock</p>"
                        "/price" -> if (attempt == 1) "<p>Temporary page</p>" else "<p id='price'>€149.00</p>"
                        else -> "<p>Price missing from HTML</p>"
                    }
                    return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(html)
                }
            }
            start()
        }
        val keyword = Monitor(name = "Missing keyword", url = server.url("/keyword").toString(),
            trigger = Trigger.KEYWORD_MISSING, keyword = "stock", minSeconds = 3600, maxSeconds = 3600,
            alternateNetworks = false, alternateIpFamilies = false)
        val price = keyword.copy(id = "price", name = "Price", url = server.url("/price").toString(),
            trigger = Trigger.PRICE, selector = "#price", threshold = "100")
        val rejected = price.copy(id = "rejected", name = "Still missing", url = server.url("/missing").toString())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val engine = MonitorEngine(scope, context.repository)
        try {
            instrumentation.runOnMainSync {
                context.repository.update(true) { AppState(monitors = listOf(keyword, price, rejected), running = true) }
                engine.reconcile(context.repository.state.value)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (context.repository.state.value.statuses.size < 3 && System.nanoTime() < deadline) Thread.sleep(25)
            val state = context.repository.state.value
            assertEquals(3, state.statuses.size)
            assertTrue(state.alarms.isEmpty())
            assertTrue(state.history.isEmpty())
            assertEquals(6, server.requestCount)
            assertTrue(counts.values.all { it.get() == 2 })
            assertTrue(state.statuses.values.all { it.checks == 1L })
            for (monitor in listOf(keyword, price)) {
                val status = state.statuses.getValue(monitor.id)
                assertEquals(0, status.failures)
                assertFalse(status.matched)
                assertEquals("Success after one page retry", status.connectionDebug!!.outcome)
                assertFalse(status.ipFallback)
            }
            assertEquals(1, state.statuses.getValue(rejected.id).failures)
            assertTrue(state.statuses.getValue(rejected.id).detail.contains("after one immediate page retry"))
            assertEquals("Success after one page retry", MonitorRepository(context).state.value.statuses.getValue(price.id).connectionDebug!!.outcome)
        } finally {
            instrumentation.runOnMainSync { engine.close(); scope.cancel(); context.repository.update(true) { AppState() } }
            server.shutdown()
        }
    }
}
