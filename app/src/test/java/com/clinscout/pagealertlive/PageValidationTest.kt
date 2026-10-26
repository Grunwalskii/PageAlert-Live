package com.clinscout.pagealertlive

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class PageValidationTest {
    private val url = "https://www.amazon.de/dp/B0FRYBZR91?tag=bestellbar-21"
    private fun validate(html: String) = PageValidation.validate(Jsoup.parse(html, url), url)

    @Test fun `robot checks without a challenge title are rejected before keyword-missing evaluation`() {
        val html = "<title>Amazon.de</title><form action='/errors/validateCaptcha'><input id='captchacharacters'></form><p>Continue shopping</p>"
        val monitor = Monitor(name = "Product", url = url, trigger = Trigger.KEYWORD_MISSING, keyword = "sold out")
        assertThrows(PageValidationException::class.java) { TriggerEvaluator.evaluate(monitor, html) }
        assertThrows(PageValidationException::class.java) { PriceDiscovery.find(html, url) }
    }

    @Test fun `short English and German rate-limit pages require backoff`() {
        for (html in listOf("<h1>Too many requests</h1><p>Please try again later.</p>", "<p>Zu viele Anfragen. Bitte später erneut versuchen.</p>")) {
            val error = assertThrows(PageValidationException::class.java) { validate(html) }
            assertFalse(error.retryImmediately)
            assertEquals(60_000L, error.retryAfterMillis)
        }
    }

    @Test fun `product text mentioning errors is not itself an error page`() {
        validate("<title>Network troubleshooting book</title><h1>Book</h1><p>Learn about too many requests, access denied and CAPTCHA errors.</p><script>verify you are human</script>")
    }

    @Test fun `a different explicit Amazon product identity is rejected`() {
        assertThrows(PageValidationException::class.java) {
            validate("<input id='ASIN' value='B012345678'><p class='price'>€1.00</p>")
        }
        validate("<input id='ASIN' value='B0FRYBZR91'><div data-asin='B012345678'>Recommendation</div><p>Product</p>")
    }

    @Test fun `canonical and parent identities support legitimate variation groups`() {
        validate("<link rel='canonical' href='/dp/B0FRYBZR91'><input id='ASIN' value='B012345678'><p>Product variation</p>")
        validate("<input id='ASIN' value='B012345678'><input id='parentASIN' value='B0FRYBZR91'><p>Product variation</p>")
    }

    @Test fun `product redirects to sign-in and temporary error pages are rejected`() {
        assertThrows(PageValidationException::class.java) { validate("<form id='ap_signin_form'><input id='ap_email'></form><p>Sign in</p>") }
        assertThrows(PageValidationException::class.java) { validate("<title>Sorry!</title><p>Something went wrong on our end.</p>") }
    }

    @Test fun `Amazon-looking unrelated hosts do not enable product identity rules`() {
        PageValidation.validate(Jsoup.parse("<input id='ASIN' value='B012345678'><p>Other shop</p>"), "https://amazon.de.example.com/dp/B0FRYBZR91")
    }

    @Test fun `embedded sign-in forms do not invalidate product pages`() {
        validate("<input id='ASIN' value='B0FRYBZR91'><form id='ap_signin_form'><input id='ap_email'></form><p>Product</p>")
        validate("<h1 id='productTitle'>Product</h1><form name='signIn'><input id='ap_email'></form>")
    }
}
