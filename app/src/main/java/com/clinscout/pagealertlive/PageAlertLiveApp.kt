package com.clinscout.pagealertlive

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class PageAlertLiveApp : Application() {
    lateinit var repository: MonitorRepository
        private set
    override fun onCreate() {
        super.onCreate()
        CrashReports.install(this)
        repository = MonitorRepository(this)
        repository.onProcessStarted()
    }
}

val Context.repository: MonitorRepository
    get() = (applicationContext as PageAlertLiveApp).repository

class MonitorRepository(context: Context) {
    private val preferences = context.getSharedPreferences("pagealert_live", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    @Synchronized
    fun update(durable: Boolean = false, transform: (AppState) -> AppState) {
        val next = transform(mutable.value)
        if (next == mutable.value) return
        val editor = preferences.edit().putString("state", serialize(next).toString())
        if (durable) check(editor.commit()) { "Unable to save monitor settings." } else editor.apply()
        mutable.value = next
    }

    fun save(monitor: Monitor) {
        require(monitor.validate() == null) { monitor.validate().orEmpty() }
        update(true) { state -> state.copy(
            monitors = if (state.monitors.any { it.id == monitor.id })
                state.monitors.map { if (it.id == monitor.id) monitor else it }
                else state.monitors + monitor,
            statuses = state.statuses - monitor.id, alarms = state.alarms - monitor.id
        ) }
    }

    fun delete(id: String) = update(true) {
        it.copy(monitors = it.monitors.filterNot { m -> m.id == id }, statuses = it.statuses - id, alarms = it.alarms - id)
    }

    fun acknowledge() = update(true) { it.copy(alarms = emptyMap(), alarmError = null) }

    // A process restart with monitoring still requested means the previous
    // process ended without a user Stop. Closing only the Activity does not call this.
    fun onProcessStarted() {
        if (state.value.running && state.value.monitors.any { it.enabled }) {
            update(true) { it.copy(interruption = lastInterruption(it)) }
        }
    }

    fun heartbeat(now: Long = System.currentTimeMillis()) {
        val current = state.value
        if (current.running && current.serviceAlive && current.monitors.any { it.enabled }) {
            preferences.edit().putLong("last_running_at", now).apply()
        }
    }

    fun onServiceStopped() = update { current -> current.copy(
        serviceAlive = false,
        interruption = if (current.running && current.serviceAlive && current.monitors.any { it.enabled })
            current.interruption ?: lastInterruption(current) else current.interruption
    ) }

    private fun lastInterruption(current: AppState) = MonitoringInterruption(
        preferences.getLong("last_running_at", current.statuses.values.maxOfOrNull { it.checkedAt } ?: 0)
    )

    fun waitForNetwork(monitor: Monitor, connection: String, dueAt: Long) = update { current ->
        if (!current.running || current.monitors.none { it == monitor && it.enabled }) current else {
            val status = current.statuses[monitor.id] ?: MonitorStatus()
            current.copy(statuses = current.statuses + (monitor.id to status.copy(
                detail = "Waiting for $connection", connection = connection, nextAt = dueAt, routeNote = "", ipFallback = false)))
        }
    }

    fun skipNetworkTurn(monitor: Monitor, connection: String, nextTurn: Long) = update { current ->
        if (!current.running || current.monitors.none { it == monitor && it.enabled }) current else {
            val status = current.statuses[monitor.id] ?: MonitorStatus()
            current.copy(statuses = current.statuses + (monitor.id to status.copy(
                detail = "Skipped $connection check (connection unavailable).", connection = connection,
                networkTurn = nextTurn, nextAt = System.currentTimeMillis(), routeNote = "", ipFallback = false)))
        }
    }

    fun record(monitor: Monitor, result: CheckResult?, error: String?, nextAt: Long, httpStatus: Int? = null, connection: String = "", networkTurn: Long? = null, wifiIpTurn: Long? = null, routeNote: String = "", ipFallback: Boolean = false): AlertEvent? {
        var httpEvent: AlertEvent? = null
        update(result?.matched == true || httpStatus != null) { state ->
            val next = AlertReducer.record(state, monitor, result, error, System.currentTimeMillis(), nextAt, httpStatus, connection, networkTurn, wifiIpTurn, routeNote, ipFallback)
            httpEvent = next.history.firstOrNull()?.takeIf { it.kind == EventKind.HTTP_ERROR && it !== state.history.firstOrNull() }
            next
        }
        return httpEvent
    }

    private fun load(): AppState = try {
        val root = JSONObject(preferences.getString("state", "{}")!!)
        val monitors = root.optJSONArray("monitors").objects().map { item -> Monitor(
            id = item.getString("id"), name = item.getString("name"), url = item.getString("url"),
            trigger = Trigger.valueOf(item.getString("trigger")), keyword = item.optString("keyword"),
            caseSensitive = item.optBoolean("caseSensitive"), selector = item.optString("selector"),
            threshold = item.optString("threshold"), numberFormat = NumberFormat.valueOf(item.optString("numberFormat", "AUTO")),
            minSeconds = item.getLong("minSeconds"), maxSeconds = item.getLong("maxSeconds"), enabled = item.optBoolean("enabled", true),
            alternateNetworks = item.optBoolean("alternateNetworks", false), alternateIpFamilies = item.optBoolean("alternateIpFamilies", true),
            checkPrice = item.optBoolean("checkPrice"), priceSelector = item.optString("priceSelector"), requireAllConditions = item.optBoolean("requireAllConditions")
        ) }
        val statuses = root.optJSONArray("statuses").objects().associate {
            it.getString("id") to MonitorStatus(it.optLong("checkedAt"), it.optLong("nextAt"), it.optString("detail"), it.optInt("failures"), it.optBoolean("matched"), it.optLong("checks"), it.optInt("httpStatus").takeIf { code -> code > 0 }, it.optString("connection"), it.optLong("networkTurn"), it.optLong("wifiIpTurn"), it.optString("routeNote"),
                if (it.has("keywordMatched") && !it.isNull("keywordMatched")) it.getBoolean("keywordMatched") else null,
                if (it.has("priceMatched") && !it.isNull("priceMatched")) it.getBoolean("priceMatched") else null,
                it.optString("keywordDetail"), it.optString("priceDetail"), it.optBoolean("ipFallback"))
        }
        AppState(monitors, statuses,
            root.optJSONArray("alarms").objects().map(::event).associateBy { it.monitorId },
            root.optJSONArray("history").objects().map(::event), root.optBoolean("running"),
            interruption = root.optJSONObject("interruption")?.let { MonitoringInterruption(it.optLong("lastRunningAt")) })
    } catch (error: Exception) {
        android.util.Log.e("PageAlertLive", "Could not load saved state", error)
        AppState(alarmError = "Saved settings could not be read. Please recreate your monitors.")
    }

    private fun event(item: JSONObject) = AlertEvent(item.getLong("time"), item.getString("id"), item.getString("name"), item.getString("detail"), EventKind.valueOf(item.optString("kind", "ALERT")), item.optString("connection"))
    private fun eventJson(event: AlertEvent) = JSONObject().put("time", event.time).put("id", event.monitorId).put("name", event.name).put("detail", event.detail).put("kind", event.kind.name).put("connection", event.connection)

    private fun serialize(state: AppState): JSONObject = JSONObject()
        .put("running", state.running)
        .put("interruption", state.interruption?.let { JSONObject().put("lastRunningAt", it.lastRunningAt) })
        .put("monitors", JSONArray(state.monitors.map { m -> JSONObject()
            .put("id", m.id).put("name", m.name).put("url", m.url).put("trigger", m.trigger.name)
            .put("keyword", m.keyword).put("caseSensitive", m.caseSensitive).put("selector", m.selector)
            .put("threshold", m.threshold).put("numberFormat", m.numberFormat.name)
            .put("minSeconds", m.minSeconds).put("maxSeconds", m.maxSeconds).put("enabled", m.enabled)
            .put("alternateNetworks", m.alternateNetworks)
            .put("alternateIpFamilies", m.alternateIpFamilies)
            .put("checkPrice", m.checkPrice).put("priceSelector", m.priceSelector).put("requireAllConditions", m.requireAllConditions)
        }))
        .put("statuses", JSONArray(state.statuses.map { (id, s) -> JSONObject()
            .put("id", id).put("checkedAt", s.checkedAt).put("nextAt", s.nextAt)
            .put("detail", s.detail).put("failures", s.failures).put("matched", s.matched).put("checks", s.checks).put("httpStatus", s.httpStatus)
            .put("connection", s.connection).put("networkTurn", s.networkTurn)
            .put("wifiIpTurn", s.wifiIpTurn).put("routeNote", s.routeNote)
            .put("ipFallback", s.ipFallback)
            .put("keywordMatched", s.keywordMatched).put("priceMatched", s.priceMatched)
            .put("keywordDetail", s.keywordDetail).put("priceDetail", s.priceDetail)
        }))
        .put("alarms", JSONArray(state.alarms.values.map(::eventJson)))
        .put("history", JSONArray(state.history.map(::eventJson)))

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
