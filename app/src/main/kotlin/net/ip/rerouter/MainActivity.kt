package net.ip.rerouter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.ip.rerouter.ui.MainScreen
import net.ip.rerouter.ui.theme.IPRerouterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IPRerouterTheme {
                MainScreen()
            }
        }
    }
}
