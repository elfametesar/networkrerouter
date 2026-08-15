package net.ip.rerouter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Troubleshoot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.ip.rerouter.model.InterfaceKind
import net.ip.rerouter.model.SourceInterfaceType
import net.ip.rerouter.ui.components.CreateInterfaceDialog
import net.ip.rerouter.ui.components.CreateRuleDialog
import net.ip.rerouter.ui.components.CreateTun2socksDialog
import net.ip.rerouter.ui.components.DiagnosticsDialog
import net.ip.rerouter.ui.components.InterfaceCard
import net.ip.rerouter.ui.components.ResetConfirmDialog
import net.ip.rerouter.ui.components.RuleCard
import net.ip.rerouter.ui.components.Tun2socksCard
import net.ip.rerouter.ui.theme.AccentDanger
import net.ip.rerouter.ui.theme.AppType
import net.ip.rerouter.ui.theme.BgBase
import net.ip.rerouter.ui.theme.TextSecondary

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    var showCreateInterface by remember { mutableStateOf(false) }
    var showCreateRule by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showCreateTun2socks by remember { mutableStateOf(false) }

    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearError()
        }
    }

    when (state.hasRoot) {
        null -> RootCheckingScreen()
        false -> NoRootScreen()
        true -> Scaffold(
            containerColor = BgBase,
            snackbarHost = { SnackbarHost(snackbarHost) },
            floatingActionButton = {
                Column(horizontalAlignment = Alignment.End) {
                    ExtendedFloatingActionButton(
                        onClick = { showCreateInterface = true },
                        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        text = { Text("Interface") }
                    )

                    Spacer(Modifier.height(10.dp))

                    ExtendedFloatingActionButton(
                        onClick = { showCreateRule = true },
                        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        text = { Text("Route") }
                    )

                    Spacer(Modifier.height(10.dp))

                    ExtendedFloatingActionButton(
                        onClick = { showCreateTun2socks = true },
                        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        text = { Text("tun2socks") }
                    )
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .safeDrawingPadding(),
                contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("IP Rerouter", style = AppType.displayTitle)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${state.interfaces.size} live interfaces · ${state.rules.count { it.enabled }} active routes",
                                style = AppType.bodySecondary
                            )
                            if (state.realRoutingTable != null) {
                                Text(
                                    "cellular real table: ${state.realRoutingTable}",
                                    style = AppType.dataSecondary
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                viewModel.refreshDiagnostics()
                                showDiagnostics = true
                            }
                        ) {
                            Icon(
                                Icons.Outlined.Troubleshoot,
                                contentDescription = "Routing diagnostics"
                            )
                        }

                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(22.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(
                                onClick = { viewModel.refreshInterfaces(showLoading = true) }
                            ) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = "Refresh interfaces"
                                )
                            }
                        }
                    }
                }

                item { SectionHeader("Interfaces") }

                items(state.interfaces, key = { it.name }) { iface ->
                    InterfaceCard(
                        iface = iface,
                        onToggle = { up ->
                            viewModel.toggleInterfaceState(iface.name, up)
                        },
                        onRemove = if (iface.isAppCreated) {
                            { viewModel.removeInterface(iface.name) }
                        } else {
                            null
                        },
                        onEditMtu = { mtu -> viewModel.setInterfaceMtu(iface.name, mtu) }
                    )
                }

                if (state.interfaces.isEmpty()) {
                    item { EmptyRow("No kernel interfaces found") }
                }

                item { Spacer(Modifier.height(6.dp)) }
                item { SectionHeader("Routes") }

                items(state.rules, key = { it.id }) { rule ->
                    val excludedLabels = state.installedApps
                        .filter { it.uid in rule.excludedUids }
                        .map { it.label }
                    val proxyLabel = rule.proxyAppPackage?.let { pkg ->
                        state.installedApps.firstOrNull { it.packageName == pkg }?.label ?: pkg
                    }

                    RuleCard(
                        rule = rule,
                        excludedAppLabels = excludedLabels,
                        proxyAppLabel = proxyLabel,
                        onToggleEnabled = { enabled ->
                            viewModel.toggleRuleEnabled(rule.id, enabled)
                        },
                        onRemove = { viewModel.removeRule(rule.id) }
                    )
                }

                if (state.rules.isEmpty()) {
                    item {
                        EmptyRow("No routes yet — traffic follows the system routing tables")
                    }
                }

                item { Spacer(Modifier.height(6.dp)) }
                item { SectionHeader("tun2socks sessions") }

                items(state.tun2socksSessions, key = { it.id }) { session ->
                    Tun2socksCard(
                        config = session,
                        isRunning = state.tun2socksRunning[session.id] == true,
                        onStart = {
                            viewModel.startTun2socks(
                                tunInterface = session.tunInterface,
                                proxyProtocol = session.proxyProtocol,
                                proxyAddress = session.proxyAddress,
                                proxyUsername = session.proxyUsername,
                                proxyPassword = session.proxyPassword,
                                mtu = session.mtu,
                                apiPort = session.apiPort
                            )
                        },
                        onStop = { viewModel.stopTun2socks(session.id) },
                        onRemove = { viewModel.removeTun2socksSession(session.id) },
                        onViewLog = { viewModel.tun2socksLog(session.id) }
                    )
                }

                if (state.tun2socksSessions.isEmpty()) {
                    item {
                        EmptyRow("No tun2socks sessions — reads a TUN device's packets and forwards them through a proxy")
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
                item { ResetAllRow(onClick = { showResetConfirm = true }) }
            }
        }
    }

    if (showCreateInterface) {
        CreateInterfaceDialog(
            onDismiss = { showCreateInterface = false },
            onConfirm = { name, isDummy, ipCidr ->
                viewModel.createInterface(name, isDummy, ipCidr)
                showCreateInterface = false
            }
        )
    }

    if (showCreateRule) {
        CreateRuleDialog(
            interfaces = state.interfaces,
            apps = state.installedApps,
            onDismiss = { showCreateRule = false },
            onConfirm = { from, to, excluded, masquerade, proxyAppPackage, sourceType ->
                viewModel.addRule(
                    fromInterface = from,
                    toInterface = to,
                    excludedUids = excluded,
                    useMasquerade = masquerade,
                    proxyAppPackage = proxyAppPackage,
                    sourceType = sourceType
                )
                showCreateRule = false
            }
        )
    }

    if (showResetConfirm) {
        ResetConfirmDialog(
            onDismiss = { showResetConfirm = false },
            onConfirm = {
                viewModel.resetAll()
                showResetConfirm = false
            }
        )
    }

    if (showDiagnostics) {
        DiagnosticsDialog(
            policyRules = state.policyRules,
            routeTableDump = state.routeTableDump,
            realRoutingTable = state.realRoutingTable,
            onRefresh = { viewModel.refreshDiagnostics() },
            onDismiss = { showDiagnostics = false }
        )
    }

    if (showCreateTun2socks) {
        CreateTun2socksDialog(
            tunInterfaces = state.interfaces.filter { it.kind == InterfaceKind.VPN_TUN },
            onDismiss = { showCreateTun2socks = false },
            onConfirm = { tunInterface, protocol, address, username, password, mtu, apiPort ->
                viewModel.startTun2socks(tunInterface, protocol, address, username, password, mtu, apiPort)
                showCreateTun2socks = false
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title.uppercase(), style = AppType.sectionLabel)
}

@Composable
private fun EmptyRow(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Text(text, style = AppType.bodySecondary)
    }
}

@Composable
private fun ResetAllRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        TextButton(onClick = onClick) {
            Icon(
                Icons.Outlined.RestartAlt,
                contentDescription = null,
                tint = AccentDanger
            )
            Text("  Reset all", color = AccentDanger)
        }
    }
}

@Composable
private fun RootCheckingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NoRootScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Root required",
                style = AppType.displayTitle,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This app manages interfaces and routing tables directly, which needs root access. " +
                    "Grant root in your root manager (Magisk or KernelSU) and reopen the app.",
                style = AppType.bodySecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
