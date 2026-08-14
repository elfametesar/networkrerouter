package net.ip.rerouter.net

import net.ip.rerouter.model.RouteRule
import net.ip.rerouter.model.SourceInterfaceType
import net.ip.rerouter.root.RootShell

class RoutingEngine {
    private val chainPrefix = "IPRR"

    // Android tethering priorities vary by device. We inspect live rules and
    // place our hotspot rule before the first existing rule for that interface.
    private val hotspotFallbackUpper = 16999
    private val hotspotPriorityFloor = 10000

    suspend fun applyRule(rule: RouteRule): Boolean {
        val table = rule.tableId
        val mark = table
        val chain = "${chainPrefix}_${rule.id}"
        val hotspotRule =
            rule.sourceType == SourceInterfaceType.LOCAL_AND_HOTSPOT &&
                isHotspotInterface(rule.fromInterface)

        val commands = mutableListOf<String>()

        if (hotspotRule) {
            // Remove existing app-created iif rules for this interface/table.
            // We calculate priorities in Kotlin to avoid shell-variable escaping bugs.
            val existingPriorities = findIifRulePriorities(rule.fromInterface, table)
            for (priority in existingPriorities) {
                commands.add("ip rule del priority $priority 2>/dev/null || true")
            }

            commands.add(
                "ip route replace default dev ${shellEscape(rule.toInterface)} table $table"
            )

            // Crucial: choose a priority BEFORE Android's first existing
            // wlan/tethering rule, not after it.
            val priority = findHotspotPriority(
                interfaceName = rule.fromInterface,
                ignoredPriorities = existingPriorities.toSet()
            ) ?: return false

            commands.add(
                "ip rule add iif ${shellEscape(rule.fromInterface)} " +
                    "lookup $table priority $priority"
            )
        } else {
            // Existing local/fwmark routing behavior.
            commands.add(
                "iptables -t mangle -N $chain 2>/dev/null || " +
                    "iptables -t mangle -F $chain"
            )
            commands.add(
                "iptables -t mangle -C PREROUTING " +
                    "-i ${shellEscape(rule.fromInterface)} -j $chain 2>/dev/null || " +
                    "iptables -t mangle -A PREROUTING " +
                    "-i ${shellEscape(rule.fromInterface)} -j $chain"
            )
            for (uid in rule.excludedUids) {
                commands.add(
                    "iptables -t mangle -A $chain " +
                        "-m owner --uid-owner $uid -j RETURN"
                )
            }
            commands.add("iptables -t mangle -A $chain -j MARK --set-mark $mark")
            commands.add("ip rule del fwmark $mark table $table 2>/dev/null || true")
            commands.add("ip rule add fwmark $mark table $table priority ${1000 + table}")
            commands.add(
                "ip route replace default dev ${shellEscape(rule.toInterface)} table $table"
            )
        }

        // Local routing NAT. Hotspot NAT is installed in tetherctrl below.
        if (rule.useMasquerade && !hotspotRule) {
            commands.add(
                "iptables -t nat -C POSTROUTING -o ${shellEscape(rule.toInterface)} " +
                    "-j MASQUERADE 2>/dev/null || " +
                    "iptables -t nat -A POSTROUTING -o ${shellEscape(rule.toInterface)} " +
                    "-j MASQUERADE"
            )
        }

        if (hotspotRule) {
            // Android's tethering firewall would otherwise drop the tunnel path.
            commands.add(
                "iptables -C tetherctrl_FORWARD " +
                    "-i ${shellEscape(rule.fromInterface)} " +
                    "-o ${shellEscape(rule.toInterface)} -j ACCEPT 2>/dev/null || " +
                    "iptables -I tetherctrl_FORWARD 1 " +
                    "-i ${shellEscape(rule.fromInterface)} " +
                    "-o ${shellEscape(rule.toInterface)} -j ACCEPT"
            )
            commands.add(
                "iptables -C tetherctrl_FORWARD " +
                    "-i ${shellEscape(rule.toInterface)} " +
                    "-o ${shellEscape(rule.fromInterface)} -j ACCEPT 2>/dev/null || " +
                    "iptables -I tetherctrl_FORWARD 1 " +
                    "-i ${shellEscape(rule.toInterface)} " +
                    "-o ${shellEscape(rule.fromInterface)} -j ACCEPT"
            )

            if (rule.useMasquerade) {
                commands.add(
                    "iptables -t nat -C tetherctrl_nat_POSTROUTING " +
                        "-o ${shellEscape(rule.toInterface)} -j MASQUERADE 2>/dev/null || " +
                        "iptables -t nat -I tetherctrl_nat_POSTROUTING 1 " +
                        "-o ${shellEscape(rule.toInterface)} -j MASQUERADE"
                )
            }

            commands.add(
                "sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || " +
                    "echo 1 > /proc/sys/net/ipv4/ip_forward"
            )
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
            val priorities = findIifRulePriorities(rule.fromInterface, table)
            for (priority in priorities) {
                commands.add("ip rule del priority $priority 2>/dev/null || true")
            }
            commands.add("ip route flush table $table 2>/dev/null || true")
            commands.add(
                "iptables -D tetherctrl_FORWARD " +
                    "-i ${shellEscape(rule.fromInterface)} " +
                    "-o ${shellEscape(rule.toInterface)} -j ACCEPT 2>/dev/null || true"
            )
            commands.add(
                "iptables -D tetherctrl_FORWARD " +
                    "-i ${shellEscape(rule.toInterface)} " +
                    "-o ${shellEscape(rule.fromInterface)} -j ACCEPT 2>/dev/null || true"
            )
            commands.add(
                "iptables -t nat -D tetherctrl_nat_POSTROUTING " +
                    "-o ${shellEscape(rule.toInterface)} -j MASQUERADE 2>/dev/null || true"
            )
        } else {
            commands.add(
                "iptables -t mangle -D PREROUTING -i ${shellEscape(rule.fromInterface)} " +
                    "-j $chain 2>/dev/null || true"
            )
            commands.add("iptables -t mangle -F $chain 2>/dev/null || true")
            commands.add("iptables -t mangle -X $chain 2>/dev/null || true")
            commands.add("ip rule del fwmark $mark table $table 2>/dev/null || true")
            commands.add("ip route flush table $table 2>/dev/null || true")
            commands.add(
                "iptables -t nat -D POSTROUTING -o ${shellEscape(rule.toInterface)} " +
                    "-j MASQUERADE 2>/dev/null || true"
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
            "ip rule add uidrange $proxyUid-$proxyUid " +
                "lookup ${shellEscape(realTable)} priority $priority 2>/dev/null || true"
        )
        return result.isSuccess
    }

    suspend fun removeProxyAppExemption(proxyUid: Int): Boolean {
        val result = RootShell.exec(
            "ip rule del uidrange $proxyUid-$proxyUid 2>/dev/null || true"
        )
        return result.isSuccess
    }

    private fun isHotspotInterface(name: String): Boolean = when {
        name == "wlan0" -> false
        name.startsWith("wlan") -> true
        name.startsWith("swlan") -> true
        name == "ap0" -> true
        else -> false
    }

    /** Returns existing priorities matching: iif <interface> lookup <table>. */
    private suspend fun findIifRulePriorities(
        interfaceName: String,
        table: Int
    ): List<Int> {
        val result = RootShell.exec("ip rule")
        if (!result.isSuccess) return emptyList()

        val pattern = Regex(
            "^\\s*(\\d+):\\s+from all iif " +
                Regex.escape(interfaceName) +
                "(?:\\s+|$).*\\blookup\\s+" + table +
                "(?:\\s|$)"
        )

        return result.out.mapNotNull { line ->
            pattern.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.distinct()
    }

    /**
     * Finds a free priority strictly before Android's first existing policy
     * rule for this hotspot interface.
     *
     * On the target device we observed:
     *   17000: from all iif wlan1 lookup 1045
     *   20500: from all iif wlan1 lookup 200   <-- our old rule
     *   21000: from all iif wlan1 lookup 1014
     *
     * The old 20500 priority therefore lost to Android's 17000 rule.
     */
    private suspend fun findHotspotPriority(
        interfaceName: String,
        ignoredPriorities: Set<Int>
    ): Int? {
        val result = RootShell.exec("ip rule")
        if (!result.isSuccess) return null

        val used = result.out.mapNotNull { line ->
            Regex("^\\s*(\\d+):").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.toSet() - ignoredPriorities

        val interfacePattern = Regex(
            "^\\s*(\\d+):\\s+from all iif " +
                Regex.escape(interfaceName) +
                "(?:\\s|$)"
        )

        val firstAndroidPriority = result.out.mapNotNull { line ->
            interfacePattern.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.filter { it !in ignoredPriorities }
            .minOrNull()

        var candidate = firstAndroidPriority?.minus(1) ?: hotspotFallbackUpper
        if (candidate >= 20000) candidate = hotspotFallbackUpper

        while (candidate >= hotspotPriorityFloor) {
            if (candidate !in used) return candidate
            candidate--
        }

        return null
    }

    private fun shellEscape(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    suspend fun resetAll(
        rules: List<RouteRule>,
        createdInterfaces: List<String>
    ): Boolean {
        val teardown = rules.map { removeRule(it) }
        val cleanupCommands = mutableListOf<String>()

        cleanupCommands.add(
            "for c in \$(iptables -t mangle -S | " +
                "grep -o \"${chainPrefix}_[a-zA-Z0-9]*\" | sort -u); do " +
                "iptables -t mangle -F \$c 2>/dev/null; " +
                "iptables -t mangle -X \$c 2>/dev/null; done"
        )

        for (name in createdInterfaces) {
            cleanupCommands.add(
                "ip link delete ${shellEscape(name)} 2>/dev/null || true"
            )
        }

        cleanupCommands.add("echo 0 > /proc/sys/net/ipv4/ip_forward")

        val cleanupResults = RootShell.execSequential(cleanupCommands)
        return teardown.all { it } && cleanupResults.all { it.isSuccess }
    }
}
