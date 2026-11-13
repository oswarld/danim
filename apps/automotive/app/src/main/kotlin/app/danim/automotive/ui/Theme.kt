package app.danim.automotive.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.danim.automotive.R

// Pleos 2.1.5: background 67:5182, surfaces 67:5404, type 67:12340 / 67:12351.
// Hyundai accents are separate from the platform's semantic status colors.
private val HyundaiBlue = Color(0xFF002C5F)
private val SkyBlue = Color(0xFFAACAE6)
private val ActiveBlue = Color(0xFF00AAD2)
private val Sand = Color(0xFFE4DCD3)

private val DayColors = lightColorScheme(
    primary = HyundaiBlue, onPrimary = Color.White,
    primaryContainer = SkyBlue, onPrimaryContainer = HyundaiBlue,
    secondary = HyundaiBlue, onSecondary = Color.White,
    secondaryContainer = Sand, onSecondaryContainer = HyundaiBlue,
    tertiary = ActiveBlue, onTertiary = HyundaiBlue,
    background = Color(0xFFF7F8FA), onBackground = Color(0xFF131417),
    surface = Color.White, onSurface = Color(0xFF131417),
    surfaceVariant = Color(0xFFEDEEF2), onSurfaceVariant = Color(0xFF565C65),
    outline = Color(0xFF737983), outlineVariant = Color(0xFFD2D5DC),
    error = Color(0xFFAD2910), onError = Color.White,
    errorContainer = Color(0xFFFFE4DC), onErrorContainer = Color(0xFFAD2910),
)

private val NightColors = darkColorScheme(
    primary = SkyBlue, onPrimary = HyundaiBlue,
    primaryContainer = HyundaiBlue, onPrimaryContainer = Color.White,
    secondary = SkyBlue, onSecondary = HyundaiBlue,
    secondaryContainer = Color(0xFF313236), onSecondaryContainer = Color.White,
    tertiary = ActiveBlue, onTertiary = HyundaiBlue,
    background = Color(0xFF1D1E21), onBackground = Color.White,
    surface = Color(0xFF131417), onSurface = Color.White,
    surfaceVariant = Color(0xFF313236), onSurfaceVariant = Color(0xFFC1C3CA),
    outline = Color(0xFF92959E), outlineVariant = Color(0xFF44464E),
    error = Color(0xFFFF8A75), onError = Color(0xFF131417),
    errorContainer = Color(0xFF44251F), onErrorContainer = Color(0xFFFFB4A5),
)

@OptIn(ExperimentalTextApi::class)
private val AstaSans = FontFamily(
    Font(R.font.asta_sans, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.asta_sans, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = AstaSans, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight,
)

private val PleosTypography = Typography(
    headlineLarge = type(56, 72, FontWeight.Bold),
    titleLarge = type(40, 52, FontWeight.Bold),
    titleMedium = type(36, 44, FontWeight.Bold),
    titleSmall = type(32, 40, FontWeight.Bold),
    bodyLarge = type(30, 38),
    bodyMedium = type(28, 36),
    bodySmall = type(26, 34),
    labelLarge = type(30, 38, FontWeight.Bold),
    labelMedium = type(24, 28),
    labelSmall = type(20, 24),
)

@Composable
fun DanimTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) NightColors else DayColors,
        typography = PleosTypography,
        shapes = Shapes(
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(24.dp),
            large = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
