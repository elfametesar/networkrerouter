package net.ip.rerouter.net

import net.ip.rerouter.model.RouteRule
import net.ip.rerouter.model.SourceInterfaceType
import net.ip.rerouter.root.RootShell

class RoutingEngine {
    private val chainPrefix = "IPRR"

    private fun isHotspotInterface(name: String): Boolean = when {
        name == "wlan0" -> false
        name.startsWith("wlan") -> true
        name.startsWith("swlan") -> true
        name == "ap0" -> true
        else -> false
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private suspend fun findHotspotPolicyPriority(interfaceName: String): Int? {
        val lines = RootShell.exec("ip rule").out
        val used = lines.mapNotNull { line ->
            line.substringBefore(":").trim().toIntOrNull()
        }.toSet()

        val existingForInterface = lines.mapNotNull { line ->
            if (line.contains("from all iif $interfaceName ")) {
                line.substringBefore(":").trim().toIntOrNull()
            } else null
        }

        val upperBound = (existingForInterface.minOrNull() ?: 20000) - 1
        return generateSequence(upperBound) { it - 1 }
            .firstOrNull { it > 0 && it !in used }
    }

    private suspend fun findExistingHotspotRulePriorities(
        interfaceName: String,
        table: Int
    ): List<Int> {
        return RootShell.exec("ip rule").out.mapNotNull { line ->
            if (
                line.contains("from all iif $interfaceName ") &&
                line.contains("lookup $table")
            ) {
                line.substringBefore(":").trim().toIntOrNull()
            } else null
        }
    }

    /**
     * Hotspot mode is ingress-oriented.
     *
     * If the user selected wlan1 -> tun1, use wlan1 as ingress and tun1 as
     * tunnel as-is.
     *
     * If the user selected tun1 -> wlan1 (which is a natural choice when
     * thinking in terms of packet exit direction), automatically invert the
     * policy for hotspot clients: wlan1 -> tun1.
     */
    private fun hotspotEndpoints(rule: RouteRule): Pair<String, String>? {
        if (rule.sourceType != SourceInterfaceType.LOCAL_AND_HOTSPOT) return null

        return when {
            isHotspotInterface(rule.fromInterface) ->
                rule.fromInterface to rule.toInterface

            isHotspotInterface(rule.toInterface) ->
                rule.toInterface to rule.fromInterface

            else -> null
        }
    }

    suspend fun applyRule(rule: RouteRule): Boolean {
        val table = rule.tableId
        val mark = table
        val chain = "${chainPrefix}_${rule.id}"
        val hotspot = hotspotEndpoints(rule)
        val hotspotRule = hotspot != null
        val commands = mutableListOf<String>()

        if (hotspotRule) {
            val (hotspotInterface, tunnelInterface) = hotspot!!

            findExistingHotspotRulePriorities(hotspotInterface, table)
                .forEach { priority ->
                    commands.add("ip rule del priority $priority 2>/dev/null || true")
                }

            commands.add(
                "ip route replace default dev ${shellQuote(tunnelInterface)} table $table"
            )

            val priority = findHotspotPolicyPriority(hotspotInterface)
                ?: return false

            commands.add(
                "ip rule add iif ${shellQuote(hotspotInterface)} lookup $table priority $priority"
            )

            commands.add(
                "iptables -C tetherctrl_FORWARD -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -j ACCEPT 2>/dev/null || " +
                    "iptables -I tetherctrl_FORWARD 1 -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -j ACCEPT"
            )
            commands.add(
                "iptables -C tetherctrl_FORWARD -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -j ACCEPT 2>/dev/null || " +
                    "iptables -I tetherctrl_FORWARD 1 -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -j ACCEPT"
            )

            if (rule.useMasquerade) {
                commands.add(
                    "iptables -t nat -C tetherctrl_nat_POSTROUTING -o ${shellQuote(tunnelInterface)} -j MASQUERADE 2>/dev/null || " +
                        "iptables -t nat -I tetherctrl_nat_POSTROUTING 1 -o ${shellQuote(tunnelInterface)} -j MASQUERADE"
                )
            }

            commands.add(
                "sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || echo 1 > /proc/sys/net/ipv4/ip_forward"
            )
        } else {
            commands.add(
                "iptables -t mangle -N $chain 2>/dev/null || iptables -t mangle -F $chain"
            )
            commands.add(
                "iptables -t mangle -C PREROUTING -i ${shellQuote(rule.fromInterface)} -j $chain 2>/dev/null || " +
                    "iptables -t mangle -A PREROUTING -i ${shellQuote(rule.fromInterface)} -j $chain"
            )
            for (uid in rule.excludedUids) {
                commands.add(
                    "iptables -t mangle -A $chain -m owner --uid-owner $uid -j RETURN"
                )
            }
            commands.add("iptables -t mangle -A $chain -j MARK --set-mark $mark")
            commands.add("ip rule del fwmark $mark table $table 2>/dev/null || true")
            commands.add("ip rule add fwmark $mark table $table priority ${1000 + table}")
            commands.add(
                "ip route replace default dev ${shellQuote(rule.toInterface)} table $table"
            )
            if (rule.useMasquerade) {
                commands.add(
                    "iptables -t nat -C POSTROUTING -o ${shellQuote(rule.toInterface)} -j MASQUERADE 2>/dev/null || " +
                        "iptables -t nat -A POSTROUTING -o ${shellQuote(rule.toInterface)} -j MASQUERADE"
                )
            }
        }

        val results = RootShell.execSequential(commands)
        return results.isNotEmpty() && results.all { it.isSuccess }
    }

    suspend fun removeRule(rule: RouteRule): Boolean {
        val table = rule.tableId
        val mark = table
        val chain = "${chainPrefix}_${rule.id}"
        val hotspot = hotspotEndpoints(rule)
        val commands = mutableListOf<String>()

        if (hotspot != null) {
            val (hotspotInterface, tunnelInterface) = hotspot

            findExistingHotspotRulePriorities(hotspotInterface, table)
                .forEach { priority ->
                    commands.add("ip rule del priority $priority 2>/dev/null || true")
                }
            commands.add("ip route flush table $table 2>/dev/null || true")
            commands.add(
                "iptables -D tetherctrl_FORWARD -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -j ACCEPT 2>/dev/null || true"
            )
            commands.add(
                "iptables -D tetherctrl_FORWARD -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -j ACCEPT 2>/dev/null || true"
            )
            commands.add(
                "iptables -t nat -D tetherctrl_nat_POSTROUTING -o ${shellQuote(tunnelInterface)} -j MASQUERADE 2>/dev/null || true"
            )
        } else {
            commands.add(
                "iptables -t mangle -D PREROUTING -i ${shellQuote(rule.fromInterface)} -j $chain 2>/dev/null || true"
            )
            commands.add("iptables -t mangle -F $chain 2>/dev/null || true")
            commands.add("iptables -t mangle -X $chain 2>/dev/null || true")
            commands.add("ip rule del fwmark $mark table $table 2>/dev/null || true")
            commands.add("ip route flush table $table 2>/dev/null || true")
            commands.add(
                "iptables -t nat -D POSTROUTING -o ${shellQuote(rule.toInterface)} -j MASQUERADE 2>/dev/null || true"
            )
        }

        val results = RootShell.execSequential(commands)
        return results.all { it.isSuccess }
    }

    suspend fun exemptProxyApp(
        proxyUid: Int,
        realTable: String,
        priority: Int = 20400
    ): Boolean {
        return RootShell.exec(
            "ip rule add uidrange $proxyUid-$proxyUid lookup ${shellQuote(realTable)} priority $priority 2>/dev/null || true"
        ).isSuccess
    }

    suspend fun removeProxyAppExemption(proxyUid: Int): Boolean {
        return RootShell.exec(
            "ip rule del uidrange $proxyUid-$proxyUid 2>/dev/null || true"
        ).isSuccess
    }

    suspend fun resetAll(
        rules: List<RouteRule>,
        createdInterfaces: List<String>
    ): Boolean {
        val teardown = rules.map { removeRule(it) }
        val cleanupCommands = mutableListOf<String>()

        cleanupCommands.add(
            "for c in \$(iptables -t mangle -S | grep -o \"${chainPrefix}_[a-zA-Z0-9]*\" | sort -u); do " +
                "iptables -t mangle -F \$c 2>/dev/null; iptables -t mangle -X \$c 2>/dev/null; done"
        )

        for (name in createdInterfaces) {
            cleanupCommands.add("ip link delete ${shellQuote(name)} 2>/dev/null || true")
        }

        cleanupCommands.add("echo 0 > /proc/sys/net/ipv4/ip_forward")
        val cleanupResults = RootShell.execSequential(cleanupCommands)
        return teardown.all { it } && cleanupResults.all { it.isSuccess }
    }
}
