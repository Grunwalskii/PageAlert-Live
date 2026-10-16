package com.clinscout.pagealertlive

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import kotlinx.coroutines.delay
import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address

object DeviceNetworkState {
    fun read(context: Context): DeviceConnections {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val vpn = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val hasMobile = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val mobile = if (airplane || !hasMobile) false else try {
            context.getSystemService(TelephonyManager::class.java)?.isDataEnabled == true
        } catch (_: SecurityException) {
            true // ACCESS_NETWORK_STATE normally suffices; OEMs may restrict it.
        } catch (_: RuntimeException) {
            // An OEM may restrict this query. Treat unknown as enabled and
            // wait for connectivity, rather than claiming the user turned it off.
            true
        }
        return DeviceConnections(wifi, mobile, vpn)
    }

    fun wifiSupport(context: Context, network: Network? = null): WifiIpSupport {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        // Mobile data or a VPN can be the default while Wi-Fi is still connected.
        // An explicit request network must never be replaced with another link.
        val wifi = network ?: wifiNetwork(manager.activeNetwork, manager.allNetworks.toList()) { isWifi(manager.getNetworkCapabilities(it)) }
            ?: return WifiIpSupport()
        val capabilities = manager.getNetworkCapabilities(wifi) ?: return WifiIpSupport()
        if (!isWifi(capabilities)) return WifiIpSupport()
        val link = manager.getLinkProperties(wifi) ?: return WifiIpSupport()
        return WifiIpSupport.detect(link.linkAddresses.map { it.address },
            link.routes.any { it.isDefaultRoute && it.destination.address is Inet4Address },
            link.routes.any { it.isDefaultRoute && it.destination.address is Inet6Address })
    }

    internal fun wifiNetwork(active: Network?, available: List<Network>, isWifi: (Network) -> Boolean): Network? =
        (listOfNotNull(active) + available).distinct().firstOrNull(isWifi)

    fun wifiDetails(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager.activeNetwork
        val wifi = wifiNetwork(active, manager.allNetworks.toList()) { isWifi(manager.getNetworkCapabilities(it)) }
        return "VPN active: ${manager.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true}\n" +
            "Default network: ${active?.networkHandle ?: "none"}\nWi-Fi:\n${networkDetails(context, wifi)}"
    }

    fun networkDetails(context: Context, network: Network?): String {
        if (network == null) return "Network unavailable"
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(network)
        val link = manager.getLinkProperties(network) ?: return "Network ${network.networkHandle}: link properties unavailable"
        val v4Default = link.routes.any { it.isDefaultRoute && it.destination.address is Inet4Address }
        val v6Default = link.routes.any { it.isDefaultRoute && it.destination.address is Inet6Address }
        val support = WifiIpSupport.detect(link.linkAddresses.map { it.address }, v4Default, v6Default)
        return buildList {
            add("Network: ${network.networkHandle} • Interface: ${link.interfaceName ?: "unknown"}")
            add("Validated: ${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}")
            add("IPv4 usable: ${support.ipv4} • IPv6 usable: ${support.ipv6}")
            add("IPv4 default route: ${if (v4Default) "present" else "missing"}")
            add("IPv6 default route: ${if (v6Default) "present" else "missing"}")
            add("Addresses: ${link.linkAddresses.take(12).joinToString().ifBlank { "none" }}")
            add("Default routes: ${link.routes.filter { it.isDefaultRoute }.take(6).joinToString().ifBlank { "none" }}")
            add("DNS servers: ${link.dnsServers.take(6).joinToString { it.hostAddress.orEmpty() }.ifBlank { "none" }}")
            if (Build.VERSION.SDK_INT >= 28) {
                add("Private DNS: ${if (link.isPrivateDnsActive) "active" else "inactive"} • ${link.privateDnsServerName ?: "automatic/not specified"}")
            }
        }.joinToString("\n").take(4096)
    }

    private fun isWifi(capabilities: NetworkCapabilities?) = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

/** All callbacks and callers run on Main; requests retain at most two networks. */
class NetworkRouting(private val context: Context) : RouteChooser {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<NetworkPath, ConnectivityManager.NetworkCallback>()
    private val networks = mutableMapOf<NetworkPath, Network>()
    private var wanted = false
    private var mobileWanted = false

