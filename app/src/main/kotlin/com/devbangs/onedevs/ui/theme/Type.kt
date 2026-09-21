package com.devbangs.onedevs.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R

// Geist, SIL OFL 1.1 (c) 2023 Vercel in collaboration with basement.studio.
// Static weights are instanced from the variable font by art/gen_type.py;
// the licence travels with them in art/licenses/Geist-OFL.txt.
private val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

// Material's default tracking is tuned for Roboto, which needs more air than
// Geist. Large sizes tighten up; small labels keep slight positive tracking so
// they hold together at 11-12sp on a budget panel.
internal val OneDevsTypography = with(Typography()) {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Geist, letterSpacing = (-1.5).sp),
        displayMedium = displayMedium.copy(fontFamily = Geist, letterSpacing = (-1.0).sp),
        displaySmall = displaySmall.copy(fontFamily = Geist, letterSpacing = (-0.5).sp),
        headlineLarge = headlineLarge.copy(fontFamily = Geist, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontFamily = Geist, letterSpacing = (-0.4).sp),
        headlineSmall = headlineSmall.copy(fontFamily = Geist, letterSpacing = (-0.3).sp),
        titleLarge = titleLarge.copy(fontFamily = Geist, letterSpacing = (-0.3).sp),
        titleMedium = titleMedium.copy(fontFamily = Geist, letterSpacing = (-0.1).sp),
        titleSmall = titleSmall.copy(fontFamily = Geist, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(fontFamily = Geist, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontFamily = Geist, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontFamily = Geist, letterSpacing = 0.1.sp),
        labelLarge = labelLarge.copy(fontFamily = Geist, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontFamily = Geist, letterSpacing = 0.2.sp),
        labelSmall = labelSmall.copy(fontFamily = Geist, letterSpacing = 0.3.sp),
    )
}
