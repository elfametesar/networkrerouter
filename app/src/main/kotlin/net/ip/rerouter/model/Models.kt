package net.ip.rerouter.model

import kotlinx.serialization.Serializable

/** A network interface as seen on the device, real or app-created. */
@Serializable
data class NetInterface(
    val name: String,
    val isUp: Boolean,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val mac: String? = null,
    val mtu: Int? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    /** True if this interface was created by the app (tun/dummy we own), vs a system one. */
    val isAppCreated: Boolean = false,
    val kind: InterfaceKind = InterfaceKind.OTHER,
    /** Default gateway for this interface, read live from `ip route`, if any. */
    val gateway: String? = null,
    /** DNS servers currently associated with this interface (from `getprop net.dns*` / resolvers), if discoverable. */
    val dnsServers: List<String> = emptyList()
)

enum class InterfaceKind {
    WIFI, CELLULAR, VPN_TUN, ETHERNET, LOOPBACK, DUMMY, BRIDGE, OTHER;

    companion object {
        fun fromName(name: String): InterfaceKind = when {
            name.startsWith("wlan") -> WIFI
            name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("radio") -> CELLULAR
            name.startsWith("tun") || name.startsWith("ppp") -> VPN_TUN
            name.startsWith("eth") -> ETHERNET
            name == "lo" -> LOOPBACK
            name.startsWith("dummy") -> DUMMY
            name.startsWith("br") -> BRIDGE
            else -> OTHER
        }
    }
}

/** Specifies whether a rule applies to local traffic only or includes hotspot clients. */
enum class SourceInterfaceType {
    /** Only route local apps' traffic through this rule. */
    LOCAL_ONLY,
    /** Route both local apps and hotspot clients through this rule. */
    LOCAL_AND_HOTSPOT
}

/** A routing rule the user has created: forward/route traffic from one interface to another. */
@Serializable
data class RouteRule(
    val id: String,
    val fromInterface: String,
    val toInterface: String,
    val enabled: Boolean = true,
    /** Custom routing table number allocated for this rule (100-252 user range). */
    val tableId: Int,
    /** UIDs of apps excluded from this specific rule. */
    val excludedUids: Set<Int> = emptySet(),
    val useMasquerade: Boolean = true,
    /** Package name of proxy app to exempt (if any) to prevent deadlock. */
    val proxyAppPackage: String? = null,
    /** Whether to include hotspot clients in this rule. */
    val sourceType: SourceInterfaceType = SourceInterfaceType.LOCAL_ONLY
)

/** An installed app, for the exclusion picker. */
@Serializable
data class AppInfo(
    val uid: Int,
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean = false
)

/** Persisted app state: created interfaces, active rules. Used to support "reset all". */
@Serializable
data class AppState(
    val createdInterfaces: List<String> = emptyList(),
    val rules: List<RouteRule> = emptyList(),
    val nextTableId: Int = 100,
    val tun2socksSessions: List<Tun2socksConfig> = emptyList()
)

/** Proxy protocols tun2socks can dial out through. */
enum class ProxyProtocol { SOCKS5, HTTP, SHADOWSOCKS, DIRECT }

/**
 * Configuration for a tun2socks session: reads IP packets off a TUN device
 * this app owns and forwards each TCP/UDP flow through a proxy.
 */
@Serializable
data class Tun2socksConfig(
    val id: String,
    /** The TUN interface this session reads/writes packets on. Must already exist (see InterfaceRepository.createTunInterface). */
    val tunInterface: String,
    val proxyProtocol: ProxyProtocol = ProxyProtocol.SOCKS5,
    /** host:port of the upstream proxy, e.g. "127.0.0.1:1080". */
    val proxyAddress: String = "",
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    /** MTU to advertise on the gVisor stack; should match the TUN interface's MTU. */
    val mtu: Int = 1500,
    /** Optional loopback API port for /netstats and /debug/pprof; null disables it. */
    val apiPort: Int? = null,
    val enabled: Boolean = true
)
