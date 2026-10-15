package com.clinscout.pagealertlive

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    private fun isWifi(capabilities: NetworkCapabilities?) = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

/** All callbacks and callers run on Main; requests retain at most two networks. */
class NetworkRouting(private val context: Context) : RouteChooser {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<NetworkPath, ConnectivityManager.NetworkCallback>()
    private val networks = mutableMapOf<NetworkPath, Network>()
    private val wifiPlans = mutableMapOf<String, Pair<Monitor, WifiIpSupport>>()
    private var wanted = false
    private var mobileWanted = false

    fun reconcile(state: AppState) {
        wanted = state.running && state.monitors.any { it.enabled && (it.alternateNetworks || it.alternateIpFamilies) }
        mobileWanted = state.running && state.monitors.any { it.enabled && it.alternateNetworks }
        wifiPlans.keys.retainAll(state.monitors.filter { it.enabled }.map { it.id }.toSet())
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
            // Rotate over individual request paths, rather than alternating
            // transports and giving mobile a turn after every Wi-Fi family.
            // Retain the last Wi-Fi plan through a temporary network loss.
            var support = wifiPlans[monitor.id]?.takeIf { it.first == monitor }?.second
                ?: DeviceNetworkState.wifiSupport(context, networks[NetworkPath.WIFI])
            var slot = ConnectionRotation.select(device, support, monitor.alternateIpFamilies, turn)
                ?: ConnectionSlot(device.selectedPath(turn))
            if (slot.path == NetworkPath.WIFI && monitor.alternateIpFamilies && device.wifiEnabled) {
                networks[NetworkPath.WIFI]?.let { wifi ->
                    val link = DeviceNetworkState.wifiSupport(context, wifi)
                    val answers = withContext(Dispatchers.IO) { wifi.getAllByName(monitor.url.toHttpUrl().host).toList() }
                    support = WifiIpSupport(link.ipv4 && answers.any { it is Inet4Address }, link.ipv6 && answers.any { it is Inet6Address })
                    wifiPlans[monitor.id] = monitor to support
                    slot = ConnectionRotation.select(device, support, true, turn) ?: slot
                }
            }
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
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile data"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> return RequestRoute()
        }
        // Capture the transport when selecting the check; keep Android's routing unchanged.
        return RequestRoute(label = "$transport • System routing")
    }

    private suspend fun route(monitor: Monitor, path: NetworkPath, network: Network, wifiIpTurn: Long, plannedFamily: IpFamily? = null): RequestRoute {
        var family: IpFamily? = null
        var note = ""
        if (path == NetworkPath.WIFI && monitor.alternateIpFamilies) {
            val support = DeviceNetworkState.wifiSupport(context, network)
            val host = monitor.url.toHttpUrl().host
            val answers = withContext(Dispatchers.IO) { network.getAllByName(host).toList() }
            family = plannedFamily ?: IpFamilies.select(answers, support, wifiIpTurn)
            if (support.dualStack && family != null && !(answers.any { it is Inet4Address } && answers.any { it is Inet6Address })) {
                note = "Site supports ${family.label} only"
            }
        }
        val selected = family
        return RequestRoute(
            key = "${path.name}:${network.networkHandle}:${selected?.name ?: "AUTO"}",
            label = path.label + (selected?.let { " • ${it.label}" } ?: ""),
            socketFactory = network.socketFactory,
            dns = object : Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList().let { answers ->
                    if (selected == null) answers else IpFamilies.filter(answers, selected)
                }
            }, path = path, family = selected, note = note
        )
    }

    private fun releaseRequests() {
        val retired = callbacks.values.toList()
        callbacks.clear()
        networks.clear()
        retired.forEach { try { manager.unregisterNetworkCallback(it) } catch (_: IllegalArgumentException) { } }
    }

    fun close() { wanted = false; releaseRequests() }
}
