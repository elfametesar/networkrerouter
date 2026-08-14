package net.ip.rerouter.net

import net.ip.rerouter.model.RouteRule
import net.ip.rerouter.model.SourceInterfaceType
import net.ip.rerouter.root.RootShell

class RoutingEngine {
    private val chainPrefix = "IPRR"

    private data class RpFilterState(
        val all: String?,
        val hotspot: String?,
        val tunnel: String?
    )

    private val rpFilterStates = mutableMapOf<String, RpFilterState>()

    private fun isHotspotInterface(name: String): Boolean = when {
        name == "wlan0" -> false
        name.startsWith("wlan") -> true
        name.startsWith("swlan") -> true
        name == "ap0" -> true
        else -> false
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private suspend fun findFreePriority(min: Int, max: Int): Int? {
        val used = RootShell.exec("ip rule").out.mapNotNull { line ->
            line.substringBefore(":").trim().toIntOrNull()
        }.toSet()
        return (max downTo min).firstOrNull { it !in used }
    }

    private suspend fun findIifLoTableRules(table: Int): List<Int> =
        RootShell.exec("ip rule").out.mapNotNull { line ->
            if (line.contains("from all iif lo ") && line.contains("lookup $table")) {
                line.substringBefore(":").trim().toIntOrNull()
            } else null
        }

    private suspend fun findUidTableRules(uid: Int, table: String): List<Int> =
        RootShell.exec("ip rule").out.mapNotNull { line ->
            if (line.contains("uidrange $uid-$uid") && line.contains("lookup $table")) {
                line.substringBefore(":").trim().toIntOrNull()
            } else null
        }

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

    private suspend fun findExistingHotspotRulePriorities(interfaceName: String, table: Int): List<Int> =
        RootShell.exec("ip rule").out.mapNotNull { line ->
            if (
                line.contains("from all iif $interfaceName ") &&
                line.contains("lookup $table")
            ) {
                line.substringBefore(":").trim().toIntOrNull()
            } else null
        }

    private fun hotspotEndpoints(rule: RouteRule): Pair<String, String>? {
        if (!isHotspotInterface(rule.fromInterface) && !isHotspotInterface(rule.toInterface)) return null
        return when {
            isHotspotInterface(rule.fromInterface) -> rule.fromInterface to rule.toInterface
            isHotspotInterface(rule.toInterface) -> rule.toInterface to rule.fromInterface
            else -> null
        }
    }

    private suspend fun captureRpFilterState(hotspotInterface: String, tunnelInterface: String) {
        if (rpFilterStates.containsKey(tunnelInterface)) return
        suspend fun read(path: String): String? = RootShell.exec("cat $path 2>/dev/null").out
            .firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        rpFilterStates[tunnelInterface] = RpFilterState(
            all = read("/proc/sys/net/ipv4/conf/all/rp_filter"),
            hotspot = read("/proc/sys/net/ipv4/conf/$hotspotInterface/rp_filter"),
            tunnel = read("/proc/sys/net/ipv4/conf/$tunnelInterface/rp_filter")
        )
    }

    private suspend fun disableRpFilter(hotspotInterface: String, tunnelInterface: String) {
        RootShell.execSequential(
            listOf(
                "echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter",
                "echo 0 > /proc/sys/net/ipv4/conf/${shellQuote(hotspotInterface)}/rp_filter",
                "echo 0 > /proc/sys/net/ipv4/conf/${shellQuote(tunnelInterface)}/rp_filter"
            )
        )
    }

    private suspend fun restoreRpFilter(hotspotInterface: String, tunnelInterface: String) {
        val state = rpFilterStates.remove(tunnelInterface) ?: return
        val commands = mutableListOf<String>()
        state.all?.let { commands.add("echo $it > /proc/sys/net/ipv4/conf/all/rp_filter") }
        state.hotspot?.let { commands.add("echo $it > /proc/sys/net/ipv4/conf/${shellQuote(hotspotInterface)}/rp_filter") }
        state.tunnel?.let { commands.add("echo $it > /proc/sys/net/ipv4/conf/${shellQuote(tunnelInterface)}/rp_filter") }
        if (commands.isNotEmpty()) RootShell.execSequential(commands)
    }

    /** Reproduces the user's known-working local-device routing script:
     * default dev tunnel in a custom table + iif lo policy rule. */
    private suspend fun applyLocalEgressRule(
        rule: RouteRule,
        table: Int,
        realRoutingTable: String?
    ): List<String>? {
        val localPriority = findFreePriority(20001, 20999) ?: return null
        val commands = mutableListOf<String>()

        if (!realRoutingTable.isNullOrBlank()) {
            var offset = 1
            for (uid in rule.excludedUids) {
                val priority = localPriority - offset
                if (priority <= 0) return null
                findUidTableRules(uid, realRoutingTable).forEach { old ->
                    commands.add("ip rule del priority $old 2>/dev/null || true")
                }
                commands.add(
                    "ip rule add uidrange $uid-$uid lookup ${shellQuote(realRoutingTable)} priority $priority"
                )
                offset++
            }
        }

        findIifLoTableRules(table).forEach { old ->
            commands.add("ip rule del priority $old 2>/dev/null || true")
        }

        commands.add("ip route replace default dev ${shellQuote(rule.toInterface)} table $table")
        commands.add("ip rule add iif lo lookup $table priority $localPriority")
        return commands
    }

    suspend fun applyRule(rule: RouteRule, realRoutingTable: String? = null): Boolean {
        val table = rule.tableId
        val chain = "${chainPrefix}_${rule.id}"
        val hotspot = hotspotEndpoints(rule)
        val localEgress = hotspot == null && rule.sourceType == SourceInterfaceType.LOCAL_ONLY
        val commands = mutableListOf<String>()

        if (hotspot != null) {
            val (hotspotInterface, tunnelInterface) = hotspot
            captureRpFilterState(hotspotInterface, tunnelInterface)
            disableRpFilter(hotspotInterface, tunnelInterface)
            findExistingHotspotRulePriorities(hotspotInterface, table).forEach { priority ->
                commands.add("ip rule del priority $priority 2>/dev/null || true")
            }
            commands.add("ip route replace default dev ${shellQuote(tunnelInterface)} table $table")
            val priority = findHotspotPolicyPriority(hotspotInterface) ?: return false
            commands.add("ip rule add iif ${shellQuote(hotspotInterface)} lookup $table priority $priority")
            commands.add(
                "iptables -C tetherctrl_FORWARD -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -j ACCEPT 2>/dev/null || " +
                    "iptables -I tetherctrl_FORWARD 1 -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -j ACCEPT"
            )
            commands.add(
                "iptables -C tetherctrl_FORWARD -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -j ACCEPT 2>/dev/null || " +
                    "iptables -I tetherctrl_FORWARD 1 -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -j ACCEPT"
            )
            commands.add(
                "iptables -t mangle -C tetherctrl_mangle_FORWARD -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || " +
                    "iptables -t mangle -I tetherctrl_mangle_FORWARD 1 -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu"
            )
            commands.add(
                "iptables -t mangle -C tetherctrl_mangle_FORWARD -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || " +
                    "iptables -t mangle -I tetherctrl_mangle_FORWARD 1 -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu"
            )
            if (rule.useMasquerade) {
                commands.add(
                    "iptables -t nat -C tetherctrl_nat_POSTROUTING -o ${shellQuote(tunnelInterface)} -j MASQUERADE 2>/dev/null || " +
                        "iptables -t nat -I tetherctrl_nat_POSTROUTING 1 -o ${shellQuote(tunnelInterface)} -j MASQUERADE"
                )
            }
            commands.add("sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || echo 1 > /proc/sys/net/ipv4/ip_forward")
        } else if (localEgress) {
            val localCommands = applyLocalEgressRule(rule, table, realRoutingTable) ?: return false
            commands.addAll(localCommands)
        } else {
            val priority = findFreePriority(1200, 19999) ?: return false
            commands.add("iptables -t mangle -N $chain 2>/dev/null || iptables -t mangle -F $chain")
            commands.add(
                "iptables -t mangle -C PREROUTING -i ${shellQuote(rule.fromInterface)} -j $chain 2>/dev/null || " +
                    "iptables -t mangle -A PREROUTING -i ${shellQuote(rule.fromInterface)} -j $chain"
            )
            commands.add("iptables -t mangle -A $chain -m addrtype --dst-type LOCAL -j RETURN")
            for (uid in rule.excludedUids) {
                commands.add("iptables -t mangle -A $chain -m owner --uid-owner $uid -j RETURN")
            }
            commands.add("iptables -t mangle -A $chain -j MARK --set-mark ${table}")
            commands.add("ip rule del fwmark ${table} table $table 2>/dev/null || true")
            commands.add("ip rule add fwmark ${table} table $table priority $priority")
            commands.add("ip route replace default dev ${shellQuote(rule.toInterface)} table $table")
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

    suspend fun removeRule(rule: RouteRule, realRoutingTable: String? = null): Boolean {
        val table = rule.tableId
        val mark = table
        val chain = "${chainPrefix}_${rule.id}"
        val hotspot = hotspotEndpoints(rule)
        val localEgress = hotspot == null && rule.sourceType == SourceInterfaceType.LOCAL_ONLY
        val commands = mutableListOf<String>()

        if (hotspot != null) {
            val (hotspotInterface, tunnelInterface) = hotspot
            findExistingHotspotRulePriorities(hotspotInterface, table).forEach { priority ->
                commands.add("ip rule del priority $priority 2>/dev/null || true")
            }
            commands.add("ip route flush table $table 2>/dev/null || true")
            commands.add("iptables -D tetherctrl_FORWARD -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -j ACCEPT 2>/dev/null || true")
            commands.add("iptables -D tetherctrl_FORWARD -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -j ACCEPT 2>/dev/null || true")
            commands.add("iptables -t mangle -D tetherctrl_mangle_FORWARD -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true")
            commands.add("iptables -t mangle -D tetherctrl_mangle_FORWARD -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true")
            commands.add("iptables -t nat -D tetherctrl_nat_POSTROUTING -o ${shellQuote(tunnelInterface)} -j MASQUERADE 2>/dev/null || true")
            restoreRpFilter(hotspotInterface, tunnelInterface)
        } else if (localEgress) {
            findIifLoTableRules(table).forEach { priority ->
                commands.add("ip rule del priority $priority 2>/dev/null || true")
            }
            if (!realRoutingTable.isNullOrBlank()) {
                rule.excludedUids.forEach { uid ->
                    findUidTableRules(uid, realRoutingTable).forEach { priority ->
                        commands.add("ip rule del priority $priority 2>/dev/null || true")
                    }
                }
            }
            commands.add("ip route flush table $table 2>/dev/null || true")
        } else {
            commands.add("iptables -t mangle -D PREROUTING -i ${shellQuote(rule.fromInterface)} -j $chain 2>/dev/null || true")
            commands.add("iptables -t mangle -F $chain 2>/dev/null || true")
            commands.add("iptables -t mangle -X $chain 2>/dev/null || true")
            commands.add("ip rule del fwmark $mark table $table 2>/dev/null || true")
            commands.add("ip route flush table $table 2>/dev/null || true")
            commands.add("iptables -t nat -D POSTROUTING -o ${shellQuote(rule.toInterface)} -j MASQUERADE 2>/dev/null || true")
        }

        val results = RootShell.execSequential(commands)
        return results.all { it.isSuccess }
    }

    suspend fun exemptProxyApp(proxyUid: Int, realTable: String, priority: Int = 20400): Boolean =
        RootShell.exec(
            "ip rule add uidrange $proxyUid-$proxyUid lookup ${shellQuote(realTable)} priority $priority 2>/dev/null || true"
        ).isSuccess

    suspend fun removeProxyAppExemption(proxyUid: Int): Boolean =
        RootShell.exec(
            "ip rule del uidrange $proxyUid-$proxyUid 2>/dev/null || true"
        ).isSuccess

    suspend fun resetAll(
        rules: List<RouteRule>,
        createdInterfaces: List<String>,
        realRoutingTable: String? = null
    ): Boolean {
        val teardown = rules.map { removeRule(it, realRoutingTable) }
        val cleanupCommands = mutableListOf<String>()
        cleanupCommands.add(
            "for c in \$(iptables -t mangle -S | grep -o \"${chainPrefix}_[a-zA-Z0-9]*\" | sort -u); do iptables -t mangle -F \${'$'}c 2>/dev/null; iptables -t mangle -X \${'$'}c 2>/dev/null; done"
        )
        for (name in createdInterfaces) {
            cleanupCommands.add("ip link delete ${shellQuote(name)} 2>/dev/null || true")
        }
        cleanupCommands.add("echo 0 > /proc/sys/net/ipv4/ip_forward")
        val cleanupResults = RootShell.execSequential(cleanupCommands)
        return teardown.all { it } && cleanupResults.all { it.isSuccess }
    }
}
