package com.clinscout.pagealertlive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

class CrawlException(message: String, val retryAfterMillis: Long = 0, val httpStatus: Int? = null) : IOException(message)

object HttpErrors {
    fun describe(code: Int, reason: String = ""): String {
        val description = when (code) {
            400 -> "Bad request (check URL)."
            401 -> "Sign-in required (use a public page)."
            403 -> "Access denied (site may block monitoring)."
            404 -> "Page not found (check URL)."
            405 -> "Request method not allowed (try another page)."
            408 -> "Server timed out (will retry)."
            410 -> "Page removed (update URL)."
            429 -> "Too many requests (increase check interval)."
            500 -> "Website error (will retry)."
            502 -> "Bad gateway (will retry)."
            503 -> "Website unavailable (will retry)."
            504 -> "Gateway timed out (will retry)."
            else -> reason.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(120).ifEmpty {
                when (code) {
                    in 300..399 -> "Redirect could not be followed (check URL)."
                    in 400..499 -> "Request rejected (check URL)."
                    in 500..599 -> "Website error (will retry)."
                    else -> "Unexpected response."
                }
            }
        }
        return "HTTP $code: $description"
    }
}

class Crawler(private val client: OkHttpClient = defaultClient()) {
    private val routedClients = linkedMapOf<String, OkHttpClient>()
    private var closed = false

    @Synchronized private fun clientFor(route: RequestRoute): OkHttpClient {
        check(!closed) { "Crawler stopped." }
        if (route.socketFactory == null) return client
        return routedClients.getOrPut(route.key) {
            if (routedClients.size >= 4) {
                val retired = routedClients.remove(routedClients.keys.first())!!
                cleanupScope.launch { retired.connectionPool.evictAll() }
            }
            client.newBuilder().socketFactory(route.socketFactory).dns(requireNotNull(route.dns))
                .addNetworkInterceptor { chain ->
                    route.family?.let { IpFamilies.requireLiteralFamily(chain.request().url.host, it) }
                    chain.proceed(chain.request())
                }
                // Never reuse a Wi-Fi connection for a mobile-data request.
                .connectionPool(ConnectionPool()).build()
        }
    }

    suspend fun check(monitor: Monitor, route: RequestRoute = RequestRoute(), onRouteChanged: (RequestRoute) -> Unit = {}): CheckResult {
        return try {
            fetch(monitor, route) { TriggerEvaluator.evaluate(monitor, it) }
        } catch (error: IOException) {
            // A server response (including Retry-After) is not a connection
            // failure. Only retry DNS/socket/TLS/read failures on the other family.
            if (error is CrawlException) throw error
            val fallback = route.fallback?.copy(ipFallback = true, fallbackReason = ConnectionDebug.failure(error)) ?: throw error
            onRouteChanged(fallback)
            fetch(monitor, fallback) { TriggerEvaluator.evaluate(monitor, it) }
        }
    }

    suspend fun findPrices(monitor: Monitor): List<PriceCandidate> = fetch(monitor, RequestRoute()) {
        PriceDiscovery.find(it, monitor.url, monitor.numberFormat)
    }

