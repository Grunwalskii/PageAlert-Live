package com.clinscout.pagealertlive

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ActivitySettingsIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun activityPersistsFailedRoutesAndClearsFromTheTopWithConfirmation() {
        unlock()
        val watchdog = Monitor(name = "Console", url = "https://example.com", keyword = "stock")
        context.repository.update(true) { AppState(monitors = listOf(watchdog), running = true) }
        val routes = listOf("Wi-Fi • IPv4", "Wi-Fi • IPv6", "Mobile data")
        routes.forEachIndexed { index, route ->
            val code = listOf(429, 503, 403)[index]
            context.repository.record(watchdog, null, HttpErrors.describe(code), 0, code, route)
        }
        context.repository.record(watchdog, CheckResult(false, "Missing"), null, 0, connection = "Wi-Fi • IPv4")
        context.repository.update(true) { it.copy(running = false) }
        // Migrate a pre-connection history entry without borrowing today's route.
        val preferences = context.getSharedPreferences("pagealert_live", Context.MODE_PRIVATE)
        val json = JSONObject(preferences.getString("state", "{}")!!)
        json.getJSONArray("history").put(JSONObject().put("time", 1).put("id", "legacy")
            .put("name", "Older watchdog").put("detail", HttpErrors.describe(429)).put("kind", "HTTP_ERROR"))
        assertTrue(preferences.edit().putString("state", json.toString()).commit())
        val saved = MonitorRepository(context).state.value
        assertEquals(routes.reversed() + "", saved.history.map { it.connection })
        context.repository.update(true) { saved }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun ui(action: () -> Unit) { scenario.onActivity { action() }; instrumentation.waitForIdleSync() }
        try {
            ui { click("Activity") }
            ui {
                routes.forEach { assertNotNull(text("Connection: $it")) }
                assertNotNull(text("Connection: Not recorded"))
                val clear = text("Clear activity")!!
                val firstEntry = text("HTTP error • Console")!!
                val clearPosition = IntArray(2).also(clear::getLocationOnScreen)
                val entryPosition = IntArray(2).also(firstEntry::getLocationOnScreen)
                assertTrue("Clear belongs above the first event", clearPosition[1] + clear.height <= entryPosition[1])
                assertEquals(1, windows().filterIsInstance<TextView>().count { it.text == "Clear activity" })
            }
            shell("screencap -p /sdcard/Download/pagealert-activity-110.png")
            ui { click("Clear activity") }
            ui { click("Cancel") }
            assertEquals(4, MonitorRepository(context).state.value.history.size)
            ui { click("Clear activity") }
            ui { click("Clear") }
            assertTrue(MonitorRepository(context).state.value.history.isEmpty())
            ui { assertNotNull(text("No activity yet.")); assertNull(text("Clear activity")) }
        } finally { scenario.close(); context.repository.update(true) { AppState() } }
    }

    @Test fun settingsButtonsKeepTheirSystemDestinationsAndChannelIds() {
        unlock()
        context.repository.update(true) { AppState() }
        val launched = mutableListOf<Intent>()
        val interceptor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action?.startsWith("android.settings.") != true) return null
                launched += Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(interceptor)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun ui(action: () -> Unit) { scenario.onActivity { action() }; instrumentation.waitForIdleSync() }
        fun checkDestination(label: String, action: String): Intent {
            ui { assertTrue(text(label) is Button); click(label) }
            assertEquals(action, launched.last().action)
            return launched.last()
        }
        try {
            ui { click("Settings") }
            shell("screencap -p /sdcard/Download/pagealert-settings-111.png")
            val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            assertEquals("package:${context.packageName}", checkDestination(
                if (exempt) "Battery settings" else "Allow unrestricted battery use", "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL").dataString)
            assertEquals(context.packageName, checkDestination("Notification settings", Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .getStringExtra(Settings.EXTRA_APP_PACKAGE))
            listOf("Monitoring notification settings" to MonitorService.STATUS_CHANNEL,
                "Alarm notification settings" to MonitorService.ALARM_CHANNEL,
                "HTTP error notification settings" to MonitorService.HTTP_CHANNEL).forEach { (label, channel) ->
                val intent = checkDestination(label, Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
                assertEquals(channel, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
            }
            checkDestination("Alarm volume settings", Settings.ACTION_SOUND_SETTINGS)
            checkDestination("Device notification settings", "android.settings.NOTIFICATION_SETTINGS")
            assertEquals("package:${context.packageName}", checkDestination("Device app settings", Settings.ACTION_APPLICATION_DETAILS_SETTINGS).dataString)
            ui {
                val scroll = windows().filterIsInstance<ScrollView>().last()
                scroll.scrollTo(0, scroll.getChildAt(0).height)
            }
            shell("screencap -p /sdcard/Download/pagealert-settings-bottom-111.png")
        } finally { scenario.close(); instrumentation.removeMonitor(interceptor); context.repository.update(true) { AppState() } }
    }

    @Test fun unavailableBatteryPageFallsBackToThisAppsInfo() {
        unlock()
        context.repository.update(true) { AppState() }
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            val launched = mutableListOf<Intent>()
            val interceptor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action?.startsWith("android.settings.") != true) return null
                    launched += Intent(intent)
                    if (intent.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) throw failure
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
                }
            }
            instrumentation.addMonitor(interceptor)
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                scenario.onActivity { click("Settings") }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
                    click(if (exempt) "Battery settings" else "Allow unrestricted battery use")
                }
                assertEquals(listOf("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", "android.settings.APP_BATTERY_SETTINGS", Settings.ACTION_APPLICATION_DETAILS_SETTINGS), launched.map { it.action })
                assertTrue(launched.all { it.dataString == "package:${context.packageName}" })
            } finally { scenario.close(); instrumentation.removeMonitor(interceptor) }
        }
    }

    @Test fun batteryDetailsSupportsXiaomiAndFallsBackOnOtherPhones() {
        unlock()
        context.repository.update(true) { AppState() }
        // Cover the Poco route, both Android routes, and a restricted OEM fallback.
        val actions = listOf("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY", "android.settings.APP_BATTERY_SETTINGS",
            "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        for (accepted in actions.indices) {
            val launched = mutableListOf<Intent>()
            val interceptor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action !in actions) return null
                    launched += Intent(intent)
                    if (intent.action != actions[accepted]) {
                        if (accepted == actions.lastIndex) throw SecurityException()
                        throw ActivityNotFoundException()
                    }
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
                }
            }
            instrumentation.addMonitor(interceptor)
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                scenario.onActivity { click("Settings") }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val labels = windows().filterIsInstance<TextView>().map { it.text.toString() }
                    val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
                    val oldButton = if (exempt) "Battery settings" else "Allow unrestricted battery use"
                    assertTrue(labels.indexOf("Battery details (Akkudetails)") < labels.indexOf(oldButton))
                    click("Battery details (Akkudetails)")
                }
                assertEquals(actions.take(accepted + 1), launched.map { it.action })
                assertEquals("com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity", launched.first().component?.flattenToString())
                assertEquals(context.packageName, launched.first().getStringExtra("package_name"))
                assertEquals(context.getString(R.string.app_name), launched.first().getStringExtra("package_label"))
                launched.drop(1).forEach { assertEquals("package:${context.packageName}", it.dataString) }
            } finally { scenario.close(); instrumentation.removeMonitor(interceptor) }
        }
    }

    @Test fun olderBatteryPageIsTriedWhenTheCurrentActionIsUnavailable() {
        unlock()
        context.repository.update(true) { AppState() }
        val launched = mutableListOf<Intent>()
        val interceptor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action?.startsWith("android.settings.") != true) return null
                launched += Intent(intent)
                if (intent.action == "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL") throw ActivityNotFoundException()
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(interceptor)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { click("Settings") }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
                click(if (exempt) "Battery settings" else "Allow unrestricted battery use")
            }
            assertEquals(listOf("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", "android.settings.APP_BATTERY_SETTINGS"), launched.map { it.action })
            assertTrue(launched.all { it.dataString == "package:${context.packageName}" })
        } finally { scenario.close(); instrumentation.removeMonitor(interceptor) }
    }

    private fun unlock() { shell("input keyevent 224"); shell("wm dismiss-keyguard") }
    private fun click(label: String) { checkNotNull(text(label)) { "Missing button: $label" }.performClick() }
    private fun text(label: String) = windows().filterIsInstance<TextView>().firstOrNull { it.text == label }
    private fun windows() = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
    }
}
