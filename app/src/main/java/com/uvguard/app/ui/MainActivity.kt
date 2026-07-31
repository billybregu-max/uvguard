package com.uvguard.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.work.*
import com.uvguard.app.data.*
import com.uvguard.app.service.SuiviPositionService
import com.uvguard.app.worker.UvRefreshWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var repo: UvRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = UvRepository(applicationContext)
        planifierRafraichissementPeriodique(applicationContext)

        setContent {
            MaterialTheme {
                AppRoot(repo = repo)
            }
        }
    }
}

/**
 * Enregistre le rafraîchissement périodique de l'UV (toutes les 30 min, réseau requis).
 * `KEEP` évite de recréer la tâche à chaque ouverture de l'appli si elle existe déjà.
 */
private fun planifierRafraichissementPeriodique(context: android.content.Context) {
    val contraintes = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val demande = PeriodicWorkRequestBuilder<UvRefreshWorker>(30, TimeUnit.MINUTES)
        .setConstraints(contraintes)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UvRefreshWorker.WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        demande,
    )
}

/** Couleur de la pastille représentant chaque phototype (échelle de teinte de peau, du plus clair au plus foncé). */
private fun couleurPastillePhototype(phototype: Phototype): Color = when (phototype) {
    Phototype.I -> Color(0xFFF6D9C4)
    Phototype.II -> Color(0xFFEAC49E)
    Phototype.III -> Color(0xFFD2A679)
    Phototype.IV -> Color(0xFFAD7A4C)
    Phototype.V -> Color(0xFF7C4E2A)
    Phototype.VI -> Color(0xFF4A2F1D)
}

private const val ROUTE_REGLAGES = "reglages"
private const val ROUTE_AUJOURDHUI = "aujourdhui"
private const val ROUTE_SUIVI = "suivi"
private const val ROUTE_HISTORIQUE = "historique"

/**
 * Racine de l'appli : conserve tout l'état partagé (position, session, dose...) et héberge la
 * navigation à 4 onglets. Les onglets eux-mêmes (EcranReglages, ContenuAujourdhui, EcranSuivi,
 * EcranHistorique) ne sont que des vues sur cet état — aucun état dupliqué entre onglets.
 */
