package com.clinscout.pagealertlive

import android.content.Context
import android.net.Network
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class NetworkSettingsIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun wifiDiscoveryFindsNonDefaultWifiAndExcludesVpnLinks() {
        fun network(id: Long) = Network.fromNetworkHandle((id shl 32) or 0xcafed00dL)
        val mobile = network(101)
        val wifi = network(102)
        val vpn = network(103)
        val isWifi: (Network) -> Boolean = { it == wifi }
        assertEquals(wifi, DeviceNetworkState.wifiNetwork(mobile, listOf(mobile, wifi), isWifi))
        assertEquals(wifi, DeviceNetworkState.wifiNetwork(vpn, listOf(vpn, wifi), isWifi))
        assertEquals(wifi, DeviceNetworkState.wifiNetwork(null, listOf(wifi), isWifi))
        assertNull(DeviceNetworkState.wifiNetwork(vpn, listOf(vpn, mobile), isWifi))
        assertNull(DeviceNetworkState.wifiNetwork(wifi, listOf(wifi)) { false })
    }

    @Test fun missingMobilePreferenceDefaultsOffAndExplicitChoicesSurviveReload() {
        val preferences = context.getSharedPreferences("pagealert_live", Context.MODE_PRIVATE)
        val original = preferences.getString("state", null)
        val monitor = JSONObject().put("id", "legacy").put("name", "Legacy").put("url", "https://example.com")
            .put("trigger", "KEYWORD_PRESENT").put("keyword", "stock").put("minSeconds", 60).put("maxSeconds", 120)
        try {
            fun load(): Monitor {
                assertTrue(preferences.edit().putString("state", JSONObject().put("monitors", JSONArray().put(monitor)).toString()).commit())
                return MonitorRepository(context).state.value.monitors.single()
            }
            assertFalse(load().alternateNetworks)
            monitor.put("alternateNetworks", true)
            assertTrue(load().alternateNetworks)
            monitor.put("alternateNetworks", false)
            assertFalse(load().alternateNetworks)
        } finally {
            assertTrue(preferences.edit().putString("state", original).commit())
        }
    }

    @Test fun editorKeepsIpChoiceEditableAcrossLiveWifiChangesAndSavesIt() {
        val originalWifi = DeviceNetworkState.read(context).wifiEnabled
        shell("svc wifi enable")
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        await { DeviceNetworkState.wifiSupport(context).ipv4 }
        context.repository.update(true) { AppState() }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().first { it.text == "+ Add" }.performClick()
                assertFalse(checkBox("Alternate Wi-Fi & mobile data").isChecked)
                assertTrue(checkBox("Alternate IPv4 & IPv6 (Wi-Fi)").isChecked)
                assertTrue(checkBox("Alternate IPv4 & IPv6 (Wi-Fi)").isEnabled)
            }
            shell("svc wifi disable")
            await {
                var waiting = false
                scenario.onActivity { waiting = windowText().any { it == "Wi-Fi unavailable" } }
                waiting
            }
            scenario.onActivity {
                val ip = checkBox("Alternate IPv4 & IPv6 (Wi-Fi)")
                assertTrue(ip.isEnabled && ip.isChecked)
                ip.isChecked = false
                val fields = windows().filterIsInstance<EditText>()
                fields.first { it.hint == "e.g. Headphones restock" }.setText("Preferences")
                fields.first { it.hint == "https://example.com/product" }.setText("https://example.com")
                fields.first { it.hint == "e.g. In stock" }.setText("stock")
                windows().filterIsInstance<TextView>().first { it.text == "Save" }.performClick()
                assertTrue(context.repository.state.value.monitors.isEmpty())
                windows().filterIsInstance<TextView>().first { it.text == "Confirm & save" }.performClick()
            }
            instrumentation.waitForIdleSync()
            val saved = MonitorRepository(context).state.value.monitors.single()
            assertFalse(saved.alternateNetworks)
            assertFalse(saved.alternateIpFamilies)
            scenario.onActivity { activity ->
                descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().first { it.text == "Edit" }.performClick()
                assertFalse(checkBox("Alternate IPv4 & IPv6 (Wi-Fi)").isChecked)
                checkBox("Alternate IPv4 & IPv6 (Wi-Fi)").isChecked = true
            }
            shell("svc wifi enable")
            await {
                var connected = false
                scenario.onActivity { connected = windowText().any { it.startsWith("Wi-Fi: IPv4") } }
                connected
            }
            scenario.onActivity {
                assertTrue(checkBox("Alternate IPv4 & IPv6 (Wi-Fi)").isChecked)
                windows().filterIsInstance<TextView>().first { it.text == "Save" }.performClick()
            }
            instrumentation.waitForIdleSync()
            assertTrue(MonitorRepository(context).state.value.monitors.single().alternateIpFamilies)
        } finally {
            scenario.close()
            context.repository.update(true) { AppState() }
            if (!originalWifi) shell("svc wifi disable")
        }
    }

    @Test fun privacyNoticeOpensOfflineEnglishAndGermanPolicy() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().first { it.text == "Settings" }.performClick()
                assertTrue(windowText().any { it.startsWith("No data is collected by the developer.") })
                windows().filterIsInstance<TextView>().first { it.text == "Privacy policy / Datenschutzerklärung" }.performClick()
                val policy = windowText().first { it.startsWith("PageAlert Live — Privacy policy") }
                assertTrue(policy.contains("your IP address"))
                assertTrue(policy.contains("Ihre IP-Adresse"))
                assertTrue(policy.contains("Nothing is uploaded automatically."))
                assertTrue(policy.contains("/PageAlert-Live/issues"))
                assertTrue(policy.contains("Clinscout Database GmbH"))
                assertTrue(policy.contains("info@clinscout.com"))
                windows().filterIsInstance<TextView>().first { it.text == "Close" }.performClick()
                windows().filterIsInstance<TextView>().first { it.text == "Impressum" }.performClick()
                val imprint = windows().filterIsInstance<TextView>().first { it.text.startsWith("PageAlert Live — Impressum") }
                assertTrue(imprint.text.contains("Clinscout Database GmbH"))
                assertTrue(imprint.text.contains("info@clinscout.com"))
                assertTrue(imprint.isTextSelectable)
            }
            instrumentation.waitForIdleSync()
            shell("screencap -p /sdcard/Download/pagealert-impressum-111.png")
        } finally { scenario.close() }
    }

    @Test fun firstSaveAndChangedUrlRequireConfirmationButOrdinaryEditsDoNot() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        context.repository.update(true) { AppState() }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun click(label: String) { windows().filterIsInstance<TextView>().first { it.text == label }.performClick() }
        fun field(hint: String) = windows().filterIsInstance<EditText>().first { it.hint == hint }
        fun ui(action: () -> Unit) { scenario.onActivity { action() }; instrumentation.waitForIdleSync() }
        try {
            ui { click("+ Add") }
            ui {
                field("e.g. Headphones restock").setText("Terms")
                field("https://example.com/product").setText("https://example.com/first")
                field("e.g. In stock").setText("stock")
                click("Save")
            }
            ui {
                assertTrue("Confirmation text: ${windowText()}", windowText().any { it.contains("automated checks comply") && it.contains("(AGB)") })
                assertTrue(context.repository.state.value.monitors.isEmpty())
                // The last Cancel is the confirmation's button, above the editor.
                windows().filterIsInstance<TextView>().last { it.text == "Cancel" }.performClick()
            }
            ui {
                assertTrue(context.repository.state.value.monitors.isEmpty())
                click("Save")
            }
            ui { click("Confirm & save") }
            assertEquals("https://example.com/first", MonitorRepository(context).state.value.monitors.single().url)
            ui { click("Edit") }
            ui {
                field("e.g. Headphones restock").setText("Renamed")
                click("Save")
                assertFalse(windowText().contains("Confirm & save"))
            }
            assertEquals("Renamed", MonitorRepository(context).state.value.monitors.single().name)
            ui { click("Edit") }
            ui {
                field("https://example.com/product").setText("https://example.com/changed")
                click("Save")
            }
            ui {
                assertEquals("https://example.com/first", MonitorRepository(context).state.value.monitors.single().url)
                windows().filterIsInstance<TextView>().last { it.text == "Cancel" }.performClick()
            }
            ui {
                assertEquals("https://example.com/first", MonitorRepository(context).state.value.monitors.single().url)
                click("Save")
            }
            ui { click("Confirm & save") }
            assertEquals("https://example.com/changed", MonitorRepository(context).state.value.monitors.single().url)
        } finally { scenario.close(); context.repository.update(true) { AppState() } }
    }

    @Test fun editingKeepsDisabledStateAndLegacyScopeAndSavesIntervalsAsSeconds() {
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        val original = Monitor(id = "editor", name = "Console", url = "https://example.com/product",
            keyword = "In stock", selector = "#stock", minSeconds = 120, maxSeconds = 180, enabled = false)
        context.repository.update(true) { AppState(monitors = listOf(original)) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun ui(action: () -> Unit) { scenario.onActivity { action() }; instrumentation.waitForIdleSync() }
        fun click(label: String) { windows().filterIsInstance<TextView>().first { it.text == label }.performClick() }
        fun field(hint: String) = windows().filterIsInstance<EditText>().first { it.hint == hint }
        try {
            ui { click("Edit") }
            ui {
                assertEquals("120", field("60").text.toString())
                assertEquals("180", field("120").text.toString())
                assertFalse(windows().filterIsInstance<EditText>().any { it.hint == "e.g. #availability" })
                assertFalse(windows().filterIsInstance<CheckBox>().any { it.text == "Enable this monitor" })
                field("60").setText("90")
                field("120").setText("150")
            }
            shell("screencap -p /sdcard/Download/pagealert-editor-109.png")
            ui { windows().filterIsInstance<android.widget.ScrollView>().last().fullScroll(View.FOCUS_DOWN) }
            shell("screencap -p /sdcard/Download/pagealert-editor-connections-109.png")
            ui { click("Save") }
            val saved = MonitorRepository(context).state.value.monitors.single()
            assertFalse("Editing must not enable a disabled watchdog", saved.enabled)
            assertEquals("#stock", saved.selector)
            assertEquals(90L, saved.minSeconds)
            assertEquals(150L, saved.maxSeconds)
            ui { click("+ Add") }
            ui {
                assertEquals("60", field("60").text.toString())
                assertEquals("120", field("120").text.toString())
                field("e.g. Headphones restock").setText("New watchdog")
                field("https://example.com/product").setText("https://example.com/new")
                field("e.g. In stock").setText("stock")
                click("Save")
            }
            ui { click("Confirm & save") }
            val newWatchdog = MonitorRepository(context).state.value.monitors.last()
            assertTrue(newWatchdog.enabled)
            assertEquals("", newWatchdog.selector)
            assertEquals(60L, newWatchdog.minSeconds)
            assertEquals(120L, newWatchdog.maxSeconds)
        } finally { scenario.close(); context.repository.update(true) { AppState() } }
    }

    private fun checkBox(label: String) = windows().filterIsInstance<CheckBox>().first { it.text == label }
    private fun windowText() = windows().filterIsInstance<TextView>().map { it.text.toString() }
    private fun windows() = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("Network setting did not update", predicate())
    }
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }
}
