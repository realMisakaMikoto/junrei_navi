package cn.anitabi.navigator.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.anitabi.navigator.security.AppAppearance

val Paper = Color(0xFFF7F6F2)
val Ink = Color(0xFF20231F)
val Vermilion = Color(0xFFC93E4F)
val Moss = Color(0xFF5F625D)
val Sand = Color(0xFFC9C6BE)
val MutedInk = Color(0xFF5F625D)

val BrandYellow = Color(0xFFF4C95E)
val BrandSky = Color(0xFF9DDBF1)
val SoftVermilion = Color(0xFFF8DADD)
val SurfaceMuted = Color(0xFFECEAE4)

private val paperLightColors = lightColorScheme(
    primary = Vermilion,
    onPrimary = Color.White,
    primaryContainer = SoftVermilion,
    onPrimaryContainer = Color(0xFF5E101B),
    inversePrimary = Color(0xFFFFB2BA),
    secondary = Moss,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E6E1),
    onSecondaryContainer = Color(0xFF292B28),
    tertiary = Color(0xFF666963),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE7E8E4),
    onTertiaryContainer = Color(0xFF252824),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = MutedInk,
    surfaceTint = Vermilion,
    inverseSurface = Color(0xFF2E312D),
    inverseOnSurface = Color(0xFFF1F0EB),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Sand,
    outlineVariant = Color(0xFFE2DFD7),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFDFCF8),
    surfaceDim = Color(0xFFDEDDD8),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F7F3),
    surfaceContainer = Color(0xFFF2F1EC),
    surfaceContainerHigh = Color(0xFFECEBE6),
    surfaceContainerHighest = Color(0xFFE6E5E0),
)

private val paperDarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A6),
    onPrimary = Color(0xFF571C12),
    primaryContainer = Color(0xFF733025),
    onPrimaryContainer = Color(0xFFFFDAD3),
    secondary = Color(0xFFBFC9B2),
    onSecondary = Color(0xFF29331F),
    secondaryContainer = Color(0xFF3F4935),
    onSecondaryContainer = Color(0xFFDBE5CD),
    tertiary = Color(0xFFDAC3A4),
    onTertiary = Color(0xFF3C2E19),
    tertiaryContainer = Color(0xFF55452E),
    onTertiaryContainer = Color(0xFFF7DFBF),
    background = Color(0xFF1A1815),
    onBackground = Color(0xFFECE1D6),
    surface = Color(0xFF211E1A),
    onSurface = Color(0xFFECE1D6),
    surfaceVariant = Color(0xFF49423B),
    onSurfaceVariant = Color(0xFFD0C5BA),
    outline = Color(0xFF9B8F83),
    outlineVariant = Color(0xFF49423B),
    surfaceTint = Color(0xFFFFB4A6),
    inverseSurface = Color(0xFFECE1D6),
    inverseOnSurface = Color(0xFF35302A),
    inversePrimary = Vermilion,
    surfaceBright = Color(0xFF403A33),
    surfaceDim = Color(0xFF191612),
    surfaceContainerLowest = Color(0xFF14120F),
    surfaceContainerLow = Color(0xFF211E1A),
    surfaceContainer = Color(0xFF27231F),
    surfaceContainerHigh = Color(0xFF322D27),
    surfaceContainerHighest = Color(0xFF3D3730),
)

private val typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

val NumericTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontFeatureSettings = "tnum",
    fontWeight = FontWeight.SemiBold,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun AnitabiTheme(
    appearance: AppAppearance = AppAppearance.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (appearance) {
        AppAppearance.SYSTEM -> isSystemInDarkTheme()
        AppAppearance.LIGHT -> false
        AppAppearance.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) paperDarkColors else paperLightColors,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}

@Composable
fun MapSurfaceTheme(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    MaterialTheme(
        colorScheme = colors.copy(
            background = if (dark) Color(0xFF17191C) else Color(0xFFF5F6F7),
            onBackground = if (dark) Color(0xFFE2E3E5) else Color(0xFF202124),
            surface = if (dark) Color(0xFF202226) else Color.White,
            onSurface = if (dark) Color(0xFFE2E3E5) else Color(0xFF202124),
            surfaceContainer = if (dark) Color(0xFF25272B) else Color(0xFFEFF1F3),
            surfaceContainerLow = if (dark) Color(0xFF202226) else Color(0xFFF8F9FA),
            surfaceContainerHigh = if (dark) Color(0xFF303236) else Color(0xFFE9ECEF),
            surfaceContainerHighest = if (dark) Color(0xFF3A3C40) else Color(0xFFE2E5E8),
            onSurfaceVariant = if (dark) Color(0xFFC3C7CC) else Color(0xFF53585F),
            outlineVariant = if (dark) Color(0xFF44474C) else Color(0xFFDDE1E5),
        ),
        content = content,
    )
}
