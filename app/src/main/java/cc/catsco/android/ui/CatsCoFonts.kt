package cc.catsco.android.ui

import android.content.Context
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.TypefaceSpan
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import cc.catsco.android.R

/** Shared by Compose, native Markdown, text measurement and hosted knowledge. */
internal val CatsCoFontFamily = FontFamily(
    Font(R.font.alibaba_puhuiti_light, FontWeight.Light),
    Font(R.font.alibaba_puhuiti_regular, FontWeight.Normal),
    Font(R.font.alibaba_puhuiti_medium, FontWeight.Medium),
    Font(R.font.alibaba_puhuiti_semibold, FontWeight.SemiBold),
    Font(R.font.alibaba_puhuiti_bold, FontWeight.Bold),
)

internal object CatsCoFonts {
    fun regular(context: Context): Typeface = context.resources.getFont(R.font.alibaba_puhuiti_regular)
    fun medium(context: Context): Typeface = context.resources.getFont(R.font.alibaba_puhuiti_medium)
    fun bold(context: Context): Typeface = context.resources.getFont(R.font.alibaba_puhuiti_bold)
    val webResources = mapOf(
        "regular.ttf" to R.font.alibaba_puhuiti_regular,
        "medium.ttf" to R.font.alibaba_puhuiti_medium,
        "semibold.ttf" to R.font.alibaba_puhuiti_semibold,
        "bold.ttf" to R.font.alibaba_puhuiti_bold,
    )
}

/** The Typeface constructor on TypefaceSpan requires API 28; this also works on 26. */
internal class CatsCoTypefaceSpan(private val face: Typeface) : TypefaceSpan("Alibaba PuHuiTi 2.0") {
    override fun updateDrawState(ds: TextPaint) = apply(ds)
    override fun updateMeasureState(paint: TextPaint) = apply(paint)
    private fun apply(paint: TextPaint) {
        paint.typeface = face
        paint.isFakeBoldText = false
    }
}
