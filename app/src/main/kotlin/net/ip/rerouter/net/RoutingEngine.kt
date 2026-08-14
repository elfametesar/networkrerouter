package net.ip.rerouter.net

import net.ip.rerouter.model.RouteRule
import net.ip.rerouter.model.SourceInterfaceType
import net.ip.rerouter.root.RootShell

class RoutingEngine {
    private val chainPrefix = "IPRR"
    private val hotspotPriorityMin = 20001
    private val hotspotPriorityMax = 20999

    suspend fun applyRule(rule: RouteRule): Boolean {
        val table = rule.tableId
        val mark = table
        val chain = "${chainPrefix}_${rule.id}"
        val hotspotRule = rule.sourceType == SourceInterfaceType.LOCAL_AND_HOTSPOT && isHotspotInterface(rule.fromInterface)
        val commands = mutableListOf<String>()

        if (hotspotRule) {
            commands.add(
                "for p in \$(ip rule | " +
                    "grep \"from all iif ${shellEscape(rule.fromInterface)} lookup $table\" | " +
                    "sed -n 's/^\\([0-9][0-9]*\\):.*/\\1/p'); do " +
                    "ip rule del priority \$p 2>/dev/null || true; " +
                    "done"
            )
            commands.add("ip route replace default dev ${shellEscape(rule.toInterface)} table $table")
            commands.add(
                "HOTSPOT_PRIORITY=0; " +
                    "for p in \$(seq $hotspotPriorityMax -1 $hotspotPriorityMin); do " +
                    "if ! ip rule | grep -q \"^\$p:\"; then " +
                    "HOTSPOT_PRIORITY=\$p; break; " +
                    "fi; " +
                    "done; " +
                    "if [ \"\$HOTSPOT_PRIORITY\" = \"0\" ]; then exit 1; fi; " +
                    "ip rule add iif ${shellEscape(rule.fromInterface)} lookup $table priority \$HOTSPOT_PRIORITY"
            )
        } else {
            commands.add("iptables -t mangle -N $chain 2>/dev/null || iptables -t mangle -F $chain")
            commands.add(
                "iptables -t mangle -C PREROUTING -i ${shellEscape(rule.fromInterface)} -j $chain 2>/dev/null || " +
                    "iptables -t mangle -A PREROUTING -i ${shellEscape(rule.fromInterface)} -j $chain"
            )
            for (uid in rule.excludedUids) {
                commands.add("iptables -t mangle -A $chain -m owner --uid-owner $uid -j RETURN")
            }
            commands.add("iptables -t mangle -A $chain -j MARK --set-mark $mark")
            commands.add("ip rule del fwmark $mark table $table 2>/dev/null || true")
            commands.add("ip rule add fwmark $mark table $table priority ${1000 + table}")
            commands.add("ip route replace default dev ${shellEscape(rule.toInterface)} table $table")
        }

        if (rule.useMasquerade && !hotspotRule) {
            commands.add(
                "iptables -t nat -C POSTROUTING -o ${shellEscape(rule.toInterface)} -j MASQUERADE 2>/dev/null || " +
                    "iptables -t nat -A POSTROUTING -o ${shellEscape(rule.toInterface)} -j MASQUERADE"
            )
        }

        if (hotspotRule) {
            commands.add(
                "iptables -C tetherctrl_FORWARD -i ${shellEscape(rule.fromInterface)} -o ${shellEscape(rule.toInterface)} -j ACCEPT 2>/dev/null || " +
                    "iptables -I tetherctrl_FORWARD -i ${shellEscape(rule.fromInterface)} -o ${shellEscape(rule.toInterface)} -j ACCEPT"
            )
            commands.add(
                "iptables -C tetherctrl_FORWARD -i ${shellEscape(rule.toInterface)} -o ${shellEscape(rule.fromInterface)} -j ACCEPT 2>/dev/null || " +
                    "iptables -I tetherctrl_FORWARD -i ${shellEscape(rule.toInterface)} -o ${shellEscape(rule.fromInterface)} -j ACCEPT"
            )
            if (rule.useMasquerade) {
                commands.add(
                    "iptables -t nat -C tetherctrl_nat_POSTROUTING -o ${shellEscape(rule.toInterface)} -j MASQUERADE 2>/dev/null || " +
                        "iptables -t nat -I tetherctrl_nat_POSTROUTING -o ${shellEscape(rule.toInterface)} -j MASQUERADE"
                )
            }
            commands.add("echo 1 > /proc/sys/net/ipv4/ip_forward")
        }

        val results = RootShell.execSequential(commands)
        return results.isNotEmpty() && results.all { it.isSuccess }
    }

