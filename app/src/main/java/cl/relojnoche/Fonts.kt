package cl.relojnoche

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.Immutable
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit

/**
 * Nunito: letra redondeada parecida a la SF Pro Rounded del StandBy de iOS.
 * Licencia SIL Open Font License 1.1 (assets/licenses/OFL-Nunito.txt).
 */
val Nunito = FontFamily(
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
    Font(R.font.nunito_black, FontWeight.Black),
)

/**
 * Estilo para dígitos grandes: alto de línea = tamaño de letra y sin espacio
 * extra arriba/abajo, para poder calcular celdas exactas.
 */
fun clockTextStyle(weight: FontWeight, size: TextUnit) = TextStyle(
    fontFamily = Nunito,
    fontWeight = weight,
    fontSize = size,
    lineHeight = size,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
    fontFeatureSettings = "tnum",
)

/** Estilo para textos chicos (fecha, batería, alarma). */
fun infoTextStyle(size: TextUnit) = TextStyle(
    fontFamily = Nunito,
    fontWeight = FontWeight.SemiBold,
    fontSize = size,
    lineHeight = size * 1.2f,
)

private fun nunitoResFor(weight: FontWeight?): Int = when {
    weight == null -> R.font.nunito_semibold
    weight >= FontWeight.Black -> R.font.nunito_black
    weight >= FontWeight.ExtraBold -> R.font.nunito_extrabold
    else -> R.font.nunito_semibold
}

/**
 * Dónde está la "tinta" real de los dígitos respecto de la línea base, en
 * fracciones del tamaño de letra (negativo = sobre la línea base).
 * La caja de texto de Nunito mide ~1,36 em, pero los dígitos solo ~0,7 em:
 * con esto las celdas se ajustan al dígito y no a la caja.
 */
@Immutable
data class InkRatios(val digitTop: Float, val digitBottom: Float, val colonCenter: Float)

private val inkCache = HashMap<Int, InkRatios>()

fun inkRatios(context: Context, weight: FontWeight?): InkRatios {
    val res = nunitoResFor(weight)
    return inkCache.getOrPut(res) {
        runCatching {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = ResourcesCompat.getFont(context, res)
                textSize = 1000f
            }
            val r = Rect()
            paint.getTextBounds("0123456789", 0, 10, r)
            val top = r.top / 1000f
            val bottom = r.bottom / 1000f
            paint.getTextBounds(":", 0, 1, r)
            InkRatios(top, bottom, (r.top + r.bottom) / 2000f)
        }.getOrElse { InkRatios(-0.705f, 0.012f, -0.25f) }
    }
}
