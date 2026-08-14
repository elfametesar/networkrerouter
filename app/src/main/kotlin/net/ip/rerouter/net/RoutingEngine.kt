package net.ip.rerouter.net

import net.ip.rerouter.model.RouteRule
import net.ip.rerouter.model.SourceInterfaceType
import net.ip.rerouter.root.RootShell

class RoutingEngine {
    private val chainPrefix = "IPRR"

    suspend fun applyRule(rule: RouteRule): Boolean {
        val table = rule.tableId
        val mark = table
        val chain = "${chainPrefix}_${rule.id}"

        /*
         * Treat a non-wlan0 wlan interface as hotspot-capable whenever the
         * rule explicitly includes hotspot clients. This keeps the routing
         * path independent of any stale UI state about interface type.
         */
        val hotspotRule =
            rule.sourceType == SourceInterfaceType.LOCAL_AND_HOTSPOT &&
                isHotspotInterface(rule.fromInterface)

        val commands = mutableListOf<String>()

        if (hotspotRule) {
            /*
             * Remove only our previous iif rule for this exact interface/table.
             */
            commands.add(
                "while read -r p; do ip rule del priority \$p 2>/dev/null || true; done <<EOF\n" +
                    "\$(ip rule | awk -v iface=${shellQuote(rule.fromInterface)} -v table=$table '$0 ~ /from all iif/ && index($0, \"iif \" iface \" lookup \" table)==0 {print $1+0}')\nEOF"
            )

            commands.add(
                "ip route replace default dev ${shellQuote(rule.toInterface)} table $table"
            )

            /*
             * Android's tethering rule is commonly 17000/21000. We need to
             * run before the earliest existing wlan* iif rule, not merely pick
             * an arbitrary 20xxx priority.
             *
             * Read the current rule priorities in the Kotlin process so we do
             * not depend on shell escaping or awk portability for the value.
             */
            val currentRules = RootShell.exec("ip rule").out
            val usedPriorities = currentRules.mapNotNull { line ->
                line.substringBefore(":").trim().toIntOrNull()
            }.toSet()

            val wlanPriorities = currentRules.mapNotNull { line ->
                if (line.contains("from all iif ${rule.fromInterface} ")) {
                    line.substringBefore(":").trim().toIntOrNull()
                } else {
                    null
                }
            }

            val upperBound = (wlanPriorities.minOrNull() ?: 20000) - 1
            val priority = generateSequence(upperBound) { it - 1 }
                .firstOrNull { it > 0 && it !in usedPriorities }
                ?: return false

            commands.add(
                "ip rule add iif ${shellQuote(rule.fromInterface)} lookup $table priority $priority"
            )

            /* Android tetherctrl otherwise ends in DROP for unknown paths. */
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
            commands.add(
                "iptables -t mangle -A $chain -j MARK --set-mark $mark"
            )
            commands.add(
                "ip rule del fwmark $mark table $table 2>/dev/null || true"
            )
            commands.add(
                "ip rule add fwmark $mark table $table priority ${1000 + table}"
            )
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
        val hotspotRule =
            rule.sourceType == SourceInterfaceType.LOCAL_AND_HOTSPOT &&
                isHotspotInterface(rule.fromInterface)

        val commands = mutableListOf<String>()

        if (hotspotRule) {
            val currentRules = RootShell.exec("ip rule").out
            val priorities = currentRules.mapNotNull { line ->
                if (
                    line.contains("from all iif ${rule.fromInterface} ") &&
                    line.contains("lookup $table")
                ) {
                    line.substringBefore(":").trim().toIntOrNull()
                } else {
                    null
                }
            }

            priorities.forEach { priority ->
                commands.add(
                    "ip rule del priority $priority 2>/dev/null || true"
                )
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
            commands.add(
                "iptables -t mangle -F $chain 2>/dev/null || true"
            )
            commands.add(
                "iptables -t mangle -X $chain 2>/dev/null || true"
            )
            commands.add(
                "ip rule del fwmark $mark table $table 2>/dev/null || true"
            )
            commands.add(
                "ip route flush table $table 2>/dev/null || true"
            )
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
        val result = RootShell.exec(
            "ip rule add uidrange $proxyUid-$proxyUid lookup ${shellQuote(realTable)} priority $priority 2>/dev/null || true"
        )
        return result.isSuccess
    }

    suspend fun removeProxyAppExemption(proxyUid: Int): Boolean {
        val result = RootShell.exec(
            "ip rule del uidrange $proxyUid-$proxyUid 2>/dev/null || true"
        )
        return result.isSuccess
    }

    private fun isHotspotInterface(name: String): Boolean =
        when {
            name == "wlan0" -> false
            name.startsWith("wlan") -> true
            name.startsWith("swlan") -> true
            name == "ap0" -> true
            else -> false
        }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    suspend fun resetAll(
        rules: List<RouteRule>,
        createdInterfaces: List<String>
    ): Boolean {
        val teardown = rules.map { removeRule(it) }
        val cleanupCommands = mutableListOf<String>()

        cleanupCommands.add(
            "for c in \$(iptables -t mangle -S | grep -o \"${chainPrefix}_[a-zA-Z0-9]*\" | sort -u); do " +
                "iptables -t mangle -F \$c 2>/dev/null; " +
                "iptables -t mangle -X \$c 2>/dev/null; done"
        )

        for (name in createdInterfaces) {
            cleanupCommands.add(
                "ip link delete ${shellQuote(name)} 2>/dev/null || true"
            )
        }

        cleanupCommands.add("echo 0 > /proc/sys/net/ipv4/ip_forward")

        val cleanupResults = RootShell.execSequential(cleanupCommands)
        return teardown.all { it } && cleanupResults.all { it.isSuccess }
    }
}
