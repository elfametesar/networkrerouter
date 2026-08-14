package net.ip.rerouter.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.ip.rerouter.model.InterfaceKind
import net.ip.rerouter.model.NetInterface
import net.ip.rerouter.root.RootShell

/**
 * Everything to do with discovering, creating, and destroying interfaces.
 * Relies on `ip` (iproute2, present on essentially all modern Android builds)
 * rather than ifconfig/net-tools, which may be absent.
 */
class InterfaceRepository {

    private val json = Json { ignoreUnknownKeys = true }

    /** Lists all interfaces currently visible on the device, with live stats. */
    suspend fun listInterfaces(appCreatedNames: Set<String>): List<NetInterface> {
        val addrResult = RootShell.exec("ip -j addr show")
        val statsResult = RootShell.exec("cat /proc/net/dev")

        val stats = parseProcNetDev(statsResult.out)

        if (!addrResult.isSuccess || addrResult.out.isEmpty()) {
            return parsePlainIpAddr(appCreatedNames, stats)
        }

        return try {
            val raw = addrResult.out.joinToString("\n")
            val arr = json.parseToJsonElement(raw).jsonArray
            arr.map { el -> toNetInterface(el.jsonObject, appCreatedNames, stats) }
        } catch (e: Exception) {
            parsePlainIpAddr(appCreatedNames, stats)
        }
    }

