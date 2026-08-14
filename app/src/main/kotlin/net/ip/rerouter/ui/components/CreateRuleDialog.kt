package net.ip.rerouter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.ip.rerouter.model.AppInfo
import net.ip.rerouter.model.NetInterface
import net.ip.rerouter.ui.theme.AccentSignal
import net.ip.rerouter.ui.theme.AppType
import net.ip.rerouter.ui.theme.BgSurfaceRaised
import net.ip.rerouter.ui.theme.TextSecondary
import net.ip.rerouter.ui.theme.TextTertiary

@Composable
fun CreateRuleDialog(
    interfaces: List<NetInterface>,
    apps: List<AppInfo>,
    onDismiss: () -> Unit,
    onConfirm: (
        from: String,
        to: String,
        excludedUids: Set<Int>,
        masquerade: Boolean
    ) -> Unit
) {
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var masquerade by remember { mutableStateOf(true) }
    var excluded by remember { mutableStateOf(setOf<Int>()) }
    var fromMenuOpen by remember { mutableStateOf(false) }
    var toMenuOpen by remember { mutableStateOf(false) }

    // Keep the picker valid if a hotspot/tunnel appears while this dialog is open.
    LaunchedEffect(interfaces) {
        if (from !in interfaces.map { it.name }) {
            from = interfaces.firstOrNull()?.name.orEmpty()
        }
        if (to !in interfaces.map { it.name } || to == from) {
            to = interfaces.firstOrNull { it.name != from }?.name.orEmpty()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(BgSurfaceRaised, RoundedCornerShape(14.dp))
                .padding(20.dp)
        ) {
            Text("New route", style = AppType.displayTitle)
            Spacer(Modifier.height(4.dp))
            Text(
                "Traffic entering on one interface is routed through another.",
                style = AppType.bodySecondary
            )
            Spacer(Modifier.height(16.dp))

            Text("From", style = AppType.sectionLabel)
            Spacer(Modifier.height(6.dp))
            InterfacePicker(
                interfaces = interfaces,
                selected = from,
                expanded = fromMenuOpen,
                onExpandedChange = { fromMenuOpen = it },
                onSelect = {
                    from = it
                    if (to == it) {
                        to = interfaces.firstOrNull { iface -> iface.name != it }?.name.orEmpty()
                    }
                    fromMenuOpen = false
                }
            )

            Spacer(Modifier.height(14.dp))

            Text("Routes out through", style = AppType.sectionLabel)
            Spacer(Modifier.height(6.dp))
            InterfacePicker(
                interfaces = interfaces,
                selected = to,
                expanded = toMenuOpen,
                onExpandedChange = { toMenuOpen = it },
                onSelect = {
                    to = it
                    toMenuOpen = false
                }
            )

            Spacer(Modifier.height(8.dp))

            if (from.isNotEmpty() && to.isNotEmpty()) {
                Text(
                    "$from  →  $to",
                    style = AppType.dataPrimary
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Masquerade (NAT)", style = AppType.body)
                    Text(
                        "Rewrite source address on the outgoing interface",
                        style = AppType.dataSecondary
                    )
                }

                Switch(
                    checked = masquerade,
                    onCheckedChange = { masquerade = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = AccentSignal
                    )
                )
            }

            Spacer(Modifier.height(16.dp))

            Text("Exclude apps (${excluded.size})", style = AppType.sectionLabel)
            Spacer(Modifier.height(6.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                items(apps, key = { it.uid }) { app ->
                    AppExcludeRow(
                        app = app,
                        checked = app.uid in excluded,
                        onCheckedChange = { checked ->
                            excluded = if (checked) {
                                excluded + app.uid
                            } else {
                                excluded - app.uid
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }

                Spacer(Modifier.width(8.dp))

                OutlinedButton(
                    enabled = from.isNotEmpty() &&
                        to.isNotEmpty() &&
                        from != to,
                    onClick = {
                        onConfirm(from, to, excluded, masquerade)
                    }
                ) {
                    Text("Create route")
                }
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
    Box {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            enabled = interfaces.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                selected.ifEmpty {
                    if (interfaces.isEmpty()) "No interfaces available"
                    else "Select interface"
                }
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            interfaces.forEach { iface ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(iface.name, style = AppType.dataPrimary)
                            Text(
                                buildString {
                                    append(iface.kind.name.lowercase())
                                    if (iface.ipv4 != null) {
                                        append(" · ")
                                        append(iface.ipv4)
                                    }
                                    if (iface.isUp) append(" · UP")
                                },
                                style = AppType.dataSecondary
                            )
                        }
                    },
                    onClick = { onSelect(iface.name) }
                )
            }
        }
    }
}

@Composable
private fun AppExcludeRow(
    app: AppInfo,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = AccentSignal)
        )

        Column {
            Text(app.label, style = AppType.body)
            Text(
                app.packageName,
                style = AppType.dataSecondary.copy(color = TextSecondary)
            )
        }
    }
}
