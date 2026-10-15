package com.clinscout.pagealertlive

import okhttp3.Dns
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import javax.net.SocketFactory

enum class NetworkPath(val label: String) { WIFI("Wi-Fi"), MOBILE("Mobile data") }
enum class IpFamily(val label: String) { IPV4("IPv4"), IPV6("IPv6") }

data class WifiIpSupport(val ipv4: Boolean = false, val ipv6: Boolean = false) {
    val dualStack get() = ipv4 && ipv6

    companion object {
        fun detect(addresses: List<InetAddress>, ipv4Default: Boolean, ipv6Default: Boolean) = WifiIpSupport(
            ipv4Default && addresses.any { it is Inet4Address && !it.isAnyLocalAddress && !it.isLinkLocalAddress && !it.isLoopbackAddress },
            ipv6Default && addresses.any { it is Inet6Address && (it.address[0].toInt() and 0xe0) == 0x20 }
        )
    }
}

object IpFamilies {
    fun select(answers: List<InetAddress>, support: WifiIpSupport, wifiTurn: Long): IpFamily? {
        val v4 = support.ipv4 && answers.any { it is Inet4Address }
        val v6 = support.ipv6 && answers.any { it is Inet6Address }
        return when {
            v4 && v6 -> if (wifiTurn % 2 == 0L) IpFamily.IPV4 else IpFamily.IPV6
            v4 -> IpFamily.IPV4
            v6 -> IpFamily.IPV6
            else -> null
        }
    }

    fun filter(answers: List<InetAddress>, family: IpFamily): List<InetAddress> = answers.filter {
        if (family == IpFamily.IPV4) it is Inet4Address else it is Inet6Address
    }.ifEmpty { throw UnknownHostException("${family.label} is unavailable for this host.") }
}

data class DeviceConnections(val wifiEnabled: Boolean, val mobileEnabled: Boolean, val vpnActive: Boolean = false) {
    val note: String get() = when {
        vpnActive -> "VPN active • System connection"
        !wifiEnabled && !mobileEnabled -> "Wi-Fi and mobile data off"
        !wifiEnabled -> "Wi-Fi off • Mobile data only"
        !mobileEnabled -> "Mobile data off • Wi-Fi only"
        else -> ""
    }

    fun selectedPath(turn: Long): NetworkPath = when {
        !wifiEnabled && mobileEnabled -> NetworkPath.MOBILE
        wifiEnabled && !mobileEnabled -> NetworkPath.WIFI
        turn % 2 == 0L -> NetworkPath.WIFI
        else -> NetworkPath.MOBILE
    }

    fun enabled(path: NetworkPath) = if (path == NetworkPath.WIFI) wifiEnabled else mobileEnabled
}

data class ConnectionSlot(val path: NetworkPath, val family: IpFamily? = null) {
    val label get() = path.label + (family?.let { " • ${it.label}" } ?: "")
}

object ConnectionRotation {
    fun slots(device: DeviceConnections, wifi: WifiIpSupport, alternateIpFamilies: Boolean): List<ConnectionSlot> = buildList {
        if (device.wifiEnabled) {
            if (alternateIpFamilies && (wifi.ipv4 || wifi.ipv6)) {
                if (wifi.ipv4) add(ConnectionSlot(NetworkPath.WIFI, IpFamily.IPV4))
                if (wifi.ipv6) add(ConnectionSlot(NetworkPath.WIFI, IpFamily.IPV6))
            } else add(ConnectionSlot(NetworkPath.WIFI))
        }
        if (device.mobileEnabled) add(ConnectionSlot(NetworkPath.MOBILE))
    }

    fun select(device: DeviceConnections, wifi: WifiIpSupport, alternateIpFamilies: Boolean, turn: Long): ConnectionSlot? {
        val available = slots(device, wifi, alternateIpFamilies)
        return available.takeIf { it.isNotEmpty() }?.get(Math.floorMod(turn, available.size.toLong()).toInt())
    }
}

data class RequestRoute(
    val key: String = "system",
    val label: String = "System",
    val socketFactory: SocketFactory? = null,
    val dns: Dns? = null,
    val path: NetworkPath? = null,
    val family: IpFamily? = null,
    val note: String = ""
)

class NetworkTurnSkipped(val connection: String) : IOException("Skipped $connection check (connection unavailable).")

fun interface RouteChooser {
    suspend fun choose(monitor: Monitor, turn: Long, wifiIpTurn: Long, waitMillis: Long, onWaiting: (String, Long) -> Unit): RequestRoute
}
