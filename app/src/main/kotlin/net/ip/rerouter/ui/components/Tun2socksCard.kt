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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.ip.rerouter.model.Tun2socksConfig
import net.ip.rerouter.ui.theme.AccentDanger
import net.ip.rerouter.ui.theme.AccentSignal
import net.ip.rerouter.ui.theme.AppType
import net.ip.rerouter.ui.theme.BgSurface
import net.ip.rerouter.ui.theme.TextTertiary

@Composable
fun Tun2socksCard(
    config: Tun2socksConfig,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
    onViewLog: suspend () -> List<String>
) {
    var showLog by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf(listOf<String>()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BgSurface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (isRunning) AccentSignal else TextTertiary)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(config.tunInterface, style = AppType.dataPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${config.proxyProtocol.name.lowercase()} → ${config.proxyAddress}",
                        style = AppType.dataSecondary
                    )
                }
                IconButton(onClick = { if (isRunning) onStop() else onStart() }) {
                    Icon(
                        if (isRunning) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                        contentDescription = if (isRunning) "Stop" else "Start",
                        tint = if (isRunning) AccentDanger else AccentSignal
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove session", tint = AccentDanger)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "mtu ${config.mtu}" + (config.apiPort?.let { " · stats :$it" } ?: ""),
                    style = AppType.dataSecondary
                )
                TextButton(onClick = { showLog = true }) { Text("View log") }
            }
        }
    }

    if (showLog) {
        LaunchedEffect(config.id) {
            logLines = onViewLog()
        }
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("${config.tunInterface} log", style = AppType.displayTitle) },
            text = {
                if (logLines.isEmpty()) Text("No output yet.", style = AppType.bodySecondary)
                else LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(logLines) { line ->
                        Text(line, style = AppType.dataSecondary, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("Close") } }
        )
    }
}
