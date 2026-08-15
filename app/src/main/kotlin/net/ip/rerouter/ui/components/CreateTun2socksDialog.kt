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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.ip.rerouter.model.NetInterface
import net.ip.rerouter.model.ProxyProtocol
import net.ip.rerouter.ui.theme.AccentSignal
import net.ip.rerouter.ui.theme.AppType
import net.ip.rerouter.ui.theme.BgSurfaceRaised
import net.ip.rerouter.ui.theme.TextPrimary
import net.ip.rerouter.ui.theme.TextSecondary

/**
 * Configures a tun2socks session: which TUN device to read packets from
 * (must already exist — create one first) and which proxy to forward
 * TCP/UDP flows through.
 */
@Composable
fun CreateTun2socksDialog(
    tunInterfaces: List<NetInterface>,
    onDismiss: () -> Unit,
    onConfirm: (
        tunInterface: String,
        proxyProtocol: ProxyProtocol,
        proxyAddress: String,
        username: String?,
        password: String?,
        mtu: Int,
        apiPort: Int?
    ) -> Unit
) {
    var tunInterface by remember { mutableStateOf(tunInterfaces.firstOrNull()?.name.orEmpty()) }
    var tunMenuOpen by remember { mutableStateOf(false) }
    var protocol by remember { mutableStateOf(ProxyProtocol.SOCKS5) }
    var proxyAddress by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mtu by remember { mutableStateOf(tunInterfaces.firstOrNull()?.mtu?.toString() ?: "1500") }
    var enableStats by remember { mutableStateOf(false) }
    var statsPort by remember { mutableStateOf("9999") }

    val addressValid = proxyAddress.matches(Regex("^[^\\s:]+:\\d{1,5}$"))
    val mtuValid = mtu.toIntOrNull()?.let { it in 576..9000 } == true
    val statsPortValid = !enableStats || statsPort.toIntOrNull()?.let { it in 1..65535 } == true

    @Composable
    fun fieldColors() = TextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedContainerColor = BgSurfaceRaised,
        unfocusedContainerColor = BgSurfaceRaised,
        focusedIndicatorColor = AccentSignal,
        unfocusedIndicatorColor = TextSecondary,
        cursorColor = AccentSignal,
        focusedLabelColor = AccentSignal,
        unfocusedLabelColor = TextSecondary
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(BgSurfaceRaised, RoundedCornerShape(14.dp))
                .padding(20.dp)
        ) {
            Text("tun2socks session", style = AppType.displayTitle)
            Spacer(Modifier.height(4.dp))
            Text(
                "Terminate a TUN device's IP packets and forward each connection through a proxy.",
                style = AppType.bodySecondary
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                item {
                    Text("TUN device", style = AppType.sectionLabel)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { tunMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(tunInterface.ifEmpty { "Select TUN interface" }, style = AppType.dataPrimary)
                    }
                    if (tunInterfaces.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("No TUN interfaces yet — create one first.", style = AppType.dataSecondary)
                    }
                    if (tunMenuOpen) {
                        AlertDialog(
                            onDismissRequest = { tunMenuOpen = false },
                            title = { Text("Select TUN interface", style = AppType.displayTitle) },
                            text = {
                                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                                    items(tunInterfaces, key = { it.name }) { iface ->
                                        OutlinedButton(
                                            onClick = {
                                                tunInterface = iface.name
                                                iface.mtu?.let { mtu = it.toString() }
                                                tunMenuOpen = false
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text(iface.name, style = AppType.dataPrimary)
                                        }
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { tunMenuOpen = false }) { Text("Close") } }
                        )
                    }
                }
                item { Spacer(Modifier.height(14.dp)) }
                item {
                    Text("Proxy protocol", style = AppType.sectionLabel)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ProxyProtocol.SOCKS5, ProxyProtocol.HTTP, ProxyProtocol.SHADOWSOCKS).forEach { p ->
                            FilterChip(selected = protocol == p, onClick = { protocol = p }, label = { Text(p.name.lowercase()) })
                        }
                    }
                }
                item { Spacer(Modifier.height(14.dp)) }
                item {
                    Text("Proxy address", style = AppType.sectionLabel)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = proxyAddress,
                        onValueChange = { proxyAddress = it.trim() },
                        placeholder = { Text("127.0.0.1:1080", color = TextSecondary) },
                        isError = proxyAddress.isNotEmpty() && !addressValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "host:port of the upstream proxy. Avoid pointing this at a proxy bound to " +
                            "$tunInterface itself or its own default route — that creates a routing loop.",
                        style = AppType.dataSecondary
                    )
                }
                item { Spacer(Modifier.height(14.dp)) }
                item {
                    Text("Auth (optional)", style = AppType.sectionLabel)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username", color = TextSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password", color = TextSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors()
                    )
                }
                item { Spacer(Modifier.height(14.dp)) }
                item {
                    Text("MTU", style = AppType.sectionLabel)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = mtu,
                        onValueChange = { mtu = it.filter { c -> c.isDigit() } },
                        isError = mtu.isNotEmpty() && !mtuValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Pre-filled from the selected TUN device; should match its actual MTU.", style = AppType.dataSecondary)
                }
                item { Spacer(Modifier.height(14.dp)) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Expose stats API", style = AppType.body)
                            Text("Loopback-only /netstats endpoint for debugging", style = AppType.dataSecondary)
                        }
                        Switch(checked = enableStats, onCheckedChange = { enableStats = it })
                    }
                    if (enableStats) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = statsPort,
                            onValueChange = { statsPort = it.filter { c -> c.isDigit() } },
                            label = { Text("Port", color = TextSecondary) },
                            isError = !statsPortValid,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors()
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = tunInterface.isNotEmpty() && addressValid && mtuValid && statsPortValid,
                    onClick = {
                        onConfirm(
                            tunInterface,
                            protocol,
                            proxyAddress,
                            username.ifBlank { null },
                            password.ifBlank { null },
                            mtu.toIntOrNull() ?: 1500,
                            if (enableStats) statsPort.toIntOrNull() else null
                        )
                    }
                ) { Text("Start") }
            }
        }
    }
}
