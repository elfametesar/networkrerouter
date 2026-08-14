package net.ip.rerouter.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import net.ip.rerouter.ui.theme.AccentDanger

@Composable
fun ResetConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset everything?") },
        text = {
            Text(
                "Removes every route this app created, deletes app-created " +
                    "interfaces, and restores default routing. System interfaces " +
                    "like wlan0 and rmnet0 are untouched."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Reset all", color = AccentDanger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
