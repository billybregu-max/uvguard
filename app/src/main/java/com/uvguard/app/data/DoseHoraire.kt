package com.uvguard.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Un point de prévision horaire : début de l'heure (epoch millis) + UV prévu pour cette heure. */
data class PointUvHoraire(
    val debutHeureEpochMillis: Long,
    val uv: Double,
)

/**
 * Convertit la réponse Open-Meteo (horodatages locaux ISO, ex. "2026-07-27T14:00") en liste de
 * points exploitables, en utilisant le fuseau horaire de l'appareil (cohérent avec timezone=auto
 * côté API, qui renvoie l'heure locale du lieu demandé).
 */
fun OpenMeteoPrevisionResponse.versPointsHoraires(): List<PointUvHoraire> {
    return hourly.time.zip(hourly.uv_index).mapNotNull { (horodatage, uv) ->
        try {
            val instant = LocalDateTime.parse(horodatage).atZone(ZoneId.systemDefault()).toInstant()
            PointUvHoraire(instant.toEpochMilli(), uv)
        } catch (e: Exception) {
            null
        }
    }
}

/** Ne garde que les points de prévision correspondant à la journée civile en cours (fuseau de l'appareil). */
fun List<PointUvHoraire>.pourAujourdhui(): List<PointUvHoraire> {
    val aujourdHui = LocalDate.now(ZoneId.systemDefault())
    return filter {
        Instant.ofEpochMilli(it.debutHeureEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate() == aujourdHui
    }.sortedBy { it.debutHeureEpochMillis }
}

/**
 * Regroupe les heures contiguës où l'UV prévu dépasse le seuil donné en plages lisibles
 * (ex. "11h – 16h"), plutôt qu'une heure isolée par heure isolée.
 *
 * Adapté au phototype : on compare l'UV équivalent (uv / phototype.factor) au seuil, avec la
 * même formule que celle utilisée pour les conseils d'équipement — pour qu'une peau plus
 * sensible voie des heures à éviter plus larges qu'une peau plus résistante, à UV réel identique.
 */
fun calculerPlagesAEviter(previsions: List<PointUvHoraire>, phototype: Phototype, seuilUv: Double = 5.0): List<String> {
    val zone = ZoneId.systemDefault()
    val heuresDangereuses = previsions
        .filter { (it.uv / phototype.factor) > seuilUv }
        .map { Instant.ofEpochMilli(it.debutHeureEpochMillis).atZone(zone).hour }
        .distinct()
        .sorted()

    if (heuresDangereuses.isEmpty()) return emptyList()

    val plages = mutableListOf<String>()
    var debutPlage = heuresDangereuses.first()
    var heurePrecedente = debutPlage

    for (heure in heuresDangereuses.drop(1)) {
        if (heure == heurePrecedente + 1) {
            heurePrecedente = heure
        } else {
            plages.add(formaterPlageHoraire(debutPlage, heurePrecedente + 1))
            debutPlage = heure
            heurePrecedente = heure
        }
    }
    plages.add(formaterPlageHoraire(debutPlage, heurePrecedente + 1))
    return plages
}

private fun formaterPlageHoraire(debutHeure: Int, finHeure: Int): String =
    "%02dh – %02dh".format(debutHeure, finHeure.coerceAtMost(24))

/** Une période de la session passée à une position donnée (avant un éventuel changement de ville/position). */
data class SegmentPosition(
    val latitude: Double,
    val longitude: Double,
    val debutEpochMillis: Long,
    val finEpochMillis: Long,
)

/**
 * Calcule l'UV moyen pondéré sur une session composée d'un ou plusieurs segments de position
 * (si l'utilisateur a changé de ville, ou de position GPS, en cours d'exposition). Chaque segment
 * est d'abord moyenné sur ses propres prévisions horaires, puis les segments sont combinés au
 * prorata de leur durée respective. `recupererPrevisions` isole cette fonction de UvRepository.
 */
suspend fun calculerUvMoyennePondereMultiPosition(
    segments: List<SegmentPosition>,
    recupererPrevisions: suspend (Double, Double) -> List<PointUvHoraire>,
): Double? {
    if (segments.isEmpty()) return null
    var sommePonderee = 0.0
    var dureeTotale = 0L

    for (segment in segments) {
        val duree = segment.finEpochMillis - segment.debutEpochMillis
        if (duree <= 0) continue

        val previsions = try {
            recupererPrevisions(segment.latitude, segment.longitude)
        } catch (e: Exception) {
            continue // segment ignoré si sa prévision n'a pas pu être récupérée (pas de réseau, etc.)
        }
        val uvSegment = calculerUvMoyennePondere(segment.debutEpochMillis, segment.finEpochMillis, previsions) ?: continue

        sommePonderee += uvSegment * duree
        dureeTotale += duree
    }

    if (dureeTotale <= 0) return null
    return sommePonderee / dureeTotale
}

/**
 * Calcule l'UV moyen pondéré par le temps réellement passé dans chaque tranche horaire, pour une
 * session d'exposition donnée (un seul segment de position). Si aucune prévision ne couvre la
 * session, retourne null (l'appelant peut alors se replier sur l'UV instantané comme avant).
 */
fun calculerUvMoyennePondere(
    debutEpochMillis: Long,
    finEpochMillis: Long,
    previsions: List<PointUvHoraire>,
): Double? {
    if (previsions.isEmpty() || finEpochMillis <= debutEpochMillis) return null

    val previsionsTriees = previsions.sortedBy { it.debutHeureEpochMillis }
    var sommePonderee = 0.0
    var dureeCouverteMillis = 0L

    for (i in previsionsTriees.indices) {
        val debutHeure = previsionsTriees[i].debutHeureEpochMillis
        val finHeure = previsionsTriees.getOrNull(i + 1)?.debutHeureEpochMillis ?: (debutHeure + 3_600_000L)

        val chevauchementDebut = maxOf(debutHeure, debutEpochMillis)
        val chevauchementFin = minOf(finHeure, finEpochMillis)
        val dureeChevauchement = chevauchementFin - chevauchementDebut

        if (dureeChevauchement > 0) {
            sommePonderee += previsionsTriees[i].uv * dureeChevauchement
            dureeCouverteMillis += dureeChevauchement
        }
    }

    if (dureeCouverteMillis <= 0) return null
    return sommePonderee / dureeCouverteMillis
}
