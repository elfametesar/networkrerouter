package net.ip.rerouter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val BgBase = Color(0xFF0B0F14)
val BgSurface = Color(0xFF141B23)
val BgSurfaceRaised = Color(0xFF1D2732)
val HairlineColor = Color(0xFF34404D)

val TextPrimary = Color(0xFFF2F6FA)
val TextSecondary = Color(0xFFB7C2CE)
val TextTertiary = Color(0xFF8793A0)

val AccentSignal = Color(0xFF45E58A)
val AccentWarn = Color(0xFFFFC247)
val AccentDanger = Color(0xFFFF6B70)

val InterfaceWifi = Color(0xFF70BCFF)
val InterfaceCellular = Color(0xFFC6A2FF)
val InterfaceTun = Color(0xFF45E58A)
val InterfaceOther = Color(0xFFB7C2CE)

val MonoFamily = FontFamily.Monospace
val SansFamily = FontFamily.SansSerif

object AppType {
    val displayTitle = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        color = TextPrimary
    )

    val sectionLabel = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 1.2.sp,
        color = TextSecondary
    )

    val body = TextStyle(
        fontFamily = SansFamily,
        fontSize = 14.sp,
        color = TextPrimary
    )

    val bodySecondary = TextStyle(
        fontFamily = SansFamily,
        fontSize = 13.sp,
        color = TextSecondary
    )

    val dataPrimary = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        color = TextPrimary
    )

    val dataSecondary = TextStyle(
        fontFamily = MonoFamily,
        fontSize = 12.sp,
        color = TextSecondary
    )
}

private val DarkScheme = darkColorScheme(
    primary = AccentSignal,
    onPrimary = Color(0xFF002A18),
    primaryContainer = Color(0xFF0D4A2C),
    onPrimaryContainer = Color(0xFFB8FFD4),

    secondary = InterfaceCellular,
    onSecondary = Color(0xFF24113E),
    secondaryContainer = Color(0xFF3C2460),
    onSecondaryContainer = Color(0xFFEADBFF),

    background = BgBase,
    onBackground = TextPrimary,
    surface = BgSurface,
    onSurface = TextPrimary,
    surfaceVariant = BgSurfaceRaised,
    onSurfaceVariant = TextSecondary,

    outline = HairlineColor,
    outlineVariant = Color(0xFF46525F),

    error = AccentDanger,
    onError = Color(0xFF3A0004)
)

@Composable
fun IPRerouterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content
    )
}
