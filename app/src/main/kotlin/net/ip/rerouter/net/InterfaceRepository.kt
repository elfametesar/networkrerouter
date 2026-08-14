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
 * Live network-interface discovery.
 *
 * Important: Android's ConnectivityManager is intentionally not used here.
 * Root routing sees kernel interfaces that may not be exposed as ordinary
 * Android networks (for example a hotspot wlan1 or a root-created tun device).
 */
class InterfaceRepository {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listInterfaces(appCreatedNames: Set<String>): List<NetInterface> {
        val linkResult = RootShell.exec("ip -j link show")
        val addrResult = RootShell.exec("ip -j addr show")
        val statsResult = RootShell.exec("cat /proc/net/dev")
        val stats = parseProcNetDev(statsResult.out)

        val linkObjects = parseJsonArray(linkResult.out)
        val addrObjects = parseJsonArray(addrResult.out)

        if (linkObjects.isNotEmpty()) {
            val addrByName = addrObjects.associateBy {
                it["ifname"]?.jsonPrimitive?.content.orEmpty()
            }

            return linkObjects.mapNotNull { link ->
                val name = link["ifname"]?.jsonPrimitive?.content ?: return@mapNotNull null
                toNetInterface(
                    link = link,
                    addr = addrByName[name],
                    appCreatedNames = appCreatedNames,
                    stats = stats
                )
            }
        }

        // Fallback for older/unusual iproute2 builds.
        if (addrObjects.isNotEmpty()) {
            return addrObjects.mapNotNull { addr ->
                val name = addr["ifname"]?.jsonPrimitive?.content ?: return@mapNotNull null
                toNetInterface(
                    link = addr,
                    addr = addr,
                    appCreatedNames = appCreatedNames,
                    stats = stats
                )
            }
        }

        return parsePlainIpAddr(appCreatedNames, stats)
    }

    private fun parseJsonArray(lines: List<String>): List<JsonObject> {
        if (lines.isEmpty()) return emptyList()
        return runCatching {
            json.parseToJsonElement(lines.joinToString("\n")).jsonArray
                .map { it.jsonObject }
        }.getOrDefault(emptyList())
    }

    private fun toNetInterface(
        link: JsonObject,
        addr: JsonObject?,
        appCreatedNames: Set<String>,
        stats: Map<String, Pair<Long, Long>>
    ): NetInterface {
        val name = link["ifname"]?.jsonPrimitive?.content ?: "unknown"
        val flags = (link["flags"] as? JsonArray)
            ?.map { it.jsonPrimitive.content }
            ?: emptyList()

        val isUp = "UP" in flags
        val mac = link["address"]?.jsonPrimitive?.content
        val mtu = link["mtu"]?.jsonPrimitive?.content?.toIntOrNull()

        val addrInfo = (addr?.get("addr_info") as? JsonArray)
            ?.map { it.jsonObject }
            ?: emptyList()

        val ipv4 = addrInfo.firstOrNull {
            it["family"]?.jsonPrimitive?.content == "inet"
        }?.get("local")?.jsonPrimitive?.content

        val ipv6 = addrInfo.firstOrNull {
            it["family"]?.jsonPrimitive?.content == "inet6"
        }?.get("local")?.jsonPrimitive?.content

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

    private fun parseProcNetDev(lines: List<String>): Map<String, Pair<Long, Long>> {
        val result = mutableMapOf<String, Pair<Long, Long>>()

        for (line in lines) {
            if (!line.contains(":")) continue

            val parts = line.split(":", limit = 2)
            if (parts.size != 2) continue

            val name = parts[0].trim()
            val fields = parts[1].trim().split(Regex("\\s+"))
            if (fields.size < 9) continue

            val rx = fields[0].toLongOrNull() ?: 0L
            val tx = fields[8].toLongOrNull() ?: 0L
            result[name] = rx to tx
        }

        return result
    }

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
            val name = currentName ?: return
            val (rx, tx) = stats[name] ?: (0L to 0L)

            interfaces += NetInterface(
                name = name,
                isUp = currentUp,
                ipv4 = currentIpv4,
                ipv6 = currentIpv6,
                mac = currentMac,
                mtu = currentMtu,
                rxBytes = rx,
                txBytes = tx,
                isAppCreated = name in appCreatedNames,
                kind = InterfaceKind.fromName(name)
            )
        }

        val header = Regex("""^\d+:\s+([^:@]+)(@\S+)?:\s+<([^>]*)>.*mtu (\d+)""")

        for (line in plain.out) {
            val match = header.find(line)
            if (match != null) {
                flush()
                currentName = match.groupValues[1].trim()
                currentUp = "UP" in match.groupValues[3].split(",")
                currentMtu = match.groupValues[4].toIntOrNull()
                currentMac = null
                currentIpv4 = null
                currentIpv6 = null
                continue
            }

            when {
                line.trim().startsWith("link/") -> {
                    val parts = line.trim().split(Regex("\\s+"))
                    currentMac = parts.getOrNull(1)
                }

                line.trim().startsWith("inet ") -> {
                    currentIpv4 = line.trim()
                        .split(Regex("\\s+"))
                        .getOrNull(1)
                        ?.substringBefore("/")
                }

                line.trim().startsWith("inet6 ") && currentIpv6 == null -> {
                    currentIpv6 = line.trim()
                        .split(Regex("\\s+"))
                        .getOrNull(1)
                        ?.substringBefore("/")
                }
            }
        }

        flush()
        return interfaces
    }

    suspend fun createTunInterface(name: String): Boolean =
        RootShell.exec("ip tuntap add dev $name mode tun && ip link set $name up").isSuccess

    suspend fun createDummyInterface(name: String): Boolean =
        RootShell.exec("ip link add $name type dummy && ip link set $name up").isSuccess

    suspend fun removeInterface(name: String): Boolean =
        RootShell.exec("ip link delete $name").isSuccess

    suspend fun setInterfaceState(name: String, up: Boolean): Boolean {
        val state = if (up) "up" else "down"
        return RootShell.exec("ip link set $name $state").isSuccess
    }
}
