package com.clinscout.pagealertlive

import java.text.DateFormat
import java.util.Date

data class ConnectionDebug(
    val checkedAt: Long,
    val requested: String,
    val used: String,
    val outcome: String,
    val fallbackReason: String = "",
    val network: String = ""
) {
    fun report(): String = buildList {
        add("Time: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(checkedAt))}")
        add("Requested: $requested")
        add("Used: $used")
        if (fallbackReason.isNotBlank()) add("Fallback reason: $fallbackReason")
        add("Outcome: $outcome")
        if (network.isNotBlank()) add("Network at attempt:\n$network")
    }.joinToString("\n")

    companion object {
        fun failure(error: Throwable): String = generateSequence(error) { it.cause }.take(4)
            .map { "${it.javaClass.simpleName}: ${it.message.orEmpty().replace(Regex("[\\r\\n\\t]+"), " ").take(768)}" }
            .distinct().joinToString(" → ").take(2048)
    }
}
