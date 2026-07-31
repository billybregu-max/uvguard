package com.uvguard.app.data

/**
 * Échelle de Fitzpatrick (I = peau très claire, brûle toujours / VI = peau très foncée, ne brûle
 * quasiment jamais). Les facteurs multiplicatifs ci-dessous sont des ordres de grandeur indicatifs
 * dérivés des ratios de DEM (Dose Érythémateuse Minimale) courants en dermatologie — PAS une valeur
 * médicale certifiée. À affiner si une source clinique plus précise est intégrée.
 */
enum class Phototype(val label: String, val factor: Double) {
    I("Très claire, brûle toujours, ne bronze jamais", 0.5),
    II("Claire, brûle facilement, bronze peu", 0.75),
    III("Claire à mate, brûle modérément, bronze progressivement", 1.0),
    IV("Mate, brûle peu, bronze bien", 1.5),
    V("Foncée, brûle rarement, bronze facilement", 2.5),
    VI("Très foncée, brûle exceptionnellement", 4.0),
}

data class ConseilUv(
    val niveau: String,
    val niveauEmoji: String, // ✅ Faible → 👍 Modéré → ✋ Élevé → 🚨 Très élevé → 🙅‍♀️ Extrême
    val dureeMaxMinutes: Int?, // null = pas de limite pratique (UV très faible)
    val conseils: List<String>, // un conseil par ligne, avec pictogramme
)

/**
 * Durée max de référence (en minutes), calculée pour un phototype III (référence),
 * à un indice UV donné. Source : recommandations d'indices UV de type OMS / info.gouv.fr,
 * simplifiées en paliers.
 */
private fun dureeReferencePhototypeIII(uv: Double): Int? = when {
    uv < 3 -> null // pas de limite pratique
    uv < 6 -> 40
    uv < 8 -> 20
    uv < 11 -> 12
    else -> 8
}

private fun niveauUv(uv: Double): String = when {
    uv < 3 -> "Faible"
    uv < 6 -> "Modéré"
    uv < 8 -> "Élevé"
    uv < 11 -> "Très élevé"
    else -> "Extrême"
}

/** Emoji de niveau, une échelle distincte de celle des équipements (🧴🕶️🧢🌳), progressant de "tout va bien" à "stop". */
private fun niveauEmoji(uv: Double): String = when {
    uv < 3 -> "✅"
    uv < 6 -> "👍"
    uv < 8 -> "✋"
    uv < 11 -> "🚨"
    else -> "🙅‍♀️"
}

/** Un conseil par ligne, chacun actionnable (quoi faire), plutôt qu'un seul message condensé.
 *
 * Adapté au phototype : à UV réel identique, une peau plus sensible (facteur < 1) reçoit des
 * conseils correspondant à un niveau plus élevé, et inversement pour une peau plus résistante
 * (facteur > 1) — en réutilisant le même facteur que celui qui ajuste la durée, pour rester
 * cohérent avec un seul modèle de sensibilité plutôt que deux règles séparées.
 */
private fun conseilsProtection(uv: Double, phototype: Phototype): List<String> {
    val uvEquivalent = uv / phototype.factor
    return when {
        uvEquivalent < 3 -> listOf("✅ Aucune protection nécessaire pour une exposition courte.")
        uvEquivalent < 6 -> listOf(
            "🕶️ Portez des lunettes de soleil.",
            "🧢 Portez un chapeau.",
        )
        uvEquivalent < 8 -> listOf(
            "🧴 Appliquez une crème SPF30+.",
            "🕶️ Portez des lunettes de soleil.",
            "🌳 Recherchez l'ombre régulièrement.",
        )
        uvEquivalent < 11 -> listOf(
            "🧴 Appliquez une crème SPF50+.",
            "🧢 Portez un chapeau.",
            "🌳 Restez à l'ombre le plus possible.",
        )
        else -> listOf(
            "🚫 Évitez l'exposition directe au soleil.",
            "🌳 Restez à l'ombre.",
            "🧴 Protection maximale (SPF50+) si sortie indispensable.",
        )
    }
}

/**
 * Calcule le conseil Mode 1 (simple) pour un indice UV et un phototype donnés.
 */
fun getConseil(uvIndex: Double, phototype: Phototype): ConseilUv {
    val dureeRef = dureeReferencePhototypeIII(uvIndex)
    val dureeAjustee = dureeRef?.let { (it * phototype.factor).toInt() }
    return ConseilUv(
        niveau = niveauUv(uvIndex),
        niveauEmoji = niveauEmoji(uvIndex),
        dureeMaxMinutes = dureeAjustee,
        conseils = conseilsProtection(uvIndex, phototype),
    )
}
