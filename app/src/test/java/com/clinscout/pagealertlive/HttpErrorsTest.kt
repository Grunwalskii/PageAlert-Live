package com.clinscout.pagealertlive

import org.junit.Assert.*
import org.junit.Test

class HttpErrorsTest {
    @Test fun `common failures have short guidance even without a reason phrase`() {
        assertTrue(HttpErrors.describe(401).contains("Sign-in required"))
        assertTrue(HttpErrors.describe(403).contains("Access denied"))
        assertTrue(HttpErrors.describe(404).contains("check URL"))
        assertTrue(HttpErrors.describe(429).contains("increase check interval"))
        listOf(408, 500, 502, 503, 504).forEach { code ->
            assertTrue(HttpErrors.describe(code).startsWith("HTTP $code:"))
            assertTrue(HttpErrors.describe(code).contains("will retry"))
        }
    }

    @Test fun `unusual failures preserve status and use a readable fallback`() {
        assertEquals("HTTP 418: Teapot", HttpErrors.describe(418, "Teapot"))
        assertTrue(HttpErrors.describe(599).contains("Website error"))
        assertTrue(HttpErrors.describe(499).contains("Request rejected"))
        assertTrue(HttpErrors.describe(302).contains("Redirect"))
        assertFalse(HttpErrors.describe(499, "Failure\nmessage").contains('\n'))
    }
}
