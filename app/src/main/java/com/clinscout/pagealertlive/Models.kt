package com.clinscout.pagealertlive

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.math.BigDecimal
import java.util.UUID
import java.util.Locale
import kotlin.random.Random

enum class Trigger { KEYWORD_PRESENT, KEYWORD_MISSING, PRICE }
enum class NumberFormat { AUTO, DOT_DECIMAL, COMMA_DECIMAL }

data class Monitor(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val trigger: Trigger = Trigger.KEYWORD_PRESENT,
    val keyword: String = "",
    val caseSensitive: Boolean = false,
    val selector: String = "",
    val threshold: String = "",
    val numberFormat: NumberFormat = NumberFormat.AUTO,
    val minSeconds: Long = 60,
    val maxSeconds: Long = 120,
    val enabled: Boolean = true,
    val alternateNetworks: Boolean = false,
    val alternateIpFamilies: Boolean = true,
    val checkPrice: Boolean = false,
    val priceSelector: String = "",
    val requireAllConditions: Boolean = false
) {
    val usesKeyword get() = trigger != Trigger.PRICE
    val usesPrice get() = trigger == Trigger.PRICE || checkPrice
    val effectivePriceSelector get() = priceSelector.ifBlank { if (trigger == Trigger.PRICE) selector else "" }
    fun validate(): String? {
        if (name.isBlank()) return "Give this monitor a name."
        val parsed = url.toHttpUrlOrNull() ?: return "Enter a valid http:// or https:// URL."
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return "URLs containing credentials are not supported."
        if (minSeconds < 1 || maxSeconds < minSeconds || maxSeconds > 86400) return "Intervals must be 1–86,400 seconds, with maximum ≥ minimum."
        if (usesKeyword && keyword.isBlank()) return "Enter the keyword to watch."
        if (usesPrice && (threshold.toBigDecimalOrNull()?.let { it < BigDecimal.ZERO } != false)) return "Enter a non-negative target price, using a decimal point."
        val scopes = buildList { if (usesKeyword) add(selector); if (usesPrice) add(effectivePriceSelector) }
        for (scope in scopes.filter { it.isNotBlank() }) {
            try { Jsoup.parse("<html></html>").select(scope) }
            catch (_: RuntimeException) { return "The CSS selector is invalid." }
        }
        return null
    }

    fun randomDelayMillis(random: Random = Random.Default): Long =
        random.nextLong(minSeconds * 1000, maxSeconds * 1000 + 1)
}

data class CheckResult(val matched: Boolean, val detail: String, val keywordMatched: Boolean? = null,
    val priceMatched: Boolean? = null, val keywordDetail: String = "", val priceDetail: String = "")
data class MonitorStatus(
    val checkedAt: Long = 0,
    val nextAt: Long = 0,
    val detail: String = "Ready for first check",
    val failures: Int = 0,
    val matched: Boolean = false,
    val checks: Long = 0,
    val httpStatus: Int? = null,
    val connection: String = "",
    val networkTurn: Long = 0,
    val wifiIpTurn: Long = 0,
    val routeNote: String = "",
    val keywordMatched: Boolean? = null,
    val priceMatched: Boolean? = null,
    val keywordDetail: String = "",
    val priceDetail: String = "",
    val ipFallback: Boolean = false
)
enum class EventKind { ALERT, HTTP_ERROR }
data class AlertEvent(val time: Long, val monitorId: String, val name: String, val detail: String,
    val kind: EventKind = EventKind.ALERT, val connection: String = "")
data class MonitoringInterruption(val lastRunningAt: Long)
data class AppState(
    val monitors: List<Monitor> = emptyList(),
    val statuses: Map<String, MonitorStatus> = emptyMap(),
    val alarms: Map<String, AlertEvent> = emptyMap(),
    val history: List<AlertEvent> = emptyList(),
    val running: Boolean = false,
    val serviceAlive: Boolean = false,
    val alarmError: String? = null,
    val interruption: MonitoringInterruption? = null
)

object PriceParser {
    fun parse(text: String, format: NumberFormat = NumberFormat.AUTO): BigDecimal {
        val amounts = Regex("[+-]?\\d+(?:[.,'’\\u00a0\\u202f ]\\d+)*").findAll(text.trim()).toList()
        require(amounts.size == 1) { "Expected one price; narrow the CSS selector to the current price." }
        var value = amounts.single().value.replace(Regex("[ '’\\u00a0\\u202f]"), "")
        require(!value.startsWith('-')) { "Negative prices are not supported." }
        value = value.removePrefix("+")
        val decimal = when (format) {
            NumberFormat.DOT_DECIMAL -> '.'
            NumberFormat.COMMA_DECIMAL -> ','
            NumberFormat.AUTO -> when {
                value.contains('.') && value.contains(',') -> if (value.lastIndexOf('.') > value.lastIndexOf(',')) '.' else ','
                value.count { it == '.' || it == ',' } == 0 -> null
                else -> {
                    val sep = if (value.contains('.')) '.' else ','
                    require(value.substringAfterLast(sep).length != 3) { "Ambiguous price format; select dot or comma decimals." }
                    sep
                }
            }
        }
        val grouping = if (decimal == '.') ',' else '.'
        if (value.contains(grouping)) {
            val integerPart = if (decimal != null) value.substringBefore(decimal) else value
            require(Regex("\\d{1,3}(?:\\${grouping}\\d{3})+").matches(integerPart)) { "Invalid price grouping." }
            require(decimal == null || !value.substringAfter(decimal, "").contains(grouping)) { "Invalid price format." }
        }
        value = value.replace(grouping.toString(), "")
        if (decimal != null) value = value.replace(decimal, '.')
        require(Regex("\\d+(?:\\.\\d{1,4})?").matches(value)) { "Invalid price format." }
        return value.toBigDecimal()
    }
}

