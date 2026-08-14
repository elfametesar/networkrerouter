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
    val kind: InterfaceKind = InterfaceKind.OTHER
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
    val useMasquerade: Boolean = true
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
    val nextTableId: Int = 100
)
