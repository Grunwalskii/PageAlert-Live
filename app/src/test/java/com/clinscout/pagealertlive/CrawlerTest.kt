package com.clinscout.pagealertlive

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
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
            server.enqueue(MockResponse().setBody("x".repeat(16 * 1024 * 1024 + 1)))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("data"))
            val crawler = Crawler()
            val monitor = Monitor(name = "Test", url = server.url("/").toString(), keyword = "stock")
            try {
                listOf("Page exceeds the 16 MB safety limit.", "Unsupported page type: image/png").forEach { expected ->
                    try { crawler.check(monitor); error("Expected page rejection") }
                    catch (error: CrawlException) { assertEquals(expected, error.message) }
                }
            } finally { crawler.close() }
        }
    }

    @Test fun `HTML up to exactly 16 MiB is accepted and checked through its final bytes`() = runBlocking {
        MockWebServer().use { server ->
            val suffix = "--><p>In stock</p>"
            val html = "<!--" + "x".repeat(16 * 1024 * 1024 - 4 - suffix.length) + suffix
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(html))
            val crawler = Crawler()
            try {
                assertTrue(crawler.check(Monitor(name = "Large page", url = server.url("/").toString(), keyword = "in stock")).matched)
                assertEquals(1, server.requestCount)
            } finally { crawler.close().join() }
        }
    }

    @Test fun `chunked and compressed pages cannot bypass the 16 MiB limit`() = runBlocking {
        MockWebServer().use { server ->
            val oversized = "x".repeat(16 * 1024 * 1024 + 1)
            val compressed = Buffer()
            GzipSink(compressed).buffer().use { it.writeUtf8(oversized) }
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setChunkedBody(oversized, 8192))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setHeader("Content-Encoding", "gzip").setBody(compressed))
            val crawler = Crawler()
            val monitor = Monitor(name = "Oversized stream", url = server.url("/").toString(), keyword = "stock")
            try {
                repeat(2) {
                    try { crawler.check(monitor); fail("Expected oversized page rejection") }
                    catch (expected: CrawlException) { assertEquals("Page exceeds the 16 MB safety limit.", expected.message) }
                }
            } finally { crawler.close().join() }
        }
    }

    @Test fun `checks and price discovery retain compression and never load page assets`() = runBlocking {
        MockWebServer().use { server ->
            val html = """
                <html><head><link rel="stylesheet" href="/style.css">
                <style>body { background-image: url('/background.png'); }</style>
                <script src="/app.js"></script></head><body>
                <p>In stock</p><span class="price">$12.34</span>
                <img src="/photo.jpg" srcset="/large.jpg 2x">
                <iframe src="/frame.html"></iframe><video src="/movie.mp4" poster="/poster.jpg"></video>
                <script>fetch('/extra-data');</script>
                <!--${"x".repeat(3 * 1024 * 1024)}--></body></html>
            """.trimIndent()
            val compressed = Buffer()
            GzipSink(compressed).buffer().use { it.writeUtf8(html) }
            repeat(2) {
                server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
                    .setHeader("Content-Encoding", "gzip").setBody(compressed.clone()))
            }
            val crawler = Crawler()
            val monitor = Monitor(name = "HTML only", url = server.url("/page").toString(), keyword = "in stock")
            try {
                assertTrue(crawler.check(monitor).matched)
                assertTrue(crawler.findPrices(monitor).isNotEmpty())
                assertEquals("Only the two explicitly requested HTML pages should be fetched", 2, server.requestCount)
                repeat(2) {
                    val request = server.takeRequest()
                    assertEquals("/page", request.path)
                    assertEquals("gzip", request.getHeader("Accept-Encoding"))
                    assertFalse(request.getHeader("Accept")!!.contains("image/"))
                }
                assertTrue("Compressed transfer stays much smaller than the expanded HTML", compressed.size < 32 * 1024)
            } finally { crawler.close().join() }
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
