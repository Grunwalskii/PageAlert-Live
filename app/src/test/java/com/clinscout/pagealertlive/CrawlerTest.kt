package com.clinscout.pagealertlive

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CrawlerTest {
    @Test fun `each outgoing request including redirects gets a new user agent and never resends cookies`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final")
                .setHeader("Set-Cookie", "sample_session=demo; Path=/"))
            server.enqueue(MockResponse().setHeader("Set-Cookie", "sample_session=other; Path=/").setBody("<p>In stock</p>"))
            server.enqueue(MockResponse().setBody("<p>In stock</p>"))
            val crawler = Crawler()
            val monitor = Monitor(name = "Headers", url = server.url("/start").toString(), keyword = "in stock")
            try {
                repeat(2) { assertTrue(crawler.check(monitor).matched) }
                val requests = List(3) { server.takeRequest() }
                assertEquals(listOf("/start", "/final", "/start"), requests.map { it.path })
                assertTrue(requests.all { it.getHeader("Cookie") == null && it.getHeader("Authorization") == null })
                val agents = requests.map { it.getHeader("User-Agent")!! }
                assertTrue(agents.all { it.startsWith("Mozilla/5.0") })
                assertTrue(agents.zipWithNext().all { (previous, next) -> previous != next })
            } finally { crawler.close().join() }
        }
    }

    @Test fun `actual HTML response evaluates keyword`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<p>In stock</p>"))
            val crawler = Crawler()
            try {
                assertTrue(crawler.check(Monitor(name = "Test", url = server.url("/").toString(), keyword = "in stock")).matched)
                assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
            } finally { crawler.close() }
        }
    }

    @Test fun `HTTP errors are errors and preserve Retry After`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("Missing item"))
            val crawler = Crawler()
            try {
                val failure = try {
                    crawler.check(Monitor(name = "Test", url = server.url("/").toString(), trigger = Trigger.KEYWORD_MISSING, keyword = "stock"))
                    error("Expected HTTP failure")
                } catch (error: CrawlException) { error }
                assertEquals(120000L, failure.retryAfterMillis)
                assertEquals(429, failure.httpStatus)
                assertEquals("HTTP 429: Too many requests (increase check interval).", failure.message)
            } finally { crawler.close() }
        }
    }

    @Test fun `oversized and unsupported pages are rejected`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 1)))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("data"))
            val crawler = Crawler()
            val monitor = Monitor(name = "Test", url = server.url("/").toString(), keyword = "stock")
            try {
                repeat(2) {
                    try { crawler.check(monitor); error("Expected page rejection") }
                    catch (error: CrawlException) { assertTrue(error.message!!.isNotEmpty()) }
                }
            } finally { crawler.close() }
        }
    }

    @Test fun `cancellation stops a pending request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<p>stock</p>").setHeadersDelay(2, TimeUnit.SECONDS))
            val crawler = Crawler()
            try {
                val job = launch { crawler.check(Monitor(name = "Test", url = server.url("/").toString(), keyword = "stock")) }
                withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            } finally { crawler.close() }
        }
    }

    @Test fun `Retry After dates seconds and invalid values`() {
        assertEquals(30000L, Crawler.retryAfter("30", 0))
        assertEquals(0L, Crawler.retryAfter("-10", 0))
        assertEquals(86400000L, Crawler.retryAfter("999999999", 0))
        assertEquals(60000L, Crawler.retryAfter("Thu, 1 Jan 1970 00:01:00 GMT", 0))
        assertEquals(0L, Crawler.retryAfter("garbage", 0))
    }
}
