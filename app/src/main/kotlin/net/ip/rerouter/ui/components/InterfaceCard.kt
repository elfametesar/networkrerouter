package net.ip.rerouter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.ip.rerouter.model.InterfaceKind
import net.ip.rerouter.model.NetInterface
import net.ip.rerouter.ui.theme.AccentDanger
import net.ip.rerouter.ui.theme.AccentSignal
import net.ip.rerouter.ui.theme.AppType
import net.ip.rerouter.ui.theme.BgSurface
import net.ip.rerouter.ui.theme.HairlineColor
import net.ip.rerouter.ui.theme.InterfaceCellular
import net.ip.rerouter.ui.theme.InterfaceOther
import net.ip.rerouter.ui.theme.InterfaceTun
import net.ip.rerouter.ui.theme.InterfaceWifi
import net.ip.rerouter.ui.theme.TextSecondary
import net.ip.rerouter.ui.theme.TextTertiary

fun kindColor(kind: InterfaceKind): Color = when (kind) {
    InterfaceKind.WIFI -> InterfaceWifi
    InterfaceKind.CELLULAR -> InterfaceCellular
    InterfaceKind.VPN_TUN -> InterfaceTun
    else -> InterfaceOther
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

@Composable
fun InterfaceCard(
    iface: NetInterface,
    onToggle: (Boolean) -> Unit,
    onRemove: (() -> Unit)?,
    onEditMtu: ((Int) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var editingMtu by remember { mutableStateOf(false) }
    var mtuInput by remember(iface.mtu) { mutableStateOf(iface.mtu?.toString() ?: "") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BgSurface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(onClick = { expanded = !expanded }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status dot: solid signal-green when up, dim tertiary when down.
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (iface.isUp) AccentSignal else TextTertiary)
                )
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(iface.name, style = AppType.dataPrimary)
                        Spacer(Modifier.width(8.dp))
                        KindTag(iface.kind)
                        if (iface.isAppCreated) {
                            Spacer(Modifier.width(6.dp))
                            Text("· created", style = AppType.dataSecondary)
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        iface.ipv4 ?: iface.ipv6 ?: "no address",
                        style = AppType.dataSecondary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "↓ ${formatBytes(iface.rxBytes)}   ↑ ${formatBytes(iface.txBytes)}",
                        style = AppType.dataSecondary
                    )
                }

                IconButton(onClick = { onToggle(!iface.isUp) }) {
                    Icon(
                        Icons.Outlined.PowerSettingsNew,
                        contentDescription = if (iface.isUp) "Bring interface down" else "Bring interface up",
                        tint = if (iface.isUp) AccentSignal else TextSecondary
                    )
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove interface", tint = AccentDanger)
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                DetailRow("MAC", iface.mac ?: "—")
                DetailRow(
                    "MTU",
                    iface.mtu?.toString() ?: "—",
                    onClick = if (onEditMtu != null) { { editingMtu = true } } else null
                )
                DetailRow("Gateway", iface.gateway ?: "—")
                DetailRow("IPv6", iface.ipv6 ?: "—")
                DetailRow(
                    "DNS",
                    iface.dnsServers.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "—"
                )

                if (editingMtu) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = mtuInput,
                            onValueChange = { mtuInput = it.filter { c -> c.isDigit() } },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            label = { Text("MTU") }
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            mtuInput.toIntOrNull()?.let { onEditMtu?.invoke(it) }
                            editingMtu = false
                        }) { Text("Set") }
                        TextButton(onClick = { editingMtu = false }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = AppType.dataSecondary)
        Text(value, style = AppType.dataSecondary)
    }
}

@Composable
private fun KindTag(kind: InterfaceKind) {
    val color = kindColor(kind)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            kind.name.lowercase(),
            style = AppType.dataSecondary.copy(color = color)
        )
    }
}

