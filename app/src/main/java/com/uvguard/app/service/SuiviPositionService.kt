package com.uvguard.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.uvguard.app.data.UvRepository
import com.uvguard.app.widget.UvWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Service au premier plan qui poursuit le suivi de position pendant une session d'exposition
 * active, même si l'appli n'est plus à l'écran (ex. l'utilisateur consulte le widget). Sans ce
 * service, le suivi continu s'arrête dès que l'écran principal n'est plus visible — Android
 * limite fortement l'accès à la position pour les applis en arrière-plan.
 *
 * Nécessite une notification persistante (obligation Android pour tout service au premier plan) :
 * l'utilisateur voit donc explicitement que le suivi est actif. La notification propose aussi une
 * action "Terminer la session", pour ne pas obliger à rouvrir l'appli juste pour arrêter le suivi.
 */
class SuiviPositionService : Service() {

    private val portee = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repo: UvRepository
    private var callbackPosition: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        repo = UvRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TERMINER_SESSION) {
            terminerSessionDepuisNotification()
            return START_NOT_STICKY
        }

        demarrerNotificationPremierPlan()
        demarrerSuiviPosition()
        demarrerBattementPeriodique()
        // START_NOT_STICKY : si le système tue ce service pour libérer de la mémoire, il ne le
        // relance pas automatiquement. Un redémarrage automatique sans session active en cours
        // créerait un service "fantôme", écrivant des segments de position sans qu'aucune
        // session ne soit réellement suivie côté écran.
        return START_NOT_STICKY
    }

    /** Déclenché par l'action "Terminer la session" de la notification, sans passer par l'écran. */
    private fun terminerSessionDepuisNotification() {
        portee.launch {
            val phototype = repo.phototypeFlow.first()
            repo.cloturerSessionActive(phototype)
            stopSelf()
        }
    }

    private fun demarrerNotificationPremierPlan() {
        val idCanal = "uvguard_suivi_position"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                idCanal,
                "Suivi d'exposition UV",
                NotificationManager.IMPORTANCE_LOW,
            )
            val gestionnaire = getSystemService(NotificationManager::class.java)
            gestionnaire?.createNotificationChannel(canal)
        }

        val intentTerminer = Intent(this, SuiviPositionService::class.java).setAction(ACTION_TERMINER_SESSION)
        val pendingTerminer = PendingIntent.getService(
            this,
            ID_REQUETE_ACTION_TERMINER,
            intentTerminer,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, idCanal)
            .setContentTitle("UV Guard — session en cours")
            .setContentText("Suivi de votre position pour affiner le calcul de dose UV.")
            .setSmallIcon(com.uvguard.app.R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(com.uvguard.app.R.drawable.ic_notification, "Terminer la session", pendingTerminer)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            @Suppress("DEPRECATION")
            startForeground(ID_NOTIFICATION, notification)
        }
    }

    private fun demarrerSuiviPosition() {
        val client = LocationServices.getFusedLocationProviderClient(this)
        val requete = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5 * 60 * 1000L)
            .setMinUpdateDistanceMeters(300f)
            .build()

        callbackPosition = object : LocationCallback() {
            override fun onLocationResult(resultat: LocationResult) {
                resultat.lastLocation?.let { loc ->
                    portee.launch {
                        repo.ajouterSegmentPosition(loc.latitude, loc.longitude)
                        repo.enregistrerBattement()

                        // Rafraîchit aussi l'UV affiché (Mode simple + widget) à la nouvelle
                        // position : sans ça, si l'utilisateur se déplace vers une zone à UV très
                        // différent pendant une session, l'affichage resterait figé sur l'ancienne
                        // valeur jusqu'au prochain rafraîchissement périodique (30 min).
                        try {
                            val uv = repo.getUvActuel(loc.latitude, loc.longitude)
                            repo.setDernierUvConnu(uv)
                            UvWidget().updateAll(applicationContext)
                        } catch (e: Exception) {
                            // Pas de réseau à ce moment précis : le segment de position est quand
                            // même enregistré, seul l'affichage immédiat n'est pas mis à jour.
                        }
                    }
                }
            }
        }

        try {
            client.requestLocationUpdates(requete, callbackPosition!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // Permission retirée entre-temps : impossible de continuer, on arrête le service proprement.
            stopSelf()
        }
    }

    /**
     * Émet un battement régulier tant que le service tourne, indépendamment des mises à jour de
     * position (qui peuvent être suspendues si l'appareil ne bouge pas). Sert à détecter, à la
     * clôture d'une session, une coupure du suivi (ex. téléphone éteint) plutôt que de supposer à
     * tort une exposition continue pendant toute la période sans observation.
     */
    private fun demarrerBattementPeriodique() {
        portee.launch {
            while (isActive) {
                repo.enregistrerBattement()
                delay(2 * 60 * 1000L)
            }
        }
    }

    override fun onDestroy() {
        callbackPosition?.let {
            LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it)
        }
        portee.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ID_NOTIFICATION = 1001
        private const val ID_REQUETE_ACTION_TERMINER = 2001
        const val ACTION_TERMINER_SESSION = "com.uvguard.app.action.TERMINER_SESSION"
    }
}
