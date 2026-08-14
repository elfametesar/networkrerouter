package net.ip.rerouter.net

import net.ip.rerouter.model.RouteRule
import net.ip.rerouter.root.RootShell

/**
 * Owns the actual `ip rule` / `ip route` / `iptables` state for user-created
 * routing rules. Each rule gets its own routing table (100-252, the Linux
 * user-defined range) so rules stay independent and are trivial to tear down.
 *
 * App exclusion is implemented with iptables' `owner` match on UID: excluded
 * apps get a rule that skips the MASQUERADE/mark-based routing before the
 * general rule would otherwise catch them.
 */
class RoutingEngine {

    private val chainPrefix = "IPRR" // IP ReRouter, keeps our iptables chains identifiable

    /** Applies a rule: marks packets from fromInterface, routes marked packets out toInterface. */
    suspend fun applyRule(rule: RouteRule): Boolean {
        val table = rule.tableId
        val mark = table // reuse table id as fwmark for simplicity, both are in 100-252 range
        val chain = "${chainPrefix}_${rule.id}"

        val commands = mutableListOf(
            // Fresh chain for this rule so we can flush it independently later
            "iptables -t mangle -N $chain 2>/dev/null || iptables -t mangle -F $chain",
            "iptables -t mangle -C PREROUTING -i ${rule.fromInterface} -j $chain 2>/dev/null || " +
                "iptables -t mangle -A PREROUTING -i ${rule.fromInterface} -j $chain"
        )

        // Excluded apps: RETURN before the mark is applied, so their traffic
        // falls through to normal system routing untouched.
        for (uid in rule.excludedUids) {
            commands.add("iptables -t mangle -A $chain -m owner --uid-owner $uid -j RETURN")
        }

        // Everything else from this interface gets marked and routed via the custom table.
        commands.add("iptables -t mangle -A $chain -j MARK --set-mark $mark")

        commands.add("ip rule del fwmark $mark table $table 2>/dev/null || true")
        commands.add("ip rule add fwmark $mark table $table priority ${1000 + table}")
        commands.add("ip route replace default dev ${rule.toInterface} table $table")

        if (rule.useMasquerade) {
            commands.add(
                "iptables -t nat -C POSTROUTING -o ${rule.toInterface} -j MASQUERADE 2>/dev/null || " +
                    "iptables -t nat -A POSTROUTING -o ${rule.toInterface} -j MASQUERADE"
            )
        }

        commands.add("echo 1 > /proc/sys/net/ipv4/ip_forward")

        val results = RootShell.execSequential(commands)
        return results.isNotEmpty() && results.last().isSuccess && results.size == commands.size
    }

    /** Tears down everything applyRule set up for this specific rule. */
    suspend fun removeRule(rule: RouteRule): Boolean {
        val table = rule.tableId
        val mark = table
        val chain = "${chainPrefix}_${rule.id}"

        val commands = listOf(
            "iptables -t mangle -D PREROUTING -i ${rule.fromInterface} -j $chain 2>/dev/null || true",
            "iptables -t mangle -F $chain 2>/dev/null || true",
            "iptables -t mangle -X $chain 2>/dev/null || true",
            "ip rule del fwmark $mark table $table 2>/dev/null || true",
            "ip route flush table $table 2>/dev/null || true",
            "iptables -t nat -D POSTROUTING -o ${rule.toInterface} -j MASQUERADE 2>/dev/null || true"
        )

        val results = RootShell.execSequential(commands)
        return results.all { it.isSuccess }
    }

    /**
     * Full reset: removes every rule this app has ever created (by chain
     * prefix and known table range), deletes app-created interfaces, and
     * restores ip_forward to its default-off state. This is the "reset all"
     * button — it should be safe to call even if internal state was lost.
     */
    suspend fun resetAll(rules: List<RouteRule>, createdInterfaces: List<String>): Boolean {
        val teardown = rules.map { removeRule(it) }

        val cleanupCommands = mutableListOf<String>()
        // Belt-and-suspenders: sweep any leftover IPRR chains even if we lost track of a rule.
        cleanupCommands.add(
            "for c in \$(iptables -t mangle -S | grep -o \"${chainPrefix}_[a-zA-Z0-9]*\" | sort -u); do " +
                "iptables -t mangle -F \$c 2>/dev/null; iptables -t mangle -X \$c 2>/dev/null; done"
        )
        for (name in createdInterfaces) {
            cleanupCommands.add("ip link delete $name 2>/dev/null || true")
        }
        cleanupCommands.add("echo 0 > /proc/sys/net/ipv4/ip_forward")

        val cleanupResults = RootShell.execSequential(cleanupCommands)

        return teardown.all { it } && cleanupResults.all { it.isSuccess }
    }
}
