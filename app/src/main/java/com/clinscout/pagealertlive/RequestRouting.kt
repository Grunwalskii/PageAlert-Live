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
    // Availability is diagnostic only: a missing address or route must not
    // remove a configured family's future attempts from the rotation.
    fun select(wifiTurn: Long): IpFamily = if (wifiTurn % 2 == 0L) IpFamily.IPV4 else IpFamily.IPV6

    fun filter(answers: List<InetAddress>, family: IpFamily): List<InetAddress> = answers.filter {
        if (family == IpFamily.IPV4) it is Inet4Address else it is Inet6Address
    }.ifEmpty { throw UnknownHostException("No ${family.label} DNS address for this host. Will retry on its next turn.") }

    fun requireLiteralFamily(host: String, family: IpFamily) {
        // OkHttp bypasses Dns.lookup for literal addresses, including redirects.
        if (':' in host || host.all { it in '0'..'9' || it == '.' }) {
            filter(listOf(InetAddress.getByName(host)), family)
        }
    }
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
    fun slots(device: DeviceConnections, alternateIpFamilies: Boolean): List<ConnectionSlot> = buildList {
        if (device.wifiEnabled) {
            if (alternateIpFamilies) {
                add(ConnectionSlot(NetworkPath.WIFI, IpFamily.IPV4))
                add(ConnectionSlot(NetworkPath.WIFI, IpFamily.IPV6))
            } else add(ConnectionSlot(NetworkPath.WIFI))
        }
        if (device.mobileEnabled) add(ConnectionSlot(NetworkPath.MOBILE))
    }

    fun select(device: DeviceConnections, alternateIpFamilies: Boolean, turn: Long): ConnectionSlot? {
        val available = slots(device, alternateIpFamilies)
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
    val note: String = "",
    val fallback: RequestRoute? = null,
    val ipFallback: Boolean = false
) {
    fun withIpFamilyFallback(preferred: IpFamily): RequestRoute {
        val resolver = requireNotNull(dns)
        fun single(family: IpFamily) = copy(
            key = "$key:${family.name}", label = "$label • ${family.label}",
            dns = object : Dns {
                override fun lookup(hostname: String) = IpFamilies.filter(resolver.lookup(hostname), family)
            }, family = family, fallback = null, ipFallback = false
        )
        val other = if (preferred == IpFamily.IPV4) IpFamily.IPV6 else IpFamily.IPV4
        return single(preferred).copy(fallback = single(other))
    }
}

class NetworkTurnSkipped(val connection: String) : IOException("Skipped $connection check (connection unavailable).")

fun interface RouteChooser {
    suspend fun choose(monitor: Monitor, turn: Long, wifiIpTurn: Long, waitMillis: Long, onWaiting: (String, Long) -> Unit): RequestRoute
}
