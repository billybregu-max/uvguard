package com.uvguard.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Plein écran de sélection d'une position en déplaçant la carte sous un repère fixe au centre
 * (principe "pin au centre", plutôt qu'un marqueur déplaçable — plus simple et fiable avec osmdroid).
 * Gratuit et sans clé API (tuiles OpenStreetMap).
 */
@Composable
fun SelecteurCarte(
    positionInitiale: Pair<Double, Double>?,
    onPositionConfirmee: (Double, Double) -> Unit,
    onFermer: () -> Unit,
) {
    val context = LocalContext.current
    var positionCentrale by remember {
        mutableStateOf(positionInitiale ?: (48.8566 to 2.3522)) // Paris par défaut si aucune position connue
    }

    // Configuration requise par osmdroid : identifiant d'appli (politique d'usage OSM) et cache
    // interne à l'appli, pour éviter de demander une permission de stockage.
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
        Configuration.getInstance().load(context, prefs)
        Configuration.getInstance().userAgentValue = context.packageName
        Configuration.getInstance().osmdroidTileCache = context.cacheDir
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(11.0)
                    controller.setCenter(GeoPoint(positionCentrale.first, positionCentrale.second))
                    addMapListener(object : org.osmdroid.events.MapListener {
                        override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                            val centre = mapCenter
                            positionCentrale = centre.latitude to centre.longitude
                            return true
                        }
                        override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean = false
                    })
                }
            },
        )

        // Repère fixe au centre de l'écran : la position sélectionnée est celle sous ce repère.
        Icon(
            imageVector = Icons.Filled.LocationOn,
            contentDescription = "Position sélectionnée",
            modifier = Modifier.align(Alignment.Center).size(48.dp),
            tint = Color(0xFFD32F2F),
        )

        // Bandeau du haut : fermer + coordonnées lisibles
        Surface(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            color = Color.White.copy(alpha = 0.95f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Déplacez la carte pour positionner le repère")
                IconButton(onClick = onFermer) {
                    Icon(Icons.Filled.Close, contentDescription = "Fermer")
                }
            }
        }

        // Bouton de confirmation en bas
        Button(
            onClick = { onPositionConfirmee(positionCentrale.first, positionCentrale.second) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .fillMaxWidth(),
        ) {
            Text("Confirmer cet emplacement")
        }

        // Attribution requise par la politique d'usage d'OpenStreetMap (tuiles + géocodage inverse).
        Text(
            "© OpenStreetMap contributors",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 4.dp),
            color = Color.Black.copy(alpha = 0.6f),
        )
    }
}