@Composable
fun AppRoot(repo: UvRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var phototype by remember { mutableStateOf(Phototype.III) }
    var dialoguePhototypeOuvert by remember { mutableStateOf(false) }
    var dialoguePrecisionOuvert by remember { mutableStateOf(false) }
    var uvActuel by remember { mutableStateOf<Double?>(null) }
    var infoFraicheur by remember { mutableStateOf<InfoFraicheur?>(null) }
    var sessionEnCours by remember { mutableStateOf<Long?>(null) } // début epoch millis, si "je m'expose" actif
    var dosePourcent by remember { mutableStateOf(0.0) }
    var utiliserGps by remember { mutableStateOf(true) }
    var texteRechercheVille by remember { mutableStateOf("") }
    var resultatsVilles by remember { mutableStateOf<List<GeocodingResult>>(emptyList()) }
    var villeChoisie by remember { mutableStateOf<String?>(null) }
    var permissionRefusee by remember { mutableStateOf(false) }
    var carteOuverte by remember { mutableStateOf(false) }
    var actionSessionEnCours by remember { mutableStateOf(false) } // évite un double-clic pendant le traitement
    var messageCoupureDetectee by remember { mutableStateOf<String?>(null) }
    var previsionsHoraires by remember { mutableStateOf<List<PointUvHoraire>>(emptyList()) }
    // Libellé de la position pour laquelle la prévision actuellement affichée a été obtenue
    // (nom de ville, ou "votre position actuelle" en GPS) — sert à construire le texte discret
    // "Prévision du [date] pour [lieu]", pour que l'utilisateur sache si la courbe affichée est
    // encore pertinente en cas de coupure réseau.
    var libellePrevisionAffichee by remember { mutableStateOf<String?>(null) }
    // Sessions déjà terminées aujourd'hui, nécessaires pour construire la courbe de dose en temps
    // réel de l'onglet Suivi (au-delà du simple %, il faut le détail palier par palier).
    var sessionsAujourdhui by remember { mutableStateOf<List<SessionExposition>>(emptyList()) }
    // Horodatage "maintenant", rafraîchi régulièrement pendant une session active pour faire
    // "vivre" la courbe de dose en temps réel.
    var maintenant by remember { mutableStateOf(System.currentTimeMillis()) }

    fun positionAutorisee(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    fun formaterHeureCoupure(epochMillis: Long): String {
        val format = java.text.SimpleDateFormat("HH'h'mm", java.util.Locale.FRANCE)
        return format.format(java.util.Date(epochMillis))
    }

    fun messageCoupure(epochMillis: Long): String =
        "⚠️ Suivi interrompu à ${formaterHeureCoupure(epochMillis)} (téléphone éteint ou suivi arrêté); " +
            "seule l'exposition jusqu'à cette heure a été comptée."

    /**
     * Tente une mise à jour réseau ; en cas d'échec (pas de connexion, etc.), conserve la
     * dernière donnée connue et l'affiche quand même, avec un horodatage explicite.
     *
     * `libellePosition` décrit la position utilisée pour cette prévision (nom de ville, ou
     * "votre position actuelle" en GPS) : il est associé à la prévision (affiché + persisté),
     * pour que l'utilisateur sache toujours pour quel lieu et quel jour la courbe affichée est
     * valable, même après une coupure réseau ou un redémarrage de l'appli hors ligne.
     */
    suspend fun mettreAJourUv(latitude: Double, longitude: Double, libellePosition: String) {
        try {
            val uv = repo.getUvActuel(latitude, longitude)
            repo.setDernierUvConnu(uv)
        } catch (e: Exception) {
            // Pas de réseau ou erreur d'appel : on garde silencieusement la dernière donnée connue.
        }
        uvActuel = repo.getDernierUvConnu()
        infoFraicheur = calculerInfoFraicheur(repo.getDernierUvTimestamp())

        try {
            val previsions = repo.getPrevisionHoraire(latitude, longitude).pourAujourdhui()
            if (previsions.isNotEmpty()) {
                previsionsHoraires = previsions
                libellePrevisionAffichee = libellePosition
                repo.setDernierePrevision(previsions, libellePosition)
            }
        } catch (e: Exception) {
            // Pas de réseau : on garde les prévisions déjà affichées si on en a une, sinon on se
            // replie sur la dernière prévision connue persistée (ex. après un redémarrage de
            // l'appli hors ligne). `pourAujourdhui()` filtre naturellement une prévision devenue
            // obsolète (jour différent) : dans ce cas rien ne s'affiche, plutôt qu'une donnée
            // trompeuse.
            if (previsionsHoraires.isEmpty()) {
                val (previsionsPersistees, libellePersiste) = repo.getDernierePrevision()
                val previsionsFiltrees = previsionsPersistees.pourAujourdhui()
                if (previsionsFiltrees.isNotEmpty()) {
                    previsionsHoraires = previsionsFiltrees
                    libellePrevisionAffichee = libellePersiste
                }
            }
        }
    }

    suspend fun chargerUvDepuisGps() {
        val position = repo.getPositionActuelle()
        if (position != null) {
            mettreAJourUv(position.first, position.second, "votre position actuelle")
        } else {
            uvActuel = repo.getDernierUvConnu()
            infoFraicheur = calculerInfoFraicheur(repo.getDernierUvTimestamp())
        }
    }

    /**
     * Si une session d'exposition est active et que la position change (ville modifiée, carte, ou
     * nouvelle position GPS), enregistre le changement de segment dans le dépôt — source unique
     * partagée avec SuiviPositionService, pour que le calcul de dose pondère chaque position par
     * le temps réellement passé dessus, que le changement vienne de l'écran ou du service.
     */
    suspend fun enregistrerChangementPosition(latitude: Double, longitude: Double) {
        if (sessionEnCours == null) return
        repo.ajouterSegmentPosition(latitude, longitude)
    }

    // Déclenché uniquement quand l'utilisateur choisit explicitement "Utiliser ma position (GPS)".
    // On ne demande jamais la permission automatiquement au lancement de l'appli.
    val lanceurPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { accordee ->
        if (accordee) {
            permissionRefusee = false
            utiliserGps = true
            scope.launch {
                repo.setUtiliserGps(true)
                chargerUvDepuisGps()
                repo.getPositionActuelle()?.let { enregistrerChangementPosition(it.first, it.second) }
            }
        } else {
            // Refus : on informe l'utilisateur et on bascule automatiquement sur la saisie de ville.
            permissionRefusee = true
            utiliserGps = false
            scope.launch { repo.setUtiliserGps(false) }
        }
    }

    fun notificationsAutorisees(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    // Demande la permission de notification (Android 13+) uniquement si nécessaire, juste avant de
    // démarrer le service de suivi — c'est le seul moment où cette notification sert à quelque chose.
    val lanceurPermissionNotifications = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // Que la permission soit accordée ou refusée, le suivi démarre quand même : sans elle, le
        // service tourne simplement sans notification visible (comportement Android standard).
        ContextCompat.startForegroundService(context, Intent(context, SuiviPositionService::class.java))
    }

    /** Démarre le service de suivi, en demandant d'abord la permission de notification si nécessaire (Android 13+). */
    fun demarrerServiceSuivi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAutorisees()) {
            lanceurPermissionNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ContextCompat.startForegroundService(context, Intent(context, SuiviPositionService::class.java))
        }
    }

    // Charger le phototype sauvegardé + l'UV actuel + la dose du jour au démarrage
    LaunchedEffect(Unit) {
        phototype = repo.phototypeFlow.first()
        utiliserGps = repo.utiliserGpsFlow.first()
        villeChoisie = repo.getNomVilleEnregistree()

        // Affiche immédiatement la dernière prévision connue (même avant toute tentative
        // réseau ou récupération de position), pour que l'utilisateur ait toujours une donnée
        // exploitable dès l'ouverture de l'appli — y compris hors ligne. `pourAujourdhui()`
        // filtre naturellement une prévision devenue obsolète (jour différent).
        val (previsionsPersisteesAuDemarrage, libellePersisteAuDemarrage) = repo.getDernierePrevision()
        val previsionsFiltreesAuDemarrage = previsionsPersisteesAuDemarrage.pourAujourdhui()
        if (previsionsFiltreesAuDemarrage.isNotEmpty()) {
            previsionsHoraires = previsionsFiltreesAuDemarrage
            libellePrevisionAffichee = libellePersisteAuDemarrage
        }

        // Réconciliation avec une éventuelle session encore active avant un redémarrage de
        // l'appli (ex. processus tué par le système) : on retrouve l'état exact plutôt que de le
        // perdre silencieusement, ce qui laisserait le bouton repartir à "Je m'expose" alors
        // qu'une session — et potentiellement le service de suivi — était toujours en cours.
        val debutSessionPersistee = repo.getSessionActive()
        if (debutSessionPersistee != null) {
            sessionEnCours = debutSessionPersistee
            // Le service ne survit pas à un redémarrage du processus (START_NOT_STICKY, voir
            // SuiviPositionService) : on le relance ici si la session récupérée était en mode GPS.
            if (utiliserGps && positionAutorisee()) {
                demarrerServiceSuivi()
            }
        } else {
            // Aucune session active connue : on s'assure qu'aucun service de suivi ne tourne à
            // vide (ex. redémarré par erreur par le système). Arrêter un service déjà arrêté ne
            // fait rien, cet appel est donc sans risque.
            context.stopService(Intent(context, SuiviPositionService::class.java))
        }

        if (utiliserGps && positionAutorisee()) {
            chargerUvDepuisGps()
        } else if (!utiliserGps) {
            val position = repo.getVilleEnregistree()
            if (position != null) {
                mettreAJourUv(position.first, position.second, villeChoisie ?: "la position enregistrée")
            } else {
                uvActuel = repo.getDernierUvConnu()
                infoFraicheur = calculerInfoFraicheur(repo.getDernierUvTimestamp())
            }
        }
        // Si utiliserGps == true mais la permission n'est pas encore accordée, on n'affiche pas la
        // pop-up automatiquement : on attend que l'utilisateur fasse un choix explicite dans Réglages.

        repo.consommerAlerteCoupure()?.let { finCoupure ->
            messageCoupureDetectee = messageCoupure(finCoupure)
        }

        val sessions = repo.getSessionsDuJour(LocalDate.now().toString())
        dosePourcent = DoseTracker.calculerDosePourcent(sessions)
        sessionsAujourdhui = sessions
    }

    /**
     * Recale l'état affiché sur celui réellement persisté — utile quand la session a pu être
     * terminée ailleurs pendant que l'écran n'était pas visible (ex. action "Terminer la session"
     * sur la notification). Appelé à chaque retour au premier plan, pas seulement au lancement.
     */
    suspend fun rafraichirEtatSessionEtDose() {
        sessionEnCours = repo.getSessionActive()
        repo.consommerAlerteCoupure()?.let { finCoupure ->
            messageCoupureDetectee = messageCoupure(finCoupure)
        }
        val sessions = repo.getSessionsDuJour(LocalDate.now().toString())
        dosePourcent = DoseTracker.calculerDosePourcent(sessions)
        sessionsAujourdhui = sessions
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observateur = LifecycleEventObserver { _, evenement ->
            if (evenement == Lifecycle.Event.ON_RESUME) {
                scope.launch { rafraichirEtatSessionEtDose() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observateur)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observateur) }
    }

    // Fait "vivre" la courbe de dose en temps réel : tant qu'une session est active, "maintenant"
    // est rafraîchi régulièrement pour que la montée en cours progresse visuellement.
    LaunchedEffect(sessionEnCours) {
        if (sessionEnCours != null) {
            while (sessionEnCours != null) {
                maintenant = System.currentTimeMillis()
                delay(30_000L)
            }
        } else {
            maintenant = System.currentTimeMillis()
        }
    }

    // --- Actions de session, partagées entre l'onglet Aujourd'hui (dupliqué) et l'onglet Suivi ---

    fun demarrerSession() {
        actionSessionEnCours = true
        val debut = System.currentTimeMillis()
        sessionEnCours = debut
        scope.launch {
            try {
                val position = repo.getPositionSelonPreference()
                repo.demarrerSegmentsSession(position, debut)
                repo.setSessionActive(debut)
                if (utiliserGps && positionAutorisee()) {
                    demarrerServiceSuivi()
                }
            } finally {
                actionSessionEnCours = false
            }
        }
    }

    fun terminerSession() {
        actionSessionEnCours = true
        scope.launch {
            try {
                context.stopService(Intent(context, SuiviPositionService::class.java))
                val resultat = repo.cloturerSessionActive(phototype)
                if (resultat.dosePourcent != null) dosePourcent = resultat.dosePourcent
                messageCoupureDetectee = resultat.finEffectiveEpochMillis?.let { messageCoupure(it) }
                sessionEnCours = null
                sessionsAujourdhui = repo.getSessionsDuJour(LocalDate.now().toString())
            } finally {
                actionSessionEnCours = false
            }
        }
    }

    // Texte discret affiché sous la courbe de prévisions ("Prévision du 29 juillet 2026 pour
    // Lyon"), pour que l'utilisateur sache toujours pour quel jour et quel lieu la courbe
    // actuellement affichée est valable — même après une coupure réseau.
    val infoPrevisionTexte = remember(previsionsHoraires, libellePrevisionAffichee) {
        if (previsionsHoraires.isEmpty() || libellePrevisionAffichee == null) {
            null
        } else {
            val format = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.FRANCE)
            val dateTexte = format.format(java.util.Date(previsionsHoraires.first().debutHeureEpochMillis))
            "Prévision du $dateTexte pour $libellePrevisionAffichee"
        }
    }

    val navController = rememberNavController()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = { BarreNavigation(navController) },
        ) { paddingInterieur ->
            NavHost(
                navController = navController,
                startDestination = ROUTE_AUJOURDHUI,
                modifier = Modifier.padding(paddingInterieur),
            ) {
                composable(ROUTE_REGLAGES) {
                    EcranReglages(
                        phototype = phototype,
                        dialoguePhototypeOuvert = dialoguePhototypeOuvert,
                        onOuvrirDialoguePhototype = { dialoguePhototypeOuvert = true },
                        onFermerDialoguePhototype = { dialoguePhototypeOuvert = false },
                        onChoisirPhototype = { type ->
                            phototype = type
                            scope.launch { repo.setPhototype(type) }
                            dialoguePhototypeOuvert = false
                        },
                        utiliserGps = utiliserGps,
                        permissionRefusee = permissionRefusee,
                        villeChoisie = villeChoisie,
                        texteRechercheVille = texteRechercheVille,
                        onTexteRechercheVilleChange = { texteRechercheVille = it },
                        resultatsVilles = resultatsVilles,
                        onRechercherVille = {
                            scope.launch { resultatsVilles = repo.rechercherVilles(texteRechercheVille) }
                        },
                        onChoisirVille = { ville ->
                            scope.launch {
                                repo.setVilleChoisie(ville)
                                villeChoisie = ville.name
                                resultatsVilles = emptyList()
                                mettreAJourUv(ville.latitude, ville.longitude, ville.name)
                                enregistrerChangementPosition(ville.latitude, ville.longitude)
                            }
                        },
                        onChoisirGps = {
                            if (positionAutorisee()) {
                                utiliserGps = true
                                permissionRefusee = false
                                scope.launch {
                                    repo.setUtiliserGps(true)
                                    chargerUvDepuisGps()
                                    repo.getPositionActuelle()?.let { enregistrerChangementPosition(it.first, it.second) }
                                }
                            } else {
                                lanceurPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        },
                        onChoisirVilleManuelle = {
                            utiliserGps = false
                            scope.launch { repo.setUtiliserGps(false) }
                        },
                        onOuvrirCarte = { carteOuverte = true },
                    )
                }
                composable(ROUTE_AUJOURDHUI) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        Text("Aujourd'hui", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(16.dp))
                        ContenuAujourdhui(
                            uvActuel = uvActuel,
                            phototype = phototype,
                            infoFraicheur = infoFraicheur,
                            previsionsHoraires = previsionsHoraires,
                            infoPrevisionTexte = infoPrevisionTexte,
                        )
                    }
                }
                composable(ROUTE_SUIVI) {
                    EcranSuivi(
                        dosePourcent = dosePourcent,
                        dialoguePrecisionOuvert = dialoguePrecisionOuvert,
                        onOuvrirDialoguePrecision = { dialoguePrecisionOuvert = true },
                        onFermerDialoguePrecision = { dialoguePrecisionOuvert = false },
                        messageCoupureDetectee = messageCoupureDetectee,
                        onFermerMessageCoupure = { messageCoupureDetectee = null },
                        sessionEnCoursDebut = sessionEnCours,
                        actionSessionEnCours = actionSessionEnCours,
                        afficherAvertissementBatterie = utiliserGps && positionAutorisee(),
                        onDemarrerSession = ::demarrerSession,
                        onTerminerSession = ::terminerSession,
                        uvActuel = uvActuel,
                        phototype = phototype,
                        infoFraicheur = infoFraicheur,
                        previsionsHoraires = previsionsHoraires,
                        infoPrevisionTexte = infoPrevisionTexte,
                        sessionsAujourdhui = sessionsAujourdhui,
                        maintenant = maintenant,
                    )
                }
                composable(ROUTE_HISTORIQUE) {
                    EcranHistorique(repo = repo)
                }
            }
        }

        if (carteOuverte) {
            SelecteurCarte(
                positionInitiale = null, // pas de position précédente mémorisée séparément ; le composant centre sur Paris par défaut
                onPositionConfirmee = { latitude, longitude ->
                    carteOuverte = false
                    scope.launch {
                        val nomLieu = repo.getNomLieuInverse(latitude, longitude)
                        repo.setPositionChoisieSurCarte(latitude, longitude, nomLieu)
                        utiliserGps = false
                        val libelle = nomLieu ?: "le point choisi sur la carte"
                        villeChoisie = nomLieu ?: "Point choisi sur la carte (nom indisponible)"
                        mettreAJourUv(latitude, longitude, libelle)
                        enregistrerChangementPosition(latitude, longitude)
                    }
                },
                onFermer = { carteOuverte = false },
            )
        }
    }
}

