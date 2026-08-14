package net.ip.rerouter.net

import net.ip.rerouter.model.RouteRule
import net.ip.rerouter.model.SourceInterfaceType
import net.ip.rerouter.root.RootShell

/**
 * Owns the actual `ip rule` / `ip route` / `iptables` state for user-created
 * routing rules.
 *
 * Normal/local rules continue to use the existing fwmark mechanism.
 *
 * Hotspot rules are different on Android: tethered packets arrive on the
 * hotspot interface (for example wlan1), and Android's own fwmark/tethering
 * rules can override a normal fwmark-based route. For hotspot traffic we
 * therefore install an interface-based policy rule:
 *
 *   from all iif wlan1 lookup <table>
 *
 * before Android's normal tethering rules.
 *
 * This gives:
 *
 *   hotspot client
 *       -> wlan1
 *       -> policy rule
 *       -> custom table
 *       -> tun1
 *       -> tun2socks/Xray
 */
class RoutingEngine {

    private val chainPrefix = "IPRR"

    /*
     * Android commonly puts tethering rules around 21000.
     *
     * We dynamically find a free priority in this range so we don't hardcode
     * 20500. The important property is that it runs before Android's later
     * wlan1 tethering rule.
     */
    private val hotspotPriorityMin = 20001
    private val hotspotPriorityMax = 20999

