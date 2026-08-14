package net.ip.rerouter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// --- Palette -----------------------------------------------------------
// A systems-utility dark surface, not a consumer-app one. One accent only.
val BgBase = Color(0xFF0B0F14)        // near-black slate, cooler than pure black
val BgSurface = Color(0xFF12181F)     // cards / rows
val BgSurfaceRaised = Color(0xFF1A222B)
val HairlineColor = Color(0xFF232C36)
val TextPrimary = Color(0xFFE6EDF3)
val TextSecondary = Color(0xFF8A97A6)
val TextTertiary = Color(0xFF5B6672)
val AccentSignal = Color(0xFF3DDC84)  // routed/active state
val AccentWarn = Color(0xFFE3A008)
val AccentDanger = Color(0xFFE5484D)
val InterfaceWifi = Color(0xFF5AB0FF)
val InterfaceCellular = Color(0xFFB98CFF)
val InterfaceTun = Color(0xFF3DDC84)
val InterfaceOther = Color(0xFF8A97A6)

// --- Type ----------------------------------------------------------------
// Interface names, IPs, MACs are data — monospace. Everything else is UI chrome.
val MonoFamily = FontFamily.Monospace
val SansFamily = FontFamily.SansSerif

object AppType {
    val displayTitle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp
    )
    val sectionLabel = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp,
        letterSpacing = 1.2.sp
    )
    val body = TextStyle(fontFamily = SansFamily, fontSize = 14.sp)
    val bodySecondary = TextStyle(fontFamily = SansFamily, fontSize = 13.sp, color = TextSecondary)
    val dataPrimary = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    val dataSecondary = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, color = TextSecondary)
}

private val DarkScheme = darkColorScheme(
    background = BgBase,
    surface = BgSurface,
    surfaceVariant = BgSurfaceRaised,
    primary = AccentSignal,
    onPrimary = Color(0xFF00281A),
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    secondary = InterfaceCellular,
    error = AccentDanger,
    outline = HairlineColor
)

@Composable
fun IPRerouterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content
    )
}
