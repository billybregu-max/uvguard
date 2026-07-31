package com.uvguard.app.data

import kotlin.math.min

/**
 * Représente une session d'exposition déclenchée manuellement par l'utilisateur
 * ("Je m'expose" / "Je suis à l'ombre"), avec l'UV moyen observé pendant la session et le
 * phototype effectivement sélectionné à ce moment-là — pour que le calcul (y compris dans
 * l'historique) reste fidèle à la situation réelle même si le phototype est modifié plus tard.
 */
data class SessionExposition(
    val debutEpochMillis: Long,
    val finEpochMillis: Long,
    val uvMoyen: Double,
    val phototype: Phototype,
)

/**
 * Calcule le % de la DEM quotidienne consommé (Mode 2, précis).
 *
 * Principe : à un instant donné, le "taux de consommation" de la dose est proportionnel à
 * uvIndex / dureeMaxRecommandee(uv, phototype). Une session de dureeMax minutes à cet UV = 100 %.
 * On somme la contribution de chaque session de la journée, chacune avec SON PROPRE phototype
 * (celui sélectionné au moment de la session), pas un phototype externe unique.
 */
object DoseTracker {

    /** Rappel court, toujours visible avec le Mode précis. Le détail complet est dans une fenêtre "En savoir plus". */
    const val NOTE_HYPOTHESE = "Estimation basée sur les prévisions météo."

    /** Contribution d'une seule session au % de dose du jour (0 si UV trop faible pour avoir une durée max définie). */
    private fun contributionDose(session: SessionExposition): Double {
        val dureeMinutes = (session.finEpochMillis - session.debutEpochMillis) / 60_000.0
        if (dureeMinutes <= 0) return 0.0

        val conseil = getConseil(session.uvMoyen, session.phototype)
        val dureeMaxPourCetUv = conseil.dureeMaxMinutes ?: return 0.0

        return (dureeMinutes / dureeMaxPourCetUv) * 100.0
    }

    fun calculerDosePourcent(sessions: List<SessionExposition>): Double {
        return sessions.sumOf { contributionDose(it) }
    }

    fun messageDose(dosePourcent: Double): String = when {
        dosePourcent < 50 -> "Dose du jour : ${dosePourcent.toInt()} %. Marge confortable."
        dosePourcent < 80 -> "Dose du jour : ${dosePourcent.toInt()} %. Restez vigilant(e)."
        dosePourcent < 100 -> "Dose du jour : ${dosePourcent.toInt()} %. Limitez fortement une nouvelle exposition."
        else -> "Dose du jour : ${min(dosePourcent, 999.0).toInt()} % — dose atteinte ou dépassée. Évitez toute exposition supplémentaire aujourd'hui."
    }
}

/** Un point de la courbe de dose cumulée d'une journée : un horodatage + le % cumulé à cet instant. */
data class PointDoseJour(
    val heureEpochMillis: Long,
    val dosePourcentCumule: Double,
)

/**
 * Construit une courbe en paliers de la dose cumulée au fil des heures d'une journée : plat entre
 * les sessions, montée à chaque session. Permet de voir *quand* la dose a été prise dans la
 * journée, pas seulement le total — c'est l'écran de détail d'un jour dans l'Historique.
 */
fun construireCourbeDoseJour(sessions: List<SessionExposition>): List<PointDoseJour> {
    if (sessions.isEmpty()) return emptyList()

    val sessionsTriees = sessions.sortedBy { it.debutEpochMillis }
    val points = mutableListOf<PointDoseJour>()
    var cumul = 0.0

    points.add(PointDoseJour(sessionsTriees.first().debutEpochMillis, 0.0))
    for (session in sessionsTriees) {
        points.add(PointDoseJour(session.debutEpochMillis, cumul))
        cumul += DoseTracker.calculerDosePourcent(listOf(session))
        points.add(PointDoseJour(session.finEpochMillis, cumul))
    }
    return points
}

/**
 * Estimation de la contribution au % de dose d'une session ENCORE EN COURS (pas encore
 * clôturée), à l'instant "maintenant" donné. Utilisée pour faire "vivre" la courbe de Suivi en
 * temps réel pendant une exposition active, avant que la session soit terminée et enregistrée
 * définitivement (avec son UV moyen pondéré réel). On se base ici sur l'UV instantané affiché
 * plutôt que sur une moyenne pondérée, ce qui est cohérent avec le fait que cette estimation est
 * provisoire et sera remplacée par le calcul définitif à la clôture de la session.
 */
fun estimerContributionEnCours(
    debutEpochMillis: Long,
    maintenantEpochMillis: Long,
    uvActuel: Double,
    phototype: Phototype,
): Double {
    val dureeMinutes = (maintenantEpochMillis - debutEpochMillis) / 60_000.0
    if (dureeMinutes <= 0) return 0.0

    val conseil = getConseil(uvActuel, phototype)
    val dureeMaxPourCetUv = conseil.dureeMaxMinutes ?: return 0.0

    return (dureeMinutes / dureeMaxPourCetUv) * 100.0
}