    /**
     * Applies a rule.
     *
     * LOCAL_ONLY:
     *   Uses the existing fwmark-based routing mechanism.
     *
     * LOCAL_AND_HOTSPOT:
     *   Uses interface-based routing for the source interface when that
     *   interface looks like an Android hotspot interface.
     *
     * The hotspot path intentionally does NOT rely on the mangle MARK rule.
     * Android's tethering stack can overwrite/use marks of its own.
     */
    suspend fun applyRule(rule: RouteRule): Boolean {
        val table = rule.tableId
        val mark = table
        val chain = "${chainPrefix}_${rule.id}"

        val hotspotRule =
            rule.sourceType == SourceInterfaceType.LOCAL_AND_HOTSPOT &&
                isHotspotInterface(rule.fromInterface)

        val commands = mutableListOf<String>()

        if (hotspotRule) {
            /*
             * HOTSPOT ROUTING
             *
             * Do NOT mark hotspot packets in mangle PREROUTING.
             *
             * Instead:
             *
             *   ip rule add iif wlan1 lookup <table> priority <priority>
             *
             * This is the exact routing mechanism that works on the target
             * Android device.
             */

            /*
             * Remove any previous app-created iif rule for this table/interface
             * so re-applying a rule doesn't accumulate duplicates.
             *
             * We don't know the old priority, so remove matching rules by
             * inspecting `ip rule`.
             */
            commands.add(
                "for p in \\$(ip rule | " +
                    "grep \"from all iif ${shellEscape(rule.fromInterface)} lookup $table\" | " +
                    "sed -n 's/^\\\\([0-9][0-9]*\\\\):.*/\\\\1/p'); do " +
                    "ip rule del priority \\$p 2>/dev/null || true; " +
                    "done"
            )

            /*
             * The table must contain the tunnel default route.
             */
            commands.add(
                "ip route replace default dev ${shellEscape(rule.toInterface)} table $table"
            )

            /*
             * Pick a free priority before Android's normal tethering rules.
             */
            commands.add(
                "HOTSPOT_PRIORITY=0; " +
                    "for p in \$(seq $hotspotPriorityMax -1 $hotspotPriorityMin); do " +
                    "if ! ip rule | grep -q \"^\\$p:\"; then " +
                    "HOTSPOT_PRIORITY=\\$p; break; " +
                    "fi; " +
                    "done; " +
                    "if [ \"\\$HOTSPOT_PRIORITY\" = \"0\" ]; then exit 1; fi; " +
                    "ip rule add iif ${shellEscape(rule.fromInterface)} " +
                    "lookup $table priority \\$HOTSPOT_PRIORITY"
            )

            /*
             * We still need a mangle chain for excluded UIDs only if the
             * interface is also being used for local traffic. For pure
             * hotspot traffic, don't mark anything.
             *
             * The interface policy rule is what selects the tunnel.
             */
        } else {
            /*
             * EXISTING LOCAL/FWMARK ROUTING
             *
             * Preserve the original behavior for LOCAL_ONLY rules and for
             * non-hotspot interfaces.
             */

            commands.add(
                "iptables -t mangle -N $chain 2>/dev/null || " +
                    "iptables -t mangle -F $chain"
            )

            commands.add(
                "iptables -t mangle -C PREROUTING " +
                    "-i ${shellEscape(rule.fromInterface)} " +
                    "-j $chain 2>/dev/null || " +
                    "iptables -t mangle -A PREROUTING " +
                    "-i ${shellEscape(rule.fromInterface)} " +
                    "-j $chain"
            )

            /*
             * Excluded apps must return before the mark is applied.
             */
            for (uid in rule.excludedUids) {
                commands.add(
                    "iptables -t mangle -A $chain " +
                        "-m owner --uid-owner $uid -j RETURN"
                )
            }

            commands.add(
                "iptables -t mangle -A $chain " +
                    "-j MARK --set-mark $mark"
            )

            commands.add(
                "ip rule del fwmark $mark table $table 2>/dev/null || true"
            )

            commands.add(
                "ip rule add fwmark $mark table $table priority ${1000 + table}"
            )

            commands.add(
                "ip route replace default dev ${shellEscape(rule.toInterface)} table $table"
            )
        }

        /*
         * Standard POSTROUTING MASQUERADE.
         *
         * Keep this for the existing local-routing behavior.
         */
        if (rule.useMasquerade && !hotspotRule) {
            commands.add(
                "iptables -t nat -C POSTROUTING " +
                    "-o ${shellEscape(rule.toInterface)} " +
                    "-j MASQUERADE 2>/dev/null || " +
                    "iptables -t nat -A POSTROUTING " +
                    "-o ${shellEscape(rule.toInterface)} " +
                    "-j MASQUERADE"
            )
        }

        /*
         * Android tethering has its own FORWARD chain and its own
         * tetherctrl_nat_POSTROUTING chain.
         *
         * These are REQUIRED for hotspot -> tunnel.
         */
        if (hotspotRule) {
            commands.add(
                "iptables -C tetherctrl_FORWARD " +
                    "-i ${shellEscape(rule.fromInterface)} " +
                    "-o ${shellEscape(rule.toInterface)} " +
                    "-j ACCEPT 2>/dev/null || " +
                    "iptables -I tetherctrl_FORWARD " +
                    "-i ${shellEscape(rule.fromInterface)} " +
                    "-o ${shellEscape(rule.toInterface)} " +
                    "-j ACCEPT"
            )

            commands.add(
                "iptables -C tetherctrl_FORWARD " +
                    "-i ${shellEscape(rule.toInterface)} " +
                    "-o ${shellEscape(rule.fromInterface)} " +
                    "-j ACCEPT 2>/dev/null || " +
                    "iptables -I tetherctrl_FORWARD " +
                    "-i ${shellEscape(rule.toInterface)} " +
                    "-o ${shellEscape(rule.fromInterface)} " +
                    "-j ACCEPT"
            )

            if (rule.useMasquerade) {
                commands.add(
                    "iptables -t nat -C tetherctrl_nat_POSTROUTING " +
                        "-o ${shellEscape(rule.toInterface)} " +
                        "-j MASQUERADE 2>/dev/null || " +
                        "iptables -t nat -I tetherctrl_nat_POSTROUTING " +
                        "-o ${shellEscape(rule.toInterface)} " +
                        "-j MASQUERADE"
                )
            }

            /*
             * Make sure IPv4 forwarding is enabled.
             */
            commands.add(
                "echo 1 > /proc/sys/net/ipv4/ip_forward"
            )
        }

        val results = RootShell.execSequential(commands)

        return results.isNotEmpty() &&
            results.all { it.isSuccess }
    }

