package com.uvguard.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Enveloppe un graphique (courbe) pour le rendre zoomable : pincer pour zoomer/dézoomer, glisser
 * pour se déplacer une fois zoomé, double-tap pour réinitialiser. Le zoom agrandit visuellement
 * le contenu déjà dessiné (transformation graphique, pas un recalcul des coordonnées), ce qui
 * reste simple et fiable quel que soit le graphique passé en contenu.
 */
@Composable
fun GraphiqueZoomable(modifier: Modifier = Modifier, contenu: @Composable () -> Unit) {
    var echelle by remember { mutableStateOf(1f) }
    var decalageX by remember { mutableStateOf(0f) }
    var decalageY by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nouvelleEchelle = (echelle * zoom).coerceIn(1f, 4f)
                    echelle = nouvelleEchelle
                    if (nouvelleEchelle > 1f) {
                        decalageX += pan.x
                        decalageY += pan.y
                    } else {
                        decalageX = 0f
                        decalageY = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    echelle = 1f
                    decalageX = 0f
                    decalageY = 0f
                })
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = echelle,
                    scaleY = echelle,
                    translationX = decalageX,
                    translationY = decalageY,
                ),
        ) {
            contenu()
        }
    }
}
