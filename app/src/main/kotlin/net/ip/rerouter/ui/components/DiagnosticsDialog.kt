package net.ip.rerouter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.ip.rerouter.ui.theme.AppType
import net.ip.rerouter.ui.theme.BgSurfaceRaised

/**
 * Live view of the kernel's actual policy-routing state: `ip rule` and
 * `ip route show table all`. Exists so failures (wrong table, unexpected
 * device name, priority conflicts) are directly inspectable instead of
 * inferred from an "apply failed" toast.
 */
@Composable
fun DiagnosticsDialog(
    policyRules: List<String>,
    routeTableDump: List<String>,
    realRoutingTable: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(BgSurfaceRaised, RoundedCornerShape(14.dp))
                .padding(20.dp)
        ) {
            Text("Routing diagnostics", style = AppType.displayTitle)
            Spacer(Modifier.height(4.dp))
            Text(
                "Detected real routing table for cellular: ${realRoutingTable ?: "unknown"}",
                style = AppType.bodySecondary
            )
            Spacer(Modifier.height(12.dp))

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("ip rule") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("ip route (all)") })
            }
            Spacer(Modifier.height(10.dp))

            val lines = if (tab == 0) policyRules else routeTableDump
            if (lines.isEmpty()) {
                Text("No output.", style = AppType.bodySecondary, modifier = Modifier.padding(vertical = 10.dp))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(lines) { line ->
                        Text(line, style = AppType.dataSecondary, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRefresh) { Text("Refresh") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
