package com.uvguard.app.data

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/** Au-delà de ce délai, la donnée est considérée comme potentiellement obsolète. */
private const val SEUIL_OBSOLESCENCE_MINUTES = 90L

data class InfoFraicheur(
    val heureFormatee: String,
    val estObsolete: Boolean,
    val message: String,
)

/**
 * Calcule un message lisible sur l'ancienneté de la dernière donnée UV connue,
 * pour que l'utilisateur sache si l'info affichée est à jour ou non (ex. mode avion).
 */
fun calculerInfoFraicheur(timestampMillis: Long?, maintenantMillis: Long = System.currentTimeMillis()): InfoFraicheur? {
    if (timestampMillis == null) return null

    val minutesEcoulees = TimeUnit.MILLISECONDS.toMinutes(maintenantMillis - timestampMillis)
    val estObsolete = minutesEcoulees >= SEUIL_OBSOLESCENCE_MINUTES

    val format = SimpleDateFormat("HH:mm", Locale.FRANCE)
    val heureFormatee = format.format(Date(timestampMillis))

    val message = if (estObsolete) {
        "⚠️ Donnée du $heureFormatee — non actualisée depuis $minutesEcoulees min. Ouvrez l'appli avec une connexion pour la mettre à jour."
    } else {
        "Donnée actualisée à $heureFormatee (il y a $minutesEcoulees min)."
    }

    return InfoFraicheur(heureFormatee, estObsolete, message)
}
