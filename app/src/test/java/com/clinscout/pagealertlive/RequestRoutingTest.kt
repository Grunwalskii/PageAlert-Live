package com.clinscout.pagealertlive

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

class RequestRoutingTest {
    @Test fun `each watchdog alternates independently and starts on wifi`() {
        val device = DeviceConnections(true, true)
        assertEquals(listOf(NetworkPath.WIFI, NetworkPath.MOBILE, NetworkPath.WIFI, NetworkPath.MOBILE),
            (0L..3L).map(device::selectedPath))
        assertFalse(Monitor(name = "New", url = "https://example.com", keyword = "stock").alternateNetworks)
    }

    @Test fun `user disabled networks are ignored with a short explanation`() {
        val wifiOnly = DeviceConnections(true, false)
        val mobileOnly = DeviceConnections(false, true)
        (0L..3L).forEach { turn ->
            assertEquals(NetworkPath.WIFI, wifiOnly.selectedPath(turn))
            assertEquals(NetworkPath.MOBILE, mobileOnly.selectedPath(turn))
        }
        assertEquals("Mobile data off • Wi-Fi only", wifiOnly.note)
        assertEquals("Wi-Fi off • Mobile data only", mobileOnly.note)
        val off = DeviceConnections(false, false)
        assertTrue(NetworkPath.entries.none(off::enabled))
        assertEquals("Wi-Fi and mobile data off", off.note)
        assertEquals("VPN active • System connection", DeviceConnections(true, true, true).note)
    }

    @Test fun `route specific sockets DNS and connection pools remain separate`() = runBlocking {
        val server = MockWebServer()
        repeat(5) { server.enqueue(MockResponse().setBody("<p>In stock</p>")) }
        server.start()
        val wifi = CountingSocketFactory()
        val mobile = CountingSocketFactory()
        val wifiDns = AtomicInteger()
        val mobileDns = AtomicInteger()
        fun route(key: String, factory: SocketFactory, lookups: AtomicInteger) = RequestRoute(key, key, factory, object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                assertEquals("routing.test", hostname)
                lookups.incrementAndGet()
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        })
        val first = route("wifi", wifi, wifiDns)
        val second = route("mobile", mobile, mobileDns)
        val monitor = Monitor(name = "Routing", url = server.url("/").newBuilder().host("routing.test").build().toString(), keyword = "in stock")
        val crawler = Crawler()
        try {
            listOf(first, first, second, second, first).forEach { assertTrue(crawler.check(monitor, it).matched) }
            assertEquals(1, wifi.created.get())
            assertEquals(1, mobile.created.get())
            assertTrue(wifiDns.get() > 0)
            assertTrue(mobileDns.get() > 0)
            assertEquals(listOf(0, 1, 0, 1, 2), (1..5).map { server.takeRequest().sequenceNumber })
        } finally { crawler.close().join(); server.shutdown() }
    }

    private class CountingSocketFactory : SocketFactory() {
        val created = AtomicInteger()
        override fun createSocket(): Socket = Socket().also { created.incrementAndGet() }
        override fun createSocket(host: String, port: Int): Socket = Socket(host, port)
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = Socket(host, port, local, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = Socket(host, port)
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = Socket(host, port, local, localPort)
    }
}
