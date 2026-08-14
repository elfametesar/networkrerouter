package net.ip.rerouter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import net.ip.rerouter.model.RouteRule
import net.ip.rerouter.ui.theme.AccentDanger
import net.ip.rerouter.ui.theme.AccentSignal
import net.ip.rerouter.ui.theme.AppType
import net.ip.rerouter.ui.theme.BgSurface
import net.ip.rerouter.ui.theme.TextSecondary
import net.ip.rerouter.ui.theme.TextTertiary

/**
 * The signature element of this app's UI: a literal flow-line between the
 * source and destination interface names, since "route A to B" is the one
 * thing this whole app exists to do. Solid + moving-feeling dashed segments
 * when active, dim static dash when a rule is disabled.
 */
@Composable
private fun FlowConnector(active: Boolean, modifier: Modifier = Modifier) {
    val color = if (active) AccentSignal else TextTertiary
    Canvas(modifier = modifier.height(2.dp)) {
        val y = size.height / 2
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = if (active) 2.5f else 1.5f,
            cap = StrokeCap.Round,
            pathEffect = if (active) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
        )
    }
}

@Composable
fun RuleCard(
    rule: RouteRule,
    excludedAppLabels: List<String>,
    onToggleEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BgSurface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rule.fromInterface, style = AppType.dataPrimary)
                Spacer(Modifier.width(10.dp))
                FlowConnector(active = rule.enabled, modifier = Modifier.width(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(rule.toInterface, style = AppType.dataPrimary)

                Spacer(Modifier.weight(1f))
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = AccentSignal)
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Close, contentDescription = "Delete rule", tint = AccentDanger)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Text(
                    if (rule.useMasquerade) "NAT · table ${rule.tableId}" else "table ${rule.tableId}",
                    style = AppType.dataSecondary
                )
                if (excludedAppLabels.isNotEmpty()) {
                    Text(
                        "  ·  ${excludedAppLabels.size} excluded",
                        style = AppType.dataSecondary
                    )
                }
            }
        }
    }
}
