package com.clinscout.pagealertlive

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

class PageValidationException(
    message: String,
    val retryImmediately: Boolean = true,
    val retryAfterMillis: Long = 0,
    val httpStatus: Int? = null,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

class MissingPageElementException(message: String) : IllegalArgumentException(message)

object PageValidation {
    private val rateMessages = setOf("too many requests", "zu viele anfragen", "request limit exceeded", "request rate limit exceeded")
    private val amazonHost = Regex("(?:^|\\.)amazon\\.(?:com|de|co\\.uk|fr|it|es|ca|co\\.jp|in|com\\.au|com\\.br|com\\.mx|nl|pl|se|com\\.be|ie|sg|ae|sa|com\\.tr)$")
    private val productPath = Regex("/(?:dp|gp/product|gp/aw/d)/([A-Z0-9]{10})(?:/|$)", RegexOption.IGNORE_CASE)

    private fun productId(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!amazonHost.containsMatchIn(parsed.host)) return null
        return productPath.find(parsed.encodedPath)?.groupValues?.get(1)?.uppercase()
    }

    fun validate(document: Document, requestedUrl: String) {
        val title = document.title().trim().lowercase()
        val body = document.body().text().trim()
        val text = body.lowercase()
        val heading = document.selectFirst("h1")?.text()?.trim()?.lowercase()
        if (title in rateMessages || heading in rateMessages ||
            (text.length <= 1024 && rateMessages.any { text.startsWith(it) })) {
            throw PageValidationException("Website returned a rate-limit page; waiting before the next check.",
                retryImmediately = false, retryAfterMillis = 60_000)
        }
        val challengeTitle = listOf("just a moment", "access denied", "verify you are human", "attention required").any { title.contains(it) } ||
            title in setOf("robot check", "amazon robot check", "amazon captcha")
        val challengeForm = document.select("form[action*=/errors/validateCaptcha], #captchacharacters, #challenge-form, #challenge-container").isNotEmpty()
        val challengeText = text.length < 4096 && listOf(
            "enter the characters you see below", "geben sie die zeichen unten ein", "verify that you're not a robot"
        ).any { text.contains(it) }
        if (challengeTitle || challengeForm || challengeText) {
            throw PageValidationException("Website returned an access challenge.")
        }
        if (title in setOf("service unavailable", "temporarily unavailable", "internal server error") ||
            (text.length < 4096 && title.startsWith("sorry") && text.contains("something went wrong"))) {
            throw PageValidationException("Website returned a temporary error page.")
        }
        val expectedProduct = productId(requestedUrl) ?: return
        // Inspect only the product's explicit identity, never recommendation cards.
        // A parent ASIN/canonical may legitimately identify the requested variation group.
        val identities = document.select("input#ASIN[value], input#parentASIN[value]")
            .map { it.attr("value").uppercase() }.filter { it.matches(Regex("[A-Z0-9]{10}")) }.toMutableSet()
        document.select("link[rel=canonical]").forEach { link -> productId(link.absUrl("href"))?.let(identities::add) }
        if (expectedProduct !in identities && document.selectFirst("#productTitle") == null &&
            document.select("form#ap_signin_form, form[name=signIn], input#ap_email").isNotEmpty()) {
            throw PageValidationException("Website returned a sign-in page instead of the product page.")
        }
        if (identities.isNotEmpty() && expectedProduct !in identities) {
            throw PageValidationException("Website returned a different product page (expected $expectedProduct).")
        }
    }
}
