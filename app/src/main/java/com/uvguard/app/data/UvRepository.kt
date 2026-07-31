package com.uvguard.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

private val Context.dataStore by preferencesDataStore(name = "uv_guard_prefs")

class UvRepository(private val context: Context) {

    private val api: OpenMeteoApi by lazy {
        Retrofit.Builder()
            .baseUrl(OpenMeteoApi.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenMeteoApi::class.java)
    }

    private val geocodingApi: GeocodingApi by lazy {
        Retrofit.Builder()
            .baseUrl(GeocodingApi.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GeocodingApi::class.java)
    }

    private val nominatimApi: NominatimApi by lazy {
        Retrofit.Builder()
            .baseUrl(NominatimApi.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NominatimApi::class.java)
    }

    /**
     * Nom de lieu lisible pour des coordonnées données (ex. point choisi sur la carte).
     * Retourne null si la requête échoue ou si aucun nom n'est disponible ; l'appelant peut
     * alors se replier sur un libellé générique.
     */
    suspend fun getNomLieuInverse(latitude: Double, longitude: Double): String? {
        return try {
            val reponse = nominatimApi.geocodageInverse(latitude, longitude)
            val adresse = reponse.address
            val ville = adresse?.city ?: adresse?.town ?: adresse?.village ?: adresse?.municipality
            listOfNotNull(ville, adresse?.country).joinToString(", ").ifBlank { reponse.display_name }
        } catch (e: Exception) {
            null
        }
    }

    // --- Position ---

