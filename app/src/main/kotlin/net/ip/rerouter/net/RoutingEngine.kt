package net.ip.rerouter.net

import net.ip.rerouter.model.RouteRule
import net.ip.rerouter.model.SourceInterfaceType
import net.ip.rerouter.root.RootShell

data class RuleApplyResult(
    val isSuccess: Boolean,
    val failedCommand: String? = null,
    val stderr: String? = null
)

class RoutingEngine {
    private val chainPrefix = "IPRR"

    private data class RpFilterState(val all: String?, val hotspot: String?, val tunnel: String?)
    private val rpFilterStates = mutableMapOf<String, RpFilterState>()

    private fun isHotspotInterface(name: String): Boolean = when {
        name == "wlan0" -> false
        name.startsWith("wlan") -> true
        name.startsWith("swlan") -> true
        name == "ap0" -> true
        else -> false
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun ipRuleLines(): List<String> = RootShell.exec("ip rule").out

    private suspend fun findFreePriority(min: Int, max: Int): Int? {
        val used = ipRuleLines().mapNotNull { it.substringBefore(":").trim().toIntOrNull() }.toSet()
        return (max downTo min).firstOrNull { it > 0 && it !in used }
    }

    /** Lowest non-local policy priority currently installed by Android. */
    private suspend fun findPolicyFloor(): Int? = ipRuleLines()
        .mapNotNull { line ->
            val priority = line.substringBefore(":").trim().toIntOrNull() ?: return@mapNotNull null
            if (priority <= 0) return@mapNotNull null
            if (line.contains("lookup ") || line.contains("table ")) priority else null
        }
        .filter { it > 0 }
        .minOrNull()

    /**
     * Local device traffic must beat Android's fwmark rules. Pick a free
     * priority immediately before the first policy rule, entirely from the
     * live kernel topology; no device-specific constant is used.
     */
    private suspend fun findLocalEgressPriority(exclusionCount: Int): Int? {
        val floor = findPolicyFloor() ?: return findFreePriority(1, 9999 - exclusionCount)
        if (floor <= exclusionCount + 1) return null
        val upper = floor - 1
        val candidates = mutableListOf<Int>()
        var cursor = upper
        while (cursor > 0 && candidates.size < exclusionCount + 1) {
            val used = ipRuleLines().mapNotNull { it.substringBefore(":").trim().toIntOrNull() }.toSet()
            if (cursor !in used) candidates.add(cursor)
            cursor--
        }
        return if (candidates.size == exclusionCount + 1) candidates.last() else null
    }

    private suspend fun findIifLoTableRules(table: Int): List<Int> = ipRuleLines().mapNotNull { line ->
        if (line.contains("from all iif lo ") && line.contains("lookup $table")) line.substringBefore(":").trim().toIntOrNull() else null
    }

    private suspend fun findUidTableRules(uid: Int, table: String): List<Int> = ipRuleLines().mapNotNull { line ->
        if (line.contains("uidrange $uid-$uid") && line.contains("lookup $table")) line.substringBefore(":").trim().toIntOrNull() else null
    }

    private suspend fun findHotspotPolicyPriority(interfaceName: String): Int? {
        val lines = ipRuleLines()
        val used = lines.mapNotNull { it.substringBefore(":").trim().toIntOrNull() }.toSet()
        val existing = lines.mapNotNull { line ->
            if (line.contains("from all iif $interfaceName ")) line.substringBefore(":").trim().toIntOrNull() else null
        }
        val upper = (existing.minOrNull() ?: 20000) - 1
        return generateSequence(upper) { it - 1 }.firstOrNull { it > 0 && it !in used }
    }

    private suspend fun findExistingHotspotRulePriorities(interfaceName: String, table: Int): List<Int> = ipRuleLines().mapNotNull { line ->
        if (line.contains("from all iif $interfaceName ") && line.contains("lookup $table")) line.substringBefore(":").trim().toIntOrNull() else null
    }

    private fun hotspotEndpoints(rule: RouteRule): Pair<String, String>? {
        if (!isHotspotInterface(rule.fromInterface)) return null
        return rule.fromInterface to rule.toInterface
    }

    private suspend fun captureRpFilterState(hotspotInterface: String, tunnelInterface: String) {
        if (rpFilterStates.containsKey(tunnelInterface)) return
        suspend fun read(path: String): String? = RootShell.exec("cat $path 2>/dev/null").out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        rpFilterStates[tunnelInterface] = RpFilterState(
            read("/proc/sys/net/ipv4/conf/all/rp_filter"),
            read("/proc/sys/net/ipv4/conf/$hotspotInterface/rp_filter"),
            read("/proc/sys/net/ipv4/conf/$tunnelInterface/rp_filter")
        )
    }

    private suspend fun disableRpFilter(hotspotInterface: String, tunnelInterface: String) {
        RootShell.execSequential(listOf(
            "echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter",
            "echo 0 > /proc/sys/net/ipv4/conf/${shellQuote(hotspotInterface)}/rp_filter",
            "echo 0 > /proc/sys/net/ipv4/conf/${shellQuote(tunnelInterface)}/rp_filter"
        ))
    }

    private suspend fun restoreRpFilter(hotspotInterface: String, tunnelInterface: String) {
        val state = rpFilterStates.remove(tunnelInterface) ?: return
        val commands = mutableListOf<String>()
        state.all?.let { commands.add("echo $it > /proc/sys/net/ipv4/conf/all/rp_filter") }
        state.hotspot?.let { commands.add("echo $it > /proc/sys/net/ipv4/conf/${shellQuote(hotspotInterface)}/rp_filter") }
        state.tunnel?.let { commands.add("echo $it > /proc/sys/net/ipv4/conf/${shellQuote(tunnelInterface)}/rp_filter") }
        if (commands.isNotEmpty()) RootShell.execSequential(commands)
    }

    private suspend fun applyLocalEgressRule(rule: RouteRule, table: Int, realRoutingTable: String?): List<String>? {
        val localPriority = findLocalEgressPriority(rule.excludedUids.size) ?: return null
        val commands = mutableListOf<String>()

        // Exempted UIDs must be evaluated before the local tunnel rule so their
        // upstream proxy/VPN sockets remain on the real network.
        var uidPriority = localPriority
        if (!realRoutingTable.isNullOrBlank()) {
            for (uid in rule.excludedUids) {
                val priority = uidPriority
                findUidTableRules(uid, realRoutingTable).forEach { old ->
                    commands.add("ip rule del priority $old 2>/dev/null || true")
                }
                commands.add("ip rule add uidrange $uid-$uid lookup ${shellQuote(realRoutingTable)} priority $priority")
                uidPriority++
            }
        }

        val tunnelPriority = uidPriority
        findIifLoTableRules(table).forEach { old ->
            commands.add("ip rule del priority $old 2>/dev/null || true")
        }
        commands.add("ip route replace default dev ${shellQuote(rule.toInterface)} table $table")
        commands.add("ip rule add iif lo lookup $table priority $tunnelPriority")
        return commands
    }

    suspend fun applyRule(rule: RouteRule, realRoutingTable: String? = null): RuleApplyResult {
        val table = rule.tableId
        val chain = "${chainPrefix}_${rule.id}"
        val hotspot = hotspotEndpoints(rule)
        val commands = mutableListOf<String>()

        if (hotspot != null) {
            val (hotspotInterface, tunnelInterface) = hotspot
            captureRpFilterState(hotspotInterface, tunnelInterface)
            disableRpFilter(hotspotInterface, tunnelInterface)
            findExistingHotspotRulePriorities(hotspotInterface, table).forEach { p -> commands.add("ip rule del priority $p 2>/dev/null || true") }
            commands.add("ip route replace default dev ${shellQuote(tunnelInterface)} table $table")
            val priority = findHotspotPolicyPriority(hotspotInterface)
                ?: return RuleApplyResult(false, stderr = "No free policy-routing priority found for hotspot interface")
            commands.add("ip rule add iif ${shellQuote(hotspotInterface)} lookup $table priority $priority")
            commands.add("iptables -C tetherctrl_FORWARD -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -j ACCEPT 2>/dev/null || iptables -I tetherctrl_FORWARD 1 -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -j ACCEPT")
            commands.add("iptables -C tetherctrl_FORWARD -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -j ACCEPT 2>/dev/null || iptables -I tetherctrl_FORWARD 1 -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -j ACCEPT")
            commands.add("iptables -t mangle -C tetherctrl_mangle_FORWARD -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || iptables -t mangle -I tetherctrl_mangle_FORWARD 1 -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu")
            commands.add("iptables -t mangle -C tetherctrl_mangle_FORWARD -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || iptables -t mangle -I tetherctrl_mangle_FORWARD 1 -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu")
            if (rule.useMasquerade) commands.add("iptables -t nat -C tetherctrl_nat_POSTROUTING -o ${shellQuote(tunnelInterface)} -j MASQUERADE 2>/dev/null || iptables -t nat -I tetherctrl_nat_POSTROUTING 1 -o ${shellQuote(tunnelInterface)} -j MASQUERADE")
            commands.add("sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || echo 1 > /proc/sys/net/ipv4/ip_forward")
        } else if (rule.sourceType == SourceInterfaceType.LOCAL_ONLY) {
            val localCommands = applyLocalEgressRule(rule, table, realRoutingTable)
                ?: return RuleApplyResult(false, stderr = "Could not allocate priorities before Android policy rules for local routing")
            commands.addAll(localCommands)
        } else {
            val priority = findFreePriority(1200, 19999)
                ?: return RuleApplyResult(false, stderr = "No free ip rule priority available for interface routing")
            commands.add("iptables -t mangle -N $chain 2>/dev/null || iptables -t mangle -F $chain")
            commands.add("iptables -t mangle -C PREROUTING -i ${shellQuote(rule.fromInterface)} -j $chain 2>/dev/null || iptables -t mangle -A PREROUTING -i ${shellQuote(rule.fromInterface)} -j $chain")
            commands.add("iptables -t mangle -A $chain -m addrtype --dst-type LOCAL -j RETURN")
            for (uid in rule.excludedUids) commands.add("iptables -t mangle -A $chain -m owner --uid-owner $uid -j RETURN")
            commands.add("iptables -t mangle -A $chain -j MARK --set-mark $table")
            commands.add("ip rule del fwmark $table table $table 2>/dev/null || true")
            commands.add("ip rule add fwmark $table table $table priority $priority")
            commands.add("ip route replace default dev ${shellQuote(rule.toInterface)} table $table")
            if (rule.useMasquerade) commands.add("iptables -t nat -C POSTROUTING -o ${shellQuote(rule.toInterface)} -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -o ${shellQuote(rule.toInterface)} -j MASQUERADE")
        }

        val results = RootShell.execSequential(commands)
        if (results.isEmpty() || !results.all { it.isSuccess }) {
            val failed = results.lastOrNull { !it.isSuccess }
            return RuleApplyResult(false, failed?.command, failed?.err?.joinToString("\n")?.takeIf { it.isNotBlank() } ?: failed?.out?.joinToString("\n"))
        }
        return RuleApplyResult(true)
    }

    suspend fun removeRule(rule: RouteRule, realRoutingTable: String? = null): Boolean {
        val table = rule.tableId
        val mark = table
        val chain = "${chainPrefix}_${rule.id}"
        val hotspot = hotspotEndpoints(rule)
        val commands = mutableListOf<String>()
        if (hotspot != null) {
            val (hotspotInterface, tunnelInterface) = hotspot
            findExistingHotspotRulePriorities(hotspotInterface, table).forEach { p -> commands.add("ip rule del priority $p 2>/dev/null || true") }
            commands.add("ip route flush table $table 2>/dev/null || true")
            commands.add("iptables -D tetherctrl_FORWARD -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -j ACCEPT 2>/dev/null || true")
            commands.add("iptables -D tetherctrl_FORWARD -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -j ACCEPT 2>/dev/null || true")
            commands.add("iptables -t mangle -D tetherctrl_mangle_FORWARD -i ${shellQuote(hotspotInterface)} -o ${shellQuote(tunnelInterface)} -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true")
            commands.add("iptables -t mangle -D tetherctrl_mangle_FORWARD -i ${shellQuote(tunnelInterface)} -o ${shellQuote(hotspotInterface)} -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true")
            commands.add("iptables -t nat -D tetherctrl_nat_POSTROUTING -o ${shellQuote(tunnelInterface)} -j MASQUERADE 2>/dev/null || true")
            restoreRpFilter(hotspotInterface, tunnelInterface)
        } else if (rule.sourceType == SourceInterfaceType.LOCAL_ONLY) {
            findIifLoTableRules(table).forEach { p -> commands.add("ip rule del priority $p 2>/dev/null || true") }
            if (!realRoutingTable.isNullOrBlank()) rule.excludedUids.forEach { uid -> findUidTableRules(uid, realRoutingTable).forEach { p -> commands.add("ip rule del priority $p 2>/dev/null || true") } }
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

    suspend fun exemptProxyApp(proxyUid: Int, realTable: String, priority: Int): Boolean =
        RootShell.exec("ip rule add uidrange $proxyUid-$proxyUid lookup ${shellQuote(realTable)} priority $priority").isSuccess

    suspend fun removeProxyAppExemption(proxyUid: Int): Boolean =
        RootShell.exec("ip rule del uidrange $proxyUid-$proxyUid 2>/dev/null || true").isSuccess

    suspend fun resetAll(rules: List<RouteRule>, createdInterfaces: List<String>, realRoutingTable: String? = null): Boolean {
        val teardown = rules.map { removeRule(it, realRoutingTable) }
        val cleanupCommands = mutableListOf<String>()
        cleanupCommands.add("for c in \$(iptables -t mangle -S | grep -o \"${chainPrefix}_[a-zA-Z0-9]*\" | sort -u); do iptables -t mangle -F \\${'$'}c 2>/dev/null; iptables -t mangle -X \\${'$'}c 2>/dev/null; done")
        for (name in createdInterfaces) cleanupCommands.add("ip link delete ${shellQuote(name)} 2>/dev/null || true")
        cleanupCommands.add("echo 0 > /proc/sys/net/ipv4/ip_forward")
        val cleanupResults = RootShell.execSequential(cleanupCommands)
        return teardown.all { it } && cleanupResults.all { it.isSuccess }
    }
}
