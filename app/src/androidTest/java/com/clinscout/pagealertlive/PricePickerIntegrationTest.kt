package com.clinscout.pagealertlive

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.CheckBox
import android.widget.EditText
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
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class PricePickerIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val html = "<p id='stock'>In stock</p><div data-test='product-price'><span aria-hidden='true'>99,</span> <span aria-hidden='true'>99 €</span><span>99,99€</span></div><p id='delivery'>Delivery 4,99 €</p>"

    @Test fun pricePickerFetchesPageAndSavesIndependentKeywordAndPriceConditions() {
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        context.repository.update(true) { AppState() }
        val server = MockWebServer().apply { enqueue(MockResponse().setBody(html)); start() }
        val pageUrl = server.url("/console").toString()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun ui(action: () -> Unit) { scenario.onActivity { action() }; instrumentation.waitForIdleSync() }
        try {
            ui { click("+ Add") }
            ui {
                field("e.g. Headphones restock").setText("Console")
                field("https://example.com/product").setText(pageUrl)
                field("e.g. In stock").setText("in stock")
                check("Check price").isChecked = true
                field("e.g. 99.99").setText("100")
                check("Require both conditions to match").isChecked = true
                click("Find prices on page")
            }
            await {
                var found = false
                ui { found = windows().filterIsInstance<TextView>().any { it.text == "Choose price element" } }
                found
            }
            shell("screencap -p /sdcard/Download/pagealert-price-picker-109.png")
            ui {
                val list = windows().filterIsInstance<ListView>().last()
                val position = (0 until list.adapter.count).first { (list.adapter.getItem(it) as PriceCandidate).selector == "[data-test=\"product-price\"]" }
                assertEquals("99.99", (list.adapter.getItem(position) as PriceCandidate).price.toPlainString())
                list.performItemClick(list.getChildAt(position - list.firstVisiblePosition), position, list.adapter.getItemId(position))
            }
            ui {
                assertEquals("[data-test=\"product-price\"]", field("Choose a price below or enter a selector").text.toString())
                click("Save")
            }
            ui { click("Confirm & save") }
            val monitor = MonitorRepository(context).state.value.monitors.single()
            assertTrue(monitor.usesKeyword && monitor.usesPrice && monitor.requireAllConditions)
            assertEquals("", monitor.selector)
            assertEquals("[data-test=\"product-price\"]", monitor.priceSelector)
            assertTrue(TriggerEvaluator.evaluate(monitor, html).matched)
            assertFalse(TriggerEvaluator.evaluate(monitor, html.replace("In stock", "Sold out")).matched)
            assertEquals(1, server.requestCount)
            assertEquals("/console", server.takeRequest().path)
            val result = TriggerEvaluator.evaluate(monitor, html)
            context.repository.update(true) { state -> AlertReducer.record(state.copy(running = true), monitor, result, null, 100, 200) }
            val status = MonitorRepository(context).state.value.statuses.getValue(monitor.id)
            assertEquals(true, status.keywordMatched)
            assertEquals(true, status.priceMatched)
            ui {
                assertTrue(windows().filterIsInstance<TextView>().any { it.text == "Keyword present • “in stock” ✓" })
                assertTrue(windows().filterIsInstance<TextView>().any { it.text == "Price at or below 100 - 99.99 ≤ 100" })
                assertFalse(windows().filterIsInstance<TextView>().any { it.text == "Price 99.99 ≤ 100" })
            }
        } finally { scenario.close(); context.repository.update(true) { AppState() }; server.shutdown() }
    }

    @Test fun priceOnlyNeedsNoKeywordAndAtLeastOneConditionIsRequired() {
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        context.repository.update(true) { AppState() }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun ui(action: () -> Unit) { scenario.onActivity { action() }; instrumentation.waitForIdleSync() }
        try {
            ui { click("+ Add") }
            ui {
                field("e.g. Headphones restock").setText("Price only")
                field("https://example.com/product").setText("https://example.com")
                check("Check keyword").isChecked = false
                click("Save")
                assertTrue(windows().filterIsInstance<TextView>().any { it.text == "Enable keyword, price, or both." })
                assertTrue(context.repository.state.value.monitors.isEmpty())
                check("Check price").isChecked = true
                field("e.g. 99.99").setText("100")
                field("Choose a price below or enter a selector").setText("#price")
                click("Save")
            }
            ui { click("Confirm & save") }
            val monitor = MonitorRepository(context).state.value.monitors.single()
            assertFalse(monitor.usesKeyword)
            assertTrue(monitor.usesPrice)
            assertTrue(monitor.keyword.isEmpty())
            assertNull(monitor.validate())
            assertTrue(TriggerEvaluator.evaluate(monitor, "<p id='price'>€50.00</p>").matched)
        } finally { scenario.close(); context.repository.update(true) { AppState() } }
    }

    private fun field(hint: String) = windows().filterIsInstance<EditText>().first { it.hint == hint }
    private fun check(label: String) = windows().filterIsInstance<CheckBox>().first { it.text == label }
    private fun click(label: String) { windows().filterIsInstance<TextView>().first { it.text == label }.performClick() }
    private fun windows() = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("Price picker did not finish loading", predicate())
    }
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
    }
}
