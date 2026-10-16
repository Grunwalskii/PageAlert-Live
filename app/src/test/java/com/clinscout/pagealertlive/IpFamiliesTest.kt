package com.clinscout.pagealertlive

import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Collections
import javax.net.SocketFactory

class IpFamiliesTest {
    private val v4 = InetAddress.getByName("192.168.1.5")
    private val v6 = InetAddress.getByName("2001:db8::5")

    @Test fun `dual stack requires both addresses and default routes`() {
        assertTrue(WifiIpSupport.detect(listOf(v4, v6), true, true).dualStack)
        assertFalse(WifiIpSupport.detect(listOf(v4, v6), true, false).dualStack)
        assertFalse(WifiIpSupport.detect(listOf(v6), true, true).dualStack)
        listOf("fe80::5", "fd00::5", "::1").forEach { local ->
            assertFalse(WifiIpSupport.detect(listOf(v4, InetAddress.getByName(local)), true, true).dualStack)
        }
    }

    @Test fun `WiFi IP alternation uses its own turn counter without removing unavailable families`() {
        assertEquals(listOf(IpFamily.IPV4, IpFamily.IPV6, IpFamily.IPV4),
            (0L..2L).map(IpFamilies::select))
    }

    @Test fun `family filtering never silently sends a request over the wrong family`() {
        assertEquals(listOf(v4), IpFamilies.filter(listOf(v4, v6), IpFamily.IPV4))
        assertEquals(listOf(v6), IpFamilies.filter(listOf(v4, v6), IpFamily.IPV6))
        try { IpFamilies.filter(listOf(v4), IpFamily.IPV6); fail("Expected no IPv6 route") }
        catch (expected: UnknownHostException) { assertTrue(expected.message!!.contains("IPv6")) }
    }

    @Test fun `separate family pools send real requests to IPv4 and IPv6 sockets`() = runBlocking {
        val ipv4 = InetAddress.getByName("127.0.0.1")
        val ipv6 = InetAddress.getByName("::1")
        val v4Server = MockWebServer()
        val v6Server = MockWebServer()
        v4Server.enqueue(MockResponse().setBody("<p>In stock</p>"))
        v6Server.enqueue(MockResponse().setBody("<p>In stock</p>"))
        v4Server.start(ipv4, 0)
        v6Server.start(ipv6, v4Server.port)
        val connections = Collections.synchronizedList(mutableListOf<InetAddress>())
        val client = Crawler.defaultClient().newBuilder().eventListener(object : EventListener() {
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                connections += inetSocketAddress.address
            }
        }).build()
        val crawler = Crawler(client)
        val monitor = Monitor(name = "Both families", url = v4Server.url("/").newBuilder().host("dualstack.test").build().toString(), keyword = "in stock")
        try {
            for (family in IpFamily.entries) {
                val route = RequestRoute("wifi-${family.name}", "Wi-Fi • ${family.label}", SocketFactory.getDefault(), object : Dns {
                    override fun lookup(hostname: String) = IpFamilies.filter(listOf(ipv4, ipv6), family)
                }, NetworkPath.WIFI, family)
                assertTrue(crawler.check(monitor, route).matched)
            }
            assertEquals(1, v4Server.requestCount)
            assertEquals(1, v6Server.requestCount)
            assertEquals(2, connections.size)
            assertTrue(connections[0] is Inet4Address)
            assertTrue(connections[1] is Inet6Address)
        } finally {
            crawler.close().join()
            v4Server.shutdown()
            v6Server.shutdown()
        }
    }
}
