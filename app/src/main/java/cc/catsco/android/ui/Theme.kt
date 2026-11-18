package cc.catsco.android.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Mint = Color(0xFF29BC95)
private val Light = lightColorScheme(primary = Color(0xFF147C65), onPrimary = Color.White,
    background = Color(0xFFF8F8F8), surface = Color.White, surfaceVariant = Color(0xFFF7F7F8),
    onBackground = Color(0xFF1F1F1F), onSurface = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFF747479), outlineVariant = Color(0xFFE4E4E8),
    secondary = Color(0xFF147C65), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEEEEF), onSecondaryContainer = Color(0xFF1F1F1F),
    tertiary = Color(0xFF747479), onTertiary = Color.White,
    surfaceTint = Color.Transparent, surfaceDim = Color(0xFFF1F1F2), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFCFCFC),
    surfaceContainer = Color(0xFFF7F7F8), surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFF0F0F1))
private val Dark = darkColorScheme(primary = Color(0xFF7BE0C1), onPrimary = Color(0xFF0B0B0B),
    background = Color(0xFF0F0F0F), surface = Color(0xFF1A1A1A), surfaceVariant = Color(0xFF1A1A19),
    onBackground = Color(0xFFECECEC), onSurface = Color(0xFFECECEC),
    onSurfaceVariant = Color(0xFF9A9AA0), outlineVariant = Color(0xFF303032),
    secondary = Color(0xFF7BE0C1), onSecondary = Color(0xFF0B0B0B),
    secondaryContainer = Color(0xFF262625), onSecondaryContainer = Color(0xFFECECEC),
    tertiary = Color(0xFF9A9AA0), onTertiary = Color(0xFF0B0B0B),
    surfaceTint = Color.Transparent, surfaceDim = Color(0xFF0F0F0F), surfaceBright = Color(0xFF232325),
    surfaceContainerLowest = Color(0xFF0F0F0F), surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1A1A1A), surfaceContainerHigh = Color(0xFF232325),
    surfaceContainerHighest = Color(0xFF2A2A2C))
@Composable fun CatsCoTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = CatsCoTypography, content = content)
}

private val CatsCoTypography = Typography(
    displayLarge = Typography().displayLarge.copy(fontFamily = CatsCoFontFamily),
    displayMedium = Typography().displayMedium.copy(fontFamily = CatsCoFontFamily),
    displaySmall = Typography().displaySmall.copy(fontFamily = CatsCoFontFamily),
    headlineLarge = Typography().headlineLarge.copy(fontFamily = CatsCoFontFamily),
    headlineMedium = Typography().headlineMedium.copy(fontFamily = CatsCoFontFamily),
    headlineSmall = TextStyle(fontFamily = CatsCoFontFamily, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontFamily = CatsCoFontFamily, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = CatsCoFontFamily, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = CatsCoFontFamily, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontFamily = CatsCoFontFamily, fontSize = 16.sp, lineHeight = 25.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontFamily = CatsCoFontFamily, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontFamily = CatsCoFontFamily, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontFamily = CatsCoFontFamily, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontFamily = CatsCoFontFamily, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontFamily = CatsCoFontFamily, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
)
