package com.clinscout.pagealertlive

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.math.BigDecimal

data class PriceCandidate(val selector: String, val price: BigDecimal, val preview: String, val numberFormat: NumberFormat)

object PriceDiscovery {
    private val currency = Regex("[€$£¥]|\\b(?:EUR|USD|GBP|CHF|CAD|AUD|JPY|PLN|SEK|NOK|DKK)\\b", RegexOption.IGNORE_CASE)
    private fun normalize(text: String) = text.replace(Regex("([.,])\\s+(\\d{2})(?=\\D|$)"), "$1$2")

    fun read(element: Element, format: NumberFormat): BigDecimal {
        val machine = when {
            element.hasAttr("content") -> element.attr("content")
            element.hasAttr("value") -> element.attr("value")
            element.hasAttr("data-price") -> element.attr("data-price")
            else -> null
        }
        if (machine != null) return PriceParser.parse(machine, if (element.tagName() == "meta" || element.hasAttr("data-price")) NumberFormat.DOT_DECIMAL else format).stripTrailingZeros()
        // Many shops duplicate the visual price for screen readers. Prefer the
        // accessible copy, retaining the original when no such copy exists.
        val accessible = element.clone().apply { select("script,style,noscript,template,[aria-hidden=true]").remove() }.text()
        val options = listOf(accessible, element.text()).filter { it.isNotBlank() }.distinct()
        for (text in options) {
            runCatching { PriceParser.parse(normalize(text), format).stripTrailingZeros() }.getOrNull()?.let { return it }
        }
        throw IllegalArgumentException("Expected one price; choose a more specific price element.")
    }

    fun find(html: String, url: String, format: NumberFormat = NumberFormat.AUTO): List<PriceCandidate> {
        val document = Jsoup.parse(html, url)
        PageValidation.validate(document, url)
        document.select("script,style,noscript,template").remove()
        val results = linkedMapOf<String, PriceCandidate>()
        val elements = document.select("meta[itemprop=price],meta[property=product:price:amount],meta[property=og:price:amount],[itemprop=price],[data-price]") + document.body().getAllElements()
        for (element in elements.take(12000)) {
            if (element.tagName() in listOf("del", "s") || element.parents().any { it.tagName() in listOf("del", "s") || it.hasAttr("hidden") } || element.hasAttr("hidden")) continue
            val raw = element.text()
            val machine = element.hasAttr("content") || element.hasAttr("data-price") || element.attr("itemprop") == "price"
            if (!machine && (raw.length > 160 || !currency.containsMatchIn(raw))) continue
            val price = runCatching { read(element, format) }.getOrNull() ?: continue
            val stable = listOf("data-test", "data-testid", "id").firstNotNullOfOrNull { attribute ->
                element.attr(attribute).takeIf { it.isNotBlank() && it.length <= 120 }?.let { value ->
                    val css = "[$attribute=\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"]"
                    css.takeIf { document.select(it).size == 1 }
                }
            } ?: element.cssSelector()
            if (stable.length > 1500 || results.containsKey(stable)) continue
            val selectedFormat = if (element.tagName() == "meta" || element.hasAttr("data-price")) NumberFormat.DOT_DECIMAL else format
            results[stable] = PriceCandidate(stable, price, element.parent()?.text()?.take(160) ?: raw.take(160), selectedFormat)
            if (results.size >= 80) break
        }
        return results.values.sortedWith(compareByDescending<PriceCandidate> { it.selector.contains("price", true) }.thenBy { it.selector.length })
    }
}
