package com.clinscout.pagealertlive

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

class IpFamilyFallbackTest {
    private val v4 = InetAddress.getByName("127.0.0.1")
    private val v6 = InetAddress.getByName("::1")

    private fun route(dns: Dns, preferred: IpFamily) = RequestRoute(
        key = "wifi:test", label = "Wi-Fi", socketFactory = SocketFactory.getDefault(), dns = dns, path = NetworkPath.WIFI
    ).withIpFamilyFallback(preferred)

    @Test fun `missing DNS family falls back in both directions and preferred family is retried after recovery`() = runBlocking {
        for (preferred in IpFamily.entries) {
            val v4Server = MockWebServer()
            val v6Server = MockWebServer()
            v4Server.start(v4, 0)
            v6Server.start(v6, v4Server.port)
            v4Server.enqueue(MockResponse().setBody("<p>Available</p>"))
            v6Server.enqueue(MockResponse().setBody("<p>Available</p>"))
            val answers = AtomicReference(listOf(if (preferred == IpFamily.IPV4) v6 else v4))
            val route = route(object : Dns {
                override fun lookup(hostname: String) = answers.get()
            }, preferred)
            val monitor = Monitor(name = "Fallback", url = v4Server.url("/").newBuilder().host("families.test").build().toString(), keyword = "missing")
            val crawler = Crawler()
            val changes = mutableListOf<RequestRoute>()
            try {
                assertFalse(crawler.check(monitor, route, changes::add).matched)
                val actual = changes.single()
                assertTrue(actual.ipFallback)
                assertEquals(if (preferred == IpFamily.IPV4) IpFamily.IPV6 else IpFamily.IPV4, actual.family)
                assertEquals("Wi-Fi • ${actual.family!!.label}", actual.label)
                assertEquals(if (preferred == IpFamily.IPV4) 0 else 1, v4Server.requestCount)
                assertEquals(if (preferred == IpFamily.IPV4) 1 else 0, v6Server.requestCount)
                answers.set(listOf(v4, v6))
                changes.clear()
                assertFalse(crawler.check(monitor, route, changes::add).matched)
                assertTrue("Recovery must try the preferred family without a fallback", changes.isEmpty())
                assertEquals(1, v4Server.requestCount)
                assertEquals(1, v6Server.requestCount)
            } finally {
                crawler.close().join()
                v4Server.shutdown()
                v6Server.shutdown()
            }
        }
    }