    private fun toNetInterface(
        obj: JsonObject,
        appCreatedNames: Set<String>,
        stats: Map<String, Pair<Long, Long>>
    ): NetInterface {
        val name = obj["ifname"]?.jsonPrimitive?.content ?: "unknown"
        val flags = (obj["flags"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
        val isUp = flags.contains("UP")
        val mac = obj["address"]?.jsonPrimitive?.content
        val mtu = obj["mtu"]?.jsonPrimitive?.content?.toIntOrNull()

        val addrInfo = (obj["addr_info"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
        val ipv4 = addrInfo.firstOrNull { it["family"]?.jsonPrimitive?.content == "inet" }
            ?.get("local")?.jsonPrimitive?.content
        val ipv6 = addrInfo.firstOrNull { it["family"]?.jsonPrimitive?.content == "inet6" }
            ?.get("local")?.jsonPrimitive?.content

        val (rx, tx) = stats[name] ?: (0L to 0L)

        return NetInterface(
            name = name,
            isUp = isUp,
            ipv4 = ipv4,
            ipv6 = ipv6,
            mac = mac,
            mtu = mtu,
            rxBytes = rx,
            txBytes = tx,
            isAppCreated = name in appCreatedNames,
            kind = InterfaceKind.fromName(name)
        )
    }

    /** Parses `/proc/net/dev` for RX/TX byte counters, keyed by interface name. */
    private fun parseProcNetDev(lines: List<String>): Map<String, Pair<Long, Long>> {
        val result = mutableMapOf<String, Pair<Long, Long>>()
        for (line in lines) {
            if (!line.contains(":")) continue
            val (namePart, statsPart) = line.split(":", limit = 2).takeIf { it.size == 2 } ?: continue
            val name = namePart.trim()
            val fields = statsPart.trim().split(Regex("\\s+"))
            if (fields.size < 9) continue
            val rx = fields[0].toLongOrNull() ?: 0L
            val tx = fields[8].toLongOrNull() ?: 0L
            result[name] = rx to tx
        }
        return result
    }

    /** Fallback parser for plain-text `ip addr` output on the rare device without `-j` support. */
    private suspend fun parsePlainIpAddr(
        appCreatedNames: Set<String>,
        stats: Map<String, Pair<Long, Long>>
    ): List<NetInterface> {
        val plain = RootShell.exec("ip addr show")
        val interfaces = mutableListOf<NetInterface>()
        var currentName: String? = null
        var currentUp = false
        var currentMac: String? = null
        var currentMtu: Int? = null
        var currentIpv4: String? = null
        var currentIpv6: String? = null

        fun flush() {
            val n = currentName ?: return
            val (rx, tx) = stats[n] ?: (0L to 0L)
            interfaces.add(
                NetInterface(
                    name = n, isUp = currentUp, ipv4 = currentIpv4, ipv6 = currentIpv6,
                    mac = currentMac, mtu = currentMtu, rxBytes = rx, txBytes = tx,
                    isAppCreated = n in appCreatedNames, kind = InterfaceKind.fromName(n)
                )
            )
        }

        val ifaceHeader = Regex("""^\\d+:\\s+([^:@]+)(@\\S+)?:\\s+<([^>]*)>.*mtu (\\d+)""")
        for (line in plain.out) {
            val headerMatch = ifaceHeader.find(line)
            if (headerMatch != null) {
                flush()
                currentName = headerMatch.groupValues[1].trim()
                val flags = headerMatch.groupValues[3]
                currentUp = flags.contains("UP")
                currentMtu = headerMatch.groupValues[4].toIntOrNull()
                currentMac = null; currentIpv4 = null; currentIpv6 = null
                continue
            }
            val trimmed = line.trim()
            when {
                trimmed.startsWith("link/") -> {
                    val parts = trimmed.split(Regex("\\s+"))
                    if (parts.size > 1) currentMac = parts[1]
                }
                trimmed.startsWith("inet ") -> {
                    currentIpv4 = trimmed.split(Regex("\\s+")).getOrNull(1)?.substringBefore("/")
                }
                trimmed.startsWith("inet6 ") -> {
                    if (currentIpv6 == null) {
                        currentIpv6 = trimmed.split(Regex("\\s+")).getOrNull(1)?.substringBefore("/")
                    }
                }
            }
        }
        flush()
        return interfaces
    }

    /** Creates a new tun interface owned by the app, optionally assigning an IPv4 address. */
    suspend fun createTunInterface(name: String, ipCidr: String? = null): Boolean {
        val createResult = RootShell.exec("ip tuntap add dev $name mode tun")
        if (!createResult.isSuccess) return false

        if (!ipCidr.isNullOrBlank()) {
            val addrResult = RootShell.exec("ip addr add $ipCidr dev $name")
            if (!addrResult.isSuccess) return false
        }

        val upResult = RootShell.exec("ip link set $name up")
        return upResult.isSuccess
    }

    /** Creates a dummy interface (useful as a routing endpoint/black-hole target). */
    suspend fun createDummyInterface(name: String, ipCidr: String? = null): Boolean {
        val createResult = RootShell.exec("ip link add $name type dummy")
        if (!createResult.isSuccess) return false

        if (!ipCidr.isNullOrBlank()) {
            val addrResult = RootShell.exec("ip addr add $ipCidr dev $name")
            if (!addrResult.isSuccess) return false
        }

        val upResult = RootShell.exec("ip link set $name up")
        return upResult.isSuccess
    }

    /** Assigns (or reassigns) an IPv4 address on an existing interface. */
    suspend fun setInterfaceAddress(name: String, ipCidr: String): Boolean {
        val result = RootShell.exec("ip addr replace $ipCidr dev $name")
        return result.isSuccess
    }

    /** Removes an app-created interface. Refuses to touch real system interfaces by name pattern. */
    suspend fun removeInterface(name: String): Boolean {
        val result = RootShell.exec("ip link delete $name")
        return result.isSuccess
    }

    suspend fun setInterfaceState(name: String, up: Boolean): Boolean {
        val state = if (up) "up" else "down"
        val result = RootShell.exec("ip link set $name $state")
        return result.isSuccess
    }

    /** Detects whether an interface is a hotspot/tethering interface. */
    fun isHotspotInterface(name: String): Boolean =
        name.startsWith("wlan") && name != "wlan0"

    /**
     * Detects the numeric routing table that actually carries the device's
     * primary cellular traffic. Do not return the interface name here: Android
     * interface names (e.g. rmnet_mhi0) are not necessarily valid iproute2
     * routing-table names. The routing engine uses this value for UID
     * exemptions, so it must be a numeric table ID (or a name backed by
     * rt_tables).
     *
     * Resolution order:
     *  1. Look for a default route on an active cellular interface in `ip route
     *     show table all` and extract its table token.
     *  2. If the token is a named table, resolve it through rt_tables.
     *  3. Fall back to the kernel policy rules: inspect every numeric lookup
     *     table and select one whose default route uses the cellular interface.
     */
    suspend fun detectRealRoutingTable(): String? {
        val cellular = listInterfaces(emptySet())
            .firstOrNull { it.kind == InterfaceKind.CELLULAR && it.isUp }
            ?.name
            ?: return null

        val routeLines = RootShell.exec("ip route show table all").out
        val tableNameToId = mutableMapOf<String, String>()
        RootShell.exec("cat /etc/iproute2/rt_tables /system/etc/iproute2/rt_tables 2>/dev/null").out.forEach { line ->
            val clean = line.substringBefore('#').trim()
            val parts = clean.split(Regex("\\s+"))
            val id = parts.getOrNull(0)?.toIntOrNull()
            val name = parts.getOrNull(1)
            if (id != null && !name.isNullOrBlank()) tableNameToId[name] = id.toString()
        }

        val defaultRoute = routeLines.firstOrNull { line ->
            line.trimStart().startsWith("default ") && Regex("\\bdev\\s+${Regex.escape(cellular)}(?:\\s|$)").containsMatchIn(line)
        }
        if (defaultRoute != null) {
            val token = Regex("\\btable\\s+(\\S+)").find(defaultRoute)?.groupValues?.getOrNull(1)
            if (!token.isNullOrBlank()) {
                token.toIntOrNull()?.let { return it.toString() }
                tableNameToId[token]?.let { return it }
            }
        }

        // Android's `ip rule` may expose a named table even when the name is
        // not in rt_tables. Probe the numeric tables instead of passing the
        // interface name (which is exactly what caused `invalid table ID`).
        for (table in 100..252) {
            val result = RootShell.exec("ip route show table $table")
            if (result.out.any { line ->
                    line.trimStart().startsWith("default ") &&
                        Regex("\\bdev\\s+${Regex.escape(cellular)}(?:\\s|$)").containsMatchIn(line)
                }) {
                return table.toString()
            }
        }
        return null
    }
}