    fun reconcile(state: AppState) {
        wanted = state.running && state.monitors.any { it.enabled && (it.alternateNetworks || it.alternateIpFamilies) }
        mobileWanted = state.running && state.monitors.any { it.enabled && it.alternateNetworks }
        if (wanted) refresh(DeviceNetworkState.read(context)) else releaseRequests()
    }

    private fun refresh(device: DeviceConnections) {
        NetworkPath.entries.forEach { path ->
            if (wanted && (path != NetworkPath.MOBILE || mobileWanted) && !device.vpnActive && device.enabled(path)) {
                if (path !in callbacks) register(path)
            } else {
                callbacks.remove(path)?.let { callback ->
                    try { manager.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) { }
                }
                networks.remove(path)
            }
        }
    }

    private fun register(path: NetworkPath) {
        val transport = if (path == NetworkPath.WIFI) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR
        val request = NetworkRequest.Builder().addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (callbacks[path] !== this) return
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) networks[path] = network
                else if (networks[path] == network) networks.remove(path)
            }
            override fun onLost(network: Network) { if (callbacks[path] === this && networks[path] == network) networks.remove(path) }
        }
        callbacks[path] = callback
        try {
            if (path == NetworkPath.MOBILE) manager.requestNetwork(request, callback, handler)
            else manager.registerNetworkCallback(request, callback, handler)
        } catch (error: RuntimeException) {
            callbacks.remove(path)
            android.util.Log.w("PageAlertLive", "Could not request ${path.label}", error)
        }
    }

    override suspend fun choose(monitor: Monitor, turn: Long, wifiIpTurn: Long, waitMillis: Long, onWaiting: (String, Long) -> Unit): RequestRoute {
        if (!monitor.alternateNetworks) {
            val active = manager.activeNetwork
            val capabilities = manager.getNetworkCapabilities(active)
            if (monitor.alternateIpFamilies && active != null && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return route(monitor, NetworkPath.WIFI, active, wifiIpTurn)
            return systemRoute()
        }
        val deadline = SystemClock.elapsedRealtime() + waitMillis
        val dueAt = System.currentTimeMillis() + waitMillis
        while (true) {
            val device = DeviceNetworkState.read(context)
            refresh(device)
            // Preserve the user's VPN rather than silently routing around it.
            if (device.vpnActive) return systemRoute()
            // Keep both configured families in the cycle, including through
            // IPv6 loss or an IPv4-only DNS response. Each gets a fresh attempt.
            val slot = ConnectionRotation.select(device, monitor.alternateIpFamilies, turn)
                ?: ConnectionSlot(device.selectedPath(turn))
            val selected = slot.path
            val network = networks[selected].takeIf { device.enabled(selected) }
            if (network != null) return route(monitor, selected, network, wifiIpTurn, slot.family)
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw NetworkTurnSkipped(slot.label)
            onWaiting(slot.label, dueAt)
            delay(minOf(remaining, 500))
        }
    }

    private fun systemRoute(): RequestRoute {
        val active = manager.activeNetwork
        val capabilities = manager.getNetworkCapabilities(active)
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile data"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> return RequestRoute(networkDetails = DeviceNetworkState.networkDetails(context, active))
        }
        // Capture the transport when selecting the check; keep Android's routing unchanged.
        return RequestRoute(label = "$transport • System routing", networkDetails = DeviceNetworkState.networkDetails(context, active))
    }

    private fun route(monitor: Monitor, path: NetworkPath, network: Network, wifiIpTurn: Long, plannedFamily: IpFamily? = null): RequestRoute {
        val selected = if (path == NetworkPath.WIFI && monitor.alternateIpFamilies)
            plannedFamily ?: IpFamilies.select(wifiIpTurn) else null
        val base = RequestRoute(
            key = "${path.name}:${network.networkHandle}",
            label = path.label,
            socketFactory = network.socketFactory,
            dns = object : Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            }, path = path, networkDetails = DeviceNetworkState.networkDetails(context, network)
        )
        return selected?.let(base::withIpFamilyFallback) ?: base
    }

    private fun releaseRequests() {
        val retired = callbacks.values.toList()
        callbacks.clear()
        networks.clear()
        retired.forEach { try { manager.unregisterNetworkCallback(it) } catch (_: IllegalArgumentException) { } }
    }

    fun close() { wanted = false; releaseRequests() }
}
