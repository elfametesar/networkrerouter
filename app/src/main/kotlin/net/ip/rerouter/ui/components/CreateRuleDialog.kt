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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import net.ip.rerouter.model.AppInfo
import net.ip.rerouter.model.NetInterface
import net.ip.rerouter.model.SourceInterfaceType
import net.ip.rerouter.ui.theme.AccentSignal
import net.ip.rerouter.ui.theme.AppType
import net.ip.rerouter.ui.theme.BgSurfaceRaised

@Composable
fun CreateRuleDialog(
    interfaces: List<NetInterface>,
    apps: List<AppInfo>,
    onDismiss: () -> Unit,
    onConfirm: (
        from: String,
        to: String,
        excludedUids: Set<Int>,
        masquerade: Boolean,
        proxyAppPackage: String?,
        sourceType: SourceInterfaceType
    ) -> Unit
) {
    var from by remember { mutableStateOf(interfaces.firstOrNull()?.name.orEmpty()) }
    var to by remember { mutableStateOf(interfaces.getOrNull(1)?.name ?: interfaces.firstOrNull()?.name.orEmpty()) }
    var masquerade by remember { mutableStateOf(true) }
    var excluded by remember { mutableStateOf(setOf<Int>()) }
    var fromMenuOpen by remember { mutableStateOf(false) }
    var toMenuOpen by remember { mutableStateOf(false) }
    var proxyMenuOpen by remember { mutableStateOf(false) }
    var selectedProxyApp by remember { mutableStateOf<AppInfo?>(null) }
    var includeHotspot by remember { mutableStateOf(false) }
    var showSystemApps by remember { mutableStateOf(false) }

    val visibleApps = apps.filter { showSystemApps || !it.isSystemApp }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(BgSurfaceRaised, RoundedCornerShape(14.dp))
                .padding(20.dp)
        ) {
            Text("New route", style = AppType.displayTitle)
            Spacer(Modifier.height(4.dp))
            Text("Traffic from one interface goes out through another.", style = AppType.bodySecondary)
            Spacer(Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 550.dp)) {
                item {
                    Text("From", style = AppType.sectionLabel)
                    Spacer(Modifier.height(6.dp))
                    InterfacePicker(
                        interfaces = interfaces,
                        selected = from,
                        expanded = fromMenuOpen,
                        onExpandedChange = { fromMenuOpen = it },
                        onSelect = { from = it; fromMenuOpen = false }
                    )
                }

                item { Spacer(Modifier.height(14.dp)) }

                item {
                    Text("Routes out through", style = AppType.sectionLabel)
                    Spacer(Modifier.height(6.dp))
                    InterfacePicker(
                        interfaces = interfaces,
                        selected = to,
                        expanded = toMenuOpen,
                        onExpandedChange = { toMenuOpen = it },
                        onSelect = { to = it; toMenuOpen = false }
                    )
                }

                item { Spacer(Modifier.height(16.dp)) }

                item {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Masquerade (NAT)", style = AppType.body)
                            Text("Rewrite source address when forwarding", style = AppType.dataSecondary)
                        }
                        Switch(
                            checked = masquerade,
                            onCheckedChange = { masquerade = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = AccentSignal)
                        )
                    }
                }

                item { Spacer(Modifier.height(14.dp)) }

                item {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Route hotspot clients too", style = AppType.body)
                            Text(
                                if (includeHotspot) "Hotspot traffic → tunnel" else "Only device local traffic → tunnel",
                                style = AppType.dataSecondary
                            )
                        }
                        Switch(
                            checked = includeHotspot,
                            onCheckedChange = { includeHotspot = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = AccentSignal)
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }

                item {
                    Text("Proxy app (optional)", style = AppType.sectionLabel)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Exempt a proxy/VPN app so its own connection continues over the real network",
                        style = AppType.dataSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { proxyMenuOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            selectedProxyApp?.label ?: "Select proxy app (optional)",
                            style = AppType.dataPrimary
                        )
                    }

                    if (proxyMenuOpen) {
                        AlertDialog(
                            onDismissRequest = { proxyMenuOpen = false },
                            title = { Text("Select proxy app", style = AppType.displayTitle) },
                            text = {
                                if (visibleApps.isEmpty()) {
                                    Text("No apps found.", style = AppType.bodySecondary)
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                                    ) {
                                        items(visibleApps, key = { it.uid }) { app ->
                                            OutlinedButton(
                                                onClick = {
                                                    selectedProxyApp = app
                                                    proxyMenuOpen = false
                                                },
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Column(modifier = Modifier.fillMaxWidth()) {
                                                    Text(app.label, style = AppType.dataPrimary)
                                                    Text(app.packageName, style = AppType.dataSecondary)
                                                }
                                            }
                                        }
                                        item {
                                            OutlinedButton(
                                                onClick = {
                                                    selectedProxyApp = null
                                                    proxyMenuOpen = false
                                                },
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text("None (no exemption)", style = AppType.dataPrimary)
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { proxyMenuOpen = false }) { Text("Close") }
                            }
                        )
                    }
                }

                item { Spacer(Modifier.height(14.dp)) }

                item {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Exclude apps (${excluded.size})", style = AppType.sectionLabel)
                            Text("Excluded apps bypass this route", style = AppType.dataSecondary)
                        }
                        Switch(
                            checked = showSystemApps,
                            onCheckedChange = { showSystemApps = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = AccentSignal)
                        )
                    }
                    Text(
                        if (showSystemApps) "Showing user + system apps" else "Showing user apps only",
                        style = AppType.dataSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    if (visibleApps.isEmpty()) {
                        Text("No installed apps found", style = AppType.bodySecondary, modifier = Modifier.padding(vertical = 10.dp))
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                            items(visibleApps, key = { it.uid }) { app ->
                                AppExcludeRow(
                                    app = app,
                                    checked = app.uid in excluded,
                                    onCheckedChange = { checked ->
                                        excluded = if (checked) excluded + app.uid else excluded - app.uid
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = from.isNotEmpty() && to.isNotEmpty() && from != to,
                    onClick = {
                        onConfirm(
                            from,
                            to,
                            excluded,
                            masquerade,
                            selectedProxyApp?.packageName,
                            if (includeHotspot) SourceInterfaceType.LOCAL_AND_HOTSPOT else SourceInterfaceType.LOCAL_ONLY
                        )
                    }
                ) { Text("Create route") }
            }
        }
    }
}

