package com.clinscout.pagealertlive

import kotlin.random.Random

object RequestUserAgents {
    private val profiles = listOf(138, 140, 142, 144).flatMap { version -> listOf(
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Android 14; Mobile; rv:$version.0) Gecko/$version.0 Firefox/$version.0"
    ) }
    private var previous = -1

    @Synchronized fun next(): String {
        val draw = Random.nextInt(if (previous < 0) profiles.size else profiles.size - 1)
        val index = if (previous >= 0 && draw >= previous) draw + 1 else draw
        previous = index
        return profiles[index]
    }
}
