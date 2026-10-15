package com.clinscout.pagealertlive

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HttpsShutdownIntegrationTest {
    @Test fun closingAnIdleHttpsConnectionFromMainThreadIsSafe() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("<p>In stock</p>"))
        server.start()
        // This trust configuration exists only in the test APK. Production
        // keeps Android's normal certificate and hostname validation.
        val client = Crawler.defaultClient().newBuilder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        val crawler = Crawler(client)
        try {
            val result = crawler.check(Monitor(name = "TLS shutdown", url = server.url("/").toString(), keyword = "in stock"))
            assertTrue(result.matched)
            val idleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (client.connectionPool.idleConnectionCount() == 0 && System.nanoTime() < idleDeadline) Thread.sleep(20)
            assertEquals("Test must retain an HTTPS connection for shutdown", 1, client.connectionPool.idleConnectionCount())
            var failure: Throwable? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val original = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(original).detectNetwork().penaltyDeathOnNetwork().build())
                try { crawler.close() } catch (error: Throwable) { failure = error }
                finally { StrictMode.setThreadPolicy(original) }
            }
            assertNull("HTTPS cleanup must not perform network I/O on the service's main thread: $failure", failure)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (client.connectionPool.connectionCount() != 0 && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals("Background cleanup must release pooled connections", 0, client.connectionPool.connectionCount())
        } finally {
            crawler.close()
            server.shutdown()
        }
    }
}