    /**
     * Tears down everything applyRule() created.
     */
    suspend fun removeRule(rule: RouteRule): Boolean {
        val table = rule.tableId
        val mark = table
        val chain = "${chainPrefix}_${rule.id}"

        val hotspotRule =
            rule.sourceType == SourceInterfaceType.LOCAL_AND_HOTSPOT &&
                isHotspotInterface(rule.fromInterface)

        val commands = mutableListOf<String>()

        if (hotspotRule) {
            /*
             * Remove every iif rule for this source interface/table.
             *
             * We identify the rule by its exact interface + table rather than
             * assuming a hardcoded priority.
             */
            commands.add(
                "for p in \\$(ip rule | " +
                    "grep \"from all iif ${shellEscape(rule.fromInterface)} lookup $table\" | " +
                    "sed -n 's/^\\\\([0-9][0-9]*\\\\):.*/\\\\1/p'); do " +
                    "ip rule del priority \\$p 2>/dev/null || true; " +
                    "done"
            )

            /*
             * Remove the tunnel route from our custom table.
             */
            commands.add(
                "ip route flush table $table 2>/dev/null || true"
            )

            /*
             * Remove tethering ACCEPT rules.
             */
            commands.add(
                "iptables -D tetherctrl_FORWARD " +
                    "-i ${shellEscape(rule.fromInterface)} " +
                    "-o ${shellEscape(rule.toInterface)} " +
                    "-j ACCEPT 2>/dev/null || true"
            )

            commands.add(
                "iptables -D tetherctrl_FORWARD " +
                    "-i ${shellEscape(rule.toInterface)} " +
                    "-o ${shellEscape(rule.fromInterface)} " +
                    "-j ACCEPT 2>/dev/null || true"
            )

            /*
             * Remove exactly one tether MASQUERADE rule.
             */
            commands.add(
                "iptables -t nat -D tetherctrl_nat_POSTROUTING " +
                    "-o ${shellEscape(rule.toInterface)} " +
                    "-j MASQUERADE 2>/dev/null || true"
            )
        } else {
            /*
             * Existing local/fwmark teardown.
             */
            commands.add(
                "iptables -t mangle -D PREROUTING " +
                    "-i ${shellEscape(rule.fromInterface)} " +
                    "-j $chain 2>/dev/null || true"
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
                "iptables -t nat -D POSTROUTING " +
                    "-o ${shellEscape(rule.toInterface)} " +
                    "-j MASQUERADE 2>/dev/null || true"
            )
        }

        val results = RootShell.execSequential(commands)

        return results.all { it.isSuccess }
    }

    /**
     * Exempts a proxy app by UID.
     *
     * The proxy app's own upstream connection must bypass the tunnel, otherwise
     * it can route its SOCKS/Xray connection back into its own tunnel.
     */
    suspend fun exemptProxyApp(
        proxyUid: Int,
        realTable: String,
        priority: Int = 20400
    ): Boolean {
        val result = RootShell.exec(
            "ip rule add uidrange $proxyUid-$proxyUid " +
                "lookup ${shellEscape(realTable)} " +
                "priority $priority 2>/dev/null || true"
        )

        return result.isSuccess
    }

    /**
     * Removes the proxy app exemption rule.
     */
    suspend fun removeProxyAppExemption(proxyUid: Int): Boolean {
        val result = RootShell.exec(
            "ip rule del uidrange $proxyUid-$proxyUid " +
                "2>/dev/null || true"
        )

        return result.isSuccess
    }

    /**
     * Determines whether an interface is likely to be an Android hotspot
     * interface.
     *
     * wlan0 is normally the device's station/Wi-Fi interface.
     *
     * Android/OEM hotspot implementations commonly expose:
     *
     *   wlan1
     *   wlan2
     *   swlan0
     *   ap0
     *
     * We deliberately support these without requiring the user to hardcode
     * wlan1.
     */
    private fun isHotspotInterface(name: String): Boolean {
        return when {
            name == "wlan0" -> false
            name.startsWith("wlan") -> true
            name.startsWith("swlan") -> true
            name == "ap0" -> true
            else -> false
        }
    }

    /**
     * Escape an interface/table string before inserting it into a root shell
     * command.
     *
     * Interface names are normally kernel-controlled and therefore safe, but
     * keeping command construction defensive is worthwhile because these
     * values ultimately originate from device state/UI.
     */
    private fun shellEscape(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /**
     * Full reset.
     *
     * Removes every rule the app knows about, sweeps leftover IPRR chains,
     * deletes app-created interfaces, and restores ip_forward.
     */
    suspend fun resetAll(
        rules: List<RouteRule>,
        createdInterfaces: List<String>
    ): Boolean {
        val teardown = rules.map { removeRule(it) }

        val cleanupCommands = mutableListOf<String>()

        /*
         * Sweep leftover IPRR chains in case persisted state was lost.
         */
        cleanupCommands.add(
            "for c in \\$(iptables -t mangle -S | " +
                "grep -o \"${chainPrefix}_[a-zA-Z0-9]*\" | sort -u); do " +
                "iptables -t mangle -F \\$c 2>/dev/null; " +
                "iptables -t mangle -X \\$c 2>/dev/null; " +
                "done"
        )

        /*
         * Remove app-created interfaces.
         */
        for (name in createdInterfaces) {
            cleanupCommands.add(
                "ip link delete ${shellEscape(name)} 2>/dev/null || true"
            )
        }

        cleanupCommands.add(
            "echo 0 > /proc/sys/net/ipv4/ip_forward"
        )

        val cleanupResults =
            RootShell.execSequential(cleanupCommands)

        return teardown.all { it } &&
            cleanupResults.all { it.isSuccess }
    }
}
