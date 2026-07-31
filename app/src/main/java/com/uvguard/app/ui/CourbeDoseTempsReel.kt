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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import com.uvguard.app.data.Phototype
import com.uvguard.app.data.PointDoseJour
import com.uvguard.app.data.SessionExposition
import com.uvguard.app.data.construireCourbeDoseJour
import com.uvguard.app.data.estimerContributionEnCours
import java.time.LocalDate
import java.time.ZoneId

/**
 * Courbe de la dose UV cumulée du jour, "en construction" en temps réel dans l'onglet Suivi :
 * - un palier réel par session déjà terminée aujourd'hui (comme dans le détail de l'Historique) ;
 * - si une session est active, une montée en cours (couleur distincte) jusqu'à "maintenant" ;
 * - au-delà de "maintenant", une ligne grisée en pointillé : partie de la journée pas encore
 *   connue, plutôt que de laisser un blanc ou de supposer une valeur.
 * Axe Y gradué (% de dose) et zoomable (pincer/glisser/double-tap pour réinitialiser).
 */
@Composable
fun CourbeDoseAujourdhui(
    sessionsCompletes: List<SessionExposition>,
    sessionEnCoursDebut: Long?,
    uvActuelPourSessionEnCours: Double?,
    phototypeSessionEnCours: Phototype,
    maintenant: Long,
    modifier: Modifier = Modifier,
) {
    val debutJourMillis = remember(maintenant) {
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val finJourMillis = debutJourMillis + 24 * 3600 * 1000L

    val pointsReels = remember(sessionsCompletes) { construireCourbeDoseJour(sessionsCompletes) }
    val cumulAvantSessionEnCours = pointsReels.lastOrNull()?.dosePourcentCumule ?: 0.0

    val contributionEnCours = if (sessionEnCoursDebut != null && uvActuelPourSessionEnCours != null) {
        estimerContributionEnCours(sessionEnCoursDebut, maintenant, uvActuelPourSessionEnCours, phototypeSessionEnCours)
    } else {
        0.0
    }
    val cumulMaintenant = cumulAvantSessionEnCours + contributionEnCours
    val maxDose = maxOf(cumulMaintenant, pointsReels.maxOfOrNull { it.dosePourcentCumule } ?: 0.0).coerceAtLeast(10.0)
    val pasGraduationY = when {
        maxDose <= 50 -> 10.0
        maxDose <= 150 -> 25.0
        else -> 50.0
    }

    GraphiqueZoomable(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxWidth().weight(1f, fill = true)) {
                val margeGauche = 40f

                fun xPour(millis: Long): Float {
                    val fraction = ((millis - debutJourMillis).toFloat() / (finJourMillis - debutJourMillis).toFloat())
                        .coerceIn(0f, 1f)
                    return margeGauche + fraction * (size.width - margeGauche)
                }
                fun yPour(dose: Double): Float = (size.height - (dose / maxDose * size.height)).toFloat()

                // --- Axe Y : % de dose ---
                val paintAxe = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = 22f
                }
                var graduation = 0.0
                while (graduation <= maxDose) {
                    val y = yPour(graduation)
                    drawLine(
                        color = Color(0xFFE0E0E0),
                        start = Offset(margeGauche, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f,
                    )
                    drawContext.canvas.nativeCanvas.drawText("${graduation.toInt()}%", 0f, y + 8f, paintAxe)
                    graduation += pasGraduationY
                }

                // Zone grisée : de "maintenant" à la fin de la journée — pas encore de donnée.
                drawLine(
                    color = Color(0xFFBDBDBD),
                    start = Offset(xPour(maintenant), yPour(cumulMaintenant)),
                    end = Offset(xPour(finJourMillis), yPour(cumulMaintenant)),
                    strokeWidth = 4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )

                // Paliers des sessions déjà terminées aujourd'hui.
                val pointsAffiches: List<PointDoseJour> = if (sessionEnCoursDebut != null) {
                    pointsReels + PointDoseJour(sessionEnCoursDebut, cumulAvantSessionEnCours)
                } else {
                    pointsReels
                }
                for (i in 0 until pointsAffiches.size - 1) {
                    drawLine(
                        color = Color(0xFF1565C0),
                        start = Offset(xPour(pointsAffiches[i].heureEpochMillis), yPour(pointsAffiches[i].dosePourcentCumule)),
                        end = Offset(xPour(pointsAffiches[i + 1].heureEpochMillis), yPour(pointsAffiches[i + 1].dosePourcentCumule)),
                        strokeWidth = 4f,
                    )
                }

                // Session en cours : montée "en construction" jusqu'à maintenant.
                if (sessionEnCoursDebut != null) {
                    drawLine(
                        color = Color(0xFFEF6C00),
                        start = Offset(xPour(sessionEnCoursDebut), yPour(cumulAvantSessionEnCours)),
                        end = Offset(xPour(maintenant), yPour(cumulMaintenant)),
                        strokeWidth = 4f,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0h", "3h", "6h", "9h", "12h", "15h", "18h", "21h", "24h").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
