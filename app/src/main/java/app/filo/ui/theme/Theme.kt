package app.filo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.filo.R
import androidx.compose.ui.unit.dp
import app.filo.data.ThemeMode

// Filo teal, used when wallpaper colors are off or on phones older than Android 12.
private val Light = lightColorScheme(
    primary = Color(0xFF0F6E56),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9FE1CB),
    onPrimaryContainer = Color(0xFF04342C),
    secondary = Color(0xFF4A635A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE9DD),
    onSecondaryContainer = Color(0xFF062019),
    tertiary = Color(0xFF3F6375),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC3E8FD),
    onTertiaryContainer = Color(0xFF001F2B),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF8FAF9),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFF8FAF9),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F5F3),
    surfaceContainer = Color(0xFFECEFED),
    surfaceContainerHigh = Color(0xFFE6E9E7),
    surfaceContainerHighest = Color(0xFFE1E3E2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5DCAA5),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF085041),
    onPrimaryContainer = Color(0xFF9FE1CB),
    secondary = Color(0xFFB1CCC1),
    onSecondary = Color(0xFF1C352D),
    secondaryContainer = Color(0xFF334B43),
    onSecondaryContainer = Color(0xFFCDE9DD),
    tertiary = Color(0xFFA7CCE0),
    onTertiary = Color(0xFF0B3545),
    tertiaryContainer = Color(0xFF264B5C),
    onTertiaryContainer = Color(0xFFC3E8FD),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE0E3E1),
    surface = Color(0xFF101413),
    onSurface = Color(0xFFE0E3E1),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4945),
    surfaceContainerLowest = Color(0xFF0B0F0E),
    surfaceContainerLow = Color(0xFF191C1B),
    surfaceContainer = Color(0xFF1D201F),
    surfaceContainerHigh = Color(0xFF272B29),
    surfaceContainerHighest = Color(0xFF323534),
)

private val FiloShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Plus Jakarta Sans (SIL Open Font License), bundled in the app, so nothing is downloaded. */
val Jakarta = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

private fun TextStyle.jakarta(weight: FontWeight? = null) =
    copy(fontFamily = Jakarta, fontWeight = weight ?: fontWeight)

private val base = Typography()
private val FiloType = Typography(
    displayLarge = base.displayLarge.jakarta(),
    displayMedium = base.displayMedium.jakarta(),
    displaySmall = base.displaySmall.jakarta(),
    headlineLarge = base.headlineLarge.jakarta(),
    headlineMedium = base.headlineMedium.jakarta(),
    headlineSmall = base.headlineSmall.jakarta(FontWeight.Medium),
    titleLarge = base.titleLarge.jakarta(FontWeight.Medium),
    titleMedium = base.titleMedium.jakarta(FontWeight.SemiBold),
    titleSmall = base.titleSmall.jakarta(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.jakarta(),
    bodyMedium = base.bodyMedium.jakarta(),
    bodySmall = base.bodySmall.jakarta(),
    labelLarge = base.labelLarge.jakarta(FontWeight.SemiBold),
    labelMedium = base.labelMedium.jakarta(FontWeight.SemiBold),
    labelSmall = base.labelSmall.jakarta(FontWeight.Medium),
)

@Composable
fun FiloTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, shapes = FiloShapes, typography = FiloType, content = content)
}
