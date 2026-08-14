package net.ip.rerouter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.ip.rerouter.ui.theme.AccentSignal
import net.ip.rerouter.ui.theme.AppType
import net.ip.rerouter.ui.theme.BgSurfaceRaised
import net.ip.rerouter.ui.theme.TextPrimary
import net.ip.rerouter.ui.theme.TextSecondary

@Composable
fun CreateInterfaceDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, isDummy: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var isDummy by remember { mutableStateOf(false) }

    val nameValid = name.matches(Regex("^[a-zA-Z][a-zA-Z0-9]{0,14}$"))

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(BgSurfaceRaised, RoundedCornerShape(14.dp))
                .padding(20.dp)
        ) {
            Text("New interface", style = AppType.displayTitle)
            Spacer(Modifier.height(4.dp))
            Text(
                "Creates a virtual interface owned by this app.",
                style = AppType.bodySecondary
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { value ->
                    name = value.filter { it.isLetterOrDigit() }
                },
                label = { Text("Name", color = TextSecondary) },
                placeholder = {
                    Text(
                        if (isDummy) "dummy0" else "tun1",
                        color = TextSecondary
                    )
                },
                isError = name.isNotEmpty() && !nameValid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
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
            )

            if (name.isNotEmpty() && !nameValid) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Letters and numbers only; start with a letter; max 15 characters.",
                    style = AppType.dataSecondary
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("Type", style = AppType.sectionLabel)
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !isDummy,
                    onClick = { isDummy = false },
                    label = { Text("tun") }
                )
                FilterChip(
                    selected = isDummy,
                    onClick = { isDummy = true },
                    label = { Text("dummy") }
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                if (isDummy) {
                    "A link-layer stub — useful as a routing endpoint or sink."
                } else {
                    "A point-to-point virtual interface for tunneled traffic."
                },
                style = AppType.bodySecondary
            )

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
                    enabled = nameValid,
                    onClick = { onConfirm(name, isDummy) }
                ) {
                    Text("Create")
                }
            }
        }
    }
}
