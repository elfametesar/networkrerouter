package net.ip.rerouter.net

import net.ip.rerouter.model.RouteRule
import net.ip.rerouter.root.RootShell

class RoutingEngine {
    private val chainPrefix = "IPRR"

    /**
     * Hotspot interfaces are routed by incoming-interface policy rules.
     * This is intentionally automatic: selecting wlan1/swlan/ap0 as the source
     * is sufficient; the route does not depend on the UI hotspot toggle.
     */
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

    suspend fun applyRule(rule: net.ip.rerouter.model.RouteRule): Boolean {
        val table = rule.tableId
        val mark = table
        val chain = "${chainPrefix}_${rule.id}"
        val hotspotRule = isHotspotInterface(rule.fromInterface)
        val commands = mutableListOf<String>()

        if (hotspotRule) {
            /* Remove stale app-created rules for this exact interface/table. */
            findExistingHotspotRulePriorities(rule.fromInterface, table)
                .forEach { priority ->
                    commands.add("ip rule del priority $priority 2>/dev/null || true")
                }

            /* This is the working hotspot path: wlan1 -> table -> tun1. */
            commands.add(
                "ip route replace default dev ${shellQuote(rule.toInterface)} table $table"
            )

            val priority = findHotspotPolicyPriority(rule.fromInterface)
                ?: return false

            commands.add(
                "ip rule add iif ${shellQuote(rule.fromInterface)} lookup $table priority $priority"
            )

            /* Android tetherctrl otherwise drops unknown tether paths. */
            commands.add(
                "iptables -C tetherctrl_FORWARD -i ${shellQuote(rule.fromInterface)} -o ${shellQuote(rule.toInterface)} -j ACCEPT 2>/dev/null || " +
                    "iptables -I tetherctrl_FORWARD 1 -i ${shellQuote(rule.fromInterface)} -o ${shellQuote(rule.toInterface)} -j ACCEPT"
            )
            commands.add(
                "iptables -C tetherctrl_FORWARD -i ${shellQuote(rule.toInterface)} -o ${shellQuote(rule.fromInterface)} -j ACCEPT 2>/dev/null || " +
                    "iptables -I tetherctrl_FORWARD 1 -i ${shellQuote(rule.toInterface)} -o ${shellQuote(rule.fromInterface)} -j ACCEPT"
            )

            if (rule.useMasquerade) {
                commands.add(
                    "iptables -t nat -C tetherctrl_nat_POSTROUTING -o ${shellQuote(rule.toInterface)} -j MASQUERADE 2>/dev/null || " +
                        "iptables -t nat -I tetherctrl_nat_POSTROUTING 1 -o ${shellQuote(rule.toInterface)} -j MASQUERADE"
                )
            }

            commands.add(
                "sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || echo 1 > /proc/sys/net/ipv4/ip_forward"
            )
        } else {
            /* Existing fwmark path for non-hotspot interfaces. */
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

    suspend fun removeRule(rule: net.ip.rerouter.model.RouteRule): Boolean {
        val table = rule.tableId
        val mark = table
        val chain = "${chainPrefix}_${rule.id}"
        val hotspotRule = isHotspotInterface(rule.fromInterface)
        val commands = mutableListOf<String>()

        if (hotspotRule) {
            findExistingHotspotRulePriorities(rule.fromInterface, table)
                .forEach { priority ->
                    commands.add("ip rule del priority $priority 2>/dev/null || true")
                }
            commands.add("ip route flush table $table 2>/dev/null || true")
            commands.add(
                "iptables -D tetherctrl_FORWARD -i ${shellQuote(rule.fromInterface)} -o ${shellQuote(rule.toInterface)} -j ACCEPT 2>/dev/null || true"
            )
            commands.add(
                "iptables -D tetherctrl_FORWARD -i ${shellQuote(rule.toInterface)} -o ${shellQuote(rule.fromInterface)} -j ACCEPT 2>/dev/null || true"
            )
            commands.add(
                "iptables -t nat -D tetherctrl_nat_POSTROUTING -o ${shellQuote(rule.toInterface)} -j MASQUERADE 2>/dev/null || true"
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
        rules: List<net.ip.rerouter.model.RouteRule>,
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
