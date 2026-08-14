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

    suspend fun listInterfaces(appCreatedNames: Set<String>): List<NetInterface> {
        val addrResult = RootShell.exec("ip -j addr show")
        val statsResult = RootShell.exec("cat /proc/net/dev")
        val stats = parseProcNetDev(statsResult.out)

        if (!addrResult.isSuccess || addrResult.out.isEmpty()) return parsePlainIpAddr(appCreatedNames, stats)

        return try {
            val raw = addrResult.out.joinToString("\n")
            val arr = json.parseToJsonElement(raw).jsonArray
            arr.map { el -> toNetInterface(el.jsonObject, appCreatedNames, stats) }
        } catch (_: Exception) {
            parsePlainIpAddr(appCreatedNames, stats)
        }
    }

    private fun toNetInterface(obj: JsonObject, appCreatedNames: Set<String>, stats: Map<String, Pair<Long, Long>>): NetInterface {
        val name = obj["ifname"]?.jsonPrimitive?.content ?: "unknown"
        val flags = (obj["flags"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
        val isUp = flags.contains("UP")
        val mac = obj["address"]?.jsonPrimitive?.content
        val mtu = obj["mtu"]?.jsonPrimitive?.content?.toIntOrNull()
        val addrInfo = (obj["addr_info"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
        val ipv4 = addrInfo.firstOrNull { it["family"]?.jsonPrimitive?.content == "inet" }?.get("local")?.jsonPrimitive?.content
        val ipv6 = addrInfo.firstOrNull { it["family"]?.jsonPrimitive?.content == "inet6" }?.get("local")?.jsonPrimitive?.content
        val (rx, tx) = stats[name] ?: (0L to 0L)

        return NetInterface(name, isUp, ipv4, ipv6, mac, mtu, rx, tx, name in appCreatedNames, InterfaceKind.fromName(name))
    }

    private fun parseProcNetDev(lines: List<String>): Map<String, Pair<Long, Long>> {
        val result = mutableMapOf<String, Pair<Long, Long>>()
        for (line in lines) {
            if (!line.contains(":")) continue
            val parts = line.split(":", limit = 2)
            if (parts.size != 2) continue
            val name = parts[0].trim()
            val fields = parts[1].trim().split(Regex("\\s+"))
            if (fields.size < 9) continue
            result[name] = (fields[0].toLongOrNull() ?: 0L) to (fields[8].toLongOrNull() ?: 0L)
        }
        return result
    }

    private suspend fun parsePlainIpAddr(appCreatedNames: Set<String>, stats: Map<String, Pair<Long, Long>>): List<NetInterface> {
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
            interfaces.add(NetInterface(n, currentUp, currentIpv4, currentIpv6, currentMac, currentMtu, rx, tx, n in appCreatedNames, InterfaceKind.fromName(n)))
        }

        val ifaceHeader = Regex("""^\\d+:\\s+([^:@]+)(@\\S+)?:\\s+<([^>]*)>.*mtu (\\d+)""")
        for (line in plain.out) {
            val headerMatch = ifaceHeader.find(line)
            if (headerMatch != null) {
                flush()
                currentName = headerMatch.groupValues[1].trim()
                currentUp = headerMatch.groupValues[3].contains("UP")
                currentMtu = headerMatch.groupValues[4].toIntOrNull()
                currentMac = null; currentIpv4 = null; currentIpv6 = null
                continue
            }
            when {
                line.trim().startsWith("link/") -> currentMac = line.trim().split(Regex("\\s+")).getOrNull(1)
                line.trim().startsWith("inet ") -> currentIpv4 = line.trim().split(Regex("\\s+")).getOrNull(1)?.substringBefore("/")
                line.trim().startsWith("inet6 ") && currentIpv6 == null -> currentIpv6 = line.trim().split(Regex("\\s+")).getOrNull(1)?.substringBefore("/")
            }
        }
        flush()
        return interfaces
    }

    suspend fun createTunInterface(name: String, ipCidr: String? = null): Boolean {
        val createResult = RootShell.exec("ip tuntap add dev $name mode tun")
        if (!createResult.isSuccess) return false
        if (!ipCidr.isNullOrBlank() && !RootShell.exec("ip addr add $ipCidr dev $name").isSuccess) return false
        return RootShell.exec("ip link set $name up").isSuccess
    }

    suspend fun createDummyInterface(name: String, ipCidr: String? = null): Boolean {
        val createResult = RootShell.exec("ip link add $name type dummy")
        if (!createResult.isSuccess) return false
        if (!ipCidr.isNullOrBlank() && !RootShell.exec("ip addr add $ipCidr dev $name").isSuccess) return false
        return RootShell.exec("ip link set $name up").isSuccess
    }

    suspend fun setInterfaceAddress(name: String, ipCidr: String): Boolean = RootShell.exec("ip addr replace $ipCidr dev $name").isSuccess
    suspend fun removeInterface(name: String): Boolean = RootShell.exec("ip link delete $name").isSuccess
    suspend fun setInterfaceState(name: String, up: Boolean): Boolean = RootShell.exec("ip link set $name ${if (up) "up" else "down"}").isSuccess

    fun isHotspotInterface(name: String): Boolean = name.startsWith("wlan") && name != "wlan0"

    /** Finds the actual routing table used by the active cellular interface. */
    suspend fun detectRealRoutingTable(): String? {
        val cellular = listInterfaces(emptySet())
            .firstOrNull { it.kind == InterfaceKind.CELLULAR && it.isUp }
            ?.name ?: return null

        val ruleLines = RootShell.exec("ip rule").out
        val knownLookupNames = ruleLines.mapNotNull { line ->
            Regex("\\b(?:lookup|table)\\s+([A-Za-z_][A-Za-z0-9_]*)").find(line)?.groupValues?.getOrNull(1)
        }.toSet()

        val routeLines = RootShell.exec("ip route show table all").out
        val defaultRoute = routeLines.firstOrNull { line ->
            line.trimStart().startsWith("default ") &&
                Regex("\\bdev\\s+${Regex.escape(cellular)}(?:\\s|$)").containsMatchIn(line)
        }
        val token = defaultRoute?.let { Regex("\\btable\\s+(\\S+)").find(it)?.groupValues?.getOrNull(1) }
        if (!token.isNullOrBlank()) {
            token.toIntOrNull()?.let { return it.toString() }
            if (token in knownLookupNames) return token
        }

        // Android often exposes the real table only by name in `ip rule`.
        for (name in knownLookupNames) {
            val result = RootShell.exec("ip route show table ${shellQuote(name)}")
            if (result.out.any { line ->
                    line.trimStart().startsWith("default ") &&
                        Regex("\\bdev\\s+${Regex.escape(cellular)}(?:\\s|$)").containsMatchIn(line)
                }) return name
        }

        for (table in 100..252) {
            val result = RootShell.exec("ip route show table $table")
            if (result.out.any { line ->
                    line.trimStart().startsWith("default ") &&
                        Regex("\\bdev\\s+${Regex.escape(cellular)}(?:\\s|$)").containsMatchIn(line)
                }) return table.toString()
        }
        return null
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