    private suspend fun <T> fetch(monitor: Monitor, route: RequestRoute, process: (String) -> T): T = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(monitor.url)
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8")
            .header("Cache-Control", "no-cache").build()
        route.family?.let { IpFamilies.requireLiteralFamily(request.url.host, it) }
        val call = clientFor(route).newCall(request)
        continuation.invokeOnCancellation { cleanupScope.launch { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw CrawlException(HttpErrors.describe(it.code, it.message), retryAfter(it.header("Retry-After")), it.code)
                        val body = it.body ?: throw CrawlException("The server returned no page.")
                        val type = body.contentType()
                        if (type != null && !(type.subtype.contains("html") || type.subtype == "plain")) throw CrawlException("Unsupported page type: $type")
                        val limit = 16L * 1024 * 1024
                        if (body.contentLength() > limit) throw CrawlException("Page exceeds the 16 MB safety limit.")
                        val source = body.source()
                        source.request(limit + 1)
                        if (source.buffer.size > limit) throw CrawlException("Page exceeds the 16 MB safety limit.")
                        val html = source.buffer.readString(type?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                        process(html)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    // Closing a TLS socket may write a close_notify message. Both cancellation
    // and pool eviction must run off Main, even when invoked by onDestroy().
    // This scope outlives the cancelled service scope so cleanup still finishes.
    fun close(): Job {
        val retired = synchronized(this) {
            closed = true
            (listOf(client) + routedClients.values).also { routedClients.clear() }
        }
        return cleanupScope.launch {
            try { client.dispatcher.cancelAll() }
            finally { retired.forEach { it.connectionPool.evictAll() } }
        }
    }

    companion object {
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", RequestUserAgents.next()).build())
            }
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).followSslRedirects(false)
            .build().also { it.dispatcher.maxRequests = 6; it.dispatcher.maxRequestsPerHost = 2 }

        fun retryAfter(value: String?, now: Long = System.currentTimeMillis()): Long {
            if (value == null) return 0
            val seconds = value.trim().toLongOrNull()
            if (seconds != null) return seconds.coerceIn(0, 86400) * 1000
            return try {
                (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceIn(0, 86400000)
            } catch (_: Exception) { 0 }
        }
    }
}

class MonitorEngine(
    private val scope: CoroutineScope,
    private val repository: MonitorRepository,
    private val crawler: Crawler = Crawler(),
    private val onHttpError: (AlertEvent) -> Unit = {},
    private val routeChooser: RouteChooser = RouteChooser { _, _, _, _, _ -> RequestRoute() }
) {
    private data class Worker(val monitor: Monitor, val job: Job)
    private val workers = mutableMapOf<String, Worker>()
    private val permits = Semaphore(6)
    private val hostCooldowns = mutableMapOf<String, Long>()

    fun reconcile(state: AppState) {
        val desired = if (state.running) state.monitors.filter { it.enabled }.associateBy { it.id } else emptyMap()
        workers.keys.toList().forEach { id ->
            if (desired[id] != workers[id]?.monitor) workers.remove(id)?.job?.cancel()
        }
        desired.forEach { (id, monitor) ->
            if (id !in workers) workers[id] = Worker(monitor, scope.launch { poll(monitor) })
        }
    }

    private suspend fun poll(monitor: Monitor) {
        val host = monitor.url.toHttpUrl().host
        // Restore pending intervals on service recreation rather than creating a request burst.
        val savedNext = repository.state.value.statuses[monitor.id]?.nextAt ?: 0
        var turn = repository.state.value.statuses[monitor.id]?.networkTurn ?: 0
        var wifiIpTurn = repository.state.value.statuses[monitor.id]?.wifiIpTurn ?: 0
        delay((savedNext - System.currentTimeMillis()).coerceIn(0, 86400000))
        while (scope.isActive) {
            var result: CheckResult? = null
            var error: String? = null
            var retryAfter = 0L
            var httpStatus: Int? = null
            var route = RequestRoute()
            var requested = route.label
            var failureDetails = ""
            val interval = monitor.randomDelayMillis()
            val slotEnds = System.currentTimeMillis() + interval
            try {
                route = routeChooser.choose(monitor, turn, wifiIpTurn, interval) { selected, dueAt ->
                    repository.waitForNetwork(monitor, selected, dueAt)
                }
                requested = route.label
                var checked = false
                while (!checked) {
                    // Waiting on one host must not consume permits needed by unrelated websites.
                    val remaining = (hostCooldowns[host] ?: 0) - System.currentTimeMillis()
                    if (remaining > 0) delay(remaining)
                    permits.withPermit {
                        // Recheck after acquiring a permit: another request may have rate-limited this host.
                        if ((hostCooldowns[host] ?: 0) <= System.currentTimeMillis()) {
                            result = crawler.check(monitor, route) { route = it }
                            checked = true
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (skipped: NetworkTurnSkipped) {
                turn++
                repository.skipNetworkTurn(monitor, skipped.connection, turn)
                continue
            }
            catch (failure: Exception) {
                error = failure.message ?: "Network check failed."
                failureDetails = ConnectionDebug.failure(failure)
                retryAfter = (failure as? CrawlException)?.retryAfterMillis ?: 0
                httpStatus = (failure as? CrawlException)?.httpStatus
                if (retryAfter > 0) hostCooldowns[host] = max(hostCooldowns[host] ?: 0, System.currentTimeMillis() + retryAfter)
            }
            val failures = (repository.state.value.statuses[monitor.id]?.failures ?: 0) + 1
            val backoff = if (error == null) 0 else min(3600000L, 5000L * (1L shl min(failures - 1, 10)))
            val intervalWait = if (monitor.alternateNetworks) (slotEnds - System.currentTimeMillis()).coerceAtLeast(0) else interval
            val wait = max(intervalWait, max(backoff, retryAfter))
            turn++
            if (route.path == NetworkPath.WIFI && route.family != null) wifiIpTurn++
            val diagnostic = ConnectionDebug(System.currentTimeMillis(), requested, route.label,
                failureDetails.ifBlank { "Success" }, route.fallbackReason, route.networkDetails)
            repository.record(monitor, result, error, System.currentTimeMillis() + wait, httpStatus, route.label, turn, wifiIpTurn, route.note, route.ipFallback, diagnostic)?.let(onHttpError)
            delay(wait)
        }
    }

    fun close() { workers.values.forEach { it.job.cancel() }; workers.clear(); crawler.close() }
}