    suspend fun removeRule(rule: RouteRule): Boolean {
        val table = rule.tableId
        val mark = table
        val chain = "${chainPrefix}_${rule.id}"
        val hotspotRule = rule.sourceType == SourceInterfaceType.LOCAL_AND_HOTSPOT && isHotspotInterface(rule.fromInterface)
        val commands = mutableListOf<String>()

        if (hotspotRule) {
            commands.add(
                "for p in \$(ip rule | " +
                    "grep \"from all iif ${shellEscape(rule.fromInterface)} lookup $table\" | " +
                    "sed -n 's/^\\([0-9][0-9]*\\):.*/\\1/p'); do " +
                    "ip rule del priority \$p 2>/dev/null || true; " +
                    "done"
            )
            commands.add("ip route flush table $table 2>/dev/null || true")
            commands.add("iptables -D tetherctrl_FORWARD -i ${shellEscape(rule.fromInterface)} -o ${shellEscape(rule.toInterface)} -j ACCEPT 2>/dev/null || true")
            commands.add("iptables -D tetherctrl_FORWARD -i ${shellEscape(rule.toInterface)} -o ${shellEscape(rule.fromInterface)} -j ACCEPT 2>/dev/null || true")
            commands.add("iptables -t nat -D tetherctrl_nat_POSTROUTING -o ${shellEscape(rule.toInterface)} -j MASQUERADE 2>/dev/null || true")
        } else {
            commands.add("iptables -t mangle -D PREROUTING -i ${shellEscape(rule.fromInterface)} -j $chain 2>/dev/null || true")
            commands.add("iptables -t mangle -F $chain 2>/dev/null || true")
            commands.add("iptables -t mangle -X $chain 2>/dev/null || true")
            commands.add("ip rule del fwmark $mark table $table 2>/dev/null || true")
            commands.add("ip route flush table $table 2>/dev/null || true")
            commands.add("iptables -t nat -D POSTROUTING -o ${shellEscape(rule.toInterface)} -j MASQUERADE 2>/dev/null || true")
        }

        val results = RootShell.execSequential(commands)
        return results.all { it.isSuccess }
    }

    suspend fun exemptProxyApp(proxyUid: Int, realTable: String, priority: Int = 20400): Boolean {
        val result = RootShell.exec("ip rule add uidrange $proxyUid-$proxyUid lookup ${shellEscape(realTable)} priority $priority 2>/dev/null || true")
        return result.isSuccess
    }

    suspend fun removeProxyAppExemption(proxyUid: Int): Boolean {
        val result = RootShell.exec("ip rule del uidrange $proxyUid-$proxyUid 2>/dev/null || true")
        return result.isSuccess
    }

    private fun isHotspotInterface(name: String): Boolean {
        return when {
            name == "wlan0" -> false
            name.startsWith("wlan") -> true
            name.startsWith("swlan") -> true
            name == "ap0" -> true
            else -> false
        }
    }

    private fun shellEscape(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    suspend fun resetAll(rules: List<RouteRule>, createdInterfaces: List<String>): Boolean {
        val teardown = rules.map { removeRule(it) }
        val cleanupCommands = mutableListOf<String>()
        cleanupCommands.add(
            "for c in \$(iptables -t mangle -S | " +
                "grep -o \"${chainPrefix}_[a-zA-Z0-9]*\" | sort -u); do " +
                "iptables -t mangle -F \$c 2>/dev/null; " +
                "iptables -t mangle -X \$c 2>/dev/null; " +
                "done"
        )
        for (name in createdInterfaces) {
            cleanupCommands.add("ip link delete ${shellEscape(name)} 2>/dev/null || true")
        }
        cleanupCommands.add("echo 0 > /proc/sys/net/ipv4/ip_forward")
        val cleanupResults = RootShell.execSequential(cleanupCommands)
        return teardown.all { it } && cleanupResults.all { it.isSuccess }
    }
}