@Composable
private fun InterfacePicker(
    interfaces: List<NetInterface>,
    selected: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit
) {
    OutlinedButton(
        onClick = { onExpandedChange(true) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(selected.ifEmpty { "Select interface" }, style = AppType.dataPrimary)
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { onExpandedChange(false) },
            title = { Text("Select interface", style = AppType.displayTitle) },
            text = {
                if (interfaces.isEmpty()) {
                    Text("No network interfaces found.", style = AppType.bodySecondary)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(interfaces, key = { it.name }) { iface ->
                            OutlinedButton(
                                onClick = { onSelect(iface.name) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(iface.name, style = AppType.dataPrimary)
                                    Text(
                                        buildString {
                                            iface.ipv4?.let { append(it) }
                                            if (iface.isUp) {
                                                if (isNotEmpty()) append("  •  ")
                                                append("UP")
                                            }
                                        }.ifEmpty { "DOWN / no IPv4" },
                                        style = AppType.dataSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onExpandedChange(false) }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun AppExcludeRow(app: AppInfo, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = AccentSignal)
        )
        Column {
            Text(app.label, style = AppType.body)
            Text(
                if (app.isSystemApp) "${app.packageName}  •  system" else app.packageName,
                style = AppType.dataSecondary
            )
        }
    }
}
