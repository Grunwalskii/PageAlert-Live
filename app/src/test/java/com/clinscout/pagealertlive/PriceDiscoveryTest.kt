package com.clinscout.pagealertlive

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PriceDiscoveryTest {
    private val html = """<html><body><h1>Example console</h1>
        <div data-test="mms-product-price"><div><span aria-hidden="true">899,</span>
        <span aria-hidden="true">99</span><span aria-hidden="true"> €</span></div>
        <span class="accessible">899,99€</span></div>
        <p id="shipping">Delivery 4,99 €</p><del>999,99 €</del>
        <p hidden>10,00 €</p><script>let price = '1,00 €';</script></body></html>"""

    @Test fun `split visual prices and screen reader duplicates yield a stable selectable price`() {
        val candidates = PriceDiscovery.find(html, "https://example.com")
        val product = candidates.first { it.selector == "[data-test=\"mms-product-price\"]" }
        assertEquals(BigDecimal("899.99"), product.price)
        assertEquals(product.price, PriceDiscovery.read(Jsoup.parse(html).select(product.selector).single(), NumberFormat.AUTO))
        assertTrue(candidates.any { it.price == BigDecimal("4.99") })
        assertFalse(candidates.any { it.price == BigDecimal("999.99") || it.price == BigDecimal("10") || it.price == BigDecimal.ONE })
        val monitor = Monitor(name = "Console", url = "https://example.com", trigger = Trigger.PRICE, selector = product.selector, threshold = "900")
        assertTrue(TriggerEvaluator.evaluate(monitor, html).matched)
        assertFalse(TriggerEvaluator.evaluate(monitor, html.replace("899,", "909,").replace("899,99", "909,99")).matched)
    }

    @Test fun `two different amounts require a specific selector and machine metadata keeps dot decimals`() {
        val document = Jsoup.parse("<div>Was €99.99 now €49.99</div><meta itemprop='price' content='1234.567'>")
        assertThrows(IllegalArgumentException::class.java) { PriceDiscovery.read(document.selectFirst("div")!!, NumberFormat.AUTO) }
        assertEquals(BigDecimal("1234.567"), PriceDiscovery.read(document.selectFirst("meta")!!, NumberFormat.COMMA_DECIMAL))
    }

    @Test fun `split cents work without accessibility markup and bare product numbers are not prices`() {
        val candidates = PriceDiscovery.find("<p id='price'><span>123,</span> <span>45</span> €</p><p id='sku'>Model 12345</p>", "https://example.com")
        assertEquals(BigDecimal("123.45"), candidates.first { it.selector == "[id=\"price\"]" }.price)
        assertFalse(candidates.any { it.selector.contains("sku") })
    }
}
