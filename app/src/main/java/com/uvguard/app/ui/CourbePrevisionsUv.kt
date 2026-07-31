package com.uvguard.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import com.uvguard.app.data.PointUvHoraire
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Couleur associée à un niveau d'UV, cohérente avec les 5 niveaux détaillés dans la fenêtre
 * "En savoir plus sur les risques" (mêmes seuils que PhototypeAdvice.niveauUv).
 */
private fun couleurNiveauUv(uv: Double): Color = when {
    uv < 3 -> Color(0xFF2E7D32) // Faible
    uv < 6 -> Color(0xFFF9A825) // Modéré
    uv < 8 -> Color(0xFFEF6C00) // Élevé
    uv < 11 -> Color(0xFFD32F2F) // Très élevé
    else -> Color(0xFF6A1B9A) // Extrême
}

private fun formaterHeure(epochMillis: Long): String =
    SimpleDateFormat("HH'h'", Locale.FRANCE).format(Date(epochMillis))

/**
 * Courbe des prévisions UV du jour : segments colorés selon le niveau de risque, axes gradués
 * (heures toutes les 3h + UV avec un pas adapté à l'amplitude de la journée), et zoomable
 * (pincer pour zoomer, glisser pour se déplacer, double-tap pour réinitialiser).
 */
@Composable
fun CourbePrevisionsUv(previsions: List<PointUvHoraire>, modifier: Modifier = Modifier) {
    if (previsions.size < 2) return
    val maxUv = (previsions.maxOf { it.uv }).coerceAtLeast(11.0)
    val pasGraduationY = when {
        maxUv <= 6 -> 1.0
        maxUv <= 15 -> 2.0
        else -> 5.0
    }

    GraphiqueZoomable(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxWidth().weight(1f, fill = true)) {
                val margeGauche = 32f
                val largeurUtile = size.width - margeGauche
                val pasX = largeurUtile / (previsions.size - 1)

                fun xPour(index: Int): Float = margeGauche + index * pasX
                fun yPour(uv: Double): Float = (size.height - (uv / maxUv * size.height)).toFloat()

                // --- Axe Y : grille horizontale, pas adapté à l'amplitude du jour ---
                val paintAxe = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = 24f
                }
                var graduation = 0.0
                while (graduation <= maxUv) {
                    val y = yPour(graduation)
                    drawLine(
                        color = Color(0xFFE0E0E0),
                        start = Offset(margeGauche, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f,
                    )
                    drawContext.canvas.nativeCanvas.drawText(graduation.toInt().toString(), 0f, y + 8f, paintAxe)
                    graduation += pasGraduationY
                }

                // --- Courbe, colorée segment par segment selon le niveau de risque ---
                val points = previsions.mapIndexed { index, point -> Offset(xPour(index), yPour(point.uv)) }
                for (i in 0 until points.size - 1) {
                    val uvMoyenSegment = (previsions[i].uv + previsions[i + 1].uv) / 2.0
                    drawLine(
                        color = couleurNiveauUv(uvMoyenSegment),
                        start = points[i],
                        end = points[i + 1],
                        strokeWidth = 5f,
                    )
                }
            }

            // --- Axe X : un repère toutes les 3 heures ---
            val indicesAffiches = (previsions.indices.filter { it % 3 == 0 } + (previsions.size - 1))
                .distinct()
                .sorted()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                indicesAffiches.forEach { i ->
                    Text(
                        formaterHeure(previsions[i].debutHeureEpochMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
