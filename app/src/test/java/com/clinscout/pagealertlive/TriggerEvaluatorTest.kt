package com.clinscout.pagealertlive

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import kotlin.random.Random

class TriggerEvaluatorTest {
    private fun monitor(trigger: Trigger = Trigger.KEYWORD_PRESENT) = Monitor(
        name = "Test", url = "https://example.com/product", trigger = trigger, keyword = "in stock", threshold = "99.99"
    )

    @Test fun `visible keywords ignore scripts and match case insensitively`() {
        assertTrue(TriggerEvaluator.evaluate(monitor(), "<p>IN STOCK</p>").matched)
        assertFalse(TriggerEvaluator.evaluate(monitor(), "<script>in stock</script><p>Sold out</p>").matched)
        assertFalse(TriggerEvaluator.evaluate(monitor().copy(caseSensitive = true), "<p>IN STOCK</p>").matched)
    }

    @Test fun `missing keyword and scoped content`() {
        assertTrue(TriggerEvaluator.evaluate(monitor(Trigger.KEYWORD_MISSING), "<p>Sold out</p>").matched)
        assertFalse(TriggerEvaluator.evaluate(monitor().copy(selector = "#product"), "<header>In stock</header><p id='product'>Sold out</p>").matched)
    }

    @Test fun `empty pages and missing selectors never count as missing keywords`() {
        assertThrows(IllegalArgumentException::class.java) { TriggerEvaluator.evaluate(monitor(Trigger.KEYWORD_MISSING), "") }
        assertThrows(IllegalArgumentException::class.java) { TriggerEvaluator.evaluate(monitor(Trigger.KEYWORD_MISSING).copy(selector = ".missing"), "<p>Hello</p>") }
        assertThrows(IllegalArgumentException::class.java) { TriggerEvaluator.evaluate(monitor(Trigger.KEYWORD_MISSING), "<title>Just a moment...</title><p>Checking browser</p>") }
    }

    @Test fun `price equality fires and greater price does not`() {
        val m = monitor(Trigger.PRICE).copy(selector = ".price")
        assertTrue(TriggerEvaluator.evaluate(m, "<p class='price'>€99,99</p>").matched)
        assertFalse(TriggerEvaluator.evaluate(m, "<p class='price'>$100.00</p>").matched)
        assertTrue(TriggerEvaluator.evaluate(m, "<p class='price'>99.98 USD</p>").matched)
    }

    @Test fun `structured price metadata uses machine dot decimals`() {
        val html = "<meta itemprop='price' content='99.99'><meta property='product:price:amount' content='99.990'><p>Product</p>"
        assertTrue(TriggerEvaluator.evaluate(monitor(Trigger.PRICE), html).matched)
        assertThrows(IllegalArgumentException::class.java) {
            TriggerEvaluator.evaluate(monitor(Trigger.PRICE), "<meta itemprop='price' content='10'><meta itemprop='price' content='50'><p>Two products</p>")
        }
    }

    @Test fun `localized price formats and grouping`() {
        assertEquals(BigDecimal("1234.56"), PriceParser.parse("€1.234,56"))
        assertEquals(BigDecimal("1234.56"), PriceParser.parse("$1,234.56"))
        assertEquals(BigDecimal("1234.56"), PriceParser.parse("CHF 1’234.56"))
        assertEquals(BigDecimal("1234.56"), PriceParser.parse("1\u202f234,56 €"))
        assertEquals(BigDecimal("1234"), PriceParser.parse("1,234", NumberFormat.DOT_DECIMAL))
        assertEquals(BigDecimal("1.234"), PriceParser.parse("1,234", NumberFormat.COMMA_DECIMAL))
        assertEquals(BigDecimal("99"), PriceParser.parse("99 EUR"))
    }

    @Test fun `ambiguous and multiple amounts are rejected`() {
        listOf("1,234", "was $150.00 now $99.00", "-9.99", "Free", "12,34.56", "1.2.3").forEach { input ->
            assertThrows("Should reject $input", IllegalArgumentException::class.java) { PriceParser.parse(input) }
        }
    }

    @Test fun `invalid settings fail before saving`() {
        assertNotNull(monitor().copy(url = "javascript:alert(1)").validate())
        assertNotNull(monitor().copy(minSeconds = 0).validate())
        assertNotNull(monitor().copy(minSeconds = 20, maxSeconds = 10).validate())
        assertNotNull(monitor().copy(selector = "[").validate())
        assertNotNull(monitor(Trigger.PRICE).copy(threshold = "-1").validate())
        assertNull(monitor().validate())
    }

    @Test fun `random intervals stay within inclusive range and vary`() {
        val monitor = monitor().copy(minSeconds = 1, maxSeconds = 3)
        val random = Random(123)
        val delays = List(500) { monitor.randomDelayMillis(random) }
        assertTrue(delays.all { it in 1000..3000 })
        assertTrue(delays.distinct().size > 100)
        assertEquals(2000L, monitor.copy(minSeconds = 2, maxSeconds = 2).randomDelayMillis(random))
    }
}