    @Test fun `failed IPv6 socket retries a real IPv4 request even when both DNS families exist`() = runBlocking {
        MockWebServer().use { server ->
            server.start(v4, 0) // No listener on ::1 at this port.
            server.enqueue(MockResponse().setBody("<p>Available</p>"))
            val connections = mutableListOf<InetAddress>()
            val client = Crawler.defaultClient().newBuilder().connectTimeout(1, TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                        connections += inetSocketAddress.address
                    }
                }).build()
            val crawler = Crawler(client)
            val selected = route(object : Dns { override fun lookup(hostname: String) = listOf(v4, v6) }, IpFamily.IPV6)
            val monitor = Monitor(name = "Socket fallback", url = server.url("/").newBuilder().host("families.test").build().toString(), keyword = "missing")
            try {
                var actual = selected
                assertFalse(crawler.check(monitor, selected) { actual = it }.matched)
                assertEquals(listOf(v6, v4), connections)
                assertEquals(IpFamily.IPV4, actual.family)
                assertTrue(actual.ipFallback)
                assertEquals(1, server.requestCount)
            } finally { crawler.close().join() }
        }
    }

    @Test fun `failure of both families stops after one fallback`() = runBlocking {
        val lookups = AtomicInteger()
        val selected = route(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups.incrementAndGet()
                return emptyList()
            }
        }, IpFamily.IPV6)
        val crawler = Crawler()
        try {
            var actual = selected
            try {
                crawler.check(Monitor(name = "Neither", url = "http://families.test/", keyword = "missing"), selected) { actual = it }
                fail("Expected both DNS families to fail")
            } catch (expected: UnknownHostException) {
                assertTrue(expected.message!!.contains("IPv4"))
            }
            assertEquals(2, lookups.get())
            assertEquals(IpFamily.IPV4, actual.family)
            assertTrue(actual.ipFallback)
        } finally { crawler.close().join() }
    }

    @Test fun `literal IP URLs cannot silently bypass the preferred family`() = runBlocking {
        MockWebServer().use { server ->
            server.start(v4, 0)
            server.enqueue(MockResponse().setBody("<p>Available</p>"))
            val selected = route(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = error("Literal URLs should bypass DNS")
            }, IpFamily.IPV6)
            val crawler = Crawler()
            try {
                var actual = selected
                assertFalse(crawler.check(Monitor(name = "Literal", url = server.url("/").toString(), keyword = "missing"), selected) { actual = it }.matched)
                assertTrue(actual.ipFallback)
                assertEquals(IpFamily.IPV4, actual.family)
                assertEquals(1, server.requestCount)
            } finally { crawler.close().join() }
        }
    }

    @Test fun `redirect to a literal address of the other family triggers fallback`() = runBlocking {
        val v4Server = MockWebServer()
        val v6Server = MockWebServer()
        v4Server.start(v4, 0)
        v6Server.start(v6, v4Server.port)
        v6Server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", v4Server.url("/final")))
        v4Server.enqueue(MockResponse().setBody("<p>Available</p>"))
        val selected = route(object : Dns { override fun lookup(hostname: String) = listOf(v4, v6) }, IpFamily.IPV6)
        val crawler = Crawler()
        try {
            var actual = selected
            assertFalse(crawler.check(Monitor(name = "Redirect", url = v4Server.url("/").newBuilder().host("families.test").build().toString(), keyword = "missing"), selected) { actual = it }.matched)
            assertTrue(actual.ipFallback)
            assertEquals(IpFamily.IPV4, actual.family)
            assertEquals("A mismatched redirect must not send an HTTP request on the wrong family", "/", v4Server.takeRequest().path)
            assertEquals(1, v4Server.requestCount)
            assertEquals(1, v6Server.requestCount)
        } finally {
            crawler.close().join()
            v4Server.shutdown()
            v6Server.shutdown()
        }
    }

    @Test fun `HTTP rate limiting preserves Retry After without retrying the other family`() = runBlocking {
        MockWebServer().use { server ->
            server.start(v4, 0)
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            val selected = route(object : Dns { override fun lookup(hostname: String) = listOf(v4, v6) }, IpFamily.IPV4)
            val crawler = Crawler()
            val changes = mutableListOf<RequestRoute>()
            try {
                try {
                    crawler.check(Monitor(name = "Limited", url = server.url("/").newBuilder().host("families.test").build().toString(), keyword = "missing"), selected, changes::add)
                    fail("Expected HTTP rate limiting")
                } catch (expected: CrawlException) {
                    assertEquals(429, expected.httpStatus)
                    assertEquals(120000L, expected.retryAfterMillis)
                }
                assertTrue(changes.isEmpty())
                assertEquals(1, server.requestCount)
            } finally { crawler.close().join() }
        }
    }

    @Test fun `cancelling a check never starts a fallback request`() = runBlocking {
        MockWebServer().use { server ->
            server.start(v4, 0)
            server.enqueue(MockResponse().setBody("<p>Available</p>").setHeadersDelay(1, TimeUnit.SECONDS))
            val selected = route(object : Dns { override fun lookup(hostname: String) = listOf(v4, v6) }, IpFamily.IPV4)
            val crawler = Crawler()
            val changes = mutableListOf<RequestRoute>()
            try {
                val job = launch {
                    crawler.check(Monitor(name = "Cancel", url = server.url("/").newBuilder().host("families.test").build().toString(), keyword = "missing"), selected, changes::add)
                }
                withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertTrue(changes.isEmpty())
                assertEquals(1, server.requestCount)
            } finally { crawler.close().join() }
        }
    }
}
