package com.uvguard.app.data

import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * Réponse simplifiée de l'API Open-Meteo (gratuite, sans clé).
 * Doc : https://open-meteo.com/en/docs
 */
data class OpenMeteoResponse(
    val current: CurrentBlock,
)

data class CurrentBlock(
    val time: String,
    val uv_index: Double,
)

/** Réponse pour la prévision horaire (utilisée pour le calcul de dose pondéré, Mode précis). */
data class OpenMeteoPrevisionResponse(
    val hourly: HourlyBlock,
)

data class HourlyBlock(
    val time: List<String>, // horodatages ISO locaux, ex. "2026-07-27T14:00"
    val uv_index: List<Double>,
)

interface OpenMeteoApi {

    @GET("v1/forecast")
    suspend fun getCurrentUv(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "uv_index",
        @Query("timezone") timezone: String = "auto",
    ): OpenMeteoResponse

    @GET("v1/forecast")
    suspend fun getPrevisionHoraireUv(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = "uv_index",
        @Query("forecast_days") forecastDays: Int = 2, // couvre le jour même + le lendemain proche
        @Query("timezone") timezone: String = "auto",
    ): OpenMeteoPrevisionResponse

    companion object {
        const val BASE_URL = "https://api.open-meteo.com/"
    }
}

/**
 * Résultat de recherche de ville via l'API de géocodage Open-Meteo (gratuite, sans clé).
 * Doc : https://open-meteo.com/en/docs/geocoding-api
 */
data class GeocodingResponse(
    val results: List<GeocodingResult>?,
)

data class GeocodingResult(
    val name: String,
    val country: String?,
    val admin1: String?, // région/département, aide à distinguer les homonymes
    val latitude: Double,
    val longitude: Double,
)

interface GeocodingApi {

    @GET("v1/search")
    suspend fun rechercherVille(
        @Query("name") nom: String,
        @Query("count") count: Int = 5,
        @Query("language") language: String = "fr",
    ): GeocodingResponse

    companion object {
        const val BASE_URL = "https://geocoding-api.open-meteo.com/"
    }
}

/**
 * Géocodage inverse (coordonnées -> nom de lieu). Open-Meteo ne propose pas cette fonctionnalité
 * (demandée mais non disponible à ce jour) ; on utilise donc Nominatim, le service de recherche
 * officiel d'OpenStreetMap — gratuit, sans clé API, cohérent avec les tuiles de carte déjà utilisées.
 *
 * Politique d'usage Nominatim à respecter : maximum ~1 requête/seconde, User-Agent personnalisé
 * obligatoire (voir l'en-tête ci-dessous), et attribution "© OpenStreetMap contributors" affichée
 * à l'utilisateur (déjà présente sur la carte elle-même).
 */
data class NominatimReponse(
    val display_name: String?,
    val address: NominatimAdresse?,
)

data class NominatimAdresse(
    val city: String?,
    val town: String?,
    val village: String?,
    val municipality: String?,
    val state: String?,
    val country: String?,
)

interface NominatimApi {

    @Headers("User-Agent: UVGuard-App")
    @GET("reverse")
    suspend fun geocodageInverse(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("format") format: String = "jsonv2",
        @Query("accept-language") langue: String = "fr",
    ): NominatimReponse

    companion object {
        const val BASE_URL = "https://nominatim.openstreetmap.org/"
    }
}