object TriggerEvaluator {
    fun evaluate(monitor: Monitor, html: String): CheckResult {
        val document = Jsoup.parse(html, monitor.url)
        document.select("script,style,noscript,template").remove()
        val bodyText = document.body().text()
        require(bodyText.isNotBlank() || document.select("meta[itemprop=price],meta[property=product:price:amount]").isNotEmpty()) { "Empty page; no trigger evaluated." }
        val challenge = document.title().lowercase()
        require(listOf("just a moment", "access denied", "verify you are human", "attention required").none { challenge.contains(it) }) { "Website returned an access challenge." }
        var keywordDetail = ""
        var priceDetail = ""
        val keywordResult = if (monitor.usesKeyword) runCatching {
            val elements = if (monitor.selector.isNotBlank()) document.select(monitor.selector) else null
            require(elements == null || elements.isNotEmpty()) { "CSS selector matched no elements." }
            val found = (elements?.text() ?: bodyText).contains(monitor.keyword, ignoreCase = !monitor.caseSensitive)
            keywordDetail = "Keyword “${monitor.keyword}” ${if (found) "found" else "missing"}"
            if (monitor.trigger == Trigger.KEYWORD_PRESENT) found else !found
        }.onFailure { keywordDetail = "Keyword error: ${it.message}" } else null
        val priceResult = if (monitor.usesPrice) runCatching {
            val selector = monitor.effectivePriceSelector
            val candidates = if (selector.isNotBlank()) document.select(selector) else
                document.select("meta[itemprop=price],meta[property=product:price:amount],meta[property=og:price:amount],[itemprop=price]")
            require(candidates.isNotEmpty()) { if (selector.isBlank()) "No structured price found. Use Find prices on page to choose a price element." else "Price CSS selector matched no elements." }
            val prices = candidates.map { PriceDiscovery.read(it, monitor.numberFormat) }.distinct()
            require(prices.size == 1) { "Multiple different prices found. Use Find prices on page to select one price." }
            val price = prices.single()
            val matched = price <= monitor.threshold.toBigDecimal()
            priceDetail = "Price ${price.toPlainString()} ${if (matched) "≤" else ">"} ${monitor.threshold}"
            matched
        }.onFailure { priceDetail = "Price error: ${it.message}" } else null
        val outcomes = listOfNotNull(keywordResult, priceResult)
        if (outcomes.none { it.isSuccess }) {
            throw (outcomes.firstOrNull()?.exceptionOrNull() ?: IllegalArgumentException("Enable at least one condition."))
        }
        val matched = if (monitor.requireAllConditions) outcomes.all { it.getOrNull() == true }
            else outcomes.any { it.getOrNull() == true }
        return CheckResult(matched, listOf(keywordDetail, priceDetail).filter { it.isNotBlank() }.joinToString(" • "),
            keywordResult?.getOrNull(), priceResult?.getOrNull(), keywordDetail, priceDetail)
    }
}

object AlertReducer {
    fun record(state: AppState, monitor: Monitor, result: CheckResult?, error: String?, now: Long, nextAt: Long, httpStatus: Int? = null, connection: String = "", networkTurn: Long? = null, wifiIpTurn: Long? = null, routeNote: String = "", ipFallback: Boolean = false): AppState {
        if (!state.running || state.monitors.none { it == monitor && it.enabled }) return state
        val previous = state.statuses[monitor.id] ?: MonitorStatus()
        val status = previous.copy(
            checkedAt = now, nextAt = nextAt, detail = error ?: requireNotNull(result).detail,
            failures = if (error == null) 0 else previous.failures + 1,
            matched = result?.matched ?: previous.matched, checks = previous.checks + 1,
            httpStatus = if (error != null) httpStatus else null,
            connection = connection, networkTurn = networkTurn ?: previous.networkTurn,
            wifiIpTurn = wifiIpTurn ?: previous.wifiIpTurn, routeNote = routeNote, ipFallback = ipFallback,
            keywordMatched = result?.keywordMatched, priceMatched = result?.priceMatched,
            keywordDetail = result?.keywordDetail.orEmpty(), priceDetail = result?.priceDetail.orEmpty()
        )
        // Disarm clears the active latch. A later matching check can alert again,
        // even when the page never stopped matching between acknowledgements.
        val fired = result?.matched == true && monitor.id !in state.alarms
        val newHttpError = status.httpStatus != null && status.httpStatus != previous.httpStatus
        val event = AlertEvent(now, monitor.id, monitor.name, status.detail,
            if (newHttpError) EventKind.HTTP_ERROR else EventKind.ALERT, connection.ifBlank { "System" })
        return state.copy(
            statuses = state.statuses + (monitor.id to status),
            alarms = if (fired) state.alarms + (monitor.id to event) else state.alarms,
            history = if (fired || newHttpError) (listOf(event) + state.history).take(100) else state.history
        )
    }
}

object WatchdogNotification {
    fun lines(state: AppState, now: Long): List<String> = state.monitors.map { monitor ->
        val status = state.statuses[monitor.id]
        val timer = when {
            !monitor.enabled -> "Disabled"
            !state.running -> "Paused"
            status == null || status.nextAt <= now -> "Checking…"
            else -> countdown(status.nextAt - now)
        }
        val alarm = if (monitor.id in state.alarms) " • ALARM" else ""
        "${monitor.name.replace(Regex("[\\r\\n\\t]+"), " ")} • $timer$alarm"
    }

    fun countdown(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(0) + 999) / 1000
        val hours = seconds / 3600
        val minutes = (seconds / 60) % 60
        return if (hours > 0) String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds % 60)
    }
}
