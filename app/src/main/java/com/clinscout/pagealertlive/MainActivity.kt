package com.clinscout.pagealertlive

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.net.toUri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var ticker: Job? = null
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var statePill: TextView
    private lateinit var controls: Button
    private var tab = 0
    private var pendingCommand: String? = null
    private var refreshEditorNetwork: (() -> Unit)? = null
    private var editorDialog: AlertDialog? = null
    private var termsDialog: AlertDialog? = null
    private var networkDiagnosticsDialog: AlertDialog? = null
    private val timeLabels = mutableListOf<Pair<String, TextView>>()
    private val tabButtons = mutableListOf<Button>()
    private val bg = Color.rgb(12, 20, 28)
    private val panel = Color.rgb(23, 35, 45)
    private val mint = Color.rgb(113, 226, 188)
    private val muted = Color.rgb(151, 171, 187)
    private val white = Color.rgb(234, 243, 248)
    private val red = Color.rgb(255, 133, 133)
    private val amber = Color.rgb(244, 196, 110)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = (savedInstanceState?.getInt("tab", 0) ?: intent.getIntExtra("tab", 0)).coerceIn(0, 2)
        pendingCommand = savedInstanceState?.getString("pendingCommand")
        MonitorService.channels(this)
        val root = column(0).apply { setBackgroundColor(bg) }
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val header = row().apply { setPadding(dp(24), dp(20), dp(24), dp(12)) }
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_radar); contentDescription = getString(R.string.app_icon_description) }
        header.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)))
        header.addView(text(getString(R.string.app_name), 25, white, true).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        statePill = text("IDLE", 11, muted, true).apply { setPadding(dp(12), dp(7), dp(12), dp(7)); background = shape(panel, 20) }
        header.addView(statePill)
        root.addView(header)
        val tabs = row().apply { setPadding(dp(18), 0, dp(18), dp(8)) }
        listOf("Monitors", "Activity", "Settings").forEachIndexed { index, label ->
            val tabButton = button(label, false) {
                if (tab != index) scroll.scrollTo(0, 0)
                tab = index
                render(repository.state.value)
            }
            tabButtons += tabButton
            tabs.addView(tabButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        }
        root.addView(tabs)
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = column(24)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = column(20)
        controls = button("Start monitoring", true) {
            val state = repository.state.value
            if (state.running && state.serviceAlive) dispatch(MonitorService.ACTION_STOP)
            else if (state.monitors.none { it.enabled }) toast("Add or enable a monitor first.")
            else withNotifications(MonitorService.ACTION_START)
        }
        footer.addView(controls, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(footer)
        setContentView(root)
        render(repository.state.value)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab)
        outState.putString("pendingCommand", pendingCommand)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        observation = scope.launch { repository.state.collect { render(it) } }
        ticker = scope.launch { while (isActive) { updateTimes(); delay(1000) } }
    }
    override fun onStop() { observation?.cancel(); ticker?.cancel(); super.onStop() }
    override fun onDestroy() { networkDiagnosticsDialog?.dismiss(); termsDialog?.dismiss(); editorDialog?.dismiss(); scope.cancel(); super.onDestroy() }
    override fun onResume() { super.onResume(); if (::content.isInitialized) render(repository.state.value); refreshEditorNetwork?.invoke() }

    private fun render(state: AppState) {
        tabButtons.forEachIndexed { index, button ->
            val selected = index == tab
            button.isSelected = selected
            button.background = shape(if (selected) mint else Color.TRANSPARENT, 12)
            button.setTextColor(if (selected) bg else muted)
            button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        val oldY = scroll.scrollY
        val horizontalPadding = dp(if (tab == 0) 12 else 24)
        content.setPadding(horizontalPadding, dp(12), horizontalPadding, dp(12))
        content.removeAllViews()
        timeLabels.clear()
        val live = state.running && state.serviceAlive
        statePill.text = if (state.alarms.isNotEmpty()) "ALARM" else if (live) "LIVE" else if (state.running) "RESUME" else "IDLE"
        statePill.setTextColor(if (state.alarms.isNotEmpty()) red else if (live) mint else muted)
        controls.text = if (live) "Stop monitoring & disarm" else if (state.running) "Resume monitoring" else "Start monitoring"
        if (state.alarms.isNotEmpty()) {
            val alarmBox = card(Color.rgb(57, 31, 37))
            alarmBox.addView(text("●  ALARM ACTIVE", 12, red, true))
            alarmBox.addView(text(state.alarms.values.joinToString("\n") { "${it.name}: ${it.detail}" }, 17, white, true).spaced())
            alarmBox.addView(text("The alarm keeps ringing until you disarm it.", 13, muted).spaced())
            alarmBox.addView(button("Disarm alarm", true) { dispatch(MonitorService.ACTION_DISARM) }.spaced())
            if (state.monitors.any { it.id in state.alarms }) alarmBox.addView(button("Disarm & open webpage", false) {
                startActivity(AlarmWebpages.intent(this, repository.state.value))
            }.spaced(4))
            content.addView(alarmBox.spaced(12))
        }
        if (state.alarmError != null) content.addView(text(state.alarmError, 14, amber).spaced())
        state.interruption?.let { interruption ->
            val notice = card()
            notice.addView(text("Monitoring was interrupted", 19, amber, true))
            val lastActive = if (interruption.lastRunningAt > 0)
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(interruption.lastRunningAt))
                else "Not recorded by the previous version"
            notice.addView(text("Last active: $lastActive", 14, white).spaced(8))
            if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
                notice.addView(button("Allow unrestricted battery use", true) { openBatterySettings() }.spaced())
            }
            notice.addView(button("Dismiss", false) { repository.update(true) { it.copy(interruption = null) } }.spaced(4))
            content.addView(notice.spaced())
        }
        when (tab) {
            0 -> monitors(state)
            1 -> history(state)
            else -> settings()
        }
        content.addView(Space(this), LinearLayout.LayoutParams(1, dp(12)))
        scroll.post { scroll.scrollTo(0, oldY) }
        updateTimes()
    }

    private fun monitors(state: AppState) {
        val networkNote = if (state.monitors.any { it.alternateNetworks }) DeviceNetworkState.read(this).note else ""
        val stats = row()
        listOf("${state.monitors.count { it.enabled }}\nENABLED", "${state.statuses.values.sumOf { it.checks }}\nCHECKS", "${state.history.size}\nEVENTS").forEach { label ->
            stats.addView(text(label, 16, mint, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(16)); background = shape(panel, 14)
            }, LinearLayout.LayoutParams(0, dp(74), 1f).apply { marginEnd = dp(6) })
        }
        content.addView(stats.spaced(20))
        val heading = row()
        heading.addView(text("Your monitors", 21, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(button("+ Add", true) { editor(null) })
        content.addView(heading.spaced(12))
        if (state.monitors.isEmpty()) {
            val empty = card()
            empty.addView(text("Watch for the moment that matters.", 22, white, true))
            empty.addView(text("Track a restock keyword, a missing message, or a price drop. Each website gets its own randomized timer.", 15, muted).spaced())
            empty.addView(button("Create your first monitor", true) { editor(null) }.spaced())
            content.addView(empty.spaced())
        }
        state.monitors.forEach { monitor ->
            val status = state.statuses[monitor.id]
            val watchdogCard = column(0).apply {
                background = shape(panel, 16)
                clipToOutline = true
            }
            val card = column(16).apply { setPadding(dp(16), dp(16), dp(16), dp(14)) }
            watchdogCard.addView(card, LinearLayout.LayoutParams(-1, -2))
            val title = row()
            title.addView(text(monitor.name, 19, white, true), LinearLayout.LayoutParams(0, -2, 1f))
            title.addView(Switch(this).apply {
                contentDescription = "Enable ${monitor.name}"
                isChecked = monitor.enabled
                thumbTintList = ColorStateList.valueOf(mint)
                setOnCheckedChangeListener { _, enabled ->
                    repository.update(true) { state -> state.copy(
                        monitors = state.monitors.map { if (it.id == monitor.id) it.copy(enabled = enabled) else it },
                        alarms = if (!enabled) state.alarms - monitor.id else state.alarms
                    ) }
                }
            })
            card.addView(title)
            card.addView(text(monitor.url, 12, muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }.spaced(8))
            val priceDetail = status?.takeIf { it.checkedAt > 0 && it.failures == 0 }?.let {
                it.priceDetail.ifBlank { if (!monitor.usesKeyword) it.detail else "" }
            }?.takeIf { it.startsWith("Price ") && !it.startsWith("Price error:") }?.removePrefix("Price ")
            val priceRule = "Price at or below ${monitor.threshold}" + (priceDetail?.let { " - $it" } ?: "")
            val rule = when (monitor.trigger) {
                Trigger.KEYWORD_PRESENT -> "Keyword present • “${monitor.keyword}”"
                Trigger.KEYWORD_MISSING -> "Keyword missing • “${monitor.keyword}”"
                Trigger.PRICE -> priceRule
            }
            val keywordResult = monitor.usesKeyword && status != null && status.checkedAt > 0 && status.failures == 0 &&
                (status.keywordMatched != null || (!monitor.usesPrice && status.detail.startsWith("Keyword “")))
            val keywordMatched = status?.keywordMatched ?: status?.matched
            val outcome = if (keywordResult) if (keywordMatched == true) " ✓" else " ✗" else ""
            card.addView(text(rule + outcome, 14, mint, true).spaced(8))
            if (monitor.usesKeyword && monitor.usesPrice) {
                card.addView(text(priceRule, 14, mint, true).spaced(6))
                card.addView(text(if (monitor.requireAllConditions) "Match both conditions" else "Match either condition", 12, muted).spaced(4))
            }
            val rotates = monitor.alternateNetworks || monitor.alternateIpFamilies
            val connection = if (rotates) {
                status?.connection?.ifBlank { null }
                    ?: if (monitor.alternateNetworks) "Wi-Fi / mobile" else "Wi-Fi IPv4 / IPv6"
            } else null
            val fallbackFamily = if (status?.ipFallback == true && connection != null)
                IpFamily.entries.firstOrNull { connection.endsWith(" • ${it.label}") } else null
            val displayedConnection = if (fallbackFamily != null && connection != null)
                connection.removeSuffix(fallbackFamily.label) + "Fallback to ${fallbackFamily.label}" else connection
            val connectionLine = "Interval  ${interval(monitor)}" + (displayedConnection?.let { " • $it" } ?: "")
            card.addView(text(connectionLine, 12, muted).apply {
                if (fallbackFamily != null) {
                    text = SpannableString(connectionLine).apply {
                        setSpan(ForegroundColorSpan(red), length - fallbackFamily.label.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }.spaced(8))
            if (rotates) {
                if (monitor.alternateNetworks && networkNote.isNotBlank()) card.addView(text(networkNote, 12, amber).spaced(4))
                if (!status?.routeNote.isNullOrBlank()) card.addView(text(status!!.routeNote, 12, muted).spaced(4))
            }
            val detail = when {
                status == null -> "Ready for first check"
                status.failures > 0 -> status.detail
                monitor.usesKeyword && !keywordResult -> status.keywordDetail.ifBlank { status.detail }
                monitor.usesPrice && priceDetail == null -> status.priceDetail.ifBlank { status.detail }
                else -> null
            }
            if (!detail.isNullOrBlank()) card.addView(text(detail, 14,
                if ((status?.failures ?: 0) > 0 || detail.contains("error:", true)) amber else if (status?.matched == true) mint else white).spaced(8))
            val clock = text("", 12, muted).spaced(8)
            timeLabels += monitor.id to clock
            card.addView(clock)
            val actions = row()
            actions.addView(button("Edit", false) { editor(monitor) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(button("Duplicate", false) {
                editor(monitor.copy(id = java.util.UUID.randomUUID().toString(), name = "${monitor.name} (copy)"), isNew = true)
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(button("Open page", false) {
                BrowserLinks.open(this, monitor.url)
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(button("Delete", false) {
                AlertDialog.Builder(this).setTitle("Delete ${monitor.name}?")
                    .setMessage("This removes the monitor and disarms its active alert.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> repository.delete(monitor.id) }.show()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            val dividerColor = Color.rgb(48, 70, 83)
            actions.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            actions.dividerDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(dividerColor)
                setSize(dp(1), dp(48))
            }
            for (index in 0 until actions.childCount) {
                (actions.getChildAt(index) as Button).apply {
                    maxLines = 1
                    minWidth = 0; minimumWidth = 0
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (index == actions.childCount - 1) red else mint)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    setAutoSizeTextTypeUniformWithConfiguration(11, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    background = android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(Color.argb(45, 113, 226, 188)),
                        android.graphics.drawable.ColorDrawable(Color.rgb(29, 47, 58)),
                        android.graphics.drawable.ColorDrawable(Color.WHITE)
                    )
                }
            }
            watchdogCard.addView(View(this).apply { setBackgroundColor(dividerColor) }, LinearLayout.LayoutParams(-1, dp(1)))
            watchdogCard.addView(actions, LinearLayout.LayoutParams(-1, dp(48)))
            content.addView(watchdogCard.spaced(12))
        }
    }

    private fun history(state: AppState) {
        content.addView(text("Activity", 23, white, true).spaced())
        content.addView(text("Latest 100 alerts and HTTP errors.", 13, muted).spaced())
        if (state.history.isNotEmpty()) content.addView(settingsButton("Clear activity") {
            AlertDialog.Builder(this).setTitle("Clear activity?").setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ -> repository.update(true) { it.copy(history = emptyList()) } }.show()
        }.spaced())
        if (state.history.isEmpty()) content.addView(text("No activity yet.", 17, muted).spaced(24))
        state.history.forEach { event ->
            val box = card()
            box.addView(text(if (event.kind == EventKind.HTTP_ERROR) "HTTP error • ${event.name}" else event.name, 18, white, true))
            box.addView(text(event.detail, 14, if (event.kind == EventKind.HTTP_ERROR) amber else mint).spaced(8))
            if (event.kind == EventKind.HTTP_ERROR) {
                box.addView(text("Connection: ${event.connection.ifBlank { "Not recorded" }}", 13, muted).spaced(8))
            }
            box.addView(text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(event.time)), 12, muted).spaced(8))
            content.addView(box.spaced())
        }
    }

    private fun settings() {
        content.addView(text("Settings", 23, white, true).spaced())
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val notifications = notificationsReady()
        fun section(title: String) = card().also {
            it.addView(text(title, 19, white, true))
            content.addView(it.spaced())
        }
        val reliability = section("Background setup")
        reliability.addView(text("${if (exempt) "✓" else "○"}  Battery optimization ${if (exempt) "disabled" else "enabled"}", 14, if (exempt) mint else amber).spaced())
        reliability.addView(settingsButton("Battery details (Akkudetails)") { openBatteryDetails() }.spaced())
        reliability.addView(settingsButton("Background settings", !exempt) {
            openBatterySettings()
        }.spaced())
        val appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val notices = section("Notifications")
        notices.addView(text("${if (notifications) "✓" else "○"}  Notifications ${if (notifications) "enabled" else "need permission"}", 14, if (notifications) mint else amber).spaced())
        notices.addView(settingsButton("Notification settings") {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }.spaced())
        notices.addView(settingsButton("Monitoring notification settings") { openChannelSettings(MonitorService.STATUS_CHANNEL) }.spaced(8))
        notices.addView(settingsButton("Alarm notification settings") { openChannelSettings(MonitorService.ALARM_CHANNEL) }.spaced(8))
        notices.addView(settingsButton("HTTP error notification settings") { openChannelSettings(MonitorService.HTTP_CHANNEL) }.spaced(8))
        notices.addView(text("Allow lock-screen notifications in your phone settings.", 13, muted).spaced())
        val sound = section("Alarm sound")
        sound.addView(text("Uses your phone's alarm sound and volume.", 13, muted).spaced())
        sound.addView(settingsButton("Test looping alarm", true) { withNotifications(MonitorService.ACTION_TEST) }.spaced())
        sound.addView(settingsButton("Alarm volume settings") { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }.spaced(8))
        val diagnostics = section("Diagnostics")
        diagnostics.addView(settingsButton("Network diagnostics") { showNetworkDiagnostics() }.spaced())
        CrashReports.read(this)?.let { report ->
            diagnostics.addView(settingsButton("Copy last crash report") {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("PageAlert Live crash", report))
                toast("Crash report copied.")
            }.spaced())
        }
        val privacy = section("Privacy & legal")
        privacy.addView(text("No data is collected by the developer. No analytics or tracking. Settings and activity stay on your device.", 13, muted).spaced())
        privacy.addView(settingsButton("Privacy policy / Datenschutzerklärung") { showPrivacyPolicy() }.spaced())
        privacy.addView(settingsButton("Impressum") { showImpressum() }.spaced(8))
        content.addView(text("${getString(R.string.app_name)} $appVersion", 12, muted).spaced())
    }

    private fun networkDiagnosticsReport(): String {
        val state = repository.state.value
        val appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        return buildString {
            appendLine("${getString(R.string.app_name)} $appVersion • $packageName")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Current Wi-Fi status:")
            appendLine(DeviceNetworkState.wifiDetails(this@MainActivity))
            state.monitors.forEach { monitor ->
                appendLine()
                appendLine("Watchdog: ${monitor.name}")
                appendLine("Host: ${monitor.url.toHttpUrlOrNull()?.host ?: "invalid URL"}")
                appendLine("Network rotation: ${monitor.alternateNetworks} • IP rotation: ${monitor.alternateIpFamilies}")
                val status = state.statuses[monitor.id]
                appendLine("Last check:")
                appendLine(status?.connectionDebug?.report() ?: "No connection debug recorded yet; wait for the next check.")
                if (status?.lastIpFallback != null && status.lastIpFallback != status.connectionDebug) {
                    appendLine("Last IP fallback (retained after later checks):")
                    appendLine(status.lastIpFallback.report())
                } else if (status?.ipFallback == true && status.connectionDebug == null) {
                    appendLine("Fallback reason was not recorded by the older build; wait for the next fallback.")
                }
            }
        }.trimEnd()
    }

    private fun showNetworkDiagnostics() {
        if (networkDiagnosticsDialog?.isShowing == true) return
        val body = column(16)
        body.addView(text("Fallback to IPv4 means the IPv6 attempt failed. Fallback to IPv6 means the IPv4 attempt failed.", 13, muted))
        val report = text(networkDiagnosticsReport(), 12, muted).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
        }
        body.addView(settingsButton("Refresh network info") { report.text = networkDiagnosticsReport() }.spaced())
        body.addView(settingsButton("Copy network debug info") {
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("PageAlert Live network debug", report.text))
            toast("Network debug info copied.")
        }.spaced(8))
        body.addView(report.spaced())
        val dialog = AlertDialog.Builder(this).setTitle("Network diagnostics")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Close", null).create()
        networkDiagnosticsDialog = dialog
        dialog.setOnDismissListener { networkDiagnosticsDialog = null }
        dialog.show()
        dialog.window?.setBackgroundDrawable(shape(panel, 24))
    }

    private fun showPrivacyPolicy() {
        val policy = assets.open("privacy-policy.txt").bufferedReader().use { it.readText() }
        AlertDialog.Builder(this).setTitle("Privacy policy / Datenschutzerklärung")
            .setMessage(policy).setPositiveButton("Close", null).show()
    }

    private fun showImpressum() {
        val imprint = assets.open("impressum.txt").bufferedReader().use { it.readText() }
        AlertDialog.Builder(this).setTitle("Impressum")
            .setMessage(imprint).setPositiveButton("Close", null).show()
            .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    private fun editor(existing: Monitor?, isNew: Boolean = existing == null) {
        val form = column(16).apply { isFocusableInTouchMode = true }
        fun section(title: String? = null) = card().also { section ->
            title?.let { section.addView(text(it, 16, white, true)) }
            form.addView(section.spaced(12))
        }
        val website = section("Website")
        val name = field(website, "Name", "e.g. Headphones restock", existing?.name.orEmpty())
        val url = field(website, "URL", "https://example.com/product", existing?.url.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val keywordSection = section()
        val keywordEnabled = editorCheck("Check keyword", existing?.usesKeyword ?: true)
        keywordSection.addView(keywordEnabled)
        val keywords = column(0)
        val trigger = spinner(listOf("Keyword is present", "Keyword is missing"), if (existing?.trigger == Trigger.KEYWORD_MISSING) 1 else 0)
        keywords.addView(trigger)
        val keyword = field(keywords, "Keyword or phrase", "e.g. In stock", existing?.keyword.orEmpty())
        val sensitive = editorCheck(getString(R.string.case_sensitive), existing?.caseSensitive ?: false)
        keywords.addView(sensitive)
        keywordSection.addView(keywords)
        val priceSection = section()
        val priceEnabled = editorCheck("Check price", existing?.usesPrice ?: false)
        priceSection.addView(priceEnabled)
        val prices = column(0)
        val threshold = field(prices, "Price at or below", "e.g. 99.99", existing?.threshold.orEmpty(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        prices.addView(text("Price number format", 13, muted).spaced())
        val numberFormat = spinner(listOf("Auto-detect", "Dot decimal: 1,234.56", "Comma decimal: 1.234,56"), existing?.numberFormat?.ordinal ?: 0)
        prices.addView(numberFormat)
        val priceSelector = field(prices, "Price CSS selector (optional)", "Choose a price below or enter a selector", existing?.effectivePriceSelector.orEmpty())
        val pricePreview = text("", 12, muted).spaced(6)
        var lookupJob: Job? = null
        var pricePicker: AlertDialog? = null
        lateinit var findPrice: Button
        findPrice = button("Find prices on page", true) {
            val target = url.text.toString().trim()
            val parsed = target.toHttpUrlOrNull()
            if (parsed == null || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
                pricePreview.text = "Enter a valid public HTTP or HTTPS URL first."
                return@button
            }
            findPrice.isEnabled = false
            pricePreview.text = "Loading prices…"
            lookupJob = scope.launch {
                val crawler = Crawler()
                try {
                    val candidates = crawler.findPrices(Monitor(name = "Price preview", url = target, numberFormat = NumberFormat.entries[numberFormat.selectedItemPosition]))
                    if (url.text.toString().trim() != target) { pricePreview.text = "URL changed. Load prices again."; return@launch }
                    if (candidates.isEmpty()) { pricePreview.text = "No price elements found. The page may require JavaScript or block automated access."; return@launch }
                    pricePreview.text = "${candidates.size} price elements found. Choose the product price."
                    val picker = AlertDialog.Builder(this@MainActivity).setTitle("Choose price element")
                        .setAdapter(PriceCandidateAdapter(this@MainActivity, candidates)) { _, index ->
                            if (url.text.toString().trim() == target) {
                                val candidate = candidates[index]
                                priceSelector.setText(candidate.selector)
                                numberFormat.setSelection(candidate.numberFormat.ordinal)
                                pricePreview.text = "Selected price: ${candidate.price.toPlainString()}"
                            } else pricePreview.text = "URL changed. Load prices again."
                        }.setNegativeButton("Cancel", null).create()
                    picker.show()
                    picker.listView.apply {
                        divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                        dividerHeight = dp(12)
                        setPadding(dp(16), dp(10), dp(16), dp(10))
                        clipToPadding = false
                    }
                    pricePicker = picker
                    picker.setOnDismissListener { pricePicker = null }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { pricePreview.text = failure.message ?: "Could not load prices." }
                finally { crawler.close(); findPrice.isEnabled = true }
            }
        }
        prices.addView(findPrice.spaced(6))
        prices.addView(pricePreview)
        priceSection.addView(prices)
        val requireBoth = editorCheck("Require both conditions to match",
            existing?.takeIf { it.usesKeyword && it.usesPrice }?.requireAllConditions ?: true)
        form.addView(requireBoth.spaced(6))
        fun updateConditions() {
            keywords.visibility = if (keywordEnabled.isChecked) View.VISIBLE else View.GONE
            prices.visibility = if (priceEnabled.isChecked) View.VISIBLE else View.GONE
            requireBoth.visibility = if (keywordEnabled.isChecked && priceEnabled.isChecked) View.VISIBLE else View.GONE
        }
        keywordEnabled.setOnCheckedChangeListener { _, _ -> updateConditions() }
        priceEnabled.setOnCheckedChangeListener { _, _ -> updateConditions() }
        updateConditions()
        val intervalSection = section("Interval · seconds")
        val initialMin = existing?.minSeconds ?: 60
        val initialMax = existing?.maxSeconds ?: 120
        val range = row()
        val left = column(0)
        val minimum = field(left, "Minimum", "60", initialMin.toString(), InputType.TYPE_CLASS_NUMBER)
        val right = column(0)
        val maximum = field(right, "Maximum", "120", initialMax.toString(), InputType.TYPE_CLASS_NUMBER)
        range.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        range.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        intervalSection.addView(range)
        val connections = section("Connections")
        val alternate = editorCheck("Alternate Wi-Fi & mobile data", existing?.alternateNetworks ?: false)
        connections.addView(alternate.spaced(8))
        val ipFamilies = editorCheck("Alternate IPv4 & IPv6 (Wi-Fi)", existing?.alternateIpFamilies ?: true)
        connections.addView(ipFamilies)
        val ipAvailability = text("", 12, muted).spaced(4)
        connections.addView(ipAvailability)
        fun refreshAvailability() {
            val support = DeviceNetworkState.wifiSupport(this)
            ipAvailability.text = when {
                DeviceNetworkState.read(this).vpnActive -> "VPN active · IP rotation paused"
                support.dualStack -> "Wi-Fi: IPv4 + IPv6"
                support.ipv4 -> "Wi-Fi: IPv4"
                support.ipv6 -> "Wi-Fi: IPv6"
                else -> "Wi-Fi unavailable"
            }
        }
        refreshAvailability()
        val errors = text("", 14, red).spaced()
        form.addView(errors)
        val editorScroll = ScrollView(this).apply { addView(form); isFillViewport = true; clipToPadding = false }
        val title = text(if (isNew) "New watchdog" else "Edit watchdog", 23, white, true).apply {
            setPadding(dp(24), dp(24), dp(24), dp(8))
        }
        val dialog = AlertDialog.Builder(this).setCustomTitle(title)
            .setView(editorScroll).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        editorDialog = dialog
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun changed() { runOnUiThread { if (dialog.isShowing) refreshAvailability() } }
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = changed()
        }
        var observingNetwork = false
        dialog.setOnDismissListener {
            lookupJob?.cancel()
            pricePicker?.dismiss()
            if (observingNetwork) {
                try { connectivity.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) { }
            }
            refreshEditorNetwork = null
            editorDialog = null
        }
        dialog.setOnShowListener {
            refreshEditorNetwork = ::refreshAvailability
            try {
                connectivity.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
                observingNetwork = true
            } catch (_: RuntimeException) { /* Refresh on resume remains available on restrictive devices. */ }
            refreshAvailability()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!keywordEnabled.isChecked && !priceEnabled.isChecked) {
                    errors.text = "Enable keyword, price, or both."
                    return@setOnClickListener
                }
                val minValue = minimum.text.toString().toLongOrNull()
                val maxValue = maximum.text.toString().toLongOrNull()
                if (minValue == null || maxValue == null || minValue !in 1..86400 || maxValue !in 1..86400) {
                    errors.setText(R.string.invalid_interval); return@setOnClickListener
                }
                val monitor = Monitor(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name.text.toString().trim(), url = url.text.toString().trim(),
                    trigger = if (!keywordEnabled.isChecked) Trigger.PRICE else if (trigger.selectedItemPosition == 1) Trigger.KEYWORD_MISSING else Trigger.KEYWORD_PRESENT, keyword = keyword.text.toString(),
                    // Preserve legacy keyword scopes when editing; new keyword checks use the whole page.
                    caseSensitive = sensitive.isChecked, selector = if (keywordEnabled.isChecked) existing?.takeIf { it.usesKeyword }?.selector.orEmpty() else priceSelector.text.toString().trim(),
                    threshold = threshold.text.toString().trim().replace(',', '.'), numberFormat = NumberFormat.entries[numberFormat.selectedItemPosition],
                    minSeconds = minValue, maxSeconds = maxValue, enabled = existing?.enabled ?: true,
                    alternateNetworks = alternate.isChecked,
                    alternateIpFamilies = ipFamilies.isChecked,
                    checkPrice = priceEnabled.isChecked, priceSelector = priceSelector.text.toString().trim(),
                    requireAllConditions = requireBoth.isChecked
                )
                val error = monitor.validate()
                if (error != null) { errors.text = error; return@setOnClickListener }
                fun saveMonitor() {
                    try { repository.save(monitor); dialog.dismiss() }
                    catch (failure: Exception) { errors.text = failure.message ?: "Could not save monitor." }
                }
                if (isNew || monitor.url != existing?.url) {
                    termsDialog = AlertDialog.Builder(this).setTitle("Website terms")
                        .setMessage("By choosing Confirm & save, you confirm that your automated checks comply with this website's terms of service (AGB) and that you are permitted to monitor this URL.\n\n${monitor.url}")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Confirm & save") { _, _ -> saveMonitor() }
                        .create().also { confirmation ->
                            confirmation.setOnDismissListener { termsDialog = null }
                            confirmation.show()
                        }
                } else saveMonitor()
            }
            listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE).forEach { which ->
                dialog.getButton(which).apply {
                    val primary = which == AlertDialog.BUTTON_POSITIVE
                    isAllCaps = false; textSize = 15f
                    setTextColor(if (primary) bg else mint)
                    background = shape(if (primary) mint else panel, 12)
                    minWidth = dp(92); minimumWidth = dp(92)
                    minHeight = dp(48); minimumHeight = dp(48)
                    (layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.marginStart = dp(8) }
                }
            }
            dialog.window?.apply {
                setBackgroundDrawable(shape(bg, 24))
                setLayout(resources.displayMetrics.widthPixels - dp(24), (resources.displayMetrics.heightPixels * 0.9).toInt())
                setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        dialog.show()
    }

    private fun updateTimes() {
        val state = repository.state.value
        val now = System.currentTimeMillis()
        timeLabels.forEach { (id, label) ->
            val monitor = state.monitors.find { it.id == id }
            val status = state.statuses[id]
            label.text = when {
                monitor?.enabled != true -> "Disabled"
                !state.running || !state.serviceAlive -> if (status?.checkedAt != null && status.checkedAt > 0) "Last checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.checkedAt))} • Paused" else "Paused"
                status == null -> "First check in progress…"
                status.nextAt <= now -> "Checking… • ${status.checks} checks"
                else -> "Next check in ${((status.nextAt - now + 999) / 1000)}s • ${status.checks} checks"
            }
        }
    }

    private fun interval(m: Monitor): String = "${m.minSeconds}–${m.maxSeconds} s"

    private fun notificationsReady(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && listOf(MonitorService.STATUS_CHANNEL, MonitorService.ALARM_CHANNEL).all {
            manager.getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }

    private fun withNotifications(command: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingCommand = command
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        } else if (!notificationsReady()) {
            toast("Enable monitoring and alarm notifications in Settings first.")
            tab = 2; render(repository.state.value)
        } else dispatch(command)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            val command = pendingCommand
            pendingCommand = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && command != null) withNotifications(command)
            else { toast("Notifications are needed for persistent status and alarm controls."); tab = 2; render(repository.state.value) }
        }
    }

    private fun dispatch(command: String) {
        try { MonitorService.send(this, command) }
        catch (error: Exception) { toast("Could not start the service: ${error.message}") }
    }
    private fun openAppSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    private fun openBatteryDetails() {
        // HyperOS usage details include CPU/data/battery usage and the per-app
        // battery policy. PowerKeeper opens a different, older policy screen.
        // Try directly: package visibility can hide valid Settings activities.
        val destinations = listOf(
            Intent().setClassName("com.miui.securitycenter", "com.miui.powercenter.legacypowerrank.PowerDetailActivity")
                .putExtra("package_name", packageName)
                .putExtra("package_label", getString(R.string.app_name))
                .putExtra("uid", applicationInfo.uid),
            Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
                .setClassName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                .putExtra("package_name", packageName)
                .putExtra("package_label", getString(R.string.app_name)),
            Intent("android.settings.APP_BATTERY_SETTINGS", "package:$packageName".toUri())
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", "package:$packageName".toUri())
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
        for (destination in destinations) {
            try {
                startActivity(destination)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        openAppSettings()
        toast("Open Battery / Akkudetails, then choose Unrestricted / Keine Beschränkungen.")
    }
    private fun openBatterySettings() {
        // Open this app's background policy, including when already exempt.
        // The optimization allowlist is a different, app-list screen.
        val destinations = listOf(
            Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
                .setClassName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                .putExtra("package_name", packageName)
                .putExtra("package_label", getString(R.string.app_name)),
            Intent("android.settings.APP_BATTERY_SETTINGS", "package:$packageName".toUri())
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", "package:$packageName".toUri())
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
        for (destination in destinations) {
            try {
                startActivity(destination)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        openAppSettings()
        toast("Open this app's Battery / Background settings and allow unrestricted background use.")
    }
    private fun openChannelSettings(channel: String) {
        try {
            startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel))
        } catch (_: Exception) { openAppSettings() }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun column(padding: Int) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding / 2), dp(padding), dp(padding / 2)) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun card(color: Int = panel) = column(18).apply { background = shape(color, 16); setPadding(dp(18), dp(18), dp(18), dp(14)) }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(label: String, filled: Boolean, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        setTextColor(if (filled) bg else mint)
        background = shape(if (filled) mint else Color.TRANSPARENT, 12)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        minHeight = dp(44); minimumHeight = dp(44)
        setOnClickListener { click() }
    }
    private fun settingsButton(label: String, primary: Boolean = false, click: () -> Unit) = button(label, primary, click).apply {
        if (!primary) {
            background = shape(bg, 12).apply { setStroke(dp(1), Color.rgb(51, 75, 89)) }
            setTextColor(white)
        }
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minHeight = dp(52); minimumHeight = dp(52)
    }
    private fun <T : View> T.spaced(top: Int = 12): T = apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    }
    private fun field(parent: LinearLayout, label: String, hintText: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        parent.addView(text(label, 13, muted).spaced())
        return EditText(this).apply {
            hint = hintText; setText(value); setTextColor(white); setHintTextColor(muted)
            textSize = 16f; inputType = type; isSingleLine = true
            setPadding(dp(14), dp(13), dp(14), dp(13)); background = shape(bg, 12)
            parent.addView(this.spaced(6))
        }
    }
    private fun spinner(items: List<String>, selected: Int) = Spinner(this).apply {
        adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, items) {
            private fun label(position: Int, dropdown: Boolean) = text(getItem(position).orEmpty() + if (dropdown) "" else "  ▾", 15, white).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                minimumHeight = dp(48)
                if (dropdown) setBackgroundColor(panel)
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = label(position, false)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = label(position, true)
        }
        background = shape(bg, 12)
        setSelection(selected); minimumHeight = dp(48)
    }
    private fun editorCheck(label: String, checked: Boolean) = CheckBox(this).apply {
        text = label; textSize = 15f; setTextColor(white); isChecked = checked
        buttonTintList = ColorStateList.valueOf(mint)
        minHeight = dp(48); minimumHeight = dp(48)
        setPadding(0, dp(4), 0, dp(4))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
}
