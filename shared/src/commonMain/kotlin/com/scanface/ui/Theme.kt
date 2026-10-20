package com.scanface.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF245C45), onPrimary = Color.White,
    primaryContainer = Color(0xFFE5EDE6), onPrimaryContainer = Color(0xFF245C45),
    secondary = Color(0xFF59695F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EDE6), onSecondaryContainer = Color(0xFF16251F),
    background = Color(0xFFF6F7F2), onBackground = Color(0xFF16251F),
    surface = Color.White, onSurface = Color(0xFF16251F),
    surfaceTint = Color(0xFF245C45), surfaceBright = Color(0xFFF8FAF5), surfaceDim = Color(0xFFDDE4DC),
    surfaceVariant = Color(0xFFE5EDE6), onSurfaceVariant = Color(0xFF59695F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F6F0),
    surfaceContainer = Color(0xFFF0F3ED), surfaceContainerHigh = Color(0xFFE5EDE6),
    surfaceContainerHighest = Color(0xFFDDE6DC),
    inverseSurface = Color(0xFF22372B), inverseOnSurface = Color(0xFFF0F4EE), inversePrimary = Color(0xFFAED7BA),
    outline = Color(0xFF687C6E), outlineVariant = Color(0xFFD9E1D9),
    error = Color(0xFF9D342C), onError = Color.White,
    errorContainer = Color(0xFFFBE9E5), onErrorContainer = Color(0xFF9D342C),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFAED7BA), onPrimary = Color(0xFF12261A),
    primaryContainer = Color(0xFF2D4B39), onPrimaryContainer = Color(0xFFAED7BA),
    secondary = Color(0xFFB5C5B9), onSecondary = Color(0xFF12261A),
    secondaryContainer = Color(0xFF23382D), onSecondaryContainer = Color(0xFFF0F4EE),
    background = Color(0xFF101A16), onBackground = Color(0xFFF0F4EE),
    surface = Color(0xFF192820), onSurface = Color(0xFFF0F4EE),
    surfaceTint = Color(0xFFAED7BA), surfaceBright = Color(0xFF334A3B), surfaceDim = Color(0xFF0C1410),
    surfaceVariant = Color(0xFF23382D), onSurfaceVariant = Color(0xFFB5C5B9),
    surfaceContainerLowest = Color(0xFF0C1410), surfaceContainerLow = Color(0xFF142019),
    surfaceContainer = Color(0xFF192820), surfaceContainerHigh = Color(0xFF23382D),
    surfaceContainerHighest = Color(0xFF2B4134),
    inverseSurface = Color(0xFFE5EDE6), inverseOnSurface = Color(0xFF16251F), inversePrimary = Color(0xFF245C45),
    outline = Color(0xFF8AA994), outlineVariant = Color(0xFF3B5143),
    error = Color(0xFFFFB4A8), onError = Color(0xFF432925),
    errorContainer = Color(0xFF432925), onErrorContainer = Color(0xFFFFB4A8),
)

private val ScanTypography = Typography(
    displayLarge = TextStyle(fontSize = 68.sp, lineHeight = 72.sp, fontWeight = FontWeight.Medium, letterSpacing = (-3).sp, fontFeatureSettings = "tnum"),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ScanFaceTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = ScanTypography, content = content)
}
