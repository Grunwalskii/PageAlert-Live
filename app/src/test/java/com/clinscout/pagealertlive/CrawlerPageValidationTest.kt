package com.clinscout.pagealertlive

import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class CrawlerPageValidationTest {
    private fun price(server: MockWebServer) = Monitor(name = "Product", url = server.url("/product").toString(),
        trigger = Trigger.PRICE, selector = "#price", threshold = "100")
    private suspend fun failure(crawler: Crawler, monitor: Monitor): CrawlException {
        try { crawler.check(monitor); error("Expected a failed page check") }
        catch (error: CrawlException) { return error }
    }

    @Test fun `slow chunked downloads are read through the final price without an extra request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("<p>Product</p>" + " ".repeat(128) + "<p id='price'>€49.00</p>", 16)
                .throttleBody(32, 40, TimeUnit.MILLISECONDS).setBodyDelay(100, TimeUnit.MILLISECONDS))
            val crawler = Crawler()
            try {
                val result = crawler.check(price(server))
                assertTrue(result.matched)
                assertEquals(0, result.pageRetries)
                assertEquals(1, server.requestCount)
            } finally { crawler.close().join() }
        }
    }

    @Test fun `missing price selector retries the same URL once and recovers`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<p>Temporary page</p>"))
            server.enqueue(MockResponse().setBody("<p id='price'>€49.00</p>"))
            val crawler = Crawler()
            val routeChanges = mutableListOf<RequestRoute>()
            try {
                val result = crawler.check(price(server), onRouteChanged = routeChanges::add)
                assertTrue(result.matched)
                assertEquals(1, result.pageRetries)
                assertEquals(2, server.requestCount)
                assertEquals(listOf("/product", "/product"), List(2) { server.takeRequest().path })
                assertTrue(routeChanges.isEmpty())
            } finally { crawler.close().join() }
        }
    }

    @Test fun `repeated missing selectors stop after two requests with the validation reason`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("<p>No price in this HTML</p>")) }
            val crawler = Crawler()
            try {
                val error = failure(crawler, price(server))
                assertTrue(error.message!!.contains("HTTP 200: Price CSS selector matched no elements"))
                assertTrue(error.message!!.contains("after one immediate page retry"))
                assertEquals(2, server.requestCount)
                assertNull(error.httpStatus)
            } finally { crawler.close().join() }
        }
    }

    @Test fun `a challenge or empty response retries before evaluating a missing keyword`() = runBlocking {
        for (invalid in listOf("", "<title>Amazon.de</title><form action='/errors/validateCaptcha'><p>Continue shopping</p></form>")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(invalid))
                server.enqueue(MockResponse().setBody("<p>In stock</p>"))
                val crawler = Crawler()
                try {
                    val result = crawler.check(price(server).copy(trigger = Trigger.KEYWORD_MISSING, keyword = "stock", selector = ""))
                    assertFalse(result.matched)
                    assertEquals(1, result.pageRetries)
                    assertEquals(2, server.requestCount)
                } finally { crawler.close().join() }
            }
        }
    }

    @Test fun `WAF success-class challenge headers cannot count as a successful page`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202).setHeader("x-amzn-waf-action", "challenge").setBody("<p>Product</p>"))
            server.enqueue(MockResponse().setBody("<p id='price'>€49.00</p>"))
            val crawler = Crawler()
            try {
                assertEquals(1, crawler.check(price(server)).pageRetries)
                assertEquals(2, server.requestCount)
            } finally { crawler.close().join() }
        }
    }

    @Test fun `HTTP 200 rate-limit pages do not retry immediately and preserve longer server delays`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Retry-After", "120").setBody("<h1>Too many requests</h1><p>Try again later</p>"))
            val crawler = Crawler()
            try {
                val error = failure(crawler, price(server))
                assertEquals(120_000L, error.retryAfterMillis)
                assertTrue(error.message!!.contains("rate-limit page"))
                assertEquals(1, server.requestCount)
            } finally { crawler.close().join() }
        }
    }

    @Test fun `Retry After on a missing-selector success response prevents an immediate retry`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Retry-After", "90").setBody("<p>Price unavailable</p>"))
            val crawler = Crawler()
            try {
                assertEquals(90_000L, failure(crawler, price(server)).retryAfterMillis)
                assertEquals(1, server.requestCount)
            } finally { crawler.close().join() }
        }
    }

    @Test fun `combined rules retry missing selectors and retain an explicit either choice after the retry`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("<p>In stock</p>")) }
            val crawler = Crawler()
            try {
                val result = crawler.check(price(server).copy(trigger = Trigger.KEYWORD_PRESENT, keyword = "stock", selector = "",
                    checkPrice = true, priceSelector = "#price", requireAllConditions = false))
                assertTrue(result.matched)
                assertEquals(true, result.keywordMatched)
                assertNull(result.priceMatched)
                assertEquals(1, result.pageRetries)
                assertNotNull(result.missingPageElement)
                assertEquals(2, server.requestCount)
            } finally { crawler.close().join() }
        }
    }

    @Test fun `valid nonmatching pages are not retried`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<p id='price'>€149.00</p>"))
            val crawler = Crawler()
            try {
                assertFalse(crawler.check(price(server)).matched)
                assertEquals(1, server.requestCount)
            } finally { crawler.close().join() }
        }
    }

    @Test fun `a mismatched Amazon ASIN is rejected and refetched once`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<input id='ASIN' value='B012345678'><p id='price'>€1.00</p>"))
            server.enqueue(MockResponse().setBody("<input id='ASIN' value='B0FRYBZR91'><p id='price'>€149.00</p>"))
            val client = Crawler.defaultClient().newBuilder().dns(object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            }).build()
            val crawler = Crawler(client)
            try {
                val monitor = price(server).copy(url = server.url("/dp/B0FRYBZR91").newBuilder().host("www.amazon.de").build().toString())
                val result = crawler.check(monitor)
                assertFalse("The price from the wrong product must never match", result.matched)
                assertEquals(1, result.pageRetries)
                assertEquals(2, server.requestCount)
            } finally { crawler.close().join() }
        }
    }
}
