package com.uvguard.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.text.FontWeight
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.uvguard.app.data.UvRepository
import com.uvguard.app.data.calculerInfoFraicheur
import com.uvguard.app.data.getConseil
import kotlinx.coroutines.flow.first

class UvWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = UvRepository(context)
        val phototypeActuel = repo.phototypeFlow.first()
        val dernierUv = repo.getDernierUvConnu() ?: 0.0
        val conseil = getConseil(dernierUv, phototypeActuel)
        val fraicheur = calculerInfoFraicheur(repo.getDernierUvTimestamp())

        provideContent {
            val couleur = couleurPourNiveau(conseil.niveau)

            Column(
                modifier = androidx.glance.GlanceModifier
                    .fillMaxSize()
                    .background(Color.White)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "UV ${dernierUv.toInt()} — ${conseil.niveauEmoji} ${conseil.niveau}",
                    style = TextStyle(fontWeight = FontWeight.Bold, color = ColorProvider(couleur)),
                )
                Spacer(modifier = androidx.glance.GlanceModifier.height(4.dp))
                conseil.dureeMaxMinutes?.let {
                    Text(text = "⏱️ Avant coup de soleil (sans protection) : $it min")
                }
                conseil.conseils.forEach { ligne ->
                    Text(text = ligne)
                }
                fraicheur?.let {
                    Text(
                        text = if (it.estObsolete) "⚠️ Non actualisée depuis ${it.heureFormatee}" else "Maj ${it.heureFormatee}",
                        style = TextStyle(color = ColorProvider(if (it.estObsolete) Color(0xFFD32F2F) else Color.Gray)),
                    )
                }
            }
        }
    }

    private fun couleurPourNiveau(niveau: String): Color = when (niveau) {
        "Faible" -> Color(0xFF2E7D32)
        "Modéré" -> Color(0xFFF9A825)
        "Élevé" -> Color(0xFFEF6C00)
        "Très élevé" -> Color(0xFFD32F2F)
        else -> Color(0xFF6A1B9A)
    }
}
