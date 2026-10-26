package com.clinscout.pagealertlive

import org.junit.Assert.*
import org.junit.Test

class CombinedConditionsTest {
    private val monitor = Monitor(name = "Both", url = "https://example.com", keyword = "in stock", selector = "#stock",
        checkPrice = true, priceSelector = "#price", threshold = "50", requireAllConditions = false)
    private val html = "<p id='stock'>In stock</p><p id='price'>€99.00</p>"

    @Test fun `default combined check rejects a misspelled keyword even when price matches`() {
        val default = Monitor(name = "Delivery", url = "https://example.com", keyword = "Gewöhnlich versandfertiigg",
            checkPrice = true, priceSelector = "#price", threshold = "100")
        val page = "<p>Gewöhnlich versandfertig</p><p id='price'>€49.00</p>"
        val result = TriggerEvaluator.evaluate(default, page)
        assertFalse(result.matched)
        assertEquals(false, result.keywordMatched)
        assertEquals(true, result.priceMatched)
        assertTrue(TriggerEvaluator.evaluate(default.copy(keyword = "Gewöhnlich versandfertig"), page).matched)
        assertTrue(TriggerEvaluator.evaluate(default.copy(requireAllConditions = false), page).matched)
    }

    @Test fun `either rule can alert independently and requiring both waits for both`() {
        val either = TriggerEvaluator.evaluate(monitor, html)
        assertTrue(either.matched)
        assertEquals(true, either.keywordMatched)
        assertEquals(false, either.priceMatched)
        assertFalse(TriggerEvaluator.evaluate(monitor.copy(requireAllConditions = true), html).matched)
        val cheaper = html.replace("99.00", "49.00")
        assertTrue(TriggerEvaluator.evaluate(monitor.copy(requireAllConditions = true), cheaper).matched)
        val unavailable = html.replace("In stock", "Sold out").replace("99.00", "49.00")
        assertTrue(TriggerEvaluator.evaluate(monitor, unavailable).matched)
        assertFalse(TriggerEvaluator.evaluate(monitor.copy(requireAllConditions = true), unavailable).matched)
    }

    @Test fun `one condition error does not hide a successful independent condition`() {
        val brokenPrice = TriggerEvaluator.evaluate(monitor.copy(priceSelector = ".missing"), html)
        assertTrue(brokenPrice.matched)
        assertNull(brokenPrice.priceMatched)
        assertTrue(brokenPrice.priceDetail.startsWith("Price error:"))
        assertFalse(TriggerEvaluator.evaluate(monitor.copy(priceSelector = ".missing", requireAllConditions = true), html).matched)
        val brokenKeyword = TriggerEvaluator.evaluate(monitor.copy(selector = ".missing", threshold = "100"), html)
        assertTrue(brokenKeyword.matched)
        assertNull(brokenKeyword.keywordMatched)
        assertEquals(true, brokenKeyword.priceMatched)
    }

    @Test fun `keyword missing and price use separate scopes and validation`() {
        val missing = monitor.copy(trigger = Trigger.KEYWORD_MISSING, keyword = "sold out", threshold = "100", requireAllConditions = true)
        assertTrue(TriggerEvaluator.evaluate(missing, html).matched)
        assertNotNull(missing.copy(threshold = "bad").validate())
        assertNotNull(missing.copy(priceSelector = "[").validate())
        assertNull(missing.copy(checkPrice = false, threshold = "bad", priceSelector = "[").validate())
        assertTrue(TriggerEvaluator.evaluate(missing.copy(trigger = Trigger.PRICE, checkPrice = false, selector = "#price", priceSelector = ""), html).matched)
    }
}