    suspend fun getPositionActuelle(): Pair<Double, Double>? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val location = client.lastLocation.await() ?: return null
        return location.latitude to location.longitude
    }

    /** Recherche une ou plusieurs villes correspondant au nom saisi (pour choix manuel sans GPS). */
    suspend fun rechercherVilles(nom: String): List<GeocodingResult> {
        if (nom.isBlank()) return emptyList()
        return geocodingApi.rechercherVille(nom).results ?: emptyList()
    }

    /**
     * Renvoie la position à utiliser selon la préférence de l'utilisateur :
     * position GPS si activée, sinon la ville enregistrée manuellement.
     */
    suspend fun getPositionSelonPreference(): Pair<Double, Double>? {
        val utiliserGps = context.dataStore.data.first()[UTILISER_GPS_KEY] ?: true
        return if (utiliserGps) {
            getPositionActuelle()
        } else {
            getVilleEnregistree()
        }
    }

    // --- Préférence de source de position (GPS ou ville manuelle) ---

    private val UTILISER_GPS_KEY = booleanPreferencesKey("utiliser_gps")
    private val VILLE_NOM_KEY = stringPreferencesKey("ville_nom")
    private val VILLE_LAT_KEY = stringPreferencesKey("ville_lat")
    private val VILLE_LON_KEY = stringPreferencesKey("ville_lon")

    val utiliserGpsFlow: Flow<Boolean> = context.dataStore.data.map { it[UTILISER_GPS_KEY] ?: true }

    suspend fun setUtiliserGps(utiliser: Boolean) {
        context.dataStore.edit { it[UTILISER_GPS_KEY] = utiliser }
    }

    suspend fun setVilleChoisie(ville: GeocodingResult) {
        context.dataStore.edit { prefs ->
            prefs[VILLE_NOM_KEY] = ville.name
            prefs[VILLE_LAT_KEY] = ville.latitude.toString()
            prefs[VILLE_LON_KEY] = ville.longitude.toString()
            prefs[UTILISER_GPS_KEY] = false
        }
    }

    /** Enregistre une position choisie directement sur la carte, avec un nom de lieu optionnel (géocodage inverse). */
    suspend fun setPositionChoisieSurCarte(latitude: Double, longitude: Double, nom: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[VILLE_NOM_KEY] = nom ?: "Point choisi sur la carte"
            prefs[VILLE_LAT_KEY] = latitude.toString()
            prefs[VILLE_LON_KEY] = longitude.toString()
            prefs[UTILISER_GPS_KEY] = false
        }
    }

    suspend fun getVilleEnregistree(): Pair<Double, Double>? {
        val prefs = context.dataStore.data.first()
        val lat = prefs[VILLE_LAT_KEY]?.toDoubleOrNull() ?: return null
        val lon = prefs[VILLE_LON_KEY]?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    suspend fun getNomVilleEnregistree(): String? {
        return context.dataStore.data.first()[VILLE_NOM_KEY]
    }

    // --- UV en direct ---

    suspend fun getUvActuel(latitude: Double, longitude: Double): Double {
        val reponse = api.getCurrentUv(latitude = latitude, longitude = longitude)
        return reponse.current.uv_index
    }

    /** Prévision UV horaire pour le calcul de dose pondéré (Mode précis). */
    suspend fun getPrevisionHoraire(latitude: Double, longitude: Double): List<PointUvHoraire> {
        return api.getPrevisionHoraireUv(latitude = latitude, longitude = longitude).versPointsHoraires()
    }

    // --- Dernière prévision horaire connue (persistée) ---
    // Sert à garder la courbe de prévisions affichée même en cas de coupure réseau (voire après
    // un redémarrage de l'appli hors ligne), avec un libellé indiquant pour quelle position/ville
    // et quel jour cette prévision a été obtenue — pour que l'utilisateur sache si elle est encore
    // pertinente. `pourAujourdhui()` appliqué par l'appelant filtre naturellement une prévision
    // devenue obsolète (jour différent).

    private val DERNIERE_PREVISION_KEY = stringPreferencesKey("derniere_prevision_horaire")
    private val DERNIERE_PREVISION_LIBELLE_KEY = stringPreferencesKey("derniere_prevision_libelle")

    suspend fun setDernierePrevision(previsions: List<PointUvHoraire>, libellePosition: String) {
        context.dataStore.edit { prefs ->
            prefs[DERNIERE_PREVISION_KEY] = previsions.joinToString(";") { "${it.debutHeureEpochMillis},${it.uv}" }
            prefs[DERNIERE_PREVISION_LIBELLE_KEY] = libellePosition
        }
    }

    /** Renvoie la dernière prévision connue (peut être vide si aucune encore) et son libellé de position. */
    suspend fun getDernierePrevision(): Pair<List<PointUvHoraire>, String?> {
        val prefs = context.dataStore.data.first()
        val brut = prefs[DERNIERE_PREVISION_KEY]
        val previsions = brut?.split(";")?.filter { it.isNotBlank() }?.mapNotNull { entree ->
            val parts = entree.split(",")
            if (parts.size != 2) return@mapNotNull null
            val millis = parts[0].toLongOrNull() ?: return@mapNotNull null
            val uv = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            PointUvHoraire(millis, uv)
        } ?: emptyList()
        return previsions to prefs[DERNIERE_PREVISION_LIBELLE_KEY]
    }

    // --- Préférence phototype ---

    private val PHOTOTYPE_KEY = intPreferencesKey("phototype_ordinal")

    val phototypeFlow: Flow<Phototype> = context.dataStore.data.map { prefs ->
        val ordinal = prefs[PHOTOTYPE_KEY] ?: Phototype.III.ordinal
        Phototype.entries.getOrElse(ordinal) { Phototype.III }
    }

    suspend fun setPhototype(phototype: Phototype) {
        context.dataStore.edit { it[PHOTOTYPE_KEY] = phototype.ordinal }
    }

    // --- Dernier UV connu + horodatage (pour affichage widget/appli sans réseau) ---

    private val DERNIER_UV_KEY = stringPreferencesKey("dernier_uv")
    private val DERNIER_UV_TIMESTAMP_KEY = longPreferencesKey("dernier_uv_timestamp")

    suspend fun setDernierUvConnu(uv: Double) {
        context.dataStore.edit { prefs ->
            prefs[DERNIER_UV_KEY] = uv.toString()
            prefs[DERNIER_UV_TIMESTAMP_KEY] = System.currentTimeMillis()
        }
    }

    suspend fun getDernierUvConnu(): Double? {
        val prefs = context.dataStore.data.first()
        return prefs[DERNIER_UV_KEY]?.toDoubleOrNull()
    }

    /** Horodatage (epoch millis) de la dernière donnée UV enregistrée, ou null si aucune encore. */
    suspend fun getDernierUvTimestamp(): Long? {
        val prefs = context.dataStore.data.first()
        return prefs[DERNIER_UV_TIMESTAMP_KEY]
    }

    // --- Battement du service de suivi (pour détecter une coupure, ex. téléphone éteint) ---
    // Le service enregistre un battement régulier tant qu'il tourne. Si, à la clôture d'une
    // session, l'écart entre "maintenant" et le dernier battement dépasse un seuil, cela signale
    // que le service (et donc l'observation de la position) s'est interrompu sans qu'on le sache
    // — ex. téléphone éteint, ou tué par un gestionnaire de batterie agressif.

    private val DERNIER_BATTEMENT_KEY = longPreferencesKey("dernier_battement_suivi")

    suspend fun enregistrerBattement() {
        context.dataStore.edit { it[DERNIER_BATTEMENT_KEY] = System.currentTimeMillis() }
    }

    suspend fun getDernierBattement(): Long? {
        return context.dataStore.data.first()[DERNIER_BATTEMENT_KEY]
    }

    // Persiste l'horodatage de la dernière coupure détectée, pour que l'écran puisse l'afficher
    // même si la session a été terminée depuis la notification (donc sans que l'écran soit ouvert
    // à ce moment-là). "Consommer" l'efface, pour ne l'afficher qu'une fois.
    private val DERNIERE_COUPURE_FIN_KEY = longPreferencesKey("derniere_session_coupure_fin")

    /** Renvoie l'horodatage de la coupure si une a été détectée depuis, ou null sinon ; l'efface ensuite. */
    suspend fun consommerAlerteCoupure(): Long? {
        val finCoupure = context.dataStore.data.first()[DERNIERE_COUPURE_FIN_KEY]
        if (finCoupure != null) {
            context.dataStore.edit { it.remove(DERNIERE_COUPURE_FIN_KEY) }
        }
        return finCoupure
    }

    // --- État "session active" (persisté pour survivre à un redémarrage de l'appli) ---
    // Sans cela, si le processus de l'appli est tué pendant une session, l'écran perdrait toute
    // trace qu'une session était en cours au redémarrage, alors que le service pourrait, lui,
    // continuer de tourner — d'où l'intérêt de pouvoir réconcilier les deux au lancement.

    private val SESSION_DEBUT_KEY = longPreferencesKey("session_active_debut")

    suspend fun setSessionActive(debutEpochMillis: Long?) {
        context.dataStore.edit { prefs ->
            if (debutEpochMillis != null) {
                prefs[SESSION_DEBUT_KEY] = debutEpochMillis
            } else {
                prefs.remove(SESSION_DEBUT_KEY)
            }
        }
    }

    /** Renvoie l'heure de début de la session en cours si une session était active, sinon null. */
    suspend fun getSessionActive(): Long? {
        return context.dataStore.data.first()[SESSION_DEBUT_KEY]
    }

    // --- Segments de position de la session en cours (Mode précis) ---
    // Stockés ici (et non seulement en mémoire côté écran) pour que le service de suivi en
    // arrière-plan (SuiviPositionService) puisse les mettre à jour même si l'appli n'est pas visible.

    private val SEGMENTS_EN_COURS_KEY = stringPreferencesKey("segments_session_en_cours")

    /** Démarre une nouvelle session : efface les segments précédents et enregistre le premier. */
    suspend fun demarrerSegmentsSession(position: Pair<Double, Double>?, debut: Long) {
        context.dataStore.edit { prefs ->
            if (position != null) {
                prefs[SEGMENTS_EN_COURS_KEY] = serialiserSegments(
                    listOf(SegmentPosition(position.first, position.second, debut, debut)),
                )
            } else {
                prefs.remove(SEGMENTS_EN_COURS_KEY)
            }
        }
    }

    /** Ferme le segment ouvert (s'il y en a un) et en ouvre un nouveau à la position donnée. */
    suspend fun ajouterSegmentPosition(latitude: Double, longitude: Double) {
        val maintenant = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            val segments = parseSegments(prefs[SEGMENTS_EN_COURS_KEY]).toMutableList()
            if (segments.isNotEmpty()) {
                val dernier = segments.removeAt(segments.lastIndex)
                segments.add(dernier.copy(finEpochMillis = maintenant))
            }
            segments.add(SegmentPosition(latitude, longitude, maintenant, maintenant))
            prefs[SEGMENTS_EN_COURS_KEY] = serialiserSegments(segments)
        }
    }

    suspend fun getSegmentsSession(): List<SegmentPosition> {
        val prefs = context.dataStore.data.first()
        return parseSegments(prefs[SEGMENTS_EN_COURS_KEY])
    }

    /** Ferme le dernier segment à la fin de la session, retourne tous les segments, puis efface le stockage temporaire. */
    suspend fun cloturerSegmentsSession(fin: Long): List<SegmentPosition> {
        val prefs = context.dataStore.data.first()
        val segments = parseSegments(prefs[SEGMENTS_EN_COURS_KEY]).toMutableList()
        if (segments.isNotEmpty()) {
            val dernier = segments.removeAt(segments.lastIndex)
            segments.add(dernier.copy(finEpochMillis = fin))
        }
        context.dataStore.edit { it.remove(SEGMENTS_EN_COURS_KEY) }
        return segments
    }

    private fun serialiserSegments(segments: List<SegmentPosition>): String =
        segments.joinToString(";") { "${it.latitude},${it.longitude},${it.debutEpochMillis},${it.finEpochMillis}" }

    private fun parseSegments(brut: String?): List<SegmentPosition> {
        if (brut.isNullOrBlank()) return emptyList()
        return brut.split(";").filter { it.isNotBlank() }.mapNotNull { entree ->
            val p = entree.split(",")
            if (p.size != 4) return@mapNotNull null
            SegmentPosition(
                latitude = p[0].toDoubleOrNull() ?: return@mapNotNull null,
                longitude = p[1].toDoubleOrNull() ?: return@mapNotNull null,
                debutEpochMillis = p[2].toLongOrNull() ?: return@mapNotNull null,
                finEpochMillis = p[3].toLongOrNull() ?: return@mapNotNull null,
            )
        }
    }

    // --- Sessions d'exposition, historisées par date (Mode 2) ---
    // Une clé dynamique par date ("sessions_2026-07-27", etc.), plus un index listant les dates
    // connues — nécessaire car DataStore Preferences ne permet pas d'énumérer les clés par
    // préfixe directement. Conservation illimitée (aucune purge automatique).
    // À remplacer par Room si le volume de données grandit en Phase 2/3.

    private val INDEX_DATES_KEY = stringPreferencesKey("sessions_dates_connues")

    private fun cleSessionsPourDate(date: String) = stringPreferencesKey("sessions_$date")

    suspend fun ajouterSession(session: SessionExposition, dateDuJour: String) {
        context.dataStore.edit { prefs ->
            val cle = cleSessionsPourDate(dateDuJour)
            val sessionsExistantes = prefs[cle] ?: ""
            val nouvelleEntree = "${session.debutEpochMillis},${session.finEpochMillis},${session.uvMoyen},${session.phototype.ordinal}"
            prefs[cle] = if (sessionsExistantes.isBlank()) nouvelleEntree else "$sessionsExistantes;$nouvelleEntree"

            val datesConnues = parseListeDates(prefs[INDEX_DATES_KEY])
            if (dateDuJour !in datesConnues) {
                prefs[INDEX_DATES_KEY] = (datesConnues + dateDuJour).joinToString(";")
            }
        }
    }

    suspend fun getSessionsDuJour(dateDuJour: String): List<SessionExposition> {
        val prefs = context.dataStore.data.first()
        val brut = prefs[cleSessionsPourDate(dateDuJour)] ?: return emptyList()
        return parseSessions(brut)
    }

    /** Liste toutes les dates pour lesquelles au moins une session existe, triées de la plus récente à la plus ancienne. */
    suspend fun getToutesLesDatesAvecSessions(): List<String> {
        val prefs = context.dataStore.data.first()
        return parseListeDates(prefs[INDEX_DATES_KEY]).sortedDescending()
    }

    /**
     * Une entrée de l'historique : une date, son % de dose, et le ou les phototypes réellement
     * utilisés ce jour-là (généralement un seul, mais plusieurs si le réglage a été changé entre
     * deux sessions du même jour) — pour que l'utilisateur puisse vérifier sur quelle base le
     * calcul a été fait, plutôt qu'un chiffre sans contexte.
     */
    data class EntreeHistorique(
        val date: String,
        val dosePourcent: Double,
        val phototypes: List<Phototype>,
    )

    /** Historique complet, trié de la date la plus récente à la plus ancienne. */
    suspend fun getHistorique(): List<EntreeHistorique> {
        return getToutesLesDatesAvecSessions().map { date ->
            val sessions = getSessionsDuJour(date)
            EntreeHistorique(
                date = date,
                dosePourcent = DoseTracker.calculerDosePourcent(sessions),
                phototypes = sessions.map { it.phototype }.distinct(),
            )
        }
    }

    /** Supprime les sessions d'une seule date de l'historique. */
    suspend fun supprimerSessionsDuJour(date: String) {
        context.dataStore.edit { prefs ->
            prefs.remove(cleSessionsPourDate(date))
            val datesConnues = parseListeDates(prefs[INDEX_DATES_KEY])
            prefs[INDEX_DATES_KEY] = (datesConnues - date).joinToString(";")
        }
    }

    /** Supprime tout l'historique de sessions (toutes les dates). */
    suspend fun supprimerToutHistorique() {
        context.dataStore.edit { prefs ->
            val datesConnues = parseListeDates(prefs[INDEX_DATES_KEY])
            datesConnues.forEach { prefs.remove(cleSessionsPourDate(it)) }
            prefs.remove(INDEX_DATES_KEY)
        }
    }

    private fun parseListeDates(brut: String?): List<String> =
        brut?.split(";")?.filter { it.isNotBlank() } ?: emptyList()

    private fun parseSessions(brut: String): List<SessionExposition> {
        return brut.split(";").filter { it.isNotBlank() }.mapNotNull { entree ->
            val parts = entree.split(",")
            if (parts.size < 3) return@mapNotNull null
            SessionExposition(
                debutEpochMillis = parts[0].toLongOrNull() ?: return@mapNotNull null,
                finEpochMillis = parts[1].toLongOrNull() ?: return@mapNotNull null,
                uvMoyen = parts[2].toDoubleOrNull() ?: return@mapNotNull null,
                // Repli sur Phototype.III si l'entrée ne contient pas encore ce champ (anciennes données).
                phototype = parts.getOrNull(3)?.toIntOrNull()?.let { ordinal -> Phototype.entries.getOrNull(ordinal) }
                    ?: Phototype.III,
            )
        }
    }

    /**
     * Résultat de la clôture d'une session : le nouveau % de dose du jour (null si aucune session
     * n'était active), et si une coupure de suivi a été détectée (ex. téléphone éteint), auquel
     * cas `finEffectiveEpochMillis` indique l'heure jusqu'à laquelle l'exposition a été comptée.
     */
    data class ResultatClotureSession(
        val dosePourcent: Double?,
        val coupureDetectee: Boolean,
        val finEffectiveEpochMillis: Long?,
    )

    /**
     * Clôture la session active si une l'est (ferme le dernier segment, calcule la dose pondérée,
     * enregistre la session du jour) et retourne le nouveau % de dose cumulée du jour — ou null si
     * aucune session n'était active. Centralisé ici pour être appelable aussi bien depuis l'écran
     * que depuis SuiviPositionService (ex. action "Terminer la session" sur la notification).
     *
     * Si une coupure de suivi est détectée (le service a cessé de donner signe de vie bien avant
     * la fin réelle de la session — ex. téléphone éteint), la durée effectivement comptée est
     * tronquée au dernier battement connu, plutôt que de supposer une exposition continue pendant
     * toute la période sans observation : mieux vaut sous-compter une période inconnue que
     * fabriquer une donnée qu'on n'a pas réellement.
     */
    suspend fun cloturerSessionActive(phototype: Phototype): ResultatClotureSession {
        val debut = getSessionActive() ?: return ResultatClotureSession(null, false, null)
        val fin = System.currentTimeMillis()
        setSessionActive(null)

        val dernierBattement = getDernierBattement()
        val coupureDetectee = dernierBattement != null &&
            dernierBattement >= debut &&
            (fin - dernierBattement) > SEUIL_COUPURE_MILLIS
        val finEffective = if (coupureDetectee) dernierBattement!! else fin
        if (coupureDetectee) {
            context.dataStore.edit { it[DERNIERE_COUPURE_FIN_KEY] = finEffective }
        }

        val segments = cloturerSegmentsSession(finEffective)
        val uvPondere = try {
            calculerUvMoyennePondereMultiPosition(segments) { latitude, longitude ->
                getPrevisionHoraire(latitude, longitude)
            }
        } catch (e: Exception) {
            null
        }
        val uvRetenu = uvPondere ?: getDernierUvConnu() ?: 0.0

        // La session est attribuée à la date de son DÉBUT, pas de sa fin : une exposition qui
        // démarre à 23h50 et se termine à 00h15 doit compter pour la veille, pas pour le
        // lendemain — sans quoi elle serait quasi entièrement attribuée au mauvais jour.
        val dateSession = java.time.Instant.ofEpochMilli(debut)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        ajouterSession(SessionExposition(debut, finEffective, uvRetenu, phototype), dateSession)

        // Le % affiché à l'écran reste celui du jour EN COURS (pas forcément celui de la
        // session) : si la session vient d'être attribuée à la veille, la dose du jour affichée
        // repart logiquement sur ses propres sessions du jour, sans inclure celle-ci.
        val dateAujourdhui = java.time.LocalDate.now().toString()
        val sessionsAujourdhui = getSessionsDuJour(dateAujourdhui)
        val dose = DoseTracker.calculerDosePourcent(sessionsAujourdhui)
        return ResultatClotureSession(dose, coupureDetectee, if (coupureDetectee) finEffective else null)
    }

    companion object {
        /** Au-delà de ce délai sans battement du service, on considère le suivi comme interrompu. */
        private const val SEUIL_COUPURE_MILLIS = 15 * 60 * 1000L
    }
}