@Composable
private fun BarreNavigation(navController: NavHostController) {
    val entree by navController.currentBackStackEntryAsState()
    val routeActuelle = entree?.destination?.route

    NavigationBar {
        NavigationBarItem(
            selected = routeActuelle == ROUTE_REGLAGES,
            onClick = { navController.navigateVersOnglet(ROUTE_REGLAGES) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Réglages") },
        )
        NavigationBarItem(
            selected = routeActuelle == ROUTE_AUJOURDHUI,
            onClick = { navController.navigateVersOnglet(ROUTE_AUJOURDHUI) },
            icon = { Icon(Icons.Filled.WbSunny, contentDescription = null) },
            label = { Text("Aujourd'hui") },
        )
        NavigationBarItem(
            selected = routeActuelle == ROUTE_SUIVI,
            onClick = { navController.navigateVersOnglet(ROUTE_SUIVI) },
            icon = { Icon(Icons.Filled.TrendingUp, contentDescription = null) },
            label = { Text("Suivi") },
        )
        NavigationBarItem(
            selected = routeActuelle == ROUTE_HISTORIQUE,
            onClick = { navController.navigateVersOnglet(ROUTE_HISTORIQUE) },
            icon = { Icon(Icons.Filled.History, contentDescription = null) },
            label = { Text("Historique") },
        )
    }
}

/** Navigue vers un onglet en évitant d'empiler des destinations (comportement standard d'une barre d'onglets). */
private fun NavHostController.navigateVersOnglet(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// --- Onglet Réglages ---

@Composable
private fun EcranReglages(
    phototype: Phototype,
    dialoguePhototypeOuvert: Boolean,
    onOuvrirDialoguePhototype: () -> Unit,
    onFermerDialoguePhototype: () -> Unit,
    onChoisirPhototype: (Phototype) -> Unit,
    utiliserGps: Boolean,
    permissionRefusee: Boolean,
    villeChoisie: String?,
    texteRechercheVille: String,
    onTexteRechercheVilleChange: (String) -> Unit,
    resultatsVilles: List<GeocodingResult>,
    onRechercherVille: () -> Unit,
    onChoisirVille: (GeocodingResult) -> Unit,
    onChoisirGps: () -> Unit,
    onChoisirVilleManuelle: () -> Unit,
    onOuvrirCarte: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Réglages", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        // --- Mon phototype ---
        Text("Mon phototype", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(
                        modifier = Modifier
                            .size(28.dp)
                            .background(couleurPastillePhototype(phototype), shape = androidx.compose.foundation.shape.CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    // weight(1f) ici : force le texte du phototype (parfois long, ex. le type III)
                    // à passer à la ligne plutôt que de pousser le bouton "Modifier" hors de vue.
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Type ${phototype.name}", style = MaterialTheme.typography.bodyLarge)
                        Text(phototype.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onOuvrirDialoguePhototype) {
                    Text("Modifier")
                }
            }
        }

        if (dialoguePhototypeOuvert) {
            AlertDialog(
                onDismissRequest = onFermerDialoguePhototype,
                title = { Text("Choisissez votre phototype") },
                text = {
                    Column {
                        Text(
                            "Classement de la sensibilité de la peau au soleil (échelle de Fitzpatrick), " +
                                "utilisé pour adapter la durée d'exposition recommandée.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Phototype.entries.forEach { type ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onChoisirPhototype(type) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = phototype == type, onClick = null)
                                Spacer(Modifier.width(4.dp))
                                Spacer(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(couleurPastillePhototype(type), shape = androidx.compose.foundation.shape.CircleShape),
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text("Type ${type.name}", style = MaterialTheme.typography.bodyMedium)
                                    Text(type.label, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onFermerDialoguePhototype) { Text("Fermer") }
                },
            )
        }

        Spacer(Modifier.height(24.dp))

        // --- Mon emplacement ---
        Text("Mon emplacement", style = MaterialTheme.typography.titleMedium)
        Text(
            "Vous avez le choix entre partager votre position GPS ou indiquer une ville manuellement " +
                "sans rien partager.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = utiliserGps, onClick = onChoisirGps)
            Text("🛰️ Utiliser ma position (GPS)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !utiliserGps, onClick = onChoisirVilleManuelle)
            Text("🔍 Entrer une ville manuellement")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = false, onClick = onOuvrirCarte)
            Text("🗺️ Choisir un point sur une carte")
        }

        if (permissionRefusee) {
            Text(
                "Localisation refusée : indiquez votre ville ci-dessous pour continuer à recevoir l'indice UV.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (!utiliserGps) {
            OutlinedTextField(
                value = texteRechercheVille,
                onValueChange = onTexteRechercheVilleChange,
                label = { Text("Nom de la ville") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onRechercherVille) {
                Text("Rechercher")
            }
            resultatsVilles.forEach { ville ->
                val libelle = listOfNotNull(ville.name, ville.admin1, ville.country).joinToString(", ")
                Button(onClick = { onChoisirVille(ville) }) {
                    Text(libelle)
                }
            }
            villeChoisie?.let { Text("Ville actuelle : $it") }
        }
    }
}

// --- Contenu "Aujourd'hui", partagé entre l'onglet Aujourd'hui et sa reprise dans Suivi ---

@Composable
private fun ContenuAujourdhui(
    uvActuel: Double?,
    phototype: Phototype,
    infoFraicheur: InfoFraicheur?,
    previsionsHoraires: List<PointUvHoraire>,
    infoPrevisionTexte: String?,
) {
    var dialogueRisquesOuvert by remember { mutableStateOf(false) }
    val plagesAEviter = remember(previsionsHoraires, phototype) { calculerPlagesAEviter(previsionsHoraires, phototype) }

    Text("Quelles précautions prendre aujourd'hui ?", style = MaterialTheme.typography.titleMedium)
    uvActuel?.let { uv ->
        val conseil = getConseil(uv, phototype)
        Text("Indice UV actuel : ${uv.toInt()} (${conseil.niveauEmoji} ${conseil.niveau})")
        conseil.dureeMaxMinutes?.let {
            Text("⏱️ Sans protection, n'exposez pas votre peau plus de $it minutes au soleil.")
        }
        conseil.conseils.forEach { ligne -> Text(ligne) }
        if (plagesAEviter.isNotEmpty()) {
            Text(
                "😡 Éviter d'exposer votre peau au soleil entre ${plagesAEviter.joinToString(" et ")}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        infoFraicheur?.let {
            Text(
                text = it.message,
                color = if (it.estObsolete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } ?: Text("Récupération de la position et de l'UV...")

    Spacer(Modifier.height(24.dp))

    Text("Prévisions UV du jour selon Open-Meteo", style = MaterialTheme.typography.titleMedium)
    if (previsionsHoraires.size >= 2) {
        CourbePrevisionsUv(
            previsions = previsionsHoraires,
            modifier = Modifier.fillMaxWidth().height(140.dp).padding(vertical = 8.dp),
        )
        TextButton(onClick = { dialogueRisquesOuvert = true }) {
            Text("ℹ️ En savoir plus sur les risques")
        }
    } else {
        Text(
            "Prévisions indisponibles pour le moment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Texte discret : pour quel jour et quel lieu la courbe/donnée ci-dessus est valable — reste
    // affiché même en cas de coupure réseau, pour que l'utilisateur sache si c'est encore pertinent.
    infoPrevisionTexte?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (dialogueRisquesOuvert) {
        AlertDialog(
            onDismissRequest = { dialogueRisquesOuvert = false },
            title = { Text("Risques d'exposition par niveau UV") },
            text = {
                Column {
                    Text(
                        "Ce que représente concrètement chaque niveau, pour une peau non protégée.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("✅ Faible (0-2) : risque minime, aucune protection nécessaire pour la plupart des personnes.")
                    Spacer(Modifier.height(6.dp))
                    Text("👍 Modéré (3-5) : coup de soleil possible en cas d'exposition prolongée sans protection.")
                    Spacer(Modifier.height(6.dp))
                    Text("✋ Élevé (6-7) : risque réel de coup de soleil ; protection (crème, ombre) recommandée.")
                    Spacer(Modifier.height(6.dp))
                    Text("🚨 Très élevé (8-10) : coup de soleil rapide sans protection ; éviter les heures les plus intenses.")
                    Spacer(Modifier.height(6.dp))
                    Text("🙅‍♀️ Extrême (11+) : dommages cutanés en quelques minutes ; éviter toute exposition directe.")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ces seuils reprennent les repères de l'indice UV utilisés par l'OMS et Météo-France.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogueRisquesOuvert = false }) { Text("Fermer") }
            },
        )
    }
}

// --- Onglet Suivi ---

@Composable
private fun EcranSuivi(
    dosePourcent: Double,
    dialoguePrecisionOuvert: Boolean,
    onOuvrirDialoguePrecision: () -> Unit,
    onFermerDialoguePrecision: () -> Unit,
    messageCoupureDetectee: String?,
    onFermerMessageCoupure: () -> Unit,
    sessionEnCoursDebut: Long?,
    actionSessionEnCours: Boolean,
    afficherAvertissementBatterie: Boolean,
    onDemarrerSession: () -> Unit,
    onTerminerSession: () -> Unit,
    uvActuel: Double?,
    phototype: Phototype,
    infoFraicheur: InfoFraicheur?,
    previsionsHoraires: List<PointUvHoraire>,
    infoPrevisionTexte: String?,
    sessionsAujourdhui: List<SessionExposition>,
    maintenant: Long,
) {
    val sessionActive = sessionEnCoursDebut != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Suivi", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        // --- Mon exposition aux U.V. ---
        Text("Mon exposition aux U.V.", style = MaterialTheme.typography.titleMedium)
        Text(DoseTracker.messageDose(dosePourcent))

        Spacer(Modifier.height(8.dp))
        CourbeDoseAujourdhui(
            sessionsCompletes = sessionsAujourdhui,
            sessionEnCoursDebut = sessionEnCoursDebut,
            uvActuelPourSessionEnCours = uvActuel,
            phototypeSessionEnCours = phototype,
            maintenant = maintenant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                DoseTracker.NOTE_HYPOTHESE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOuvrirDialoguePrecision) {
                Text("En savoir plus", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (dialoguePrecisionOuvert) {
            AlertDialog(
                onDismissRequest = onFermerDialoguePrecision,
                title = { Text("Ce que représente cette estimation") },
                text = {
                    Column {
                        Text("🌤️ Basé sur des prévisions et non une mesure exacte (zone ombragée, passage d'un nuage, etc.).")
                        Spacer(Modifier.height(6.dp))
                        Text("🧴 Suppose une exposition sans aucune protection (crème, vêtement) pendant toute la session.")
                        Spacer(Modifier.height(6.dp))
                        Text("📍 La précision de votre position influence aussi l'estimation. Le GPS donne un résultat plus précis qu'une ville saisie manuellement.")
                        Spacer(Modifier.height(6.dp))
                        Text("🕐 Le temps d'exposition pris en compte est celui que vous indiquez vous-même (boutons \"Je m'expose\" / \"Je suis à l'ombre\"), la précision du calcul dépend aussi de la précision de votre saisie.")
                        Spacer(Modifier.height(6.dp))
                        Text("🔌 Une coupure du suivi (téléphone éteint, service arrêté) peut limiter la durée réellement prise en compte ; vous en serez averti si cela arrive.")
                    }
                },
                confirmButton = {
                    TextButton(onClick = onFermerDialoguePrecision) { Text("Fermer") }
                },
            )
        }

        Spacer(Modifier.height(8.dp))

        messageCoupureDetectee?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = onFermerMessageCoupure,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Fermer")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (!sessionActive) {
            if (afficherAvertissementBatterie) {
                Text(
                    "🔋 Le suivi GPS continu pendant la session consomme davantage de batterie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            Button(enabled = !actionSessionEnCours, onClick = onDemarrerSession) {
                Text("☀️ Je m'expose")
            }
        } else {
            Button(enabled = !actionSessionEnCours, onClick = onTerminerSession) {
                Text("🌳 Je suis à l'ombre (fin de session)")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        // --- Reprise du contenu "Aujourd'hui", pour ne pas devoir changer d'onglet en pleine session ---
        ContenuAujourdhui(
            uvActuel = uvActuel,
            phototype = phototype,
            infoFraicheur = infoFraicheur,
            previsionsHoraires = previsionsHoraires,
            infoPrevisionTexte = infoPrevisionTexte,
        )
    }
}
